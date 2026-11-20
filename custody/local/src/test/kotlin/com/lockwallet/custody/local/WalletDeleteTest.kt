package com.lockwallet.custody.local

import com.lockwallet.custody.local.api.RecoveryException
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.material.AccountProfile
import com.lockwallet.custody.local.material.PendingMaterial
import com.lockwallet.custody.local.pin.PinAttemptLimiter.Companion.HOUR
import com.lockwallet.custody.local.storage.WalletRole
import com.lockwallet.custody.local.storage.WalletState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import com.lockwallet.custody.local.storage.AtomicFiles
import com.lockwallet.custody.local.storage.WriteStep
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import com.lockwallet.custody.local.api.WalletInfo

class WalletDeleteTest {
    @get:Rule val tmp = TemporaryFolder()
    private val kek = FakeKekStore()
    private val clock = FakeClock()
    private var crash = false
    private fun provider() = LocalSecureProvider(tmp.root, kek, clock, atomic = AtomicFiles { if (crash && it == WriteStep.TEMP_SYNCED) throw java.io.IOException("killed") })
    private val pin = "839174"

    private fun reason(block: () -> Unit) = (runCatching(block).exceptionOrNull() as CustodyException).reason
    private fun unlock(p: LocalSecureProvider, id: String): UnlockedWallet {
        val t = p.beginUnlock(id); kek.biometricsPresented = 1
        return (p.completeUnlock(t, pin.toCharArray()) as UnlockResult.Success).session
    }
    private fun two(p: LocalSecureProvider): Pair<WalletInfo, WalletInfo> {
        val first = PendingMaterial.generate(12).use { m ->
            val t = p.beginNewVault(AuthMode.PIN_ONLY); kek.biometricsPresented = 1
            p.seal(t, m, m.discover(1)[0], "A", WalletRole.VAULT, newPin = pin.toCharArray())
        }
        val v = unlock(p, first.id).use { it.pinVerification() }
        val second = PendingMaterial.generate(12).use { m ->
            val t = p.beginSealExisting(); kek.biometricsPresented = 1
            p.seal(t, m, m.discover(1)[0], "B", WalletRole.CONNECT, verification = v)
        }
        return first to second
    }
    private fun env(id: String) = File(tmp.root, "envelopes/$id.lwle")

    @Test fun deletingOneKeepsTheOtherIntactAndUnlockable() {
        val p = provider(); val (a, b) = two(p)
        val before = env(b.id).readBytes()
        unlock(p, a.id).use { p.removeWallet(it) }
        assertEquals(listOf(b.id), p.wallets().map { it.id })
        assertFalse(env(a.id).exists())
        assertArrayEquals(before, env(b.id).readBytes())
        assertTrue(p.hasVault())
        unlock(p, b.id).close()
        assertEquals(CustodyException.Reason.UNKNOWN_WALLET, reason { p.beginUnlock(a.id) })
    }

    @Test fun deletingTheLastWalletLeavesNoVaultAndKeepsAttemptCounters() {
        val p = provider(); val (a, b) = two(p)
        p.completeUnlock(p.beginUnlock(a.id).also { kek.biometricsPresented = 1 }, "111111".toCharArray())
        unlock(p, a.id).use { p.removeWallet(it) }
        val r = p.completeUnlock(p.beginUnlock(b.id).also { kek.biometricsPresented = 1 }, "222222".toCharArray()) as UnlockResult.Failed
        assertTrue(r.failures >= 1)
        unlock(p, b.id).use { p.removeWallet(it) }
        assertTrue(p.wallets().isEmpty())
        assertFalse(p.hasVault())
    }

    @Test fun biometricOnlySessionCannotDelete() {
        val p = provider(); val (a, _) = two(p)
        val s = unlock(p, a.id)
        val field = UnlockedWallet::class.java.getDeclaredField("pinKey").apply { isAccessible = true }
        (field.get(s) as AutoCloseable?)?.close(); field.set(s, null)
        assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { p.removeWallet(s) })
        assertEquals(2, p.wallets().size)
    }

    @Test fun processDeathDuringDeleteKeepsTheWalletUsable() {
        val p = provider(); val (a, _) = two(p)
        unlock(p, a.id).use { s -> crash = true; assertTrue(runCatching { p.removeWallet(s) }.isFailure) }
        crash = false
        val fresh = provider()
        assertEquals(2, fresh.wallets().size)
        unlock(fresh, a.id).close()
    }

    @Test fun unlockRacingADeleteFailsClosedAndStaleSessionCannotDeleteAgain() {
        val p = provider(); val (a, b) = two(p)
        val pending = p.beginUnlock(a.id)
        val s = unlock(p, a.id)
        s.use { p.removeWallet(it) }
        kek.biometricsPresented = 1
        assertTrue(runCatching { p.completeUnlock(pending, pin.toCharArray()) }.exceptionOrNull() is CustodyException)
        assertEquals(CustodyException.Reason.UNKNOWN_WALLET, reason { unlock(p, b.id).close(); p.removeWallet(s) })
        assertEquals(listOf(b.id), p.wallets().map { it.id })
        unlock(p, b.id).close()
    }
}
