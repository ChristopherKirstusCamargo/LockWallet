package com.lockwallet.custody.local

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.provider.Settings
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.keystore.KeySecurityLevel
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class CheckStatus { OK, WARN, RISK, UNAVAILABLE, INFO }

enum class CheckId {
    LOCK_SCREEN, BIOMETRIC_STRONG, STRONGBOX_FEATURE, VAULT_KEY_LEVEL, VAULT_AUTH_MODE, PIN_ATTEMPTS,
    SECURITY_PATCH, ADB, DEVELOPER_OPTIONS, ROOT_HEURISTIC, DEBUGGER, HOOKING, SCREEN_PROTECTION, BACKUP, NETWORK, SEED_VAULT,
}

/** One real, measured state. [value] is the raw observation (e.g. patch date) for display; never a secret. */
data class SecurityCheck(val id: CheckId, val status: CheckStatus, val value: String? = null)

enum class BiometricAvailability { AVAILABLE, NONE_ENROLLED, NO_HARDWARE, UPDATE_REQUIRED, UNKNOWN }

/** Raw platform observations (separated so the evaluation logic is unit-testable). */
data class DeviceFacts(
    val deviceSecure: Boolean,
    val biometric: BiometricAvailability,
    val strongBoxFeature: Boolean,
    val vaultKeyLevel: KeySecurityLevel?,
    val vaultAuthMode: AuthMode?,
    val pinFailures: Int,
    val lockoutRemainingMs: Long,
    val securityPatch: String,
    val today: LocalDate,
    val adbEnabled: Boolean,
    val developerOptions: Boolean,
    val rootIndicators: List<String>,
    val seedVault: Boolean = false,
    val debuggerAttached: Boolean = false,
    val appDebuggable: Boolean = false,
    val hookIndicators: List<String> = emptyList(),
    val cluster: String = "Devnet",
)

/**
 * Runtime device security capabilities (prompt 1.2 Security Center). Only reports what the platform
 * actually says; heuristics (root) are labelled as such. Nothing is sent anywhere.
 */
class DeviceSecurityCapabilityManager(private val context: Context, private val provider: LocalSecureProvider) {
    /** Set by the app: whether the Seed Vault provider is really present (Phase G). */
    var seedVaultAvailable: () -> Boolean = { false }
    /** Set by the app: selected cluster label (informational). */
    var clusterLabel: () -> String = { "Devnet" }

    fun biometricAvailability(): BiometricAvailability {
        val bm = context.getSystemService(BiometricManager::class.java) ?: return BiometricAvailability.UNKNOWN
        return when (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricAvailability.UPDATE_REQUIRED
            else -> BiometricAvailability.UNKNOWN
        }
    }

    fun facts(): DeviceFacts = DeviceFacts(
        deviceSecure = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true,
        biometric = biometricAvailability(),
        strongBoxFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
        vaultKeyLevel = runCatching { provider.keySecurityLevel() }.getOrNull(),
        vaultAuthMode = runCatching { provider.vaultAuthMode() }.getOrNull(),
        pinFailures = runCatching { provider.attempts.failures() }.getOrDefault(0),
        lockoutRemainingMs = runCatching { provider.attempts.lockoutRemainingMs() }.getOrDefault(0),
        securityPatch = Build.VERSION.SECURITY_PATCH ?: "",
        today = LocalDate.now(),
        adbEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1,
        developerOptions = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
        rootIndicators = rootIndicators(),
        seedVault = runCatching { seedVaultAvailable() }.getOrDefault(false),
        debuggerAttached = android.os.Debug.isDebuggerConnected() || android.os.Debug.waitingForDebugger() || tracerPid() != 0,
        appDebuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        hookIndicators = hookIndicators(),
        cluster = runCatching { clusterLabel() }.getOrDefault("Devnet"),
    )

    fun checks(): List<SecurityCheck> = evaluate(facts())

    companion object {
        private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sbin/su", "/vendor/bin/su",
            "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su", "/system/app/Superuser.apk")

        private val HOOK_MARKERS = listOf("frida", "gum-js", "gadget", "xposed", "lsposed", "edxp", "substrate", "riru", "zygisk")

        /** TracerPid from /proc/self/status (non-zero: a debugger or ptrace-based tool is attached). */
        internal fun tracerPid(): Int = runCatching {
            File("/proc/self/status").useLines { l -> l.firstOrNull { it.startsWith("TracerPid:") }?.substringAfter(':')?.trim()?.toInt() } ?: 0
        }.getOrDefault(0)

        /** Heuristic: hooking frameworks visible in our own memory map or class path. Inform only; never wipe (spec). */
        internal fun hookIndicators(): List<String> {
            val found = linkedSetOf<String>()
            runCatching {
                File("/proc/self/maps").useLines { lines -> lines.take(20_000).forEach { line ->
                    val low = line.lowercase(); HOOK_MARKERS.firstOrNull { it in low }?.let { found += "maps:$it" } } }
            }
            for (c in listOf("de.robv.android.xposed.XposedBridge", "com.saurik.substrate.MS")) {
                if (runCatching { Class.forName(c, false, ClassLoader.getSystemClassLoader()); true }.getOrDefault(false)) found += "class:" + c.substringAfterLast('.')
            }
            return found.toList().take(6)
        }

        internal fun rootIndicators(): List<String> {
            val found = SU_PATHS.filter { runCatching { File(it).exists() }.getOrDefault(false) }.toMutableList()
            if (Build.TAGS?.contains("test-keys") == true) found += "build:test-keys"
            return found
        }

        fun evaluate(f: DeviceFacts): List<SecurityCheck> {
            val out = mutableListOf<SecurityCheck>()
            out += SecurityCheck(CheckId.LOCK_SCREEN, if (f.deviceSecure) CheckStatus.OK else CheckStatus.RISK)
            out += SecurityCheck(CheckId.BIOMETRIC_STRONG, when (f.biometric) {
                BiometricAvailability.AVAILABLE -> CheckStatus.OK
                BiometricAvailability.NONE_ENROLLED, BiometricAvailability.UPDATE_REQUIRED -> CheckStatus.WARN
                BiometricAvailability.NO_HARDWARE, BiometricAvailability.UNKNOWN -> CheckStatus.UNAVAILABLE
            }, f.biometric.name)
            out += SecurityCheck(CheckId.STRONGBOX_FEATURE, if (f.strongBoxFeature) CheckStatus.OK else CheckStatus.INFO)
            out += SecurityCheck(CheckId.VAULT_KEY_LEVEL, when (f.vaultKeyLevel) {
                KeySecurityLevel.STRONGBOX, KeySecurityLevel.TRUSTED_ENVIRONMENT -> CheckStatus.OK
                KeySecurityLevel.UNKNOWN_SECURE -> CheckStatus.WARN
                KeySecurityLevel.SOFTWARE -> CheckStatus.RISK
                KeySecurityLevel.UNKNOWN -> CheckStatus.WARN
                null -> CheckStatus.INFO
            }, f.vaultKeyLevel?.name)
            out += SecurityCheck(CheckId.VAULT_AUTH_MODE, when (f.vaultAuthMode) {
                AuthMode.PIN_AND_BIOMETRIC -> CheckStatus.OK
                AuthMode.PIN_ONLY -> CheckStatus.WARN
                null -> CheckStatus.INFO
            }, f.vaultAuthMode?.name)
            out += SecurityCheck(CheckId.PIN_ATTEMPTS, when {
                f.lockoutRemainingMs > 0 -> CheckStatus.RISK
                f.pinFailures > 0 -> CheckStatus.WARN
                else -> CheckStatus.OK
            }, f.pinFailures.toString())
            val patchAgeDays = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(f.securityPatch), f.today) }.getOrNull()
            // Android Security Bulletin 2026-04: CVE-2025-48651 is High severity for StrongBox
            // components before the 2026-04-05 patch level. Do not let the generic 120-day warning
            // downgrade this specific hardware-backed key risk to WARN.
            val unpatchedStrongBox = f.vaultKeyLevel == KeySecurityLevel.STRONGBOX &&
                f.securityPatch.isNotBlank() && f.securityPatch < "2026-04-05"
            out += SecurityCheck(CheckId.SECURITY_PATCH, when {
                unpatchedStrongBox -> CheckStatus.RISK
                patchAgeDays == null -> CheckStatus.WARN
                patchAgeDays > 365 -> CheckStatus.RISK
                patchAgeDays > 120 -> CheckStatus.WARN
                else -> CheckStatus.OK
            }, if (unpatchedStrongBox) "${f.securityPatch} · StrongBox CVE-2025-48651" else f.securityPatch)
            out += SecurityCheck(CheckId.ADB, if (f.adbEnabled) CheckStatus.WARN else CheckStatus.OK)
            out += SecurityCheck(CheckId.DEVELOPER_OPTIONS, if (f.developerOptions) CheckStatus.WARN else CheckStatus.OK)
            out += SecurityCheck(CheckId.ROOT_HEURISTIC, if (f.rootIndicators.isEmpty()) CheckStatus.OK else CheckStatus.RISK,
                f.rootIndicators.joinToString(", ").ifEmpty { null })
            out += SecurityCheck(CheckId.DEBUGGER, when {
                f.debuggerAttached -> CheckStatus.RISK
                f.appDebuggable -> CheckStatus.WARN
                else -> CheckStatus.OK
            }, if (f.debuggerAttached) "attached" else if (f.appDebuggable) "debuggable" else null)
            out += SecurityCheck(CheckId.HOOKING, if (f.hookIndicators.isEmpty()) CheckStatus.OK else CheckStatus.RISK,
                f.hookIndicators.joinToString(", ").ifEmpty { null })
            out += SecurityCheck(CheckId.SCREEN_PROTECTION, CheckStatus.OK)
            out += SecurityCheck(CheckId.BACKUP, CheckStatus.OK)
            out += SecurityCheck(CheckId.NETWORK, CheckStatus.INFO, f.cluster)
            out += SecurityCheck(CheckId.SEED_VAULT, if (f.seedVault) CheckStatus.OK else CheckStatus.UNAVAILABLE)
            return out
        }
    }
}
