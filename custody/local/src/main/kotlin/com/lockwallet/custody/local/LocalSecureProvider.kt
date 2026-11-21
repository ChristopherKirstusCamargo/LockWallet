package com.lockwallet.custody.local

import com.lockwallet.core.crypto.SecretBytes
import com.lockwallet.core.crypto.lwrp.LwrpCipher
import com.lockwallet.core.crypto.lwrp.LwrpFormatException
import com.lockwallet.core.crypto.lwrp.LwrpOpenException
import com.lockwallet.core.crypto.lwrp.LwrpPasswordException
import com.lockwallet.core.crypto.lwrp.LwrpPayload
import com.lockwallet.core.crypto.lwrp.MaterialKind
import com.lockwallet.core.crypto.DerivationProfile
import com.lockwallet.core.crypto.wipe
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.analyzer.TransactionAnalyzer
import com.lockwallet.custody.local.api.CustodyProviderId
import com.lockwallet.custody.local.api.KeyCustodyProvider
import com.lockwallet.custody.local.api.ProviderCapabilities
import com.lockwallet.custody.local.api.RecoveryException
import com.lockwallet.custody.local.api.WalletInfo
import com.lockwallet.custody.local.envelope.EnvelopeAuthException
import com.lockwallet.custody.local.envelope.EnvelopeCrypto
import com.lockwallet.custody.local.envelope.EnvelopeError
import com.lockwallet.custody.local.envelope.EnvelopeFormatException
import com.lockwallet.custody.local.envelope.LocalEnvelope
import com.lockwallet.custody.local.envelope.SealRandomness
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.keystore.KekException
import com.lockwallet.custody.local.keystore.KekOperation
import com.lockwallet.custody.local.keystore.KekStore
import com.lockwallet.custody.local.keystore.KeySecurityLevel
import com.lockwallet.custody.local.material.AccountProfile
import com.lockwallet.custody.local.material.DiscoveredAccount
import com.lockwallet.custody.local.material.PendingMaterial
import com.lockwallet.custody.local.pin.AttemptClock
import com.lockwallet.custody.local.pin.AttemptGate
import com.lockwallet.custody.local.pin.Pin
import com.lockwallet.custody.local.pin.PinAttemptLimiter
import com.lockwallet.custody.local.storage.AtomicFiles
import com.lockwallet.custody.local.storage.VaultConfig
import com.lockwallet.custody.local.storage.WalletEntry
import com.lockwallet.custody.local.storage.WalletRegistry
import com.lockwallet.custody.local.storage.WalletRole
import com.lockwallet.custody.local.storage.WalletState
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher

/** Failure classes the UI can act on. Messages are generic by design. */
class CustodyException(val reason: Reason) : Exception("custody: $reason") {
    enum class Reason {
        NO_VAULT, VAULT_EXISTS, UNKNOWN_WALLET, QUARANTINED, KEY_INVALIDATED, KEY_MISSING, AUTH_REQUIRED,
        UNSUPPORTED_VERSION, CORRUPTED, DUPLICATE_WALLET, ROLE_CONFLICT, BAD_NAME, BAD_PIN_FORMAT, WEAK_PIN,
        BAD_ACCOUNT, STORAGE, KEYSTORE, NOT_APPROVED,
        SEED_VAULT_UNAVAILABLE, SEED_VAULT_DECLINED, BAD_SIGNATURE,
        /** Biometric unlock: PIN required now (restart, 72 h, PIN errors), not set up for this wallet, or the OS invalidated it. */
        BIO_PIN_REQUIRED, BIO_NOT_ENROLLED, BIO_INVALIDATED,
    }
}

/** A pending Keystore operation. If [needsBiometric], pass [cipher] to BiometricPrompt.CryptoObject first. */
class KekTicket internal constructor(internal val op: KekOperation, internal val walletId: String?, internal val newVault: NewVault?) {
    val cipher: Cipher? get() = op.cipher
    val needsBiometric: Boolean get() = op.requiresUserAuth
    internal class NewVault(val vaultId: ByteArray, val aliasId: ByteArray, val mode: AuthMode)
}

/** A pending biometric unlock: pass [cipher] to BiometricPrompt.CryptoObject, then call completeBiometricUnlock. */
class BioTicket internal constructor(internal val op: com.lockwallet.custody.local.keystore.BioOperation, internal val walletId: String, internal val record: com.lockwallet.custody.local.bio.BioRecord) {
    val cipher: Cipher get() = op.cipher
}

/** Single-use proof that the vault PIN was just verified: the derived pin_key for the vault salt. */
class PinVerification internal constructor(internal val pinKey: SecretBytes, internal val pinSalt: ByteArray) : AutoCloseable {
    override fun close() = pinKey.close()
    override fun toString() = "PinVerification(REDACTED)"
}

sealed class UnlockResult {
    class Success(val session: UnlockedWallet) : UnlockResult()
    /** Wrong PIN *or* tampered envelope (indistinguishable). */
    data class Failed(val failures: Int, val attemptsBeforeLockout: Int, val lockoutMs: Long) : UnlockResult()
    data class LockedOut(val remainingMs: Long) : UnlockResult()
}

/** Opened wallet. Holds the material until [close]; the UI must close it as soon as the action is done. */
class UnlockedWallet internal constructor(
    val info: WalletInfo,
    private val material: SecretBytes,
    /** Null when opened by biometrics: such a session cannot vouch for the PIN (no sealing / adding accounts). */
    private val pinKey: SecretBytes?,
    private val pinSalt: ByteArray,
    internal val header: ByteArray = ByteArray(0),
) : AutoCloseable {
    /** True when opened with the PIN (not with biometrics). */
    val viaPin: Boolean get() = pinKey != null
    val isMnemonic: Boolean get() = info.isMnemonic

    /** Biometric sessions are view-only; every signature and secret operation requires a PIN session. */
    internal fun hasPin(): Boolean = pinKey != null
    private fun requirePin() { if (pinKey == null) throw CustodyException(CustodyException.Reason.AUTH_REQUIRED) }

    /** Phrase words for re-view (each CharArray must be wiped by the caller). PIN sessions only. */
    fun words(): List<CharArray> = requirePin().let { PendingMaterial }.fromEnvelope(PendingMaterial.KIND_BIP39, material.copy()).use { it.words() }

    /** Copy of the material, e.g. to derive another account of the same seed. */
    fun toPending(): PendingMaterial = requirePin().let { PendingMaterial }.fromEnvelope(
        if (info.isMnemonic) PendingMaterial.KIND_BIP39 else PendingMaterial.KIND_KEYPAIR, material.copy())

    /** Single-use proof for sealing another wallet into this vault without asking the PIN twice. */
    fun pinVerification(): PinVerification =
        PinVerification((pinKey ?: throw CustodyException(CustodyException.Reason.AUTH_REQUIRED)).copy(), pinSalt.copyOf())

    internal fun materialCopy(): SecretBytes = material.copy()

    /** Encrypted LWRP v1 for this wallet (password independent of the PIN). */
    fun exportLwrp(password: CharArray): ByteArray {
        requirePin()
        val payload = material.withBytes { m ->
            if (info.isMnemonic) LwrpPayload.bip39(m, DerivationProfile.ofId(info.profile.id)!!, info.accountIndex, info.address.toByteArray())
            else LwrpPayload.keypair(m, info.address.toByteArray())
        }
        return payload.use {
            try { LwrpCipher.seal(it, password) } catch (e: LwrpPasswordException) { throw RecoveryException.of(e) }
        }
    }

    /**
     * Signs exactly the bytes the user approved (ADR 0016/0019). Defence in depth inside custody: the hash must
     * match the approval, the analyzer must accept the message statically with this wallet as the only signer
     * and fee payer, and the derived key must equal the registered address. No blind signing: any doubt throws.
     */
    fun signApproved(message: ByteArray, approvedHash: ByteArray): ByteArray {
        requirePin()
        val a = TransactionAnalyzer.analyze(message, info.address)
        if (!a.messageHash.contentEquals(approvedHash)) throw CustodyException(CustodyException.Reason.NOT_APPROVED)
        if (!a.signable) throw CustodyException(CustodyException.Reason.NOT_APPROVED)
        val pending = PendingMaterial.fromEnvelope(if (info.isMnemonic) PendingMaterial.KIND_BIP39 else PendingMaterial.KIND_KEYPAIR, material.copy())
        return pending.use { it.signWith(info.profile, info.accountIndex, info.address, message) }
    }

    /**
     * Lock Connect (Phase H): signs a dApp-built message only for a CONNECT wallet, only if the dApp firewall accepts
     * these exact bytes (hash shown on the review) and raw instructions, if any, were explicitly acknowledged.
     */
    fun signDappTransaction(message: ByteArray, approvedHash: ByteArray, rawAcknowledged: Boolean): ByteArray {
        requirePin()
        if (info.role != com.lockwallet.custody.local.storage.WalletRole.CONNECT) throw CustodyException(CustodyException.Reason.NOT_APPROVED)
        if (!com.lockwallet.core.solana.dapp.DappFirewall.permits(message, info.address, approvedHash, rawAcknowledged)) throw CustodyException(CustodyException.Reason.NOT_APPROVED)
        val pending = PendingMaterial.fromEnvelope(if (info.isMnemonic) PendingMaterial.KIND_BIP39 else PendingMaterial.KIND_KEYPAIR, material.copy())
        return pending.use { it.signWith(info.profile, info.accountIndex, info.address, message) }
    }

    /** Lock Connect: off-chain text message (sign_messages / SIWS); never anything that parses as a transaction. */
    fun signDappMessage(text: ByteArray): ByteArray {
        requirePin()
        if (info.role != com.lockwallet.custody.local.storage.WalletRole.CONNECT) throw CustodyException(CustodyException.Reason.NOT_APPROVED)
        if (com.lockwallet.core.solana.dapp.MessagePolicy.check(text) != null) throw CustodyException(CustodyException.Reason.NOT_APPROVED)
        val pending = PendingMaterial.fromEnvelope(if (info.isMnemonic) PendingMaterial.KIND_BIP39 else PendingMaterial.KIND_KEYPAIR, material.copy())
        return pending.use { it.signWith(info.profile, info.accountIndex, info.address, text) }
    }

    override fun close() { material.close(); pinKey?.close() }
    override fun toString() = "UnlockedWallet(${info.id}, REDACTED)"
}

/**
 * Local secure custody (ADR 0001/0002/0010/0016). Blocking API: call off the main thread.
 * Layout under [dir]: `registry.lwreg`, `envelopes/<id>.lwle`, `attempts.bin`, `attempts.mirror`.
 */
class LocalSecureProvider(
    private val dir: File,
    private val kek: KekStore,
    clock: AttemptClock,
    private val random: SecureRandom = SecureRandom(),
    private val atomic: AtomicFiles = AtomicFiles(),
    private val now: () -> Long = System::currentTimeMillis,
    /** Biometric unlock key store (ADR 0024). Null = feature unavailable. */
    bioKeys: com.lockwallet.custody.local.keystore.BioKeyStore? = null,
) : KeyCustodyProvider {
    private val envDir = File(dir, "envelopes")
    private val registry = WalletRegistry(File(dir, "registry.lwreg"), atomic)
    val attempts = PinAttemptLimiter(
        File(dir, "attempts.bin"), File(dir, "attempts.mirror"), clock, atomic,
        vaultExists = { hasVault() },
        hmac = { data ->
            val vault = snapshot().vault ?: throw KekException(KekException.Kind.NOT_FOUND)
            kek.attemptMac(hex(vault.aliasIdHex), data)
        },
    )
    private val bio = bioKeys?.let { com.lockwallet.custody.local.bio.BioVault(File(dir, "bio"), it, clock, atomic, random) }

    init {
        if (dir.exists()) { atomic.cleanup(dir); if (envDir.exists()) atomic.cleanup(envDir) }
    }

    override val id = CustodyProviderId.LOCAL_SECURE

    override fun capabilities(): ProviderCapabilities {
        val v = snapshot().vault
        val level = v?.let { kek.securityLevel(hex(it.aliasIdHex)) }
        return ProviderCapabilities(id, true, null, true, level)
    }

    override fun wallets(): List<WalletInfo> = snapshot().wallets.map { it.toInfo() }

    fun hasVault(): Boolean = snapshot().let { s -> s.vault != null && s.wallets.any { it.state == WalletState.ACTIVE } }

    fun vaultAuthMode(): AuthMode? = if (hasVault()) AuthMode.ofId(snapshot().vault!!.authModeId) else null

    fun keySecurityLevel(): KeySecurityLevel? = snapshot().vault?.let { kek.securityLevel(hex(it.aliasIdHex)) }

    // ------------------------------------------------------------------ sealing

    /** First wallet: creates a fresh KEK (new alias, never reusing one) and returns the encrypt ticket. */
    fun beginNewVault(mode: AuthMode): KekTicket {
        if (hasVault()) throw CustodyException(CustodyException.Reason.VAULT_EXISTS)
        val vaultId = rand(16); val aliasId = rand(16)
        try {
            kek.create(aliasId, mode)
            try {
                kek.createAttemptMacKey(aliasId)
            } catch (e: Exception) {
                runCatching { kek.delete(aliasId) }
                throw e
            }
            return KekTicket(kek.encryptor(aliasId), null, KekTicket.NewVault(vaultId, aliasId, mode))
        } catch (e: KekException) {
            throw mapKek(e)
        }
    }

    /** Abandons a ticket. A KEK created by [beginNewVault] that never sealed anything is deleted (nothing references it). */
    fun cancel(ticket: KekTicket) {
        val nv = ticket.newVault ?: return
        if (snapshot().vault?.aliasIdHex != hexOf(nv.aliasId)) {
            runCatching { kek.deleteAttemptMacKey(nv.aliasId) }
            runCatching { kek.delete(nv.aliasId) }
        }
    }

    /** Additional wallet into the existing vault; needs a [PinVerification] at [seal]. */
    fun beginSealExisting(): KekTicket {
        val v = snapshot().vault?.takeIf { hasVault() } ?: throw CustodyException(CustodyException.Reason.NO_VAULT)
        try {
            return KekTicket(kek.encryptor(hex(v.aliasIdHex)), null, null)
        } catch (e: KekException) {
            if (e.kind == KekException.Kind.INVALIDATED || e.kind == KekException.Kind.NOT_FOUND) quarantineAll()
            throw mapKek(e)
        }
    }

    /**
     * Seals one account of [material] into a new envelope. For a new vault pass [newPin] (6 ASCII digits);
     * otherwise pass [verification] from a just-opened wallet of this vault. Writes envelope then registry,
     * each atomically; a crash between them leaves an orphan envelope that is ignored (never a dangling entry).
     */
    fun seal(
        ticket: KekTicket, material: PendingMaterial, account: DiscoveredAccount, name: String, role: WalletRole,
        newPin: CharArray? = null, verification: PinVerification? = null, enableBiometric: Boolean = false,
    ): WalletInfo {
        val cleanName = validName(name)
        val snap = snapshot()
        val pub = material.publicKeyFor(account.profile, account.index)
        if (pub != account.address) throw CustodyException(CustodyException.Reason.BAD_ACCOUNT)
        if (account.index < 0) throw CustodyException(CustodyException.Reason.BAD_ACCOUNT)
        val active = snap.wallets.filter { it.state == WalletState.ACTIVE }
        if (active.any { it.publicKeyHex == hexOf(pub.toByteArray()) }) throw CustodyException(CustodyException.Reason.DUPLICATE_WALLET)
        val group = hexOf(material.groupFingerprint())
        if (active.any { it.seedGroupHex == group && it.role != role }) throw CustodyException(CustodyException.Reason.ROLE_CONFLICT)

        val vault: VaultConfig
        val pinKey: SecretBytes
        val salt: ByteArray
        val nv = ticket.newVault
        if (nv != null) {
            val pin = newPin ?: throw CustodyException(CustodyException.Reason.BAD_PIN_FORMAT)
            if (!Pin.isValid(pin) || pin.size != Pin.LENGTH) throw CustodyException(CustodyException.Reason.BAD_PIN_FORMAT)
            if (Pin.isTrivial(pin)) throw CustodyException(CustodyException.Reason.WEAK_PIN)
            vault = VaultConfig(hexOf(nv.vaultId), hexOf(nv.aliasId), nv.mode.id)
            salt = rand(16)
            pinKey = Pin.encode(pin).use { EnvelopeCrypto.derivePinKey(it, salt) }
        } else {
            val ver = verification ?: throw CustodyException(CustodyException.Reason.AUTH_REQUIRED)
            vault = snap.vault ?: throw CustodyException(CustodyException.Reason.NO_VAULT)
            salt = ver.pinSalt.copyOf()
            pinKey = ver.pinKey.copy()
            ver.close()
        }
        val materialId = rand(16)
        val idHex = hexOf(materialId)
        val rnd = SealRandomness(rand(32), rand(12), rand(12))
        try {
            val env = material.copySecret().use { secret ->
                EnvelopeCrypto.seal(hex(vault.vaultIdHex), materialId, material.kind, account.profile.id, account.index,
                    pub.toByteArray(), salt, hex(vault.aliasIdHex), vault.authModeId, secret, pinKey, rnd, ticket.op)
            }
            val bytes = env.encode()
            atomic.write(envFile(idHex), bytes) { b ->
                val back = LocalEnvelope.parse(b)
                check(back.expectedPubkey().contentEquals(pub.toByteArray()))
                // Full read-back verification of the PIN + material layers (the KEK layer is checked by the tag at open).
            }
            val entry = WalletEntry(idHex, cleanName, role, material.kind, account.profile.id, account.index,
                hexOf(pub.toByteArray()), group, id.code, now(), WalletState.ACTIVE)
            registry.save(WalletRegistry.Snapshot(vault, snap.wallets.filter { nv == null || it.state != WalletState.ACTIVE } + entry))
            if (nv != null) { attempts.recordSuccess(); runCatching { bio?.disable() } } // a new vault never inherits old biometric records
            val b = bio
            if (nv != null && enableBiometric && b != null) runCatching { b.enable() }
            if (b != null && b.enabled()) runCatching { material.copySecret().use { b.write(materialId, env.header(), it) } }
            return entry.toInfo()
        } catch (e: KekException) {
            throw mapKek(e)
        } catch (e: CustodyException) {
            throw e
        } catch (e: Exception) {
            throw CustodyException(CustodyException.Reason.STORAGE)
        } finally {
            rnd.wipe(); pinKey.close()
        }
    }

    // ------------------------------------------------------------------ unlocking

    fun beginUnlock(walletId: String): KekTicket {
        val e = entry(walletId)
        if (e.state != WalletState.ACTIVE) throw CustodyException(CustodyException.Reason.QUARANTINED)
        val env = loadEnvelope(e)
        try {
            return KekTicket(kek.decryptor(env.aliasId(), env.hwIv()), walletId, null)
        } catch (x: KekException) {
            if (x.kind == KekException.Kind.INVALIDATED || x.kind == KekException.Kind.NOT_FOUND) quarantineAll()
            throw mapKek(x)
        }
    }

    /** Any active wallet — used to unlock the app and to verify the PIN before sealing another wallet. */
    fun beginUnlockAny(): KekTicket {
        val first = snapshot().wallets.firstOrNull { it.state == WalletState.ACTIVE } ?: throw CustodyException(CustodyException.Reason.NO_VAULT)
        return beginUnlock(first.walletIdHex)
    }

    /**
     * Write-ahead attempt accounting, then hardware unwrap (already biometric-authorised if required),
     * Argon2id(PIN), DEK, material, pubkey check. Any cryptographic failure ⇒ [UnlockResult.Failed].
     */
    fun completeUnlock(ticket: KekTicket, pin: CharArray): UnlockResult {
        val walletId = ticket.walletId ?: throw CustodyException(CustodyException.Reason.UNKNOWN_WALLET)
        if (!Pin.isValid(pin)) throw CustodyException(CustodyException.Reason.BAD_PIN_FORMAT)
        val e = entry(walletId)
        val env = loadEnvelope(e)
        val gate = try { attempts.gate() } catch (_: Exception) { throw CustodyException(CustodyException.Reason.STORAGE) }
        if (gate is AttemptGate.LockedOut) return UnlockResult.LockedOut(gate.remainingMs)
        gate as AttemptGate.Allowed
        val failed = UnlockResult.Failed(gate.failuresIfWrong, (PinAttemptLimiter.FREE_ATTEMPTS - gate.failuresIfWrong).coerceAtLeast(0), gate.lockoutIfWrongMs)
        val dekPin = try {
            EnvelopeCrypto.unwrapHardware(env, ticket.op)
        } catch (x: KekException) {
            if (x.kind == KekException.Kind.BAD_TAG) return failed
            if (x.kind == KekException.Kind.INVALIDATED || x.kind == KekException.Kind.NOT_FOUND) quarantineAll()
            throw mapKek(x) // auth missing / keystore error: not a PIN failure — the counted attempt stays (fail closed)
        }
        try {
            // Existing LWLE v1 files keep using their recorded KDF profile. A successful legacy unlock
            // upgrades the in-memory PIN key immediately, so any newly sealed account is written as v2.
            val unlockPinKey = Pin.encode(pin).use {
                EnvelopeCrypto.derivePinKey(it, env.pinSalt(), env.kdfProfile)
            }
            val material = try {
                EnvelopeCrypto.openWithPinKey(env, dekPin, unlockPinKey)
            } catch (_: EnvelopeAuthException) {
                unlockPinKey.close()
                return failed
            }
            try {
                attempts.recordSuccess()
            } catch (_: Exception) {
                material.close()
                unlockPinKey.close()
                throw CustodyException(CustodyException.Reason.STORAGE)
            }
            val sessionPinKey = if (env.kdfProfile == LocalEnvelope.KDF_STRONG_V2) {
                unlockPinKey
            } else {
                try {
                    Pin.encode(pin).use { EnvelopeCrypto.derivePinKey(it, env.pinSalt(), LocalEnvelope.KDF_STRONG_V2) }
                } finally {
                    unlockPinKey.close()
                }
            }
            bio?.let { b -> runCatching { b.notePin(); if (b.enabled() && !b.has(walletId)) b.write(env.materialId(), env.header(), material) } }
            return UnlockResult.Success(UnlockedWallet(e.toInfo(), material, sessionPinKey, env.pinSalt(), env.header()))
        } finally {
            dekPin.wipe()
        }
    }

    // ------------------------------------------------------------------ biometric unlock (ADR 0024)

    /** Whether biometrics can replace the PIN right now. Never READY with PIN errors pending, after a restart, after the window (24 h default) or in lockdown. */
    fun biometricGate(): com.lockwallet.custody.local.bio.BioGate {
        val b = bio ?: return com.lockwallet.custody.local.bio.BioGate.OFF
        if (!hasVault()) return com.lockwallet.custody.local.bio.BioGate.OFF
        val failures = runCatching { attempts.failures() }.getOrDefault(Int.MAX_VALUE)
        val lock = runCatching { attempts.lockoutRemainingMs() }.getOrDefault(Long.MAX_VALUE)
        return b.gate(failures, lock)
    }

    fun biometricEnabled(): Boolean = bio?.enabled() == true

    /** Turns biometric unlock on. Only from a session opened with the PIN just now (proof of knowledge). */
    fun enableBiometricUnlock(session: UnlockedWallet) {
        val b = bio ?: throw CustodyException(CustodyException.Reason.KEYSTORE)
        if (!session.viaPin) throw CustodyException(CustodyException.Reason.AUTH_REQUIRED)
        try {
            b.enable()
            session.materialCopy().use { b.write(hex(session.info.id), session.header, it) }
        } catch (e: KekException) { runCatching { b.disable() }; throw mapKek(e) }
        catch (e: CustodyException) { throw e }
        catch (e: Exception) { runCatching { b.disable() }; throw CustodyException(CustodyException.Reason.STORAGE) }
    }

    /** "Bloquear agora": persist the PIN-only state; never report success if the authenticated write fails. */
    fun biometricLockdown() {
        try { bio?.lockdown() }
        catch (_: Exception) { throw CustodyException(CustodyException.Reason.STORAGE) }
    }

    fun biometricWindowHours(): Int? = runCatching { bio?.windowHours() }.getOrNull()

    /** Shorter: always. Longer: only with a session opened by the PIN (a biometric session cannot extend itself). */
    fun setBiometricWindow(hours: Int, session: UnlockedWallet?) {
        val b = bio ?: throw CustodyException(CustodyException.Reason.KEYSTORE)
        try { b.setWindow(hours, viaPin = session?.viaPin == true) }
        catch (_: SecurityException) { throw CustodyException(CustodyException.Reason.AUTH_REQUIRED) }
        catch (_: IllegalArgumentException) { throw CustodyException(CustodyException.Reason.STORAGE) }
    }

    /** Turns it off: records, policy state and the bio key are deleted (the PIN keeps working; wallets untouched). */
    fun disableBiometricUnlock() { bio?.disable() }

    /** Biometric unlock of [walletId] (or any enrolled active wallet). The ticket's cipher must go to BiometricPrompt. */
    fun beginBiometricUnlock(walletId: String?): BioTicket {
        val b = bio ?: throw CustodyException(CustodyException.Reason.BIO_NOT_ENROLLED)
        if (biometricGate() != com.lockwallet.custody.local.bio.BioGate.READY) throw CustodyException(CustodyException.Reason.BIO_PIN_REQUIRED)
        val candidates = snapshot().wallets.filter { it.state == WalletState.ACTIVE && (walletId == null || it.walletIdHex == walletId) }
        for (e in candidates) {
            val env = try { loadEnvelope(e) } catch (_: CustodyException) { continue }
            val rec = b.record(e.walletIdHex, env.header()) ?: continue
            val op = try { b.decryptor() } catch (x: KekException) {
                if (x.kind == KekException.Kind.INVALIDATED || x.kind == KekException.Kind.NOT_FOUND) {
                    runCatching { b.disable() }; throw CustodyException(CustodyException.Reason.BIO_INVALIDATED)
                }
                throw mapKek(x)
            }
            return BioTicket(op, e.walletIdHex, rec)
        }
        throw CustodyException(CustodyException.Reason.BIO_NOT_ENROLLED)
    }

    /** After a successful BiometricPrompt: unwraps and verifies the material (public key re-derived). Not a PIN attempt. */
    fun completeBiometricUnlock(t: BioTicket): UnlockedWallet {
        val b = bio ?: throw CustodyException(CustodyException.Reason.BIO_NOT_ENROLLED)
        if (biometricGate() != com.lockwallet.custody.local.bio.BioGate.READY) throw CustodyException(CustodyException.Reason.BIO_PIN_REQUIRED)
        val e = entry(t.walletId)
        if (e.state != WalletState.ACTIVE) throw CustodyException(CustodyException.Reason.QUARANTINED)
        val env = loadEnvelope(e)
        val material = try { b.open(t.op, t.record) } catch (x: KekException) {
            if (x.kind == KekException.Kind.INVALIDATED) { runCatching { b.disable() }; throw CustodyException(CustodyException.Reason.BIO_INVALIDATED) }
            throw mapKek(x)
        } catch (_: Exception) {
            b.remove(t.walletId); throw CustodyException(CustodyException.Reason.BIO_NOT_ENROLLED) // tampered record: dropped, PIN still works
        }
        if (material.size != env.materialLength || !EnvelopeCrypto.pubkeyMatches(env, material)) {
            material.close(); b.remove(t.walletId); throw CustodyException(CustodyException.Reason.BIO_NOT_ENROLLED)
        }
        return UnlockedWallet(e.toInfo(), material, null, env.pinSalt(), env.header())
    }

    // ------------------------------------------------------------------ LWRP import

    /**
     * Opens an LWRP file (exact size/structure before KDF). Password errors are reported before the KDF;
     * everything after the KDF is one generic failure.
     */
    fun openRecoveryPackage(file: ByteArray, password: CharArray): PendingMaterial {
        val payload = try {
            LwrpCipher.open(file, password)
        } catch (e: LwrpPasswordException) {
            throw RecoveryException.of(e)
        } catch (_: LwrpFormatException) {
            throw RecoveryException(RecoveryException.Reason.NOT_A_PACKAGE)
        } catch (_: LwrpOpenException) {
            throw RecoveryException(RecoveryException.Reason.OPEN_FAILED)
        }
        payload.use { p ->
            val kind = if (p.kind == MaterialKind.BIP39_ENTROPY) PendingMaterial.KIND_BIP39 else PendingMaterial.KIND_KEYPAIR
            val profile = if (kind == PendingMaterial.KIND_BIP39) AccountProfile.ofId(p.derivationProfile!!.id)!! else AccountProfile.NONE
            val acct = DiscoveredAccount(profile, p.accountIndex, SolanaPublicKey.of(p.expectedPubkey()))
            return PendingMaterial.fromLwrp(kind, p.material.copy(), acct)
        }
    }

    /** Verify-after-export: re-open the bytes read back from the destination and compare the public key. */
    fun verifyRecoveryPackage(readBack: ByteArray, password: CharArray, expected: SolanaPublicKey): Boolean = try {
        openRecoveryPackage(readBack, password).use { it.suggested?.address == expected }
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------ management

    fun rename(walletId: String, name: String) {
        val clean = validName(name)
        val s = snapshot()
        if (s.wallets.none { it.walletIdHex == walletId }) throw CustodyException(CustodyException.Reason.UNKNOWN_WALLET)
        registry.save(s.copy(wallets = s.wallets.map { if (it.walletIdHex == walletId) it.copy(name = clean) else it }))
    }

    /**
     * Deletes the wallet [session] belongs to, on explicit user request, with a PIN session only (never biometrics).
     * Order is crash-safe: the registry entry goes first (atomic rename), then the envelope is overwritten and deleted.
     * Dying in between leaves an orphan ciphertext that no entry references — never a listed wallet without material.
     * Other wallets, the vault key and the PIN/attempt counters are untouched. Biometrics are disabled with the last wallet.
     */
    fun removeWallet(session: UnlockedWallet) {
        if (!session.hasPin()) throw CustodyException(CustodyException.Reason.AUTH_REQUIRED)
        val id = session.info.id
        val s = snapshot()
        val e = s.wallets.firstOrNull { it.walletIdHex == id } ?: throw CustodyException(CustodyException.Reason.UNKNOWN_WALLET)
        registry.save(s.copy(wallets = s.wallets - e))
        val f = envFile(id)
        if (f.exists()) { runCatching { java.io.RandomAccessFile(f, "rws").use { r -> r.write(ByteArray(r.length().toInt())) } }; f.delete() }
        runCatching { bio?.remove(id) }
        if (snapshot().wallets.none { it.state == WalletState.ACTIVE }) runCatching { bio?.disable() }
    }

    /** Removes a QUARANTINED wallet entry and its unusable envelope, on explicit user request only. */
    fun removeQuarantined(walletId: String) {
        val s = snapshot()
        val e = s.wallets.firstOrNull { it.walletIdHex == walletId } ?: throw CustodyException(CustodyException.Reason.UNKNOWN_WALLET)
        if (e.state != WalletState.QUARANTINED) throw CustodyException(CustodyException.Reason.AUTH_REQUIRED)
        registry.save(s.copy(wallets = s.wallets - e))
        envFile(walletId).delete()
        bio?.remove(walletId)
    }

    // ------------------------------------------------------------------ internals

    private fun snapshot(): WalletRegistry.Snapshot = try {
        registry.load()
    } catch (_: Exception) {
        throw CustodyException(CustodyException.Reason.CORRUPTED)
    }

    private fun entry(id: String) = snapshot().wallets.firstOrNull { it.walletIdHex == id } ?: throw CustodyException(CustodyException.Reason.UNKNOWN_WALLET)

    private fun loadEnvelope(e: WalletEntry): LocalEnvelope {
        val f = envFile(e.walletIdHex)
        if (!f.exists()) throw CustodyException(CustodyException.Reason.CORRUPTED)
        val env = try {
            LocalEnvelope.parse(f.readBytes())
        } catch (x: EnvelopeFormatException) {
            // A newer envelope version is refused without touching the file (no downgrade rewrite).
            throw CustodyException(if (x.error == EnvelopeError.UNSUPPORTED_VERSION) CustodyException.Reason.UNSUPPORTED_VERSION else CustodyException.Reason.CORRUPTED)
        }
        val v = snapshot().vault
        // Binding checks: the file must belong to this registry entry (no swapping/mixing of envelopes).
        if (hexOf(env.materialId()) != e.walletIdHex || hexOf(env.expectedPubkey()) != e.publicKeyHex ||
            env.materialKind != e.materialKind || env.derivationProfile != e.derivationProfile || env.accountIndex != e.accountIndex ||
            (e.state == WalletState.ACTIVE && v != null && (hexOf(env.vaultId()) != v.vaultIdHex || hexOf(env.aliasId()) != v.aliasIdHex))
        ) throw CustodyException(CustodyException.Reason.CORRUPTED)
        return env
    }

    private fun quarantineAll() {
        val s = snapshot()
        registry.save(s.copy(wallets = s.wallets.map { if (it.state == WalletState.ACTIVE) it.copy(state = WalletState.QUARANTINED) else it }))
    }

    private fun mapKek(e: KekException) = CustodyException(
        when (e.kind) {
            KekException.Kind.INVALIDATED -> CustodyException.Reason.KEY_INVALIDATED
            KekException.Kind.NOT_FOUND -> CustodyException.Reason.KEY_MISSING
            KekException.Kind.AUTH_REQUIRED -> CustodyException.Reason.AUTH_REQUIRED
            KekException.Kind.BAD_TAG -> CustodyException.Reason.CORRUPTED
            KekException.Kind.UNAVAILABLE -> CustodyException.Reason.KEYSTORE
        },
    )

    private fun WalletEntry.toInfo() = WalletInfo(
        walletIdHex, name, role, CustodyProviderId.entries.first { it.code == providerId }, materialKind == PendingMaterial.KIND_BIP39,
        AccountProfile.ofId(derivationProfile) ?: AccountProfile.NONE, accountIndex, SolanaPublicKey.of(hex(publicKeyHex)),
        seedGroupHex, createdAtMs, state,
    )

    private fun envFile(idHex: String) = File(envDir, "$idHex.lwle")
    private fun rand(n: Int) = ByteArray(n).also(random::nextBytes)

    private fun validName(name: String): String {
        val t = name.trim()
        if (t.isEmpty() || t.length > WalletRegistry.MAX_NAME || t.any { it.isISOControl() }) throw CustodyException(CustodyException.Reason.BAD_NAME)
        return t
    }

    companion object {
        internal fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        internal fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
