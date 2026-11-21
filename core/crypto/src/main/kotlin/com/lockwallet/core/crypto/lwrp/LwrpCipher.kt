package com.lockwallet.core.crypto.lwrp

import com.lockwallet.core.crypto.Argon2id
import com.lockwallet.core.crypto.Bip39Wordlist
import com.lockwallet.core.crypto.KdfProfile
import com.lockwallet.core.crypto.SecretBytes
import com.lockwallet.core.crypto.aead.AeadAuthenticationException
import com.lockwallet.core.crypto.aead.AesGcm
import com.lockwallet.core.crypto.wipe
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.text.Normalizer

/** Why a password was refused before any KDF (shown to the user; never reveals file content). */
enum class LwrpPasswordError { TOO_SHORT, TOO_LONG, INVALID_UNICODE }

class LwrpPasswordException(val error: LwrpPasswordError) : IllegalArgumentException("password rejected: $error")

/** Generic failure to open a package: wrong password ≡ corrupted ≡ tampered (spec §10.6). */
class LwrpOpenException : Exception("package could not be opened")

/**
 * New exports use LWRP v2 / STRONG_V2. LWRP v1 / COMPAT_V1 remains readable for existing backups.
 * Both versions retain the 46-byte header, authenticated in full as AES-256-GCM AAD. Independent of the PIN.
 */
object LwrpCipher {
    const val MIN_PASSWORD_BYTES = 16
    const val MAX_PASSWORD_BYTES = 256
    const val GENERATED_WORDS = 8 // 8 × 11 bits = 88 bits

    private val LEGACY_KDF = KdfProfile.OWASP_V1
    private val WRITE_KDF = KdfProfile.STRONG_V2

    private fun cryptoProfile(profile: LwrpKdfProfile): KdfProfile = when (profile) {
        LwrpKdfProfile.COMPAT_V1 -> LEGACY_KDF
        LwrpKdfProfile.STRONG_V2 -> WRITE_KDF
    }

    init {
        check(LEGACY_KDF.memoryKiB.toLong() == LwrpKdfProfile.COMPAT_V1.memoryKiB &&
            LEGACY_KDF.iterations.toLong() == LwrpKdfProfile.COMPAT_V1.iterations &&
            LEGACY_KDF.parallelism.toLong() == LwrpKdfProfile.COMPAT_V1.parallelism)
        check(WRITE_KDF.memoryKiB.toLong() == LwrpKdfProfile.STRONG_V2.memoryKiB &&
            WRITE_KDF.iterations.toLong() == LwrpKdfProfile.STRONG_V2.iterations &&
            WRITE_KDF.parallelism.toLong() == LwrpKdfProfile.STRONG_V2.parallelism)
    }

    /**
     * NFC + UTF-8 (strict: unpaired surrogates rejected). If the input is already NFC (always true for ASCII)
     * no String copy is made; otherwise java.text.Normalizer necessarily creates one (ADR 0013 residual risk).
     */
    fun encodePassword(password: CharArray): SecretBytes {
        val cs = CharBuffer.wrap(password)
        var normalized: CharArray? = null
        val source: CharBuffer = if (Normalizer.isNormalized(cs, Normalizer.Form.NFC)) {
            CharBuffer.wrap(password)
        } else {
            normalized = Normalizer.normalize(cs, Normalizer.Form.NFC).toCharArray()
            CharBuffer.wrap(normalized)
        }
        try {
            val enc = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val bb = try {
                enc.encode(source)
            } catch (_: CharacterCodingException) {
                throw LwrpPasswordException(LwrpPasswordError.INVALID_UNICODE)
            }
            val out = ByteArray(bb.remaining()).also { bb.get(it) }
            if (bb.hasArray()) bb.array().wipe()
            if (out.size < MIN_PASSWORD_BYTES) { out.wipe(); throw LwrpPasswordException(LwrpPasswordError.TOO_SHORT) }
            if (out.size > MAX_PASSWORD_BYTES) { out.wipe(); throw LwrpPasswordException(LwrpPasswordError.TOO_LONG) }
            return SecretBytes.adopt(out)
        } finally {
            normalized?.wipe()
        }
    }

    /** Suggested password: 8 random BIP-39 English words separated by single spaces (88 bits). */
    fun generatePassword(random: SecureRandom = SecureRandom()): CharArray {
        val words = (0 until GENERATED_WORDS).map { Bip39Wordlist.words[random.nextInt(2048)] }
        val out = CharArray(words.sumOf { it.length } + GENERATED_WORDS - 1)
        var p = 0
        words.forEachIndexed { i, w ->
            if (i > 0) out[p++] = ' '
            w.toCharArray().copyInto(out, p); p += w.length
        }
        return out
    }

    /** Encrypts new backups as LWRP v2 with the current high-cost Argon2id profile. */
    fun seal(payload: LwrpPayload, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        payload.verifyPublicKey()
        val salt = ByteArray(LwrpHeader.SALT_LEN).also(random::nextBytes)
        val nonce = ByteArray(LwrpHeader.NONCE_LEN).also(random::nextBytes)
        val header = LwrpHeader(salt, LwrpKdfProfile.STRONG_V2, nonce, LwrpHeader.VERSION_STRONG).encode()
        val plain = payload.encode()
        try {
            encodePassword(password).use { pw ->
                pw.withBytes { p -> Argon2id.derive(p, salt, WRITE_KDF) }.use { key ->
                    val ct = AesGcm.encrypt(key, nonce, header, plain)
                    return header + ct
                }
            }
        } finally {
            plain.wipe()
        }
    }

    /** Deterministic variant for regression vectors only. */
    internal fun sealWith(payload: LwrpPayload, password: CharArray, salt: ByteArray, nonce: ByteArray): ByteArray {
        payload.verifyPublicKey()
        val header = LwrpHeader(salt, LwrpKdfProfile.COMPAT_V1, nonce, LwrpHeader.VERSION).encode()
        val plain = payload.encode()
        try {
            encodePassword(password).use { pw ->
                pw.withBytes { p -> Argon2id.derive(p, salt, LEGACY_KDF) }.use { key ->
                    val ct = AesGcm.encrypt(key, nonce, header, plain)
                    return header + ct
                }
            }
        } finally {
            plain.wipe()
        }
    }

    /**
     * Opens a file: exact size/structure checks before the KDF (throws [LwrpFormatException]), then Argon2id,
     * AES-GCM and strict payload parse + pubkey re-derivation. Any post-KDF failure is a [LwrpOpenException].
     */
    fun open(file: ByteArray, password: CharArray): LwrpPayload {
        val header = LwrpHeader.parseFile(file)
        val aad = file.copyOfRange(0, LwrpHeader.HEADER_LEN)
        val ct = file.copyOfRange(LwrpHeader.HEADER_LEN, file.size)
        encodePassword(password).use { pw ->
            val profile = cryptoProfile(header.kdf)
            pw.withBytes { p -> Argon2id.derive(p, header.salt(), profile) }.use { key ->
                val plain = try {
                    AesGcm.decrypt(key, header.nonce(), aad, ct)
                } catch (_: AeadAuthenticationException) {
                    throw LwrpOpenException()
                }
                plain.use { pt ->
                    val payload = try {
                        pt.withBytes { LwrpPayload.parse(it) }
                    } catch (_: LwrpFormatException) {
                        throw LwrpOpenException()
                    }
                    try {
                        payload.verifyPublicKey()
                    } catch (_: Exception) {
                        payload.close()
                        throw LwrpOpenException()
                    }
                    return payload
                }
            }
        }
    }
}
