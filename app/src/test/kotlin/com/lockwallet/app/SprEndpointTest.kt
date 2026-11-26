package com.lockwallet.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.LocalSecureProvider
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.data.rpc.RpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Dedicated Privacy Rings (Helius) endpoint — the 5.0.7 fix: the Default Ring must use its own
 * validated endpoint (HTTPS, Helius host, api-key, Mainnet genesis), fail closed when absent,
 * and never touch the app-wide primary RPC.
 */
@RunWith(RobolectricTestRunner::class)
class SprEndpointTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val rpc = AppFakeRpc()

    @Before fun cleanPrefs() {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
    }

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
        c.setNewPin(listOf(8, 3, 5 + 4, 2 - 1, 14 - 7, 2 * 2).joinToString("").toCharArray())
        c.sealNewVault(AuthMode.PIN_AND_BIOMETRIC); c.await()
    }

    @Test fun savesValidatedHeliusEndpoint() {
        val c = controller(); restore(c)
        val url = "https://mainnet.helius-rpc.com/?api-key=TESTKEY"
        c.chain.saveSprEndpoint(url); c.await()
        assertEquals(com.lockwallet.app.ui.S.SPR_SAVED, c.chain.notice)
        assertEquals(url, c.prefs.sprEndpoint)
        assertTrue(c.prefs.customSpr)
    }

    @Test fun rejectsNonHeliusHostPlainHttpAndMissingApiKey() {
        val c = controller(); restore(c)
        val bad = listOf(
            "https://api.mainnet-beta.solana.com",
            "http://mainnet.helius-rpc.com/?api-key=X",
            "https://mainnet.helius-rpc.com/",
            "https://rpc.helius-rpc.com.evil.io/?api-key=X",
        )
        for (u in bad) {
            c.chain.saveSprEndpoint(u); c.await()
            assertEquals("rejected: $u", com.lockwallet.app.ui.S.SPR_BAD_URL, c.error)
            assertNull("not stored: $u", c.prefs.sprEndpoint)
        }
    }

    @Test fun rejectsUnreachableEndpoint() {
        val c = controller(); restore(c)
        rpc.handlers["getGenesisHash"] = { _: String -> throw RpcException(RpcException.Kind.TRANSPORT, "down") }
        c.chain.saveSprEndpoint("https://mainnet.helius-rpc.com/?api-key=X"); c.await()
        assertEquals(com.lockwallet.app.ui.S.SPR_UNREACHABLE, c.error)
        assertNull(c.prefs.sprEndpoint)
    }

    @Test fun removesEndpointAndClosesTheRoute() {
        val c = controller(); restore(c)
        c.chain.saveSprEndpoint("https://mainnet.helius-rpc.com/?api-key=X"); c.await()
        assertEquals(com.lockwallet.app.ui.S.SPR_SAVED, c.chain.notice)
        c.chain.resetSprEndpoint()
        assertEquals(com.lockwallet.app.ui.S.SPR_REMOVED, c.chain.notice)
        assertNull(c.prefs.sprEndpoint)
        assertFalse(c.prefs.customSpr)
    }

    @Test fun enterPrivacyFailsClosedWithoutEndpoint() {
        val c = controller(); restore(c)
        c.chain.enterPrivacy()
        assertEquals(com.lockwallet.app.ui.S.SPR_NOT_SET, c.error)
    }

    @Test fun migratesLegacyHeliusPrimaryOnce() {
        val sp = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        sp.edit().putString("rpc.primary", "https://mainnet.helius-rpc.com/?api-key=OLD").commit()
        val prefs = Prefs(ctx)
        assertEquals("https://mainnet.helius-rpc.com/?api-key=OLD", prefs.sprEndpoint)
        // One-shot: the migrated value is persisted and survives removal of the old key.
        sp.edit().remove("rpc.primary").commit()
        assertEquals("https://mainnet.helius-rpc.com/?api-key=OLD", prefs.sprEndpoint)
    }

    @Test fun nonHeliusPrimaryIsNotMigrated() {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
            .putString("rpc.primary", "https://api.example.com").commit()
        assertNull(Prefs(ctx).sprEndpoint)
    }

    @Test fun heliusUrlShape() {
        assertTrue(isHeliusSprUrl("https://mainnet.helius-rpc.com/?api-key=X"))
        assertTrue(isHeliusSprUrl("https://helius-rpc.com/?api-key=X&foo=1"))
        assertTrue(isHeliusSprUrl("https://mainnet.helius-rpc.com/v1/spr?api-key=X"))
        assertFalse(isHeliusSprUrl("http://mainnet.helius-rpc.com/?api-key=X"))
        assertFalse(isHeliusSprUrl("https://mainnet.helius-rpc.com/"))
        assertFalse(isHeliusSprUrl("https://mainnet.helius-rpc.com/?api-key="))
        assertFalse(isHeliusSprUrl("https://evil.com/?api-key=X"))
        assertFalse(isHeliusSprUrl("https://mainnet.helius-rpc.com.evil.io/?api-key=X"))
        assertFalse(isHeliusSprUrl("https://user:pass@mainnet.helius-rpc.com/?api-key=X"))
        assertFalse(isHeliusSprUrl("mainnet.helius-rpc.com/?api-key=X"))
        assertFalse(isHeliusSprUrl(""))
    }
}
