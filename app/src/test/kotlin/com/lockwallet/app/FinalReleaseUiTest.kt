package com.lockwallet.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.lockwallet.app.ui.C
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Final release: no version anywhere in the UI; the balance breakdown survives a small screen with huge fonts. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h568dp")
class FinalReleaseUiTest {
    @get:Rule(order = 0) val offline = object : ExternalResource() {
        override fun before() { MainActivity.rpcOverride = { null }; MainActivity.priceOverride = com.lockwallet.data.price.PriceService(emptyList(), { 0L }) }
        override fun after() { MainActivity.priceOverride = null; MainActivity.rpcOverride = null; C.dark = true }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val tmp = org.junit.rules.TemporaryFolder()

    private fun stakeImage(me: ByteArray, voter: ByteArray, stake: Long): ByteArray {
        fun le(v: Long, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 0xff).toByte() }
        val b = le(2, 4) + le(2_282_880, 8) + me + me + le(0, 8) + le(0, 8) + ByteArray(32) + voter + le(stake, 8) + le(0, 8) + le(-1, 8) + ByteArray(8) + le(0, 8) + ByteArray(1)
        return b + ByteArray(200 - b.size)
    }

    private fun walletWithStake(): AppController {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val rpc = AppFakeRpc()
        val p = com.lockwallet.custody.local.LocalSecureProvider(tmp.newFolder(), SoftKek(), Clock())
        val c = AppController(p, com.lockwallet.custody.local.DeviceSecurityCapabilityManager(ctx, p), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            Prefs(ctx).also { it.hideBalance = false }, com.lockwallet.data.price.PriceService(emptyList(), { 0L }), { _, _, _ -> true },
            transport = rpc, dataDir = tmp.newFolder()).also { it.start() }
        c.startFlow(FlowMode.RESTORE)
        c.submitRestore(RestoreKind.PHRASE, "neither lonely flavor argue grass remind eye tag avocado spot unusual intact".toCharArray()); Thread.sleep(30); while (c.busy) Thread.sleep(10)
        c.chooseAccount(c.draft!!.material!!.discover(1).first()); c.submitName("Conta 1"); c.setNewPin("listOf(8, 3, 5 + 4, 2 - 1, 14 - 7, 2 * 2).joinToString("")".toCharArray())
        c.sealNewVault(com.lockwallet.custody.local.keystore.AuthMode.PIN_AND_DEVICE_AUTH); Thread.sleep(30); while (c.busy) Thread.sleep(10)
        val me = c.chain.wallet()!!.address
        val vote = com.lockwallet.core.solana.SolanaPublicKey.fromBase58(Curated.LIST.first().vote).toByteArray()
        rpc.accounts[com.lockwallet.data.rpc.ChainReader.stakeAddress(me, 0).toBase58()] =
            Triple(com.lockwallet.core.solana.ProgramIds.STAKE.toBase58(), 123_456_002_282_880L, stakeImage(me.toByteArray(), vote, 123_456_000_000_000L))
        c.home()
        return c
    }

    @Test fun noVersionShownAnywhereAndBreakdownFitsAtHugeFont() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        val c = walletWithStake()
        rule.runOnUiThread { rule.activity.setContent { com.lockwallet.app.ui.App(c, com.lockwallet.app.ui.Platform({}, {}, {})) } }
        rule.waitUntil(10_000) { c.chain.stakes is Loadable.Ready && c.chain.balance is Loadable.Ready }
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithTag("balance-breakdown").assertIsDisplayed()
        rule.onNodeWithText("Disponível").assertExists()
        rule.onNodeWithText("Em stake").assertExists()
        for (tab in listOf(Tab.HOME, Tab.SETTINGS)) {
            rule.runOnUiThread { c.tab = tab }; rule.mainClock.advanceTimeBy(1_000); rule.waitForIdle()
            for (needle in listOf(com.lockwallet.app.ui.APP_VERSION, "v5", "4.0.0", "4.1.0", "Versão", "versão")) {
                assertEquals("$tab shows \"$needle\"", 0, rule.onAllNodes(hasText(needle, substring = true)).fetchSemanticsNodes().size)
            }
        }
        // Help texts never carry a version either.
        val all = com.lockwallet.app.ui.Help.INDEX.flatMap { it.second }
        for (h in all) for (s in listOf(h.title, h.what, h.why, h.example))
            assertEquals(h.title, false, Regex("\\b\\d+\\.\\d+\\.\\d+\\b").containsMatchIn(s))
    }
}
