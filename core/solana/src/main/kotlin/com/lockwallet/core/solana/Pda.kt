package com.lockwallet.core.solana

import java.math.BigInteger

/** Ed25519 curve membership (same rule as curve25519-dalek `CompressedEdwardsY::decompress().is_some()`). */
object Ed25519Curve {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val P_MINUS_1_HALF = P.subtract(BigInteger.ONE).shiftRight(1)

    fun isOnCurve(compressed: ByteArray): Boolean {
        require(compressed.size == 32)
        val le = compressed.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }
        val y = BigInteger(1, le.reversedArray()).mod(P)
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        if (v.signum() == 0) return u.signum() == 0
        val x2 = u.multiply(v.modInverse(P)).mod(P)
        if (x2.signum() == 0) return true
        return x2.modPow(P_MINUS_1_HALF, P) == BigInteger.ONE // Euler's criterion: x² has a root
    }
}

/** Program-derived and seed-derived addresses (solana-pubkey semantics). */
object Pda {
    private val MARKER = "ProgramDerivedAddress".toByteArray(Charsets.US_ASCII)
    const val MAX_SEED_LEN = 32

    fun createProgramAddress(seeds: List<ByteArray>, program: SolanaPublicKey): SolanaPublicKey? {
        require(seeds.size <= 16 && seeds.all { it.size <= MAX_SEED_LEN }) { "bad seeds" }
        val h = sha256(*seeds.toTypedArray(), program.toByteArray(), MARKER)
        return if (Ed25519Curve.isOnCurve(h)) null else SolanaPublicKey.of(h)
    }

    fun findProgramAddress(seeds: List<ByteArray>, program: SolanaPublicKey): Pair<SolanaPublicKey, Int> {
        for (bump in 255 downTo 0) {
            createProgramAddress(seeds + listOf(byteArrayOf(bump.toByte())), program)?.let { return it to bump }
        }
        throw IllegalStateException("no viable bump")
    }

    /** `Pubkey::create_with_seed`: sha256(base ‖ seed ‖ owner); seed is ASCII/UTF-8 ≤ 32 bytes. */
    fun createWithSeed(base: SolanaPublicKey, seed: String, owner: SolanaPublicKey): SolanaPublicKey {
        val s = seed.toByteArray(Charsets.UTF_8)
        require(s.size <= MAX_SEED_LEN) { "seed too long" }
        val o = owner.toByteArray()
        require(!o.copyOfRange(32 - MARKER.size, 32).contentEquals(MARKER)) { "illegal owner" }
        return SolanaPublicKey.of(sha256(base.toByteArray(), s, o))
    }

    fun associatedTokenAddress(owner: SolanaPublicKey, mint: SolanaPublicKey, tokenProgram: SolanaPublicKey): SolanaPublicKey =
        findProgramAddress(listOf(owner.toByteArray(), tokenProgram.toByteArray(), mint.toByteArray()), ProgramIds.ASSOCIATED_TOKEN).first

    fun metaplexMetadata(mint: SolanaPublicKey): SolanaPublicKey =
        findProgramAddress(listOf("metadata".toByteArray(), ProgramIds.METAPLEX_METADATA.toByteArray(), mint.toByteArray()), ProgramIds.METAPLEX_METADATA).first
}
