package com.lockwallet.custody.local

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.keystore.KeySecurityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DeviceSecurityCapabilityManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun facts(vararg tweaks: (DeviceFacts) -> DeviceFacts): DeviceFacts {
        var f = DeviceFacts(true, BiometricAvailability.AVAILABLE, true, KeySecurityLevel.STRONGBOX, AuthMode.PIN_AND_BIOMETRIC, 0, 0,
            "2026-10-05", LocalDate.of(2026, 11, 19), false, false, emptyList())
        tweaks.forEach { f = it(f) }
        return f
    }

    private fun status(f: DeviceFacts, id: CheckId) = DeviceSecurityCapabilityManager.evaluate(f).first { it.id == id }.status

    @Test fun evaluationReflectsRealStates() {
        val good = facts()
        assertEquals(CheckStatus.OK, status(good, CheckId.LOCK_SCREEN))
        assertEquals(CheckStatus.OK, status(good, CheckId.VAULT_KEY_LEVEL))
        assertEquals(CheckStatus.UNAVAILABLE, status(good, CheckId.SEED_VAULT))
        assertEquals(CheckStatus.RISK, status(facts({ it.copy(deviceSecure = false) }), CheckId.LOCK_SCREEN))
        assertEquals(CheckStatus.RISK, status(facts({ it.copy(vaultKeyLevel = KeySecurityLevel.SOFTWARE) }), CheckId.VAULT_KEY_LEVEL))
        assertEquals(CheckStatus.WARN, status(facts({ it.copy(vaultAuthMode = AuthMode.PIN_ONLY) }), CheckId.VAULT_AUTH_MODE))
        assertEquals(CheckStatus.RISK, status(facts({ it.copy(securityPatch = "2025-01-01") }), CheckId.SECURITY_PATCH))
        assertEquals(CheckStatus.RISK, status(facts({ it.copy(securityPatch = "2026-04-04") }), CheckId.SECURITY_PATCH))
        assertEquals(CheckStatus.WARN, status(facts({ it.copy(securityPatch = "2026-04-04", vaultKeyLevel = KeySecurityLevel.TRUSTED_ENVIRONMENT) }), CheckId.SECURITY_PATCH))
        assertEquals(CheckStatus.WARN, status(facts({ it.copy(securityPatch = "garbage") }), CheckId.SECURITY_PATCH))
        assertEquals(CheckStatus.RISK, status(facts({ it.copy(rootIndicators = listOf("/system/xbin/su")) }), CheckId.ROOT_HEURISTIC))
        assertEquals(CheckStatus.RISK, status(facts({ it.copy(lockoutRemainingMs = 5) }), CheckId.PIN_ATTEMPTS))
        assertEquals(CheckStatus.WARN, status(facts({ it.copy(biometric = BiometricAvailability.NONE_ENROLLED) }), CheckId.BIOMETRIC_STRONG))
    }

    @Test fun readsPlatformOnRobolectric() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val m = DeviceSecurityCapabilityManager(ctx, LocalSecureProvider(tmp.root, FakeKekStore(), FakeClock()))
        val checks = m.checks()
        assertEquals(CheckId.entries.size, checks.size)
        assertTrue(checks.first { it.id == CheckId.VAULT_KEY_LEVEL }.status == CheckStatus.INFO) // no vault yet
    }
}
