package com.lockwallet.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lockwallet.core.solana.Base58
import com.lockwallet.core.solana.tx.WireTransaction
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.LocalSecureProvider
import com.lockwallet.custody.local.api.CustodyProviderId
import com.lockwallet.custody.local.storage.WalletRole
import com.lockwallet.custody.seedvault.SeedSource
import com.lockwallet.custody.seedvault.SeedVaultFailure
import com.lockwallet.custody.seedvault.SeedVaultGateway
import com.lockwallet.custody.seedvault.SeedVaultProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.Signature

/** Software Seed Vault stand-in (JDK Ed25519, tests only). Records what it was asked to sign. */
class JdkSeedVault(var present: Boolean = true) : SeedVaultGateway {
    private val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val pk: ByteArray = kp.public.encoded.takeLast(32).toByteArray()
    val signed = mutableListOf<ByteArray>()
    var decline = false
    override fun available() = present
    override fun accessGranted() = true
    override fun permission(): String? = null
    override fun seedIntent(source: SeedSource) = Intent("seed")
    override fun parseSeedResult(source: SeedSource, resultCode: Int, data: Intent?) = if (resultCode == -1) 7L else throw SeedVaultFailure("x")
    override fun publicKeyIntent(authToken: Long, path: Uri) = Intent("pk")
    override fun parsePublicKey(resultCode: Int, data: Intent?) = pk to Uri.parse("bip32:/m/44'/501'/0'/0'")
    override fun signTransactionIntent(authToken: Long, path: Uri, payload: ByteArray) = Intent("sign").putExtra("p", payload)
    override fun signMessageIntent(authToken: Long, path: Uri, payload: ByteArray) = Intent("msg").putExtra("p", payload)
    override fun parseSignature(resultCode: Int, data: Intent?, message: Boolean): ByteArray {
        if (resultCode != -1) throw SeedVaultFailure("declined")
        val m = data!!.getByteArrayExtra("p")!!; signed += m
        return Signature.getInstance("Ed25519").run { initSign(kp.private); update(m); sign() }
    }
    override fun authorizedTokens() = setOf(7L)
    override fun deauthorize(authToken: Long) {}
    override fun accountPath(account: Int): Uri = Uri.parse("bip44:/$account'/0'")
}

@RunWith(RobolectricTestRunner::class)
class SeedVaultFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val rpc = AppFakeRpc()

    private fun controller(g: JdkSeedVault): AppController {
        val p = LocalSecureProvider(tmp.newFolder(), SoftKek(), Clock())
        return AppController(p, DeviceSecurityCapabilityManager(ctx, p), CoroutineScope(Dispatchers.Default), Prefs(ctx), null, { _, _ -> true },
            transport = rpc, dataDir = tmp.newFolder(), seedVault = SeedVaultProvider(tmp.newFolder(), g)).also { c ->
            c.start()
            c.activityRunner = { i -> (if (g.decline && i.action == "sign") 0 else -1) to i }
        }
    }

    private fun AppController.await() {
        val end = System.currentTimeMillis() + 20_000
        Thread.sleep(30)
        while (busy && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("timed out", !busy)
    }

    @Test fun seedVaultWalletSignsAnalyzedBytesWithoutLockWalletPin() {
        val g = JdkSeedVault()
        val c = controller(g)
        assertTrue(c.seedVaultAvailable)
        c.addSeedVaultWallet(WalletRole.VAULT, "Seeker", SeedSource.AUTHORIZE_EXISTING); c.await()
        val w = c.wallets.single()
        assertEquals(c.error, CustodyProviderId.SEED_VAULT, w.provider)
        assertEquals(Base58.encode(g.pk), w.address.toBase58())
        runBlocking { c.chain.refreshHome() }
        c.chain.reviewSol("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", "0,25", max = false); c.await()
        val p = c.chain.prepared!!
        // Declined in Seed Vault: nothing sent.
        g.decline = true
        c.chain.authorize(); c.await()
        assertTrue(rpc.sent.isEmpty())
        assertTrue(c.error != null)
        g.decline = false; c.error = null
        c.chain.authorize(); c.await()
        assertTrue("no Lock Wallet PIN screen for Seed Vault", c.screen is Screen.TxStatus)
        assertArrayEquals(p.messageBytes, g.signed.last())
        val (sigs, msg) = WireTransaction.split(java.util.Base64.getDecoder().decode(rpc.sent.distinct().single()))
        assertArrayEquals(p.messageBytes, msg)
        assertEquals((c.screen as Screen.TxStatus).signature, Base58.encode(sigs.single()))
    }

    @Test fun absentSeedVaultIsClearlyUnavailable() {
        val c = controller(JdkSeedVault(present = false))
        assertFalse(c.seedVaultAvailable)
        c.addSeedVaultWallet(WalletRole.VAULT, "X", SeedSource.CREATE_NEW); c.await()
        assertEquals(com.lockwallet.app.ui.S.reason(com.lockwallet.custody.local.CustodyException.Reason.SEED_VAULT_UNAVAILABLE), c.error)
        assertTrue(c.wallets.isEmpty())
    }
}
