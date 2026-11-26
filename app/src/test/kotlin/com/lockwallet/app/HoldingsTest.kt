package com.lockwallet.app

import com.lockwallet.app.ui.valuationOf
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.state.StakeAccountState
import com.lockwallet.core.solana.state.StakeLifecycle
import com.lockwallet.data.price.Fiat
import com.lockwallet.data.rpc.InflationReward
import com.lockwallet.data.rpc.StakeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** 5.0: total balance = liquid + every stake account the wallet can withdraw; rewards only when the chain is consistent. */
class HoldingsTest {
    private val me = SolanaPublicKey.of(ByteArray(32) { 1 })
    private val other = SolanaPublicKey.of(ByteArray(32) { 2 })
    private var n = 10
    private fun stake(lamports: Long, life: StakeLifecycle, withdrawer: SolanaPublicKey = me, reward: InflationReward? = null, checked: Boolean = true) =
        StakeView(SolanaPublicKey.of(ByteArray(32) { (n++).toByte() }), lamports,
            StakeAccountState(2, 2_282_880, me, withdrawer, 0, 0, SolanaPublicKey.of(ByteArray(32)), other, lamports - 2_282_880, 0, Long.MAX_VALUE),
            life, null, reward, checked)
    private fun r(amount: Long, epoch: Long = 99) = InflationReward(epoch, amount, 10_000_000_000, 0)

    @Test fun totalCountsEveryLifecycleIncludingWithdrawable() {
        val all = StakeLifecycle.entries.map { stake(1_000_000_000, it) }
        val h = Holdings.of(2_000_000_000, all, 100, me)
        assertEquals(5_000_000_000L, h.staked); assertEquals(7_000_000_000L, h.total); assertEquals(5, h.stakeAccounts)
    }

    @Test fun accountsSomeoneElseCanWithdrawAreNotYours() {
        val h = Holdings.of(1, listOf(stake(5, StakeLifecycle.ACTIVE, withdrawer = other), stake(7, StakeLifecycle.ACTIVE)), 100, me)
        assertEquals(7L, h.staked); assertEquals(8L, h.total)
    }

    @Test fun unknownPartsAreNeverGuessed() {
        assertNull(Holdings.of(null, listOf(stake(5, StakeLifecycle.ACTIVE)), 100, me).total)
        val noStakes = Holdings.of(9, null, null, me)
        assertNull(noStakes.staked); assertNull(noStakes.total)
        assertEquals(9L, Holdings.of(9, emptyList(), 100, me).total)
    }

    @Test fun hostileOverflowOrNegativeMakesTotalUnknown() {
        assertNull(Holdings.of(Long.MAX_VALUE, listOf(stake(Long.MAX_VALUE, StakeLifecycle.ACTIVE)), 100, me).total)
        assertNull(Holdings.of(1, listOf(stake(Long.MAX_VALUE, StakeLifecycle.ACTIVE), stake(Long.MAX_VALUE, StakeLifecycle.ACTIVE)), 100, me).staked)
        assertNull(Holdings.sumOrNull(listOf(5, -1)))
    }

    @Test fun rewardsOnlyWhenEveryAccountAnsweredAndPlausible() {
        assertEquals(30L, Holdings.of(0, listOf(stake(1_000_000_000, StakeLifecycle.ACTIVE, reward = r(10)), stake(1_000_000_000, StakeLifecycle.ACTIVE, reward = r(20)),
            stake(1_000_000_000, StakeLifecycle.ACTIVATING, reward = null)), 100, me).lastEpochReward)
        // RPC failed for the reward call ⇒ unknown, not zero.
        assertNull(Holdings.of(0, listOf(stake(1_000_000_000, StakeLifecycle.ACTIVE, reward = null, checked = false)), 100, me).lastEpochReward)
        // Wrong epoch, negative, larger than the account, postBalance below amount ⇒ hostile ⇒ omitted.
        for (bad in listOf(r(10, epoch = 98), r(-1), r(2_000_000_000), InflationReward(99, 50, 10, 0)))
            assertNull(bad.toString(), Holdings.of(0, listOf(stake(1_000_000_000, StakeLifecycle.ACTIVE, reward = bad)), 100, me).lastEpochReward)
        // Nothing earned ⇒ nothing shown.
        assertNull(Holdings.of(0, listOf(stake(1_000_000_000, StakeLifecycle.ACTIVATING)), 100, me).lastEpochReward)
    }

    @Test fun valuationUsesTotalForValueAndChangeButKeepsLiquidSeparate() {
        val h = Holdings.of(2_000_000_000, listOf(stake(8_000_000_000, StakeLifecycle.ACTIVE)), 100, me)
        val v = valuationOf(h, Fiat.USD, BigDecimal("100"), BigDecimal("0.10"), false)
        assertEquals(0, BigDecimal("1000").compareTo(v.value))           // 10 SOL × 100
        assertEquals(0, BigDecimal("200").compareTo(v.liquidValue))      // only the sendable part
        assertEquals(2_000_000_000L, v.lamports)                         // "max" source stays liquid
        assertEquals(10_000_000_000L, v.total)
        // change = value × r/(1+r) = 1000 × 0.1/1.1
        assertTrue((v.changeValue!! - BigDecimal("90.909090909")).abs() < BigDecimal("0.0001"))
        assertEquals(0, BigDecimal("0.1").compareTo(v.change!!.setScale(10, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros()))
        // Only staked SOL, no liquid: still a holding, still moves.
        val onlyStake = valuationOf(Holdings.of(0, listOf(stake(1_000_000_000, StakeLifecycle.ACTIVE)), 100, me), Fiat.USD, BigDecimal("100"), BigDecimal("0.1"), false)
        assertTrue(onlyStake.change != null)
        // Nothing held ⇒ no variation.
        assertNull(valuationOf(Holdings.of(0, emptyList(), 100, me), Fiat.USD, BigDecimal("100"), BigDecimal("0.1"), false).change)
    }
}
