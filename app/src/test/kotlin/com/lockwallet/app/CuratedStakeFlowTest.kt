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

/** ADR 0025 end-to-end through the controller: curated stake is native, re-checked at review time. */
@RunWith(RobolectricTestRunner::class)
class CuratedStakeFlowTest {
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
        c.setNewPin("839174".toCharArray())
        c.sealNewVault(AuthMode.PIN_ONLY); c.await()
    }

    // ------------------------------------------------------------------ ADR 0025: curated validators

    private fun voteJson(entries: List<Triple<String, String, Int>>, delinquent: List<Triple<String, String, Int>> = emptyList()): String {
        fun o(t: Triple<String, String, Int>) = """{"votePubkey":"${t.first}","nodePubkey":"${t.second}","commission":${t.third},"activatedStake":1000000000000000,"lastVote":1,"epochVoteAccount":true,"epochCredits":[],"rootSlot":0}"""
        val crowd = (1..120).map { i -> val k = com.lockwallet.core.solana.SolanaPublicKey.of(ByteArray(32) { (i * 7 + it).toByte() }).toBase58(); Triple(k, k, 5) }
        return """{"current":[${(crowd + entries).joinToString(",") { o(it) }}],"delinquent":[${delinquent.joinToString(",") { o(it) }}]}"""
    }

    @Test fun curatedValidatorStakesNativelyWithNameAndNoNotCuratedWarning() {
        val c = controller(); restore(c)
        rpc.handlers["getMinimumBalanceForRentExemption"] = { b -> if (b.contains("[200")) "2282880" else "890880" }
        rpc.handlers["getVoteAccounts"] = { voteJson(Curated.LIST.map { Triple(it.vote, it.node, it.baselineCommission) }) }
        runBlocking { c.chain.loadValidators(force = true) }
        assertEquals(Curated.LIST.size, c.chain.curated.count { it.status == CuratedStatus.OK })
        val e = Curated.LIST.first()
        c.chain.reviewStakeCreate("1,5", e.vote); c.await()
        assertEquals(c.error, Screen.Review, c.screen)
        val p = c.chain.prepared!!
        assertTrue(p.signable)
        // Native staking only: create + initialize + delegate, nothing else (no pool, no liquid token).
        assertEquals(listOf("CreateAccountWithSeed", "StakeInitialize", "StakeDelegate"), p.analysis.ops.map { it::class.simpleName }.filterNot { it!!.startsWith("ComputeUnit") })
        val r = c.chain.review!!
        assertTrue(r.lines.toString(), r.lines.any { it.second.startsWith(e.name) })
        assertTrue(!r.warnings.contains(com.lockwallet.app.ui.S.NOT_CURATED))
        c.chain.cancelReview()
    }

    @Test fun advancedPickGetsNotCuratedWarning() {
        val c = controller(); restore(c)
        rpc.handlers["getMinimumBalanceForRentExemption"] = { b -> if (b.contains("[200")) "2282880" else "890880" }
        c.chain.reviewStakeCreate("1,5", "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"); c.await()
        assertEquals(c.error, Screen.Review, c.screen)
        assertTrue(c.chain.review!!.warnings.contains(com.lockwallet.app.ui.S.NOT_CURATED))
        c.chain.cancelReview()
    }

    /** Chain data is re-read at review time: a curated validator that went down or raised commission is refused. */
    @Test fun curatedValidatorThatTurnedBadIsRefusedAtReview() {
        val c = controller(); restore(c)
        val (a, b) = Curated.LIST
        rpc.handlers["getVoteAccounts"] = { voteJson(Curated.LIST.drop(2).map { Triple(it.vote, it.node, it.baselineCommission) } +
            Triple(b.vote, b.node, b.baselineCommission + Curated.JUMP_POINTS + 1), delinquent = listOf(Triple(a.vote, a.node, a.baselineCommission))) }
        c.chain.reviewStakeCreate("1,5", a.vote); c.await()
        assertEquals(com.lockwallet.app.ui.S.curatedBlocked(a.name, CuratedStatus.DELINQUENT), c.error)
        c.chain.reviewStakeCreate("1,5", b.vote); c.await()
        assertEquals(com.lockwallet.app.ui.S.curatedBlocked(b.name, CuratedStatus.COMMISSION_UP), c.error)
        assertEquals(0, rpc.sent.size)
        runBlocking { c.chain.loadValidators(force = true) }
        val v = c.chain.curated.associateBy { it.entry }
        assertTrue(v[a]!!.hidden); assertEquals(CuratedStatus.COMMISSION_UP, v[b]!!.status)
    }
}
