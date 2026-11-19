package com.lockwallet.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
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
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.LocalSecureProvider
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.data.price.CoinbaseChange
import com.lockwallet.data.price.Fiat
import com.lockwallet.data.price.PriceSource
import com.lockwallet.data.price.PriceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

private fun offlinePrice() = PriceService(listOf(object : PriceSource {
    override val name = "Teste"
    override fun fetch() = mapOf(Fiat.USD to BigDecimal("73.965"), Fiat.BRL to BigDecimal("436.24"))
}), { 0L }, change = CoinbaseChange { _, _ -> """{"open":"70","last":"73.5"}""".toByteArray() })

/** First run, through the real activity (no wallet): welcome → create flow, help sheets. */
@RunWith(RobolectricTestRunner::class)
class HomeUiTest {
    @get:Rule(order = 0) val offline = object : ExternalResource() {
        override fun before() { MainActivity.rpcOverride = { null }; MainActivity.priceOverride = offlinePrice() }
        override fun after() { MainActivity.priceOverride = null; MainActivity.rpcOverride = null; C.dark = true }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Test fun welcomeThenCreateFlowWithChoiceCards() {
        rule.onNodeWithText("Lock Wallet").assertExists()
        rule.onNodeWithText("Adicionar uma carteira existente").assertExists()
        rule.onNodeWithTag("create").performClick()
        rule.onNodeWithText("Onde a chave vai ficar?").assertExists()
        rule.onNodeWithTag("custody-sv").assertIsNotEnabled()
        rule.onNodeWithTag("custody-local").performClick()
        rule.onNodeWithText("Lock Connect").assertExists()
        rule.onNodeWithTag("role-vault").performClick()
        rule.onNodeWithText("12 palavras").assertExists()
        rule.onNodeWithText("24 palavras").assertExists()
    }

    @Test fun everyHelpIconOpensWhatWhyExample() {
        rule.onNodeWithTag("create").performClick()
        rule.onAllNodesWithTag("help").onFirst().performClick()
        rule.onNodeWithTag("sheet").assertExists()
        rule.onNodeWithText("O QUE É").assertExists()
        rule.onNodeWithText("PARA QUE SERVE").assertExists()
        rule.onNodeWithText("EXEMPLO").assertExists()
        rule.onNodeWithText("Entendi").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("sheet").assertDoesNotExist()
    }

    @Test fun restoreContinueStaysDisabledUntilPhraseHasValidLength() {
        rule.onNodeWithTag("restore").performClick()
        rule.onNodeWithTag("custody-local").performClick()
        rule.onNodeWithTag("role-vault").performClick()
        rule.onNodeWithTag("r-phrase").performClick()
        rule.onNodeWithTag("restore-continue").assertIsNotEnabled()
        rule.onNodeWithTag("phrase-input").performTextInput("neither lonely flavor argue grass")
        rule.onNodeWithTag("restore-continue").assertIsNotEnabled()
        rule.onNodeWithTag("phrase-input").performTextInput(" remind eye tag avocado spot unusual intact")
        rule.onNodeWithTag("restore-continue").assertIsEnabled()
    }
}

/** Secret paste reads once and wipes the clipboard (other apps / keyboard history cannot re-read the phrase). */
@RunWith(RobolectricTestRunner::class)
class ClipboardTest {
    @Test fun secretPasteClearsTheClipboard() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("x", "neither lonely flavor"))
        assertEquals("neither lonely flavor", readAndClearClipboard(ctx, 400))
        val after = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        assertTrue("clipboard must be empty, was '$after'", after.isNullOrEmpty())
        assertNull(readAndClearClipboard(ctx, 400)?.takeIf { it.isNotEmpty() })
    }
}

/** Main UI with a real controller (software KEK, scripted mainnet RPC, offline price). */
@RunWith(RobolectricTestRunner::class)
class MainUiTest {
    @get:Rule(order = 0) val offline = object : ExternalResource() {
        override fun before() { MainActivity.rpcOverride = { null }; MainActivity.priceOverride = offlinePrice() }
        override fun after() { MainActivity.priceOverride = null; MainActivity.rpcOverride = null; C.dark = true }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val copied = mutableListOf<String>()

    private fun start(): AppController {
        val p = LocalSecureProvider(tmp.newFolder(), SoftKek(), Clock())
        val c = AppController(p, DeviceSecurityCapabilityManager(ctx, p), CoroutineScope(Dispatchers.Default), Prefs(ctx).also { it.hideBalance = false; it.fiat = Fiat.BRL },
            offlinePrice(), { _, _ -> true }, transport = AppFakeRpc(), dataDir = tmp.newFolder()).also { it.start() }
        c.startFlow(FlowMode.RESTORE)
        c.submitRestore(RestoreKind.PHRASE, "neither lonely flavor argue grass remind eye tag avocado spot unusual intact".toCharArray()); await(c)
        c.chooseAccount(c.draft!!.material!!.discover(1).first())
        c.submitName("Conta 1")
        c.setNewPin("480917".toCharArray())
        c.sealNewVault(AuthMode.PIN_ONLY); await(c)
        c.home()
        rule.runOnUiThread { rule.activity.setContent { App(c, Platform({}, {}, { copied += it })) } }
        rule.waitForIdle()
        return c
    }

    private fun await(c: AppController) {
        val end = System.currentTimeMillis() + 20_000
        Thread.sleep(30)
        while (c.busy && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    private fun waitFor(cond: () -> Boolean) = rule.waitUntil(10_000) { cond() }

    @Test fun phantomStyleHomeBalanceChangeAndActions() {
        val c = start()
        waitFor { c.chain.balance is Loadable.Ready && c.priceState is com.lockwallet.data.price.PriceState.Fresh }
        rule.onNodeWithTag("sol-line").assertTextEquals("2 SOL")
        rule.onAllNodesWithText("+5,00%").onFirst().assertExists()        // 24 h change chip (70 → 73.5)
        rule.onNodeWithText("Conta 1").assertExists()
        rule.onNodeWithTag("home-send").assertExists()
        rule.onNodeWithTag("home-receive").assertExists()
        rule.onNodeWithTag("asset-sol").assertExists()
        rule.onNodeWithTag("fab").performClick()
        rule.onNodeWithTag("fab-Receber").performClick()
        rule.onNodeWithTag("qr").assertExists()
        rule.onNodeWithTag("copy").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(listOf(c.wallets.first().address.toBase58()), copied)
    }

    @Test fun hideBalanceAndCurrencyAndThemeSettings() {
        val c = start()
        waitFor { c.chain.balance is Loadable.Ready }
        rule.onNodeWithTag("hide-balance").performClick()
        rule.onNodeWithText("••••••").assertExists()
        rule.onNodeWithTag("sol-line").assertTextEquals("Saldo oculto")
        rule.onNodeWithTag("tab-SETTINGS").performClick()
        rule.onNodeWithTag("theme").performClick()
        rule.waitForIdle(); assertFalse(C.dark)
        rule.onNodeWithText("Apps conectados").assertDoesNotExist()   // v3: redundant with the dApps tab
        rule.onNodeWithTag("currency").performClick()
        for (f in Fiat.entries) rule.onNodeWithTag("fiat-${f.name}").performScrollTo().assertExists()
        rule.onNodeWithTag("fiat-EUR").performScrollTo().performClick()
        rule.waitForIdle(); assertEquals(Fiat.EUR, c.fiat)
        rule.onNodeWithTag("network").performScrollTo().assertExists()
        rule.onNodeWithText("Padrão").assertExists()
        rule.onNodeWithTag("security-center").performScrollTo().performClick()
        rule.onNodeWithText("Bloqueio de tela do aparelho").assertExists()
    }

    /** Hard lock gate: whatever the navigation state says, nothing but the lock screen renders while locked. */
    @Test fun lockGateCannotBeBypassedByNavigation() {
        val c = start()
        rule.runOnUiThread { c.lockNow() }
        rule.waitForIdle()
        assertTrue(c.locked)
        rule.runOnUiThread { c.screen = Screen.Receive }
        rule.waitForIdle()
        rule.onNodeWithTag("qr").assertDoesNotExist()
        rule.onNodeWithText("Bem-vindo de volta").assertExists()
        rule.runOnUiThread { c.screen = Screen.Home; c.tab = Tab.SETTINGS }
        rule.waitForIdle()
        rule.onNodeWithTag("theme").assertDoesNotExist()
        rule.onNodeWithText("Bem-vindo de volta").assertExists()
    }

    @Test fun stakeAndDappsTabs() {
        start()
        rule.onNodeWithTag("tab-STAKE").performClick()
        rule.onNodeWithText("Nenhum stake ainda.").performScrollTo().assertExists()
        rule.onNodeWithTag("tab-CONNECT").performClick()
        rule.onNodeWithText("Lock Connect").assertExists()
        rule.onNodeWithText("Nenhum app conectado.").performScrollTo().assertExists()
    }

    @Test fun reviewShowsFeeAndSignNeedsAckForNewRecipient() {
        val c = start()
        waitFor { c.chain.balance is Loadable.Ready }
        rule.runOnUiThread { c.chain.reviewSol("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", "0,5", false) }
        waitFor { c.screen == Screen.Review && !c.busy }
        rule.onNodeWithText("Taxa de rede").performScrollTo().assertExists()
        rule.onNodeWithText("GcXb fQ5y Y3ux CyBN DPBb R5Fj umHf 89E7 YHXu ULfG DBBv").assertExists()
        rule.onNodeWithTag("sign").assertIsNotEnabled()
        rule.onNodeWithTag("ack").performScrollTo().performClick()
        rule.onNodeWithTag("sign").assertIsEnabled()
        rule.onNodeWithTag("sign").assertTextContains("Assinar")
    }
}
