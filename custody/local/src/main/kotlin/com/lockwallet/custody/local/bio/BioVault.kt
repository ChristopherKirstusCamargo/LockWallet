package com.lockwallet.custody.local.bio

import com.lockwallet.core.crypto.SecretBytes
import com.lockwallet.core.crypto.aead.AesGcm
import com.lockwallet.core.crypto.wipe
import com.lockwallet.custody.local.keystore.BioKeyStore
import com.lockwallet.custody.local.keystore.BioOperation
import com.lockwallet.custody.local.pin.AttemptClock
import com.lockwallet.custody.local.storage.AtomicFiles
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/** Why biometric unlock cannot be used right now (the PIN is then required). */
enum class BioGate { READY, OFF, PIN_AFTER_RESTART, PIN_EXPIRED, PIN_AFTER_ERRORS, PIN_LOCKDOWN }

/** Persisted policy state (Keystore-HMAC authenticated; corrupt, edited or missing ⇒ "off", never "ready"). */
data class BioState(val aliasId: ByteArray, val lastPinBoot: Int, val lastPinElapsed: Long,
    /** Max hours without the PIN (one of [BioVault.WINDOWS_H]); inside the HMAC, so it cannot be edited to grow. */
    val windowHours: Int = BioVault.DEFAULT_WINDOW_H,
    /** "Bloquear agora": biometrics refused until the next correct PIN. */
    val lockdown: Boolean = false)

/** One wallet's biometric record: RSA-OAEP(bio key) wraps a fresh AES-256 key; AES-GCM encrypts the material. */
class BioRecord(val walletId: ByteArray, val aliasId: ByteArray, val headerHash: ByteArray, val rsaCt: ByteArray, val nonce: ByteArray, val ct: ByteArray) {
    fun aad(): ByteArray = prefix() + DOMAIN
    private fun prefix(): ByteArray = ByteBuffer.allocate(4 + 1 + 16 + 16 + 32 + 2 + rsaCt.size)
        .putInt(MAGIC).put(VERSION).put(walletId).put(aliasId).put(headerHash).putShort(rsaCt.size.toShort()).put(rsaCt).array()

    fun encode(): ByteArray = ByteBuffer.allocate(prefix().size + 12 + 2 + ct.size).put(prefix()).put(nonce).putShort(ct.size.toShort()).put(ct).array()

    companion object {
        const val MAGIC = 0x4C574249 // "LWBI"
        const val VERSION: Byte = 1
        private val DOMAIN = "LWBIO-v1/material".toByteArray(Charsets.US_ASCII)

        /** Strict, exact-size parser: anything unexpected ⇒ null (the record is then ignored and the PIN is used). */
        fun parse(b: ByteArray): BioRecord? = try {
            val bb = ByteBuffer.wrap(b)
            if (b.size > 2048 || bb.int != MAGIC || bb.get() != VERSION) null else {
                val wid = ByteArray(16).also { bb.get(it) }; val alias = ByteArray(16).also { bb.get(it) }; val hh = ByteArray(32).also { bb.get(it) }
                val rl = bb.short.toInt() and 0xffff
                if (rl !in 128..512) null else {
                    val rsa = ByteArray(rl).also { bb.get(it) }
                    val n = ByteArray(12).also { bb.get(it) }
                    val cl = bb.short.toInt() and 0xffff
                    if (cl !in 17..256 || bb.remaining() != cl) null else BioRecord(wid, alias, hh, rsa, n, ByteArray(cl).also { bb.get(it) })
                }
            }
        } catch (_: Exception) { null }
    }
}

/**
 * Biometric unlock storage and policy (ADR 0021). Lives next to the vault (noBackupFilesDir), independent of the
 * LWLE envelopes, which are never rewritten: turning the feature on or off cannot damage a wallet.
 */
class BioVault(
    private val dir: File,
    private val keys: BioKeyStore,
    private val clock: AttemptClock,
    private val atomic: AtomicFiles,
    private val random: SecureRandom,
) {
    private val stateFile = File(dir, "state.lwbs")

    init { if (dir.exists()) atomic.cleanup(dir) }

    fun state(): BioState? = if (!stateFile.exists()) null else decodeState(runCatching { stateFile.readBytes() }.getOrNull())

    fun enabled(): Boolean = state()?.let { keys.exists(it.aliasId) } == true

    /** Policy: biometric replaces the PIN only within the same boot, ≤ 72 h after the last PIN, and with no PIN errors pending. */
    fun gate(pinFailures: Int, lockoutMs: Long): BioGate {
        val s = state() ?: return BioGate.OFF
        if (!keys.exists(s.aliasId)) return BioGate.OFF
        val now = clock.elapsedRealtimeMs()
        val boot = clock.bootCount()
        // 5.0 (A-52): an unknown boot counter (-1, some OEM builds) used to fall back to the uptime check alone, so a
        // reboot followed by enough uptime re-enabled biometrics without the PIN. Unknown ⇒ treat as "restarted".
        if (boot == -1 || s.lastPinBoot == -1 || boot != s.lastPinBoot || now < s.lastPinElapsed) return BioGate.PIN_AFTER_RESTART
        if (s.lockdown) return BioGate.PIN_LOCKDOWN
        if (now - s.lastPinElapsed > windowMs(s.windowHours)) return BioGate.PIN_EXPIRED
        if (pinFailures > 0 || lockoutMs > 0) return BioGate.PIN_AFTER_ERRORS
        return BioGate.READY
    }

    /** Creates a NEW bio key (fresh random alias; an existing one is never reused or overwritten). */
    fun enable() {
        disable()
        val alias = ByteArray(16).also(random::nextBytes)
        keys.create(alias)
        writeState(BioState(alias, clock.bootCount(), clock.elapsedRealtimeMs(), DEFAULT_WINDOW_H))
    }

    fun disable() {
        state()?.let { keys.delete(it.aliasId) }
        dir.listFiles()?.forEach { it.delete() }
    }

    /** A correct PIN was just entered: restart the 72 h window for this boot. */
    fun notePin() { val s = state() ?: return; writeState(s.copy(lastPinBoot = clock.bootCount(), lastPinElapsed = clock.elapsedRealtimeMs(), lockdown = false)) }

    /** Panic/coercion: refuse biometrics until the next correct PIN. Safe to call any time (no-op when off). */
    fun lockdown() { val s = state() ?: return; if (!s.lockdown) writeState(s.copy(lockdown = true)) }

    fun windowHours(): Int? = state()?.windowHours

    /**
     * Changes the max time without the PIN. Shortening is always allowed (it only adds friction); making it longer
     * needs proof of the PIN right now ([viaPin]) so a coerced finger cannot extend its own window.
     */
    fun setWindow(hours: Int, viaPin: Boolean) {
        require(hours in WINDOWS_H) { "window" }
        val s = state() ?: return
        if (hours > s.windowHours && !viaPin) throw SecurityException("pin required to lengthen the window")
        writeState(s.copy(windowHours = hours))
    }

    fun has(walletIdHex: String): Boolean = recordFile(walletIdHex).exists()

    fun remove(walletIdHex: String) { recordFile(walletIdHex).delete() }

    /** Encrypts [material] for [walletId] with the public key only (no prompt). */
    fun write(walletId: ByteArray, header: ByteArray, material: SecretBytes) {
        val s = state() ?: return
        val aesKey = ByteArray(32).also(random::nextBytes)
        try {
            val rsa = BioKeyStore.encrypt(keys.publicKey(s.aliasId), aesKey)
            val nonce = ByteArray(12).also(random::nextBytes)
            val shell = BioRecord(walletId, s.aliasId, sha256(header), rsa, nonce, ByteArray(0))
            val ct = SecretBytes.copyOf(aesKey).use { k -> material.withBytes { AesGcm.encrypt(k, nonce, shell.aad(), it) } }
            val rec = BioRecord(walletId, s.aliasId, sha256(header), rsa, nonce, ct)
            atomic.write(recordFile(hex(walletId)), rec.encode()) { requireNotNull(BioRecord.parse(it)) }
        } finally { aesKey.wipe() }
    }

    /** Loads and binds the record to the wallet, the current bio key and the envelope header. Null ⇒ use the PIN. */
    fun record(walletIdHex: String, header: ByteArray): BioRecord? {
        val s = state() ?: return null
        val f = recordFile(walletIdHex)
        val r = (if (f.exists()) BioRecord.parse(f.readBytes()) else null) ?: return null
        if (hex(r.walletId) != walletIdHex || !r.aliasId.contentEquals(s.aliasId) || !r.headerHash.contentEquals(sha256(header))) { f.delete(); return null }
        return r
    }

    fun decryptor(): BioOperation = keys.decryptor(state()!!.aliasId)

    /** After the biometric: unwrap the AES key and open the material. Throws on any tampering. */
    fun open(op: BioOperation, r: BioRecord): SecretBytes {
        val aes = op.finish(r.rsaCt)
        try {
            require(aes.size == 32)
            return SecretBytes.copyOf(aes).use { k -> AesGcm.decrypt(k, r.nonce, r.aad(), r.ct) }
        } finally { aes.wipe() }
    }

    private fun recordFile(idHex: String) = File(dir, "$idHex.lwbio")

    private fun writeState(s: BioState) {
        val body = ByteBuffer.allocate(V2_BODY).putInt(STATE_MAGIC).put(2).put(s.aliasId).putInt(s.lastPinBoot).putLong(s.lastPinElapsed)
            .putShort(s.windowHours.toShort()).put(if (s.lockdown) 1 else 0).array()
        val bytes = body + keys.mac(s.aliasId, STATE_DOMAIN + body)
        atomic.write(stateFile, bytes) { requireNotNull(decodeState(it)) }
    }

    /** Parse, then verify the HMAC with the Keystore key named by the alias inside (constant-time compare). */
    private fun decodeState(b: ByteArray?): BioState? {
        if (b == null) return null
        // v1 (4.0 and earlier): 33-byte body, no window/lockdown ⇒ read with the stricter 24 h default.
        val len = when (b.size) { V1_BODY + 32 -> V1_BODY; V2_BODY + 32 -> V2_BODY; else -> return null }
        val body = b.copyOf(len)
        val bb = ByteBuffer.wrap(body)
        if (bb.int != STATE_MAGIC) return null
        val ver = bb.get().toInt()
        if ((ver == 1 && len != V1_BODY) || (ver == 2 && len != V2_BODY) || ver !in 1..2) return null
        val alias = ByteArray(16).also { bb.get(it) }; val boot = bb.int; val el = bb.long
        val s = if (ver == 1) BioState(alias, boot, el, DEFAULT_WINDOW_H, false) else {
            val w = bb.short.toInt(); val ld = bb.get().toInt()
            if (w !in WINDOWS_H || ld !in 0..1) return null
            BioState(alias, boot, el, w, ld == 1)
        }
        val mac = runCatching { keys.mac(s.aliasId, STATE_DOMAIN + body) }.getOrNull() ?: return null
        return if (MessageDigest.isEqual(mac, b.copyOfRange(len, len + 32))) s else null
    }

    companion object {
        /** Choices for the max time without the PIN; 72 h is Android's own "strong auth" ceiling. */
        val WINDOWS_H = listOf(1, 4, 24, 72)
        /** 5.0 default (stricter than 4.0's fixed 72 h): shorter coercion window. */
        const val DEFAULT_WINDOW_H = 24
        /** Upper bound kept for callers/tests. */
        const val MAX_WITHOUT_PIN_MS = 72L * 3_600_000L
        fun windowMs(h: Int) = h.coerceIn(1, 72).toLong() * 3_600_000L
        private const val V1_BODY = 33
        private const val V2_BODY = 36
        private const val STATE_MAGIC = 0x4C574253 // "LWBS"
        private val STATE_DOMAIN = "LWBIO-v1/state".toByteArray(Charsets.US_ASCII)
        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
