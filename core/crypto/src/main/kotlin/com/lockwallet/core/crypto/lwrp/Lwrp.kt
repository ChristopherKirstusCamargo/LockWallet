package com.lockwallet.core.crypto.lwrp

import com.lockwallet.core.crypto.Bip39
import com.lockwallet.core.crypto.DerivationProfile
import com.lockwallet.core.crypto.SecretBytes
import com.lockwallet.core.crypto.Slip10Ed25519
import com.lockwallet.core.crypto.wipe

/**
 * LWRP v1 binary contract — structure only (spec §10.2–10.3, v2.1 §2–§3, errata E-01).
 * Encryption (Argon2id v0x13 -> AES-256-GCM, AAD = the 46 header bytes) is added in Phase B on top of
 * these codecs; nothing here touches a password or a key.
 *
 *  file    = header(46) || AES-GCM(ciphertext of payload) || tag(16)
 *  payload = payloadHeader(48) || material(material_length)
 *  => valid file size = 46 + 48 + material_length + 16 = 110 + material_length, i.e. exactly one of
 *     126, 130, 134, 138, 142 (BIP-39 entropy 16..32 B) or 174 (imported keypair 64 B).
 * The size is validated exactly, before any KDF or allocation proportional to input (errata E-01
 * supersedes the v2.0 "62 B .. 1 MiB" window, which the v2.1 fixed payload makes obsolete).
 */
enum class LwrpError {
    BAD_SIZE, BAD_MAGIC, BAD_VERSION, BAD_KDF_ID, KDF_PROFILE_NOT_ALLOWED,
    BAD_PAYLOAD_MAGIC, BAD_PAYLOAD_VERSION, BAD_MATERIAL_KIND, BAD_LANGUAGE, BAD_DERIVATION_PROFILE,
    BAD_ACCOUNT_INDEX, BAD_MATERIAL_LENGTH, LENGTH_MISMATCH, UNKNOWN_FLAGS, PUBKEY_MISMATCH, INCONSISTENT_KEYPAIR,
}

/** Structural rejection. User-facing code MUST map every value to one generic "package could not be opened"
 *  message so a wrong password is indistinguishable from corruption (spec §10.6). */
class LwrpFormatException(val error: LwrpError) : IllegalArgumentException("LWRP rejected: $error")

private fun fail(e: LwrpError): Nothing = throw LwrpFormatException(e)

/** Approved Argon2id (v=0x13, 32-byte output) profiles. Only exact matches are accepted (v2.1 §2). */
enum class LwrpKdfProfile(val memoryKiB: Long, val iterations: Long, val parallelism: Long) {
    /** OWASP minimum / compatibility profile. Stronger profiles need benchmark + ADR + vectors. */
    COMPAT_V1(19456, 2, 1);

    companion object {
        fun of(m: Long, t: Long, p: Long): LwrpKdfProfile? =
            entries.firstOrNull { it.memoryKiB == m && it.iterations == t && it.parallelism == p }
    }
}

class LwrpHeader(salt: ByteArray, val kdf: LwrpKdfProfile, nonce: ByteArray) {
    private val saltBytes = salt.copyOf()
    private val nonceBytes = nonce.copyOf()

    init {
        require(saltBytes.size == SALT_LEN && nonceBytes.size == NONCE_LEN)
    }

    fun salt(): ByteArray = saltBytes.copyOf()
    fun nonce(): ByteArray = nonceBytes.copyOf()

    /** The exact 46 bytes, which are also the AES-GCM AAD. */
    fun encode(): ByteArray {
        val b = ByteArray(HEADER_LEN)
        MAGIC.copyInto(b, 0)
        b[4] = VERSION
        b[5] = KDF_ARGON2ID_V13
        saltBytes.copyInto(b, 6)
        putU32(b, 22, kdf.memoryKiB); putU32(b, 26, kdf.iterations); putU32(b, 30, kdf.parallelism)
        nonceBytes.copyInto(b, 34)
        return b
    }

    companion object {
        const val HEADER_LEN = 46
        const val TAG_LEN = 16
        const val SALT_LEN = 16
        const val NONCE_LEN = 12
        const val VERSION: Byte = 0x01
        const val KDF_ARGON2ID_V13: Byte = 0x01
        val MAGIC = byteArrayOf(0x4C, 0x57, 0x52, 0x50) // "LWRP"

        /** Every valid LWRP v1 file size. */
        val VALID_FILE_SIZES: Set<Int> =
            (LwrpPayload.BIP39_ENTROPY_LENGTHS + LwrpPayload.KEYPAIR_LENGTH)
                .map { HEADER_LEN + LwrpPayload.HEADER_LEN + it + TAG_LEN }.toSet()
        val MAX_FILE_SIZE: Int = VALID_FILE_SIZES.max()

        /**
         * Validates a complete file *before* any KDF: exact size, magic, version, KDF id, allowlisted m/t/p.
         * Returns the header; the caller then runs Argon2id/AES-GCM with AAD = bytes[0, 46).
         */
        fun parseFile(file: ByteArray): LwrpHeader {
            if (file.size !in VALID_FILE_SIZES) fail(LwrpError.BAD_SIZE)
            for (i in 0 until 4) if (file[i] != MAGIC[i]) fail(LwrpError.BAD_MAGIC)
            if (file[4] != VERSION) fail(LwrpError.BAD_VERSION)
            if (file[5] != KDF_ARGON2ID_V13) fail(LwrpError.BAD_KDF_ID)
            val kdf = LwrpKdfProfile.of(getU32(file, 22), getU32(file, 26), getU32(file, 30))
                ?: fail(LwrpError.KDF_PROFILE_NOT_ALLOWED)
            return LwrpHeader(file.copyOfRange(6, 22), kdf, file.copyOfRange(34, 46))
        }
    }
}

enum class MaterialKind(val id: Int) { BIP39_ENTROPY(0x01), IMPORTED_ED25519_KEYPAIR(0x02) }

/** Decrypted inner payload (v2.1 §3, 48-byte header + material). Holds secret material: close() wipes. */
class LwrpPayload private constructor(
    val kind: MaterialKind,
    /** Null for imported keypairs (derivation_profile = 0). */
    val derivationProfile: DerivationProfile?,
    val accountIndex: Int,
    expectedPubkey: ByteArray,
    val material: SecretBytes,
) : AutoCloseable {
    private val pub = expectedPubkey.copyOf()
    fun expectedPubkey(): ByteArray = pub.copyOf()
    override fun close() = material.close()
    override fun toString(): String = "LwrpPayload($kind, REDACTED)"

    /** Canonical bytes. Caller owns and must wipe the result (it contains the secret). */
    fun encode(): ByteArray = material.withBytes { m ->
        val b = ByteArray(HEADER_LEN + m.size)
        MAGIC.copyInto(b, 0)
        b[4] = VERSION
        b[5] = kind.id.toByte()
        b[6] = (if (kind == MaterialKind.BIP39_ENTROPY) LANGUAGE_EN else 0).toByte()
        b[7] = (derivationProfile?.id ?: 0).toByte()
        putU32(b, 8, accountIndex.toLong())
        b[12] = (m.size ushr 8).toByte(); b[13] = m.size.toByte()
        // flags (14..15) = 0
        pub.copyInto(b, 16)
        m.copyInto(b, HEADER_LEN)
        b
    }

    /**
     * Re-derives the public key from the material and compares with expected_pubkey (v2.1 §3).
     * BIP-39: entropy -> mnemonic (checksum) -> seed -> SLIP-0010 per profile/index.
     * Keypair: RFC 8032 pubkey of the 32-byte secret == trailing 32 bytes == expected_pubkey.
     */
    fun verifyPublicKey() {
        val derived: ByteArray = material.withBytes { m ->
            when (kind) {
                MaterialKind.BIP39_ENTROPY -> {
                    val words = Bip39.entropyToMnemonic(m)
                    try {
                        Bip39.mnemonicToSeed(words).use { seed ->
                            seed.withBytes { s -> Slip10Ed25519.deriveSolanaKey(s, derivationProfile!!, accountIndex).use { it.publicKey() } }
                        }
                    } finally {
                        words.wipe()
                    }
                }
                MaterialKind.IMPORTED_ED25519_KEYPAIR -> {
                    val sk = m.copyOfRange(0, 32)
                    try {
                        val p = Slip10Ed25519.publicKeyOf(sk)
                        if (!p.contentEquals(m.copyOfRange(32, 64))) fail(LwrpError.INCONSISTENT_KEYPAIR)
                        p
                    } finally {
                        sk.wipe()
                    }
                }
            }
        }
        if (!derived.contentEquals(pub)) fail(LwrpError.PUBKEY_MISMATCH)
    }

    companion object {
        const val HEADER_LEN = 48
        const val VERSION: Byte = 0x01
        const val LANGUAGE_EN = 0x01
        const val KEYPAIR_LENGTH = 64
        val BIP39_ENTROPY_LENGTHS = listOf(16, 20, 24, 28, 32)
        val MAGIC = byteArrayOf(0x4C, 0x57, 0x50, 0x4C) // "LWPL"

        fun bip39(entropy: ByteArray, profile: DerivationProfile, accountIndex: Int, expectedPubkey: ByteArray): LwrpPayload {
            require(entropy.size in BIP39_ENTROPY_LENGTHS && accountIndex >= 0 && expectedPubkey.size == 32)
            return LwrpPayload(MaterialKind.BIP39_ENTROPY, profile, accountIndex, expectedPubkey, SecretBytes.copyOf(entropy))
        }

        fun keypair(secretKey64: ByteArray, expectedPubkey: ByteArray): LwrpPayload {
            require(secretKey64.size == KEYPAIR_LENGTH && expectedPubkey.size == 32)
            return LwrpPayload(MaterialKind.IMPORTED_ED25519_KEYPAIR, null, 0, expectedPubkey, SecretBytes.copyOf(secretKey64))
        }

        /** Strict canonical parse of decrypted plaintext. Does NOT verify the pubkey: call [verifyPublicKey]. */
        fun parse(b: ByteArray): LwrpPayload {
            if (b.size < HEADER_LEN) fail(LwrpError.LENGTH_MISMATCH)
            for (i in 0 until 4) if (b[i] != MAGIC[i]) fail(LwrpError.BAD_PAYLOAD_MAGIC)
            if (b[4] != VERSION) fail(LwrpError.BAD_PAYLOAD_VERSION)
            val kind = MaterialKind.entries.firstOrNull { it.id == b[5].toInt() } ?: fail(LwrpError.BAD_MATERIAL_KIND)
            val language = b[6].toInt()
            val profileId = b[7].toInt()
            val account = getU32(b, 8)
            val len = ((b[12].toInt() and 0xff) shl 8) or (b[13].toInt() and 0xff)
            val flags = ((b[14].toInt() and 0xff) shl 8) or (b[15].toInt() and 0xff)
            if (flags != 0) fail(LwrpError.UNKNOWN_FLAGS)
            val profile: DerivationProfile?
            when (kind) {
                MaterialKind.BIP39_ENTROPY -> {
                    if (language != LANGUAGE_EN) fail(LwrpError.BAD_LANGUAGE)
                    profile = DerivationProfile.ofId(profileId) ?: fail(LwrpError.BAD_DERIVATION_PROFILE)
                    if (account > 0x7fffffffL) fail(LwrpError.BAD_ACCOUNT_INDEX) // hardened index must be < 2^31
                    if (len !in BIP39_ENTROPY_LENGTHS) fail(LwrpError.BAD_MATERIAL_LENGTH)
                }
                MaterialKind.IMPORTED_ED25519_KEYPAIR -> {
                    if (language != 0) fail(LwrpError.BAD_LANGUAGE)
                    if (profileId != 0) fail(LwrpError.BAD_DERIVATION_PROFILE)
                    profile = null
                    if (account != 0L) fail(LwrpError.BAD_ACCOUNT_INDEX)
                    if (len != KEYPAIR_LENGTH) fail(LwrpError.BAD_MATERIAL_LENGTH)
                }
            }
            if (b.size != HEADER_LEN + len) fail(LwrpError.LENGTH_MISMATCH) // no trailing bytes, no truncation
            return LwrpPayload(kind, profile, account.toInt(), b.copyOfRange(16, 48), SecretBytes.copyOf(b, HEADER_LEN, b.size))
        }
    }
}

private fun putU32(b: ByteArray, off: Int, v: Long) {
    require(v in 0..0xffffffffL)
    b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte(); b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
}

private fun getU32(b: ByteArray, off: Int): Long =
    ((b[off].toLong() and 0xff) shl 24) or ((b[off + 1].toLong() and 0xff) shl 16) or
        ((b[off + 2].toLong() and 0xff) shl 8) or (b[off + 3].toLong() and 0xff)
