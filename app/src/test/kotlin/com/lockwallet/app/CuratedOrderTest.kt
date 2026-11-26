package com.lockwallet.app

import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.data.rpc.EpochCredit
import com.lockwallet.data.rpc.ValidatorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.0: curated order = safety tier first, then the delegator's expected return (on-chain commission + vote credits). */
class CuratedOrderTest {
    private val RUNNING = 1091L
    private fun credits(perEpoch: List<Long>): List<EpochCredit> {
        var acc = 1_000_000L
        return perEpoch.mapIndexed { i, e -> val prev = acc; acc += e; EpochCredit(RUNNING - perEpoch.size + 1 + i, acc, prev) }
    }
    /** Full performance: 3 completed epochs at 1000 credits + a running epoch with 100. */
    private val FULL = listOf(1000L, 1000L, 1000L, 100L)

    private fun live(e: CuratedValidator, commission: Int = e.baselineCommission, perEpoch: List<Long> = FULL, bps: Int? = null, delinquent: Boolean = false, stake: Long = 1_000_000_000_000_000L) =
        ValidatorInfo(SolanaPublicKey.fromBase58(e.vote), SolanaPublicKey.fromBase58(e.node), commission, stake, 1, delinquent, credits(perEpoch), bps)

    private fun crowd(n: Int = 200) = (1..n).map { i ->
        val k = SolanaPublicKey.of(ByteArray(32) { (i * 7 + it).toByte() })
        ValidatorInfo(k, k, 5, 1_000_000_000_000_000L, 1, false, credits(FULL))
    }
    private val L = Curated.LIST
    private fun order(xs: List<ValidatorInfo>) = Curated.evaluate(crowd() + xs).map { it.entry }

    @Test fun returnIndexIsCommissionTimesCredits() {
        val v = Curated.evaluate(crowd() + live(L[2], commission = 5, perEpoch = listOf(900L, 900L, 900L, 1L))).first { it.entry == L[2] }
        assertEquals(500, v.effectiveBps)
        assertEquals(0.9, v.creditRatio!!, 1e-9)
        assertEquals(0.95 * 0.9, v.returnIndex!!, 1e-9)
    }

    @Test fun higherReturnFirstAmongEquallySafe() {
        // 0 % with 99 % credits beats 5 % with 100 %, which beats 2 % with 90 %.
        val got = order(listOf(live(L[0], 0, listOf(990L, 990L, 990L, 1L)), live(L[2], 5), live(L[4], 2, listOf(900L, 900L, 900L, 1L)),
            live(L[1], 0), live(L[3], 0), live(L[5], 0)))
        assertEquals(listOf(L[1], L[3], L[5], L[0], L[2], L[4]), got) // L1/L3/L5 tie at 100 % → list order
    }

    @Test fun safetyAlwaysWinsOverReturn() {
        // L0 pays most but jumped commission? No — make L0 superminority (concentrated) with perfect return; it must sort after any OK one.
        val giant = live(L[0], 0, stake = 900_000_000_000_000_000L)
        val got = Curated.evaluate(crowd(10) + giant + L.drop(1).map { live(it, it.baselineCommission, listOf(500L, 500L, 500L, 1L)) })
        assertEquals(CuratedStatus.CONCENTRATED, got.first { it.entry == L[0] }.status)
        assertTrue(got.indexOfFirst { it.entry == L[0] } > got.indexOfLast { it.status == CuratedStatus.OK })
        // Commission jump sorts after concentrated; delinquent/missing last.
        val g2 = Curated.evaluate(crowd() + live(L[0], 40) + live(L[1], delinquent = true) + L.drop(2).map { live(it) })
        assertEquals(L[0], g2[g2.size - 2].entry); assertEquals(CuratedStatus.COMMISSION_UP, g2[g2.size - 2].status)
        assertEquals(L[1], g2.last().entry); assertTrue(g2.last().hidden)
    }

    @Test fun tiesAreDeterministicAndBrokenByCommissionThenCreditsThenListOrder() {
        val all = L.map { live(it, 0) }
        val a = order(all); val b = order(all.reversed()); val c = order(all.shuffled(java.util.Random(7)))
        assertEquals(L, a); assertEquals(a, b); assertEquals(a, c)
        // Same return index after rounding (0,1 %), lower commission first.
        val t = order(listOf(live(L[5], 0, listOf(950L, 950L, 950L, 1L)), live(L[2], 5)) + L.filter { it != L[5] && it != L[2] }.map { live(it, 0, listOf(10L, 10L, 10L, 1L)) })
        assertEquals(listOf(L[5], L[2]), t.take(2))
    }

    @Test fun unknownPerformanceSortsAfterKnownWithinTheTier() {
        val got = Curated.evaluate(crowd() + live(L[0], 0, perEpoch = emptyList()) + L.drop(1).map { live(it, it.baselineCommission, listOf(500L, 500L, 500L, 1L)) })
        assertNull(got.first { it.entry == L[0] }.returnIndex)
        assertEquals(L[0], got.last { it.status == CuratedStatus.OK }.entry)
    }

    @Test fun bpsCommissionIsUsedWhenWorse() {
        val v = Curated.evaluate(crowd() + live(L[0], 0, bps = 150)).first { it.entry == L[0] }
        assertEquals(150, v.effectiveBps) // 0 % rounded on the percent field, 1,5 % in bps → worst wins
        assertEquals(CuratedStatus.OK, v.status)
        val jump = Curated.evaluate(crowd() + live(L[0], 0, bps = 250)).first { it.entry == L[0] }
        assertEquals(CuratedStatus.COMMISSION_UP, jump.status) // 2,5 % > baseline 0 + 2 pp
    }

    /** Hostile data: decreasing credits, duplicated epochs, absurd outlier, overflowing numbers. Never throws; never helps the liar. */
    @Test fun hostileCreditsFailClosedOrAreIgnored() {
        val e = L[0]
        val neg = live(e).copy(epochCredits = listOf(EpochCredit(RUNNING - 1, 10, 50), EpochCredit(RUNNING, 60, 10)))
        assertNull(Curated.evaluate(crowd() + neg).first { it.entry == e }.creditRatio)
        val dup = live(e).copy(epochCredits = credits(FULL) + EpochCredit(RUNNING - 1, 5_000_000, 1))
        assertNull(Curated.evaluate(crowd() + dup).first { it.entry == e }.creditRatio)
        // One fake validator (tiny stake) claiming a billion reward lamports cannot drag everyone's ratio down
        // (stake-weighted median reference).
        val k = SolanaPublicKey.of(ByteArray(32) { 99 })
        val liar = ValidatorInfo(k, k, 0, 1, 1, false, credits(listOf(1_000_000_000L, 1_000_000_000L, 1_000_000_000L, 1L)))
        assertEquals(1.0, Curated.evaluate(crowd() + liar + live(e)).first { it.entry == e }.creditRatio!!, 1e-9)
        // A curated validator claiming more credits than the reference is capped at 100 % (no bonus for lying).
        assertEquals(1.0, Curated.evaluate(crowd() + live(e, perEpoch = listOf(9_000L, 9_000L, 9_000L, 1L))).first { it.entry == e }.creditRatio!!, 1e-9)
        val huge = live(e).copy(epochCredits = listOf(EpochCredit(RUNNING - 1, Long.MAX_VALUE, 0), EpochCredit(RUNNING, Long.MAX_VALUE, Long.MAX_VALUE - 1)))
        Curated.evaluate(crowd() + huge) // must not throw
        val rnd = java.util.Random(11)
        repeat(300) {
            val noise = L.map { c -> live(c, rnd.nextInt(12), (0 until rnd.nextInt(6)).map { rnd.nextLong() % 5000 }, if (rnd.nextBoolean()) rnd.nextInt(1200) else null, rnd.nextInt(8) == 0) }
            val out = Curated.evaluate(crowd(rnd.nextInt(30)) + noise)
            assertEquals(L.size, out.size)
            for (i in 1 until out.size) assertTrue(Curated.ORDER.compare(out[i - 1], out[i]) <= 0)
            for (i in 1 until out.size) assertTrue(Curated.tier(out[i - 1].status) <= Curated.tier(out[i].status))
            out.forEach { v -> v.returnIndex?.let { assertTrue(it in 0.0..1.0) }; v.creditRatio?.let { assertTrue(it in 0.0..1.0) } }
        }
    }

    /** After Alpenglow the counter holds reward lamports, which grow with stake: a bigger validator must not rank higher. */
    @Test fun rewardsAreMeasuredPerStakedSolSoSizeIsNotPerformance() {
        val big = live(L[0], stake = 4_000_000_000_000_000L, perEpoch = listOf(4000L, 4000L, 4000L, 1L)) // 4× stake, 4× lamports
        val small = live(L[1], stake = 1_000_000_000_000_000L, perEpoch = listOf(1000L, 1000L, 1000L, 1L))
        val out = Curated.evaluate(crowd() + big + small)
        assertEquals(1.0, out.first { it.entry == L[0] }.creditRatio!!, 1e-9)
        assertEquals(1.0, out.first { it.entry == L[1] }.creditRatio!!, 1e-9)
        // Half the rate per staked SOL ⇒ half the performance, whatever the size.
        val weak = live(L[2], stake = 9_000_000_000_000_000L, perEpoch = listOf(4500L, 4500L, 4500L, 1L))
        assertEquals(0.5, Curated.evaluate(crowd() + weak).first { it.entry == L[2] }.creditRatio!!, 1e-9)
    }

    /** Epochs up to the Alpenglow migration are in other units and are never used. */
    @Test fun preAlpenglowEpochsAreIgnored() {
        fun old(k: SolanaPublicKey) = ValidatorInfo(k, k, 0, 1_000_000_000_000_000L, 1, false,
            listOf(EpochCredit(1060, 2_000, 1_000), EpochCredit(1061, 3_000, 2_000), EpochCredit(1062, 4_000, 3_000), EpochCredit(1063, 5_000, 4_000)))
        val crowd = (1..50).map { i -> old(SolanaPublicKey.of(ByteArray(32) { (i * 3 + it).toByte() })) }
        assertTrue(Curated.referenceRates(crowd).isEmpty())
        val e = live(L[0]).copy(epochCredits = old(SolanaPublicKey.of(ByteArray(32))).epochCredits)
        assertNull(Curated.evaluate(crowd + e).first { it.entry == L[0] }.creditRatio)
    }
}
