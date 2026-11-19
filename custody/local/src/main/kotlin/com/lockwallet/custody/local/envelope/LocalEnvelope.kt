package com.lockwallet.custody.local.envelope

/**
 * LWLE v1 — the local envelope binary contract (ADR 0001). Pure codec: no keys, no I/O.
 * All integers big-endian, no optional fields; total size = material_length + 236, validated exactly.
 * dek_pin itself is NEVER stored: only its Keystore wrap (otherwise the PIN could be brute-forced offline
 * without the KEK — correction E-23 to the Phase A table).
 */
enum class EnvelopeError {
    BAD_SIZE, BAD_MAGIC, UNSUPPORTED_VERSION, BAD_SUITE, BAD_MATERIAL_KIND, BAD_SCHEMA, BAD_DERIVATION_PROFILE,
    BAD_ACCOUNT_INDEX, BAD_KDF_PROFILE, BAD_AUTH_MODE, UNKNOWN_FLAGS, BAD_MATERIAL_LENGTH, LENGTH_MISMATCH,
}

class EnvelopeFormatException(val error: EnvelopeError) : IllegalArgumentException("LWLE rejected: $error")

private fun fail(e: EnvelopeError): Nothing = throw EnvelopeFormatException(e)

/** Parsed (or to-be-written) envelope. Ciphertexts are not secret; the header is the AAD source. */
class LocalEnvelope(
    vaultId: ByteArray,
    materialId: ByteArray,
    val materialKind: Int,
    val derivationProfile: Int,
    val accountIndex: Int,
    expectedPubkey: ByteArray,
    val kdfProfile: Int,
    pinSalt: ByteArray,
    aliasId: ByteArray,
    val authMode: Int,
    val materialLength: Int,
    seedNonce: ByteArray,
    seedCt: ByteArray,
    dekPinNonce: ByteArray,
    hwIv: ByteArray,
    hwCt: ByteArray,
) {
    private val vid = vaultId.copyOf(); private val mid = materialId.copyOf(); private val pub = expectedPubkey.copyOf()
    private val salt = pinSalt.copyOf(); private val alias = aliasId.copyOf()
    private val sN = seedNonce.copyOf(); private val sCt = seedCt.copyOf(); private val dN = dekPinNonce.copyOf()
    private val hIv = hwIv.copyOf(); private val hCt = hwCt.copyOf()

    init {
        require(vid.size == 16 && mid.size == 16 && pub.size == 32 && salt.size == 16 && alias.size == 16)
        require(sN.size == 12 && dN.size == 12 && hIv.size == 12)
        require(sCt.size == materialLength + 16 && hCt.size == HW_CT_LEN)
        validateHeaderFields(materialKind, derivationProfile, accountIndex.toLong() and 0xffffffffL, kdfProfile, authMode, 0, materialLength)
    }

    fun vaultId() = vid.copyOf(); fun materialId() = mid.copyOf(); fun expectedPubkey() = pub.copyOf()
    fun pinSalt() = salt.copyOf(); fun aliasId() = alias.copyOf()
    fun seedNonce() = sN.copyOf(); fun seedCt() = sCt.copyOf(); fun dekPinNonce() = dN.copyOf()
    fun hwIv() = hIv.copyOf(); fun hwCt() = hCt.copyOf()

    /** H = bytes [0, 114) — bound into every AAD domain. */
    fun header(): ByteArray = headerOf(vid, mid, materialKind, derivationProfile, accountIndex, pub, kdfProfile, salt, alias, authMode, materialLength)

    fun encode(): ByteArray {
        val out = ByteArray(totalSize(materialLength))
        var o = 0
        fun put(b: ByteArray) { b.copyInto(out, o); o += b.size }
        put(header()); put(sN); put(u32(sCt.size.toLong())); put(sCt); put(dN); put(hIv); put(u16(hCt.size)); put(hCt)
        check(o == out.size)
        return out
    }

    companion object {
        val MAGIC = byteArrayOf(0x4C, 0x57, 0x4C, 0x45) // "LWLE"
        const val VERSION = 0x01
        const val SUITE = 0x01
        const val SCHEMA = 0x01
        const val HEADER_LEN = 114
        /** Keystore AES-GCM over dek_pin (= AES-GCM(pin_key, DEK) = 32 + 16) → 48 + 16. */
        const val HW_CT_LEN = 64
        const val KIND_BIP39 = 0x01
        const val KIND_KEYPAIR = 0x02
        const val AUTH_PIN_ONLY = 0x01
        const val AUTH_PIN_BIOMETRIC = 0x02
        const val KDF_OWASP_V1 = 0x01
        val BIP39_LENGTHS = setOf(16, 20, 24, 28, 32)
        const val KEYPAIR_LENGTH = 64
        val MIN_SIZE = totalSize(16)
        val MAX_SIZE = totalSize(64)

        fun totalSize(materialLength: Int) = 130 + (materialLength + 16) + 12 + 12 + 2 + HW_CT_LEN

        val DOMAIN_SEED = "lock-local-seed-v1".toByteArray(Charsets.US_ASCII)
        val DOMAIN_PIN = "lock-local-pin-v1".toByteArray(Charsets.US_ASCII)
        val DOMAIN_HW = "lock-local-hw-v1".toByteArray(Charsets.US_ASCII)

        fun aad(domain: ByteArray, header: ByteArray): ByteArray = domain + byteArrayOf(0) + header

        fun headerOf(
            vaultId: ByteArray, materialId: ByteArray, kind: Int, profile: Int, account: Int, pubkey: ByteArray,
            kdf: Int, pinSalt: ByteArray, aliasId: ByteArray, authMode: Int, materialLength: Int,
        ): ByteArray {
            val h = ByteArray(HEADER_LEN)
            MAGIC.copyInto(h, 0); h[4] = VERSION.toByte(); h[5] = SUITE.toByte()
            vaultId.copyInto(h, 6); materialId.copyInto(h, 22)
            h[38] = kind.toByte(); h[39] = SCHEMA.toByte(); h[40] = profile.toByte()
            u32(account.toLong() and 0xffffffffL).copyInto(h, 41)
            pubkey.copyInto(h, 45); h[77] = kdf.toByte(); pinSalt.copyInto(h, 78); aliasId.copyInto(h, 94)
            h[110] = authMode.toByte(); h[111] = 0; u16(materialLength).copyInto(h, 112)
            return h
        }

        private fun validateHeaderFields(kind: Int, profile: Int, account: Long, kdf: Int, auth: Int, flags: Int, len: Int) {
            when (kind) {
                KIND_BIP39 -> {
                    if (profile != 0x01 && profile != 0x02) fail(EnvelopeError.BAD_DERIVATION_PROFILE)
                    if (account > 0x7fffffffL) fail(EnvelopeError.BAD_ACCOUNT_INDEX)
                    if (len !in BIP39_LENGTHS) fail(EnvelopeError.BAD_MATERIAL_LENGTH)
                }
                KIND_KEYPAIR -> {
                    if (profile != 0) fail(EnvelopeError.BAD_DERIVATION_PROFILE)
                    if (account != 0L) fail(EnvelopeError.BAD_ACCOUNT_INDEX)
                    if (len != KEYPAIR_LENGTH) fail(EnvelopeError.BAD_MATERIAL_LENGTH)
                }
                else -> fail(EnvelopeError.BAD_MATERIAL_KIND)
            }
            if (kdf != KDF_OWASP_V1) fail(EnvelopeError.BAD_KDF_PROFILE)
            if (auth != AUTH_PIN_ONLY && auth != AUTH_PIN_BIOMETRIC) fail(EnvelopeError.BAD_AUTH_MODE)
            if (flags != 0) fail(EnvelopeError.UNKNOWN_FLAGS)
        }

        /**
         * Strict parse. Order: size window → magic → version (a *newer* version is refused as UNSUPPORTED_VERSION
         * and the caller must leave the file untouched: no downgrade rewrite) → fields → exact size → inner lengths.
         */
        fun parse(b: ByteArray): LocalEnvelope {
            if (b.size < MIN_SIZE || b.size > MAX_SIZE) fail(EnvelopeError.BAD_SIZE)
            for (i in 0 until 4) if (b[i] != MAGIC[i]) fail(EnvelopeError.BAD_MAGIC)
            if (b[4].toInt() and 0xff != VERSION) fail(EnvelopeError.UNSUPPORTED_VERSION)
            if (b[5].toInt() and 0xff != SUITE) fail(EnvelopeError.BAD_SUITE)
            if (b[39].toInt() and 0xff != SCHEMA) fail(EnvelopeError.BAD_SCHEMA)
            val kind = b[38].toInt() and 0xff
            val profile = b[40].toInt() and 0xff
            val account = getU32(b, 41)
            val kdf = b[77].toInt() and 0xff
            val auth = b[110].toInt() and 0xff
            val flags = b[111].toInt() and 0xff
            val len = getU16(b, 112)
            validateHeaderFields(kind, profile, account, kdf, auth, flags, len)
            if (b.size != totalSize(len)) fail(EnvelopeError.LENGTH_MISMATCH)
            if (getU32(b, 126) != (len + 16).toLong()) fail(EnvelopeError.LENGTH_MISMATCH)
            var o = 130
            val seedCt = b.copyOfRange(o, o + len + 16); o += len + 16
            val dN = b.copyOfRange(o, o + 12); o += 12
            val hIv = b.copyOfRange(o, o + 12); o += 12
            if (getU16(b, o) != HW_CT_LEN) fail(EnvelopeError.LENGTH_MISMATCH); o += 2
            val hCt = b.copyOfRange(o, o + HW_CT_LEN); o += HW_CT_LEN
            check(o == b.size)
            return LocalEnvelope(
                b.copyOfRange(6, 22), b.copyOfRange(22, 38), kind, profile, account.toInt(), b.copyOfRange(45, 77), kdf,
                b.copyOfRange(78, 94), b.copyOfRange(94, 110), auth, len, b.copyOfRange(114, 126), seedCt, dN, hIv, hCt,
            )
        }

        private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        private fun getU32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)
        private fun getU16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    }
}
