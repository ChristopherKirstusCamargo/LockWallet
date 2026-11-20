package com.lockwallet.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.lockwallet.app.ui.App
import com.lockwallet.app.ui.C
import com.lockwallet.app.ui.Platform
import com.lockwallet.app.ui.Wool
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.LocalSecureProvider
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.data.price.Fiat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Design 3.0: adaptive layout on small phones / large fonts, theme cross-fade, sheet gestures, wool texture. */
@RunWith(RobolectricTestRunner::class)
class DesignUiTest {
    @get:Rule(order = 0) val offline = object : ExternalResource() {
        override fun before() { MainActivity.txJournalAuthenticatorOverride = AppTestTxJournalAuthenticator(); MainActivity.rpcOverride = { null }; MainActivity.priceOverride = com.lockwallet.data.price.PriceService(emptyList(), { 0L }) }
        override fun after() { MainActivity.txJournalAuthenticatorOverride = null; MainActivity.priceOverride = null; MainActivity.rpcOverride = null; C.dark = true }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun start(fontScale: Float = 1f): AppController {
        val p = LocalSecureProvider(tmp.newFolder(), SoftKek(), Clock())
        val c = AppController(p, DeviceSecurityCapabilityManager(ctx, p), CoroutineScope(Dispatchers.Default), Prefs(ctx).also { it.hideBalance = false; it.fiat = Fiat.BRL },
            com.lockwallet.data.price.PriceService(emptyList(), { 0L }), { _, _ -> true }, AppTestTxJournalAuthenticator(), transport = AppFakeRpc(), dataDir = tmp.newFolder()).also { it.start() }
        c.startFlow(FlowMode.RESTORE)
        c.submitRestore(RestoreKind.PHRASE, "neither lonely flavor argue grass remind eye tag avocado spot unusual intact".toCharArray()); await(c)
        c.chooseAccount(c.draft!!.material!!.discover(1).first())
        c.submitName("Conta 1")
        c.setNewPin("839174".toCharArray())
        c.sealNewVault(AuthMode.PIN_ONLY); await(c)
        c.home()
        rule.runOnUiThread { rule.activity.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) { App(c, Platform({}, {}, {})) }
        } }
        rule.waitForIdle()
        return c
    }

    private fun await(c: AppController) {
        val end = System.currentTimeMillis() + 20_000
        Thread.sleep(30)
        while (c.busy && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    private fun assertInside(tag: String) {
        val root = rule.onRoot().getBoundsInRoot()
        val b = rule.onNodeWithTag(tag).getBoundsInRoot()
        assertTrue("$tag outside screen: $b vs $root", b.left >= root.left && b.right <= root.right)
    }

    @Config(qualifiers = "w320dp-h568dp")
    @Test fun pillBarAndTilesFitA320dpPhoneAtLargeFont() {
        start(1.3f)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("price").fetchSemanticsNodes().isNotEmpty() }
        assertInside("price")
        for (t in Tab.entries) assertInside("tab-${t.name}")
        assertInside("fab")
        rule.onNodeWithTag("tab-SETTINGS").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("theme").performScrollTo()
        assertInside("theme")
    }

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun pillBarRegularPhone() {
        start()
        for (t in Tab.entries) assertInside("tab-${t.name}")
    }

    /** Theme switch cross-fades (C.mix animates) and lands exactly on the other palette; both ways. */
    @Test fun themeCrossFadesBothWays() {
        start()
        assertEquals(1f, C.mix)
        rule.onNodeWithTag("tab-SETTINGS").performClick()
        rule.onNodeWithTag("theme").performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(C.dark)
        rule.waitUntil(5_000) { C.mix == 0f }
        rule.onNodeWithTag("theme").performScrollTo().performClick()
        rule.waitUntil(5_000) { C.mix == 1f }
        assertTrue(C.dark)
    }

    /** Sheets: open over a blurred backdrop, dismiss by dragging down (no Dialog window, FLAG_SECURE-safe). */
    @Test fun sheetDismissesByDraggingDown() {
        start()
        rule.onNodeWithTag("tab-SETTINGS").performClick(); rule.waitForIdle()
        rule.onAllNodesWithTag("help")[0].performScrollTo().performClick()
        rule.onNodeWithTag("sheet").assertExists()
        rule.onNodeWithTag("sheet").performTouchInput { swipeDown(startY = top + 10f, endY = top + 900f, durationMillis = 200) }
        rule.waitForIdle()
        rule.onNodeWithTag("sheet").assertDoesNotExist()
    }

    @Test fun woolTextureIsDeterministicAndNotFlat() {
        val a = Wool.bitmap; val b = Wool.bitmap
        assertTrue(a === b)
        val px = IntArray(a.width * a.height); a.readPixels(px)
        val distinct = px.map { it and 0xFF }.toSet().size
        assertTrue("grain too flat: $distinct", distinct > 32)
    }
}
