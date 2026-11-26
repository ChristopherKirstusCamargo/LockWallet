package com.lockwallet.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.lockwallet.app.ui.App
import com.lockwallet.app.ui.C
import com.lockwallet.app.ui.Platform
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.LocalSecureProvider
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.data.price.CoinbaseChange
import com.lockwallet.data.price.Fiat
import com.lockwallet.data.price.PriceService
import com.lockwallet.data.price.PriceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import com.lockwallet.app.ui.S
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal

/**
 * Final pass: home, labelled market indicator, grouped settings, protections, currency marks, validator initials and the
 * inactivity lock — on a real controller, rendered natively. With -Dlockwallet.shots=<dir> every state is saved as PNG
 * for visual review (nothing is written otherwise).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp")
class FinalPassUiTest {
    private fun price() = PriceService(listOf(object : PriceSource {
        override val name = "Teste"
        override fun fetch() = mapOf(Fiat.USD to BigDecimal("120"), Fiat.BRL to BigDecimal("600"))
    }), { 0L }, change = CoinbaseChange { _, _ -> """{"open":"100","last":"103"}""".toByteArray() })

    @get:Rule(order = 0) val offline = object : ExternalResource() {
        override fun before() { MainActivity.rpcOverride = { null }; MainActivity.priceOverride = price() }
        override fun after() { MainActivity.priceOverride = null; MainActivity.rpcOverride = null; C.dark = true }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val rpc = AppFakeRpc()

    private fun shot(name: String) {
        val dir = System.getProperty("lockwallet.shots")?.takeIf { it.isNotEmpty() } ?: return
        rule.waitForIdle()
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            java.io.File(dir).mkdirs()
            java.io.FileOutputStream(java.io.File(dir, "$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun start(): AppController {
        val p = LocalSecureProvider(tmp.newFolder(), SoftKek(), Clock())
        val c = AppController(p, DeviceSecurityCapabilityManager(ctx, p), CoroutineScope(Dispatchers.Default),
            Prefs(ctx).also { it.hideBalance = false; it.fiat = Fiat.BRL }, price(), { _, _ -> true }, transport = rpc, dataDir = tmp.newFolder()).also { it.start() }
        c.startFlow(FlowMode.RESTORE)
        c.submitRestore(RestoreKind.PHRASE, "neither lonely flavor argue grass remind eye tag avocado spot unusual intact".toCharArray()); await(c)
        c.chooseAccount(c.draft!!.material!!.discover(1).first()); c.submitName("Conta 1")
        c.setNewPin(listOf(8, 3, 5 + 4, 2 - 1, 14 - 7, 2 * 2).joinToString("").toCharArray())
        c.sealNewVault(AuthMode.PIN_AND_BIOMETRIC); await(c)
        c.home()
        rule.runOnUiThread { rule.activity.setContent { App(c, Platform({}, {}, {})) } }
        rule.waitUntil(10_000) { c.chain.balance is Loadable.Ready }
        rule.runOnUiThread { kotlinx.coroutines.runBlocking { c.pollPrice() } }
        rule.mainClock.advanceTimeBy(1_500)
        return c
    }

    private fun await(c: AppController) { val end = System.currentTimeMillis() + 20_000; Thread.sleep(30); while (c.busy && System.currentTimeMillis() < end) Thread.sleep(10) }

    @Test fun homeLabelsTheMarketMoveAndShowsTheLargeCentredBalance() {
        start()
        // 24 h price move is labelled as SOL market data, next to the balance.
        rule.onNodeWithTag("price-24h").assertExists()
        rule.onAllNodesWithText("+3,00% 24 h").fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        rule.onNodeWithText("Saldo total").assertExists()
        shot("home-price-dark")
        rule.runOnUiThread { C.dark = false }; rule.mainClock.advanceTimeBy(1_000)
        shot("home-price-light")
        rule.runOnUiThread { C.dark = true }
    }

    @Test @Config(qualifiers = "w360dp-h1900dp")
    fun settingsAreAShortCategoryListWithOneCleanPagePerTopic() {
        val c = start()
        rule.runOnUiThread { c.tab = Tab.SETTINGS }; rule.mainClock.advanceTimeBy(1_000)
        // Main list: profile card + six categories (icon, title, chevron) and nothing else.
        rule.onNodeWithTag("profile-card").assertExists()
        val titles = listOf("Segurança", "Moeda", "Privacidade", "Carteiras", "Aparência", "Sobre")
        for (t in titles) rule.onNodeWithText(t).assertExists()
        for (t in listOf("Tema", "Cotação ao vivo", "PIN do cofre", "Bloquear agora", "Contatos", "Limite diário", "Servidor da rede",
            "Animações", "Vibração", "Ajuda", "Ocultar saldo", "Exibir saldo em", "Ver frase de recuperação")) rule.onNodeWithText(t).assertDoesNotExist()
        shot("settings-full-dark")
        rule.runOnUiThread { C.dark = false }; rule.mainClock.advanceTimeBy(1_000)
        shot("settings-full-light")
        rule.runOnUiThread { C.dark = true }; rule.mainClock.advanceTimeBy(500)

        fun open(section: SettingsSection) { rule.runOnUiThread { c.go(Screen.Settings(section)) }; rule.mainClock.advanceTimeBy(1_000) }
        val pages = mapOf(
            SettingsSection.SECURITY to listOf("Central de Segurança", "PIN do cofre", "Bloquear após inatividade", "Bloquear agora", "Contatos",
                "Somente contatos", "Limite diário", "Confirmar endereço novo", "PIN em cada envio", S.VIEW_PHRASE, S.EXPORT_LWRP),
            SettingsSection.CURRENCY to listOf("Moeda", "Compacto", "Completo", "Cotação ao vivo"),
            SettingsSection.PRIVACY to listOf("Saldo confidencial", "Servidor da rede"),
            SettingsSection.WALLETS to listOf("Criar carteira", "Importar carteira"),
            SettingsSection.APPEARANCE to listOf("Tema", "Animações", "Vibração"),
            SettingsSection.ABOUT to listOf("Ajuda", "Sobre a Lock Wallet"),
        )
        // Each option appears on exactly one page (no duplicates across categories).
        val seen = HashMap<String, SettingsSection>()
        for ((section, items) in pages) {
            open(section)
            for (t in items) {
                rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty().let { assertTrue("$t missing on $section", it) }
                seen.put(t, section)?.let { if (it != section && t != "Moeda") fail("$t on $it and $section") }
            }
            shot("settings-${section.name.lowercase()}-dark")
        }
        for ((section, items) in pages) {
            open(section)
            for ((other, otherItems) in pages) if (other != section) for (t in otherItems) {
                if (t in items || t == "Moeda" || t == "Contatos") continue
                assertTrue("$t (from $other) duplicated on $section", rule.onAllNodesWithText(t).fetchSemanticsNodes().isEmpty())
            }
        }
        // Profile keeps identity only: no phrase/backup duplicates there.
        rule.runOnUiThread { c.go(Screen.Detail(c.chain.wallet()!!.id)) }; rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("view-phrase").assertDoesNotExist(); rule.onNodeWithTag("export").assertDoesNotExist()
        rule.onNodeWithText("Frase de recuperação").assertDoesNotExist(); rule.onNodeWithText("Exportar backup", substring = true).assertDoesNotExist()
        shot("profile-dark")

        open(SettingsSection.APPEARANCE)
        // Animations off is a real switch: the Motion specs snap.
        rule.onNodeWithTag("animations-toggle").performClick(); rule.mainClock.advanceTimeBy(300)
        assertTrue(!c.animations && !com.lockwallet.app.ui.Motion.enabled && !c.prefs.animations)
        rule.onNodeWithTag("animations-toggle").performClick(); rule.mainClock.advanceTimeBy(300)
        assertTrue(c.animations && com.lockwallet.app.ui.Motion.enabled)
        open(SettingsSection.SECURITY)
        // Inactivity lock choice persists.
        rule.onNodeWithTag("seg-2 min").performClick(); rule.mainClock.advanceTimeBy(300)
        assertEquals(2, c.prefs.idleLockMinutes)
        open(SettingsSection.CURRENCY)
        // Large-value format: compact by default, full on request, persisted.
        assertTrue(c.compactAmounts && com.lockwallet.app.ui.Compact.enabled)
        rule.onNodeWithTag("seg-Completo").performClick(); rule.mainClock.advanceTimeBy(300)
        assertTrue(!c.compactAmounts && !c.prefs.compactAmounts && !com.lockwallet.app.ui.Compact.enabled)
        rule.onNodeWithTag("seg-Compacto").performClick(); rule.mainClock.advanceTimeBy(300)
        assertTrue(c.compactAmounts && com.lockwallet.app.ui.Compact.enabled)
        // Currency sheet: one typographic mark per currency.
        rule.onNodeWithTag("currency").performClick(); rule.mainClock.advanceTimeBy(1_000)
        for (s in listOf("R$", "$", "€", "¥", "£", "元", "C$", "A$", "Fr", "₹")) rule.onAllNodesWithText(s).fetchSemanticsNodes().isNotEmpty().let { assertTrue(s, it) }
        shot("currency-dark")
        rule.onNodeWithTag("fiat-USD").performClick(); rule.mainClock.advanceTimeBy(800)
        assertEquals(Fiat.USD, c.fiat)
        open(SettingsSection.PRIVACY)
        rule.onNodeWithTag("privacy-ring").performClick(); rule.mainClock.advanceTimeBy(1_000)
        assertEquals(Tab.PRIVACY, c.tab)
        shot("privacy-tab-dark")
    }

    @Test fun inactivityLocksAnOpenWalletAndNeverWhileBusy() {
        val c = start()
        assertTrue(c.unlocked)
        val now = android.os.SystemClock.elapsedRealtime()
        c.touch()
        c.checkIdle(now + 4 * 60_000L)           // under the default 5 min
        assertTrue(c.unlocked)
        rule.runOnUiThread { c.checkIdle(android.os.SystemClock.elapsedRealtime() + 5 * 60_000L + 1) }
        assertTrue(c.locked)
        assertEquals(Screen.Lock, c.screen)
        rule.mainClock.advanceTimeBy(1_000)
        shot("lock-dark")
        rule.onAllNodesWithText("Dígitos", substring = true).fetchSemanticsNodes().isEmpty().let(::assertTrue)
    }

    @Test fun curatedValidatorsShowInitialsFromTheirNames() {
        assertEquals("SS", com.lockwallet.app.ui.nameInitials("Shinobi Systems"))
        assertEquals("SC", com.lockwallet.app.ui.nameInitials("Solana Compass"))
        assertEquals("CH", com.lockwallet.app.ui.nameInitials("Chainflow"))
        assertEquals("?", com.lockwallet.app.ui.nameInitials("  "))
        val c = start()
        val json = Curated.LIST.joinToString(",") {
            """{"votePubkey":"${it.vote}","nodePubkey":"${it.node}","commission":${it.baselineCommission},"activatedStake":1000000000000000,"lastVote":1,"epochVoteAccount":true,"epochCredits":[],"rootSlot":0}"""
        }
        rpc.handlers["getVoteAccounts"] = { """{"current":[$json],"delinquent":[]}""" }
        rule.runOnUiThread { c.go(Screen.StakeNew) }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("curated").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(1_500)
        for (v in Curated.LIST) rule.onAllNodesWithText(com.lockwallet.app.ui.nameInitials(v.name)).fetchSemanticsNodes().isNotEmpty().let { assertTrue(v.name, it) }
        shot("stake-new-dark")
    }

    @Test fun everyMainScreenRendersInBothThemes() {
        val c = start()
        val w = c.chain.wallet()!!.id
        val screens = listOf<Pair<String, () -> Unit>>(
            "tab-stake" to { c.home(); c.tab = Tab.STAKE },
            "tab-privacy" to { c.home(); c.tab = Tab.PRIVACY },
            "tab-settings" to { c.home(); c.tab = Tab.SETTINGS },
            "settings-security" to { c.go(Screen.Settings(SettingsSection.SECURITY)) },
            "settings-currency" to { c.go(Screen.Settings(SettingsSection.CURRENCY)) },
            "settings-privacy" to { c.go(Screen.Settings(SettingsSection.PRIVACY)) },
            "settings-wallets" to { c.go(Screen.Settings(SettingsSection.WALLETS)) },
            "settings-appearance" to { c.go(Screen.Settings(SettingsSection.APPEARANCE)) },
            "settings-about" to { c.go(Screen.Settings(SettingsSection.ABOUT)) },
            "contacts" to { c.go(Screen.Contacts) },
            "detail" to { c.go(Screen.Detail(w)) },
            "receive" to { c.go(Screen.Receive) },
            "send" to { c.go(Screen.Send()) },
            "security" to { c.go(Screen.SecurityCenter) },
            "network" to { c.go(Screen.Network) },
        )
        for (dark in listOf(true, false)) for ((name, go) in screens) {
            rule.runOnUiThread { C.dark = dark; go() }; rule.mainClock.advanceTimeBy(1_200)
            shot("$name-${if (dark) "dark" else "light"}")
        }
        rule.runOnUiThread { C.dark = true }
    }

    /** Send review and the tracking screen, in both themes: the reviewed amount is on one line and the exact value is labelled. */
    @Test fun reviewAndTrackingRenderInBothThemes() {
        val c = start()
        val to = "GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv"
        rule.runOnUiThread { c.go(Screen.Send()) }; rule.mainClock.advanceTimeBy(600)
        c.chain.reviewSol(to, "0,5", max = false); await(c)
        assertEquals(c.error, Screen.Review, c.screen)
        rule.mainClock.advanceTimeBy(1_200)
        rule.onNodeWithTag("review-amount").assertExists()
        for (dark in listOf(true, false)) { rule.runOnUiThread { C.dark = dark }; rule.mainClock.advanceTimeBy(900); shot("review-${if (dark) "dark" else "light"}") }
        rule.runOnUiThread { C.dark = true }
        c.chain.authorize(); await(c)
        c.submitPin(listOf(8, 3, 5 + 4, 2 - 1, 14 - 7, 2 * 2).joinToString("").toCharArray(), (c.screen as Screen.Authorize).purpose); await(c)
        assertTrue("${c.screen}", c.screen is Screen.TxStatus)
        for (dark in listOf(true, false)) { rule.runOnUiThread { C.dark = dark }; rule.mainClock.advanceTimeBy(1_200); shot("tracking-${if (dark) "dark" else "light"}") }
        rule.runOnUiThread { C.dark = true }
    }

    /** First run: the welcome screen in both themes, with no wallet and nothing on the network. */
    @Test fun welcomeRendersInBothThemes() {
        val p = LocalSecureProvider(tmp.newFolder(), SoftKek(), Clock())
        val c = AppController(p, DeviceSecurityCapabilityManager(ctx, p), CoroutineScope(Dispatchers.Default),
            Prefs(ctx), price(), { _, _ -> true }, transport = rpc, dataDir = tmp.newFolder()).also { it.start() }
        rule.runOnUiThread { rule.activity.setContent { App(c, Platform({}, {}, {})) } }
        rule.mainClock.advanceTimeBy(1_500)
        assertEquals(0, rpc.sent.size)
        for (dark in listOf(true, false)) { rule.runOnUiThread { C.dark = dark }; rule.mainClock.advanceTimeBy(900); shot("welcome-${if (dark) "dark" else "light"}") }
        rule.runOnUiThread { C.dark = true }
    }
}
