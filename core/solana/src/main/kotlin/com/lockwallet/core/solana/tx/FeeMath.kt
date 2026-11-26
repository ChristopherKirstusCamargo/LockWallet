package com.lockwallet.core.solana.tx

import com.lockwallet.core.solana.ByteReader
import com.lockwallet.core.solana.MalformedException
import com.lockwallet.core.solana.ProgramIds
import java.math.BigInteger

/**
 * Deterministic upper bound of what a legacy message can cost its fee payer in network fees, computed from the
 * bytes alone (never from the RPC): base fee per signature + compute-unit price × compute-unit limit.
 * Used to show the fee the user will pay and to refuse fee-drain messages (a message setting an absurd priority fee).
 */
object FeeMath {
    const val LAMPORTS_PER_SIGNATURE = 5_000L
    /** Upper bound of the runtime default when no SetComputeUnitLimit is present (200k per instruction). */
    const val DEFAULT_UNITS_PER_INSTRUCTION = 200_000L
    const val MAX_UNITS = 1_400_000L
    private val MICRO = BigInteger.valueOf(1_000_000L)

    /** Compute budget requested by a message. [microLamportsPerUnit] is 0 when no price is set. */
    data class Budget(val unitLimit: Long, val microLamportsPerUnit: Long, val explicitLimit: Boolean) {
        /** ceil(price × limit / 10^6), saturating at Long.MAX_VALUE. */
        val priorityLamports: Long get() = priority(unitLimit, microLamportsPerUnit)
    }

    fun priority(units: Long, microLamportsPerUnit: Long): Long {
        require(units >= 0 && microLamportsPerUnit >= 0)
        val v = BigInteger.valueOf(units).multiply(BigInteger.valueOf(microLamportsPerUnit)).add(MICRO.subtract(BigInteger.ONE)).divide(MICRO)
        return if (v.bitLength() > 62) Long.MAX_VALUE else v.toLong()
    }

    /**
     * Compute budget of [m], or null if its Compute Budget instructions are malformed, duplicated, carry accounts
     * or use a variant we do not price (heap frame, loaded-data limit): callers must then refuse to sign.
     */
    fun budget(m: LegacyMessage): Budget? {
        var limit: Long? = null; var price: Long? = null; var others = 0L
        for (ix in m.instructions) {
            val program = m.accountKeys.getOrNull(ix.programIndex) ?: return null
            if (program != ProgramIds.COMPUTE_BUDGET) { others++; continue }
            if (ix.accountIndexes.isNotEmpty()) return null
            try {
                val r = ByteReader(ix.data)
                when (r.u8()) {
                    2 -> { if (limit != null) return null; limit = r.u32(); r.expectEnd() }
                    3 -> { if (price != null) return null; price = r.u64(); r.expectEnd() }
                    else -> return null
                }
            } catch (_: MalformedException) { return null }
        }
        val explicit = limit != null
        val units = (limit ?: (DEFAULT_UNITS_PER_INSTRUCTION * maxOf(others, 1L))).coerceAtMost(MAX_UNITS)
        return Budget(units, price ?: 0L, explicit)
    }

    /** Base + priority fee upper bound in lamports, or null when the budget cannot be priced. */
    fun maxFee(m: LegacyMessage): Long? {
        val b = budget(m) ?: return null
        val base = LAMPORTS_PER_SIGNATURE * m.numRequiredSignatures
        return if (b.priorityLamports > Long.MAX_VALUE - base) Long.MAX_VALUE else base + b.priorityLamports
    }
}
