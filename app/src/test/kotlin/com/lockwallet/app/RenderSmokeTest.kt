package com.lockwallet.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.lockwallet.app.ui.C
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real (native) rendering of the glass/wool/blur stack in both themes: draws without crashing and is not blank. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp")
class RenderSmokeTest {
    @get:Rule(order = 0) val offline = object : ExternalResource() {
        override fun before() { MainActivity.rpcOverride = { null }; MainActivity.priceOverride = com.lockwallet.data.price.PriceService(emptyList(), { 0L }) }
        override fun after() { MainActivity.priceOverride = null; MainActivity.rpcOverride = null; C.dark = true }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String): Int {
        rule.waitForIdle()
        lateinit var bmp: android.graphics.Bitmap
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
        }
        System.getProperty("lockwallet.shots")?.takeIf { it.isNotEmpty() }?.let { dir ->
            java.io.File(dir).mkdirs()
            java.io.FileOutputStream(java.io.File(dir, "$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val colours = HashSet<Int>()
        for (y in 0 until bmp.height step 7) for (x in 0 until bmp.width step 7) colours += bmp.getPixel(x, y)
        return colours.size
    }

    @Test fun welcomeRendersInBothThemes() {
        rule.mainClock.advanceTimeBy(2_000)
        assertTrue(shot("welcome-dark") > 8)
        rule.runOnUiThread { C.dark = false }
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue(shot("welcome-light") > 8)
    }

    @get:Rule(order = 2) val tmp = org.junit.rules.TemporaryFolder()

    @Test fun homeSettingsAndSheetRenderInBothThemes() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val p = com.lockwallet.custody.local.LocalSecureProvider(tmp.newFolder(), SoftKek(), Clock())
        val c = AppController(p, com.lockwallet.custody.local.DeviceSecurityCapabilityManager(ctx, p), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            Prefs(ctx).also { it.hideBalance = false; it.fiat = com.lockwallet.data.price.Fiat.BRL },
            com.lockwallet.data.price.PriceService(emptyList(), { 0L }), { _, _ -> true }, AppTestTxJournalAuthenticator, transport = AppFakeRpc(), dataDir = tmp.newFolder()).also { it.start() }
        c.startFlow(FlowMode.RESTORE)
        c.submitRestore(RestoreKind.PHRASE, "neither lonely flavor argue grass remind eye tag avocado spot unusual intact".toCharArray()); while (c.busy) Thread.sleep(10)
        c.chooseAccount(c.draft!!.material!!.discover(1).first()); c.submitName("Conta 1"); c.setNewPin("839174".toCharArray())
        c.sealNewVault(com.lockwallet.custody.local.keystore.AuthMode.PIN_ONLY); Thread.sleep(30); while (c.busy) Thread.sleep(10)
        c.home()
        rule.runOnUiThread { rule.activity.setContent { com.lockwallet.app.ui.App(c, com.lockwallet.app.ui.Platform({}, {}, {})) } }
        rule.waitUntil(10_000) { c.chain.balance is com.lockwallet.app.Loadable.Ready }
        rule.mainClock.advanceTimeBy(1_500)
        for (dark in listOf(true, false)) {
            val n = if (dark) "dark" else "light"
            rule.runOnUiThread { C.dark = dark; c.tab = Tab.HOME }; rule.mainClock.advanceTimeBy(1_000)
            assertTrue(shot("home-$n") > 8)
            rule.runOnUiThread { c.tab = Tab.SETTINGS }; rule.mainClock.advanceTimeBy(1_000)
            assertTrue(shot("settings-$n") > 8)
            rule.runOnUiThread { c.actionsOpen = true }; rule.mainClock.advanceTimeBy(1_000)
            assertTrue(shot("fab-$n") > 8)
            rule.runOnUiThread { c.actionsOpen = false }; rule.mainClock.advanceTimeBy(800)
        }
    }
}
