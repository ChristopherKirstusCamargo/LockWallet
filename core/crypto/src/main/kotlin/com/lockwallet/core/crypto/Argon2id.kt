package com.lockwallet.core.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id v=0x13 (RFC 9106) via the Bouncy Castle lightweight API, 32-byte output.
 * Only allowlisted parameter sets are used by production code (ADR 0002 / ADR 0004): [KdfProfile].
 */
enum class KdfProfile(val id: Int, val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    /** 0x01 — legacy profile for already-created LWLE and LWRP files. Never change its parameters. */
    OWASP_V1(0x01, 19456, 2, 1),
    /** 0x02 — strengthened profile for new credentials: RFC 9106 memory-constrained recommendation, p=4. */
    HARDENED_V2(0x02, 65536, 3, 4);

    companion object {
        fun ofId(id: Int): KdfProfile? = entries.firstOrNull { it.id == id }
    }
}

object Argon2id {
    const val OUTPUT_LEN = 32

    /** Derives a 32-byte key. [password] is not modified; the caller wipes it. */
    fun derive(password: ByteArray, salt: ByteArray, profile: KdfProfile): SecretBytes {
        require(salt.size == 16) { "salt must be 16 bytes" }
        return deriveRaw(password, salt, profile.iterations, profile.memoryKiB, profile.parallelism, OUTPUT_LEN, null, null)
    }

    /** Full RFC 9106 surface, used for the official test vector (secret + associated data). */
    internal fun deriveRaw(
        password: ByteArray, salt: ByteArray, iterations: Int, memoryKiB: Int, parallelism: Int,
        outLen: Int, secret: ByteArray?, ad: ByteArray?,
    ): SecretBytes {
        val b = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations).withMemoryAsKB(memoryKiB).withParallelism(parallelism)
            .withSalt(salt)
        if (secret != null) b.withSecret(secret)
        if (ad != null) b.withAdditional(ad)
        val gen = Argon2BytesGenerator()
        gen.init(b.build())
        val out = ByteArray(outLen)
        gen.generateBytes(password, out)
        return SecretBytes.adopt(out)
    }
}
