package com.lockwallet.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.LocalSecureProvider
import com.lockwallet.custody.local.keystore.AuthMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 5.0: the balance includes stake accounts; "max" still sends only the liquid balance. */
@RunWith(RobolectricTestRunner::class)
class BalanceFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val rpc = AppFakeRpc()

    private fun controller(): AppController {
        val p = LocalSecureProvider(tmp.newFolder(), SoftKek(), Clock())
        return AppController(p, DeviceSecurityCapabilityManager(ctx, p), CoroutineScope(Dispatchers.Default), Prefs(ctx), null, { _, _ -> true },
            transport = rpc, dataDir = tmp.newFolder()).also { it.start() }
    }

    private fun AppController.await() {
        val end = System.currentTimeMillis() + 20_000
        Thread.sleep(30)
        while (busy && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("timed out", !busy)
    }

    private fun restore(c: AppController) {
        c.startFlow(FlowMode.RESTORE)
        c.submitRestore(RestoreKind.PHRASE, "neither lonely flavor argue grass remind eye tag avocado spot unusual intact".toCharArray()); c.await()
        c.chooseAccount(c.draft!!.material!!.discover(1).first())
        c.submitName("Teste")
        c.setNewPin("480917".toCharArray())
        c.sealNewVault(AuthMode.PIN_ONLY); c.await()
    }

    private fun stakeImage(me: ByteArray, voter: ByteArray, stake: Long): ByteArray {
        fun le(v: Long, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 0xff).toByte() }
        val b = le(2, 4) + le(2_282_880, 8) + me + me + le(0, 8) + le(0, 8) + ByteArray(32) + voter + le(stake, 8) + le(0, 8) + le(-1, 8) + ByteArray(8) + le(0, 8) + ByteArray(1)
        return b + ByteArray(200 - b.size)
    }

    private fun withStake(c: AppController, lamports: Long) {
        val me = c.chain.wallet()!!.address
        val vote = com.lockwallet.core.solana.SolanaPublicKey.fromBase58(Curated.LIST.first().vote).toByteArray()
        rpc.accounts[com.lockwallet.data.rpc.ChainReader.stakeAddress(me, 0).toBase58()] =
            Triple(com.lockwallet.core.solana.ProgramIds.STAKE.toBase58(), lamports, stakeImage(me.toByteArray(), vote, lamports - 2_282_880))
    }

    @Test fun totalIncludesStakeAndRewardsButMaxSendsOnlyLiquid() {
        val c = controller(); restore(c)
        withStake(c, 8_002_282_880)
        rpc.handlers["getInflationReward"] = { _ -> """[{"epoch":19,"effectiveSlot":1,"amount":1234567,"postBalance":8002282880,"commission":0}]""" }
        runBlocking { c.chain.refreshHome() }
        val v = com.lockwallet.app.ui.valuation(c)
        assertEquals(2_000_000_000L, v.lamports)
        assertEquals(8_002_282_880L, v.staked)
        assertEquals(10_002_282_880L, v.total)
        assertEquals(1_234_567L, v.lastEpochReward)
        c.chain.reviewSol("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", "", max = true); c.await()
        assertEquals(c.error, Screen.Review, c.screen)
        val amt = c.chain.prepared!!.intent.amountUnits!!
        assertTrue("max must use only the liquid balance: $amt", amt in 1_900_000_000L until 2_000_000_000L)
        c.chain.cancelReview()
        // More than the liquid balance is refused even though the total is higher.
        c.chain.reviewSol("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", "5", max = false); c.await()
        assertTrue(c.screen !is Screen.Review)
    }

    @Test fun rewardCallFailingHidesRewardsButKeepsTotal() {
        val c = controller(); restore(c)
        withStake(c, 3_002_282_880)
        rpc.handlers.remove("getInflationReward")
        runBlocking { c.chain.refreshHome() }
        val v = com.lockwallet.app.ui.valuation(c)
        assertEquals(5_002_282_880L, v.total)
        assertEquals(null, v.lastEpochReward)
    }

    @Test fun stakeReadFailingFallsBackToLiquidMarkedPartial() {
        val c = controller(); restore(c)
        rpc.handlers.remove("getMultipleAccounts")
        runBlocking { c.chain.refreshHome() }
        val v = com.lockwallet.app.ui.valuation(c)
        assertEquals(2_000_000_000L, v.lamports)
        assertEquals(null, v.total)
        assertTrue(v.partial)
    }

    /** Attack A-49: the wallet/network changes while a refresh is in flight; the late answer must be dropped. */
    @Test fun lateAnswerAfterWalletSwitchIsDropped() {
        val c = controller(); restore(c)
        val id = c.chain.wallet()!!.id
        val orig = rpc.handlers["getBalance"]!!
        rpc.handlers["getBalance"] = { p -> c.chain.select(id); orig(p) } // switch happens mid-request
        runBlocking { c.chain.refreshHome() }
        assertTrue("stale balance written after switch: ${c.chain.balance}", c.chain.balance !is Loadable.Ready)
        rpc.handlers["getBalance"] = orig
        runBlocking { c.chain.refreshHome() }
        assertTrue(c.chain.balance is Loadable.Ready)
    }

    /** Attack A-50: a typed amount near Long.MAX_VALUE must not overflow past "amount + fee > balance". */
    @Test fun hugeAmountsCannotOverflowTheBalanceCheck() {
        val c = controller(); restore(c)
        runBlocking { c.chain.refreshHome() }
        for (a in listOf("9223372036,854775807", "9223372036", "999999999999", "1000000001")) {
            c.chain.reviewSol("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", a, max = false); c.await()
            assertTrue(a, c.screen !is Screen.Review); assertTrue(a, c.error != null)
        }
    }

    /** Attack A-51: "1.000" means one thousand in pt-BR; it must be refused, never silently read as 1 SOL. */
    @Test fun thousandSeparatorAmountIsRefusedAsAmbiguous() {
        val c = controller(); restore(c)
        runBlocking { c.chain.refreshHome() }
        c.chain.reviewSol("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", "1.000", max = false); c.await()
        assertTrue(c.screen !is Screen.Review)
        assertEquals(com.lockwallet.app.ui.S.AMBIGUOUS_AMOUNT, c.error)
        c.chain.reviewSol("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", "1,000", max = false); c.await()
        assertEquals(c.error, Screen.Review, c.screen)
    }
}
