package com.lockwallet.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.LocalSecureProvider
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.keystore.KekException
import com.lockwallet.custody.local.keystore.KekOperation
import com.lockwallet.custody.local.keystore.KekStore
import com.lockwallet.custody.local.material.AccountProfile
import com.lockwallet.custody.local.keystore.KeySecurityLevel
import com.lockwallet.custody.local.pin.AttemptClock
import com.lockwallet.custody.local.storage.WalletRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Software KEK for JVM tests only (the Android Keystore is not available under Robolectric). */
internal class SoftKek : KekStore {
    val keys = mutableMapOf<String, ByteArray>()
    override fun create(aliasId: ByteArray, mode: AuthMode): KeySecurityLevel {
        keys[KekStore.alias(aliasId)] = ByteArray(32).also(SecureRandom()::nextBytes); return KeySecurityLevel.TRUSTED_ENVIRONMENT
    }
    override fun exists(aliasId: ByteArray) = KekStore.alias(aliasId) in keys
    override fun securityLevel(aliasId: ByteArray) = if (exists(aliasId)) KeySecurityLevel.TRUSTED_ENVIRONMENT else null
    override fun delete(aliasId: ByteArray) { keys.remove(KekStore.alias(aliasId)) }
    override fun encryptor(aliasId: ByteArray) = op(aliasId, null)
    override fun decryptor(aliasId: ByteArray, iv: ByteArray) = op(aliasId, iv)
    private fun op(aliasId: ByteArray, iv: ByteArray?): KekOperation {
        val k = keys[KekStore.alias(aliasId)] ?: throw KekException(KekException.Kind.NOT_FOUND)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (iv == null) c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES")) else c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        return object : KekOperation {
            override val cipher: Cipher = c
            override val requiresUserAuth = true
            override fun iv(): ByteArray = c.iv
            override fun finish(aad: ByteArray, input: ByteArray): ByteArray =
                try { c.updateAAD(aad); c.doFinal(input) } catch (e: AEADBadTagException) { throw KekException(KekException.Kind.BAD_TAG, e) }
        }
    }
}

internal class Clock(var t: Long = 10_000) : AttemptClock {
    override fun elapsedRealtimeMs() = t
    override fun bootCount() = 1
}

@RunWith(RobolectricTestRunner::class)
class AppFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock()
    private var biometricOk = true
    private var prompts = 0

    private fun controller(): AppController {
        val p = LocalSecureProvider(tmp.root, SoftKek().also { kek = it }, clock)
        return AppController(p, DeviceSecurityCapabilityManager(ctx, p), CoroutineScope(Dispatchers.Default), Prefs(ctx), null, { _, _ -> prompts++; biometricOk })
            .also { it.start() }
    }
    private var kek: SoftKek? = null

    private fun AppController.await() {
        val end = System.currentTimeMillis() + 20_000
        Thread.sleep(20)
        while (busy && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("operation timed out", !busy)
    }

    private fun pin(s: String) = s.toCharArray()

    /** Full creation ceremony through the controller: 24 words, quiz, name, PIN + biometrics. */
    private fun createWallet(c: AppController, words: Int = 24, role: WalletRole = WalletRole.VAULT): String {
        c.startFlow(FlowMode.CREATE)
        assertEquals(Screen.Custody, c.screen)
        c.draft!!.role = role
        c.draft!!.words = words
        c.generatePhrase(); c.await()
        assertEquals(Screen.ShowPhrase, c.screen)
        assertEquals(words, c.draft!!.material!!.words().size)
        c.beginQuiz(); c.await()
        assertEquals(Screen.Quiz, c.screen)
        val m = c.draft!!.material!!
        for (q in c.draft!!.quiz) assertEquals(1, q.options.count { m.isWordAt(q.position, it) })
        c.quizPassed()
        assertEquals(Screen.Name, c.screen)
        c.submitName("Principal")
        if (c.hasVault()) {
            assertTrue(c.screen is Screen.Authorize)
            c.submitPin(pin("480917"), AuthPurpose.SealDraft)
        } else {
            assertEquals(Screen.PinCreate, c.screen)
            c.setNewPin(pin("480917"))
            assertEquals(Screen.BioChoice, c.screen)
            c.sealNewVault(AuthMode.PIN_AND_BIOMETRIC)
        }
        c.await()
        assertNull(c.error)
        val s = c.screen as Screen.Detail
        assertNull(c.draft)
        return s.walletId
    }

    @Test fun createLockUnlockAndAddSecondWallet() {
        val c = controller()
        assertEquals(Screen.Home, c.screen)
        val id = createWallet(c)
        assertEquals(1, c.wallets.size)
        c.lockNow()
        assertEquals(Screen.Lock, c.screen)
        c.submitPin(pin("480917"), null); c.await()
        assertEquals(Screen.Home, c.screen)
        // Second wallet: PIN verification (prompt 1) + seal (prompt 2).
        prompts = 0
        createWallet(c, 12, WalletRole.CONNECT)
        assertEquals(2, prompts)
        assertEquals(2, c.wallets.size)
        // Re-view phrase requires authorisation and yields an opened session.
        c.go(Screen.Authorize(AuthPurpose.RevealPhrase(id)))
        c.submitPin(pin("480917"), AuthPurpose.RevealPhrase(id)); c.await()
        assertEquals(Screen.Reveal(id), c.screen)
        assertEquals(24, c.opened!!.session.words().size)
        c.home()
        assertNull(c.opened)
    }

    // ---- wallet delete (security review)
    private fun delete(c: AppController, id: String, pinText: String = "480917") {
        c.go(Screen.Authorize(AuthPurpose.DeleteWallet(id))); c.submitPin(pin(pinText), AuthPurpose.DeleteWallet(id)); c.await()
    }
    private fun envelopes() = (java.io.File(tmp.root, "envelopes").listFiles() ?: emptyArray()).filter { it.name.endsWith(".lwle") }

    @Test fun deleteActiveSwitchesToTheOtherThenLastReturnsToWelcome() {
        val c = controller()
        val a = createWallet(c); val b = createWallet(c, 12, WalletRole.CONNECT)
        c.chain.select(a)
        delete(c, a)
        assertEquals(listOf(b), c.wallets.map { it.id })
        assertEquals(b, c.chain.wallet()!!.id)
        assertEquals(b, Prefs(ctx).selectedWallet)
        assertEquals(1, envelopes().size)
        delete(c, b)
        assertTrue(c.wallets.isEmpty()); assertTrue(!c.vaultPresent)
        assertEquals(Screen.Home, c.screen)
        assertTrue(envelopes().isEmpty())
    }

    @Test fun deleteNeverAcceptsBiometricsAndWrongPinDeletesNothing() {
        val c = controller()
        val a = createWallet(c)
        assertTrue(!AuthPurpose.DeleteWallet(a).biometricAllowed())
        c.submitBiometric(AuthPurpose.DeleteWallet(a)); c.await()
        assertEquals(1, c.wallets.size)
        delete(c, a, "111111")
        assertEquals(1, c.wallets.size); assertEquals(1, envelopes().size)
    }

    @Test fun lockoutCannotBeBypassedByDeleting() {
        val c = controller()
        val a = createWallet(c)
        repeat(12) { if (c.lockoutMs == 0L) delete(c, a, "111111") }
        assertTrue(c.lockoutMs > 0)
        delete(c, a)
        assertEquals(1, c.wallets.size)
        assertTrue(c.lockoutMs > 0)
    }

    @Test fun wrongPinCountdownAndBiometricCancel() {
        val c = controller()
        createWallet(c)
        c.lockNow()
        repeat(4) { c.submitPin(pin("000001"), null); c.await() }
        assertNotNull(c.lockMessage)
        c.submitPin(pin("000001"), null); c.await()
        c.refreshLockout()
        assertEquals(3_600_000L, c.lockoutMs)
        c.submitPin(pin("480917"), null); c.await()
        assertEquals(Screen.Lock, c.screen) // locked out even with the right PIN
        clock.t += 3_600_000L
        biometricOk = false
        c.submitPin(pin("480917"), null); c.await()
        assertEquals(Screen.Lock, c.screen)
        biometricOk = true
        c.submitPin(pin("480917"), null); c.await()
        assertEquals(Screen.Home, c.screen)
    }

    @Test fun restoreByPhraseSelectsOnePrimaryAccountAndPreparesExport() {
        val c = controller()
        c.startFlow(FlowMode.RESTORE)
        c.submitRestore(RestoreKind.PHRASE, "Neither lonely flavor argue grass remind eye tag avocado spot unusual intact".toCharArray()); c.await()
        assertEquals(Screen.Name, c.screen)
        val restored = c.draft!!.account!!
        assertEquals(AccountProfile.PRIMARY, restored.profile)
        assertEquals(0, restored.index)
        assertEquals(c.draft!!.material!!.account(AccountProfile.PRIMARY, 0), restored)
        c.submitName("Restaurada")
        c.setNewPin(pin("480917"))
        c.sealNewVault(AuthMode.PIN_ONLY); c.await()
        val id = (c.screen as Screen.Detail).walletId
        assertEquals(0, c.wallets.single().accountIndex)
        // Export: authorise, seal LWRP, the destination picker is requested with a .lwrp name.
        c.submitPin(pin("480917"), AuthPurpose.ExportLwrp(id)); c.await()
        assertEquals(Screen.Export(id), c.screen)
        var requested: String? = null
        c.prepareExport("a sufficiently long password".toCharArray()) { requested = it }; c.await()
        assertTrue(requested!!.endsWith(".lwrp"))
        assertEquals(126, c.pendingExport!!.first.size)
        assertTrue(c.externalPicker)
        c.onStop() // picker in front: must NOT lock
        assertEquals(Screen.Export(id), c.screen)
        c.externalPicker = false
        c.onStop() // really leaving: lock and wipe
        assertNull(c.pendingExport); assertNull(c.opened)
        assertEquals(Screen.Lock, c.screen)
    }

    @Test fun invalidPhraseShowsPtBrError() {
        val c = controller()
        c.startFlow(FlowMode.RESTORE)
        c.submitRestore(RestoreKind.PHRASE, "abandon abandon".toCharArray()); c.await()
        assertEquals("A frase deve ter 12, 15, 18, 21 ou 24 palavras.", c.error)
    }
}

@RunWith(RobolectricTestRunner::class)
class FiatFormatTest {
    @Test fun formatsExactDecimalsInPtBr() {
        val usd = com.lockwallet.app.ui.formatFiat(java.math.BigDecimal("73.965"), com.lockwallet.data.price.Fiat.USD)
        assertTrue(usd, usd.contains("73,96") || usd.contains("73,97"))
        val brl = com.lockwallet.app.ui.formatFiat(java.math.BigDecimal("436.2381734999999038455"), com.lockwallet.data.price.Fiat.BRL)
        assertTrue(brl, brl.startsWith("R$") && brl.contains("436,24"))
    }
}
