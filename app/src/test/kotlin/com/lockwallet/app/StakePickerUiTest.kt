package com.lockwallet.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.lockwallet.app.ui.App
import com.lockwallet.app.ui.C
import com.lockwallet.app.ui.Platform
import com.lockwallet.app.ui.S
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.LocalSecureProvider
import com.lockwallet.custody.local.keystore.AuthMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** "Novo stake" 4.0: curated block first, delinquent hidden, commission jump disabled, "Avançado" reveals the full list. */
@RunWith(RobolectricTestRunner::class)
class StakePickerUiTest {
    @get:Rule(order = 0) val offline = object : ExternalResource() {
        override fun before() { MainActivity.txJournalAuthenticatorOverride = AppTestTxJournalAuthenticator(); MainActivity.rpcOverride = { null }; MainActivity.priceOverride = com.lockwallet.data.price.PriceService(emptyList(), { 0L }) }
        override fun after() { MainActivity.txJournalAuthenticatorOverride = null; MainActivity.priceOverride = null; MainActivity.rpcOverride = null; C.dark = true }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val rpc = AppFakeRpc()
    private val outsider = SolanaPublicKey.of(ByteArray(32) { (7 + it).toByte() }).toBase58()

    private fun json(vote: String, node: String, commission: Int) =
        """{"votePubkey":"$vote","nodePubkey":"$node","commission":$commission,"activatedStake":1000000000000000,"lastVote":1,"epochVoteAccount":true,"epochCredits":[],"rootSlot":0}"""

    private fun start(): AppController {
        val (a, b) = Curated.LIST
        val crowd = (1..120).map { i -> SolanaPublicKey.of(ByteArray(32) { (i * 7 + it).toByte() }).toBase58().let { json(it, it, 5) } }
        val current = crowd + Curated.LIST.drop(2).map { json(it.vote, it.node, it.baselineCommission) } + json(b.vote, b.node, 40)
        rpc.handlers["getVoteAccounts"] = { """{"current":[${current.joinToString(",")}],"delinquent":[${json(a.vote, a.node, a.baselineCommission)}]}""" }
        val p = LocalSecureProvider(tmp.newFolder(), SoftKek(), Clock())
        val c = AppController(p, DeviceSecurityCapabilityManager(ctx, p), CoroutineScope(Dispatchers.Default), Prefs(ctx).also { it.hideBalance = false },
            com.lockwallet.data.price.PriceService(emptyList(), { 0L }), { _, _ -> true }, AppTestTxJournalAuthenticator(), transport = rpc, dataDir = tmp.newFolder()).also { it.start() }
        c.startFlow(FlowMode.RESTORE)
        c.submitRestore(RestoreKind.PHRASE, "neither lonely flavor argue grass remind eye tag avocado spot unusual intact".toCharArray()); await(c)
        c.chooseAccount(c.draft!!.material!!.discover(1).first())
        c.submitName("Conta 1"); c.setNewPin("839174".toCharArray()); c.sealNewVault(AuthMode.PIN_ONLY); await(c)
        c.go(Screen.StakeNew)
        rule.runOnUiThread { rule.activity.setContent { App(c, Platform({}, {}, {})) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("curated").fetchSemanticsNodes().isNotEmpty() }
        return c
    }

    private fun await(c: AppController) { val end = System.currentTimeMillis() + 20_000; Thread.sleep(30); while (c.busy && System.currentTimeMillis() < end) Thread.sleep(10) }

    @Test fun curatedBlockHidesDelinquentAndDisablesCommissionJump() {
        start()
        val (a, _) = Curated.LIST
        // a is delinquent → not shown at all; b raised commission → shown but flagged and not pickable.
        assertEquals(Curated.LIST.size - 1, rule.onAllNodesWithTag("curated").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText(a.name).fetchSemanticsNodes().size)
        rule.onNodeWithText(S.curatedHidden(1)).performScrollTo().assertExists()
        rule.onNodeWithText("comissão subiu").performScrollTo().assertExists()
        // Live numbers on screen (commission and active stake from getVoteAccounts).
        rule.onNodeWithText("comissão 40%").assertExists()
        assertEquals(Curated.LIST.size - 1, rule.onAllNodesWithText("1.000.000 SOL em stake").fetchSemanticsNodes().size)
        // The full list stays closed until "Avançado".
        assertEquals(0, rule.onAllNodesWithTag("validator").fetchSemanticsNodes().size)
        rule.onNodeWithTag("stake-amount").performTextInput("1,5")
        rule.onNodeWithText("comissão 40%").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag("review").assertIsNotEnabled()
    }

    @Test fun pickingCuratedEnablesReview() {
        start()
        rule.onNodeWithTag("stake-amount").performTextInput("1,5")
        rule.onAllNodesWithTag("curated").onFirst().performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("review").assertIsEnabled()
    }

    @Test fun advancedRevealsFullListWithWarningsAndPasteByVoteAddress() {
        start()
        rule.onNodeWithTag("stake-amount").performTextInput("1,5")
        rule.onNodeWithTag("advanced").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithText(S.ADVANCED_WARNING).performScrollTo().assertExists()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("validator").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Buscar ou colar endereço de voto").performScrollTo().performTextInput(outsider)
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodesWithTag("validator").fetchSemanticsNodes().size)
        rule.onNodeWithTag("validator").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithText(S.NOT_CURATED).performScrollTo().assertExists()
        rule.onNodeWithTag("review").assertIsEnabled()
        // Closing "Avançado" drops a non-curated pick.
        rule.onNodeWithTag("advanced").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("validator").fetchSemanticsNodes().size)
        rule.onNodeWithTag("review").assertIsNotEnabled()
    }
}
