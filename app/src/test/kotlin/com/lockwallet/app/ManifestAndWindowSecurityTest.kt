package com.lockwallet.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Robolectric checks of the merged manifest and window flags (spec §17.1: Robolectric proves
 * contracts, not hardware security). The release APK itself is inspected by `verifyReleaseApk`.
 */
@RunWith(AndroidJUnit4::class)
class ManifestAndWindowSecurityTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun backupIsDisabled() {
        val info = context.applicationInfo
        assertEquals("allowBackup must be false", 0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun onlyAllowlistedPermissions() {
        val pkg = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = pkg.requestedPermissions?.toList().orEmpty()
            .filterNot { it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }.toSet()
        assertEquals(setOf("android.permission.USE_BIOMETRIC", "android.permission.HIDE_OVERLAY_WINDOWS", "android.permission.INTERNET",
            "com.solanamobile.seedvault.ACCESS_SEED_VAULT", "android.permission.CAMERA"), requested)
    }

    /** Internal version constant matches the build (never shown in the UI: final release is just "Lock Wallet"). */
    @Test fun aboutVersionMatchesTheBuild() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(info.versionName, com.lockwallet.app.ui.APP_VERSION)
        assertEquals(13L, info.longVersionCode)
    }

    @Test fun signingAndDappSigningAlwaysRequirePin() {
        assertFalse(AuthPurpose.Sign("wallet").biometricAllowed())
        assertFalse(AuthPurpose.DappSign("wallet").biometricAllowed())
        assertFalse(AuthPurpose.DeleteWallet("wallet").biometricAllowed())
        assertTrue(null.biometricAllowed())
    }

    @Test fun onlyLauncherAndLockConnectAreExported() {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS
        val pkg = pm.getPackageInfo(context.packageName, flags)
        val exported = buildList {
            pkg.activities?.filter { it.exported }?.forEach { add(it.name) }
            pkg.receivers?.filter { it.exported }?.forEach { add(it.name) }
            pkg.services?.filter { it.exported }?.forEach { add(it.name) }
            pkg.providers?.filter { it.exported }?.forEach { add(it.name) }
        }
        assertEquals(listOf(MainActivity::class.java.name, com.lockwallet.app.connect.LockConnectActivity::class.java.name).sorted(), exported.sorted())
    }

    @Test fun windowIsSecureAndFiltersObscuredTouches() {
        org.robolectric.Shadows.shadowOf(context).grantPermissions("android.permission.HIDE_OVERLAY_WINDOWS")
        MainActivity.rpcOverride = { null }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertTrue("FLAG_SECURE missing", flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(activity.window.decorView.filterTouchesWhenObscured)
            }
        }
    }
}
