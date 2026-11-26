package com.lockwallet.app

import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.data.rpc.ValidatorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0022: the curated block's fixed list and its live health rules (pure logic, no network). */
class CuratedValidatorsTest {
    private fun live(e: CuratedValidator, commission: Int = e.baselineCommission, stake: Long = 1_000_000_000_000_000L, delinquent: Boolean = false, node: String = e.node) =
        ValidatorInfo(SolanaPublicKey.fromBase58(e.vote), SolanaPublicKey.fromBase58(node), commission, stake, 1, delinquent)

    /** A big crowd of small validators so none of the curated ones lands in the superminority. */
    private fun crowd(n: Int = 200) = (1..n).map { i ->
        val k = ByteArray(32) { (i * 7 + it).toByte() }
        ValidatorInfo(SolanaPublicKey.of(k), SolanaPublicKey.of(k), 5, 1_000_000_000_000_000L, 1, false)
    }

    @Test fun listIsAtLeastFiveUniqueValidKeysWithSaneBaselines() {
        assertTrue(Curated.LIST.size >= 5)
        assertEquals(Curated.LIST.size, Curated.LIST.map { it.vote }.toSet().size)
        assertEquals(Curated.LIST.size, Curated.LIST.map { it.node }.toSet().size)
        for (e in Curated.LIST) {
            assertNotNull(e.name, SolanaPublicKey.fromBase58OrNull(e.vote))
            assertNotNull(e.name, SolanaPublicKey.fromBase58OrNull(e.node))
            assertTrue(e.name, e.baselineCommission in 0..Curated.MAX_COMMISSION)
            assertTrue(e.name, e.name.isNotBlank() && e.name.length <= 24)
        }
    }

    @Test fun healthyEntriesAreAllSelectable() {
        val v = Curated.evaluate(crowd() + Curated.LIST.map { live(it) })
        assertEquals(Curated.LIST.size, v.size)
        assertTrue(v.all { it.status == CuratedStatus.OK && it.selectable && !it.hidden })
    }

    @Test fun delinquentOrMissingIsHiddenAndNotSelectable() {
        val (a, b) = Curated.LIST
        val v = Curated.evaluate(crowd() + Curated.LIST.drop(2).map { live(it) } + live(a, delinquent = true)).associateBy { it.entry }
        assertEquals(CuratedStatus.DELINQUENT, v[a]!!.status); assertTrue(v[a]!!.hidden); assertFalse(v[a]!!.selectable)
        assertEquals(CuratedStatus.MISSING, v[b]!!.status); assertTrue(v[b]!!.hidden); assertFalse(v[b]!!.selectable)
        // Hidden ones sort last.
        assertTrue(Curated.evaluate(crowd() + Curated.LIST.drop(2).map { live(it) } + live(a, delinquent = true)).takeLast(2).all { it.hidden })
    }

    @Test fun commissionJumpIsFlaggedAndBlocked() {
        val e = Curated.LIST.first()
        fun st(c: Int) = Curated.evaluate(crowd() + live(e, commission = c)).first { it.entry == e }
        assertEquals(CuratedStatus.OK, st(e.baselineCommission + Curated.JUMP_POINTS).status)
        val up = st(e.baselineCommission + Curated.JUMP_POINTS + 1)
        assertEquals(CuratedStatus.COMMISSION_UP, up.status); assertFalse(up.selectable); assertFalse(up.hidden)
        assertEquals(CuratedStatus.COMMISSION_UP, st(100).status)
        // Absolute ceiling holds even for an entry whose baseline is high.
        val hi = e.copy(baselineCommission = 9)
        assertEquals(CuratedStatus.COMMISSION_UP, Curated.status(hi, live(hi, commission = 11), emptySet()))
    }

    @Test fun swappedNodeIdentityIsTreatedAsUnknown() {
        val e = Curated.LIST.first()
        val other = Curated.LIST[1].node
        assertEquals(CuratedStatus.MISSING, Curated.status(e, live(e, node = other), emptySet()))
    }

    @Test fun superminorityMemberIsFlaggedButStillSelectable() {
        val e = Curated.LIST.first()
        // e holds half of all stake → it is in the smallest set that passes 1/3.
        val v = Curated.evaluate(crowd(10) + live(e, stake = 20_000_000_000_000_000L)).first { it.entry == e }
        assertEquals(CuratedStatus.CONCENTRATED, v.status); assertTrue(v.selectable)
        assertTrue(Curated.superminority(emptyList()).isEmpty())
    }

    @Test fun superminorityIsTheSmallestSetAboveOneThird() {
        val big = crowd(3).mapIndexed { i, v -> v.copy(activatedStake = listOf(40L, 30L, 30L)[i]) }
        assertEquals(setOf(big[0].vote), Curated.superminority(big))
        val flat = crowd(9).map { it.copy(activatedStake = 10) }
        assertEquals(4, Curated.superminority(flat).size) // 3/9 is exactly 1/3 → needs a 4th
    }

    /** Hostile RPC (new attack, 4.0): contradictory duplicates, overflowing stakes, negative values, random noise. */
    @Test fun hostileVoteListsFailClosedAndNeverThrow() {
        val e = Curated.LIST.first()
        val dup = Curated.evaluate(crowd() + live(e, delinquent = true) + live(e)).first { it.entry == e }
        assertEquals(CuratedStatus.MISSING, dup.status); assertFalse(dup.selectable)
        val huge = crowd(5).map { it.copy(activatedStake = Long.MAX_VALUE) } + live(e, stake = Long.MAX_VALUE)
        assertEquals(3, Curated.superminority(huge).size) // 6 equal giants → 3 needed, no overflow
        assertTrue(Curated.superminority(crowd(3).map { it.copy(activatedStake = -5) }).isEmpty())
        val rnd = java.util.Random(4)
        repeat(300) {
            val noise = (0 until rnd.nextInt(40)).map { _ ->
                val pick = if (rnd.nextBoolean()) Curated.LIST[rnd.nextInt(Curated.LIST.size)] else null
                val k = SolanaPublicKey.of(ByteArray(32).also { b -> rnd.nextBytes(b) })
                ValidatorInfo(pick?.let { p -> SolanaPublicKey.fromBase58(p.vote) } ?: k, pick?.let { p -> if (rnd.nextBoolean()) SolanaPublicKey.fromBase58(p.node) else k } ?: k,
                    rnd.nextInt(300) - 50, rnd.nextLong(), rnd.nextLong(), rnd.nextBoolean())
            }
            val out = Curated.evaluate(noise)
            assertEquals(Curated.LIST.size, out.size)
            for (v in out) if (v.selectable) {
                val l = v.live!!
                assertFalse(l.delinquent); assertEquals(v.entry.node, l.node.toBase58())
                assertTrue(l.commission >= 0 && l.commission <= Curated.MAX_COMMISSION && l.commission <= v.entry.baselineCommission + Curated.JUMP_POINTS)
            }
        }
    }
}
