package com.lockwallet.app

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.lockwallet.app.ui.App
import com.lockwallet.app.ui.Platform
import com.lockwallet.app.ui.S
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.LocalCustody
import com.lockwallet.custody.local.RecoveryDocumentIo
import kotlinx.coroutines.suspendCancellableCoroutine
import com.lockwallet.data.price.PriceService
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * Single activity. Window hardening applied before any content (spec §4.1, v2.2 §8.3):
 *  - FLAG_SECURE: no screenshots/recording, hidden in recents.
 *  - filterTouchesWhenObscured: drops touches delivered while another window covers this one.
 *  - setHideOverlayWindows(true): hides non-system overlays (tapjacking) while the app is visible.
 * Leaving the app (onStop) locks the vault and discards secrets, except while the system file picker is up.
 * Defense in depth, not proof of a clean UI on a compromised system.
 */
open class MainActivity : FragmentActivity() {
    protected lateinit var controller: AppController
    private var onOpened: ((ByteArray?) -> Unit)? = null
    private var pendingResult: kotlinx.coroutines.CompletableDeferred<Pair<Int, android.content.Intent?>>? = null
    private var pendingPermission: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Normal permission, granted at install; if a ROM refuses it we keep running and say so in the Security Center.
        val overlaysHidden = try { window.setHideOverlayWindows(true); true } catch (_: SecurityException) { false }
        super.onCreate(savedInstanceState)
        window.decorView.filterTouchesWhenObscured = true
        installLaunchSplash(coldStart = savedInstanceState == null)

        val provider = LocalCustody.create(this)
        val docs = RecoveryDocumentIo(contentResolver, provider)
        val prefs = Prefs(this)
        com.lockwallet.app.ui.C.dark = prefs.dark
        val price = priceOverride ?: PriceService.default { android.os.SystemClock.elapsedRealtime() }
        val rpc = rpcOverride.let { f -> if (f != null) f() else com.lockwallet.data.rpc.HttpsRpcTransport() }
        val seedVault = com.lockwallet.custody.seedvault.SeedVaultProvider(java.io.File(noBackupFilesDir, "seedvault"),
            seedVaultOverride ?: com.lockwallet.custody.seedvault.SdkSeedVaultGateway(this))
        val caps = DeviceSecurityCapabilityManager(this, provider)
        controller = AppController(provider, caps, lifecycleScope, prefs, price, ::authenticate,
            txJournalAuthenticator = txJournalAuthenticatorOverride ?: AndroidTxJournalAuthenticator(),
            transport = rpc, dataDir = noBackupFilesDir, seedVault = seedVault)
        caps.seedVaultAvailable = { controller.seedVaultAvailable }
        caps.clusterLabel = { controller.chain.config.cluster.label }
        controller.overlaysHidden = overlaysHidden
        controller.start()

        setContent {
            val external = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                pendingResult?.complete(r.resultCode to r.data); pendingResult = null
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                pendingPermission?.complete(ok); pendingPermission = null
            }
            controller.activityRunner = { intent ->
                val d = kotlinx.coroutines.CompletableDeferred<Pair<Int, android.content.Intent?>>()
                pendingResult?.cancel(); pendingResult = d
                try { external.launch(intent) } catch (_: android.content.ActivityNotFoundException) { d.complete(RESULT_CANCELED to null) }
                d.await()
            }
            controller.permissionRunner = { perm ->
                if (ContextCompat.checkSelfPermission(this, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) true else {
                    val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    pendingPermission?.cancel(); pendingPermission = d
                    controller.externalPicker = true
                    try { permission.launch(perm); d.await() } finally { controller.externalPicker = false }
                }
            }
            val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                controller.externalPicker = false
                val bytes = uri?.let { runCatching { docs.readBounded(it, MAX_IMPORT_BYTES) }.getOrNull() }
                onOpened?.invoke(bytes)
            }
            val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
                val expected = controller.opened?.session?.info?.address
                if (uri == null || expected == null) { controller.externalPicker = false; controller.clearExport() }
                else controller.onExportDestination { bytes, pw -> docs.exportAndVerify(uri, bytes, pw, expected) }
            }
            val platform = Platform(
                openDocument = { cb -> onOpened = cb; controller.externalPicker = true; open.launch(arrayOf("*/*")) },
                createDocument = { name -> create.launch(name) },
                copyAddress = { addr ->
                    // Public address only (never a secret: the phrase/keys have no copy action anywhere).
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Endereço Solana", addr))
                    // Android 13+ shows its own copy confirmation; a toast would duplicate it.
                    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, S.COPIED, Toast.LENGTH_SHORT).show()
                },
                openUrl = { url ->
                    // Only https explorer links built by the app itself; leaving the app locks it (onStop).
                    if (url.startsWith("https://explorer.solana.com/")) {
                        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }
                    }
                },
                paste = {
                    getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.take(128)
                },
                pasteSecret = { readAndClearClipboard(this, 400) },
                haptics = { controller.prefs.haptics },
            )
            App(controller, platform)
        }
    }

    override fun onStart() {
        super.onStart()
        controller.onStart()
    }

    override fun onStop() {
        super.onStop()
        controller.onStop()
    }

    /** BiometricPrompt bound to the Keystore cipher (CryptoObject): success unlocks exactly this operation. */
    private suspend fun authenticate(cipher: Cipher, title: String): Boolean = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(result.cryptoObject?.cipher != null)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (cont.isActive) cont.resume(false)
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title).setSubtitle(S.PROMPT_SUB).setNegativeButtonText(S.USE_PIN)
            .setAllowedAuthenticators(BIOMETRIC_STRONG).setConfirmationRequired(true).build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }

    /**
     * Launch (Android 12+ platform SplashScreen; theme sets background = system day/night colour and the padlock).
     * Cold start: the padlock is held until the first frame is ready AND at least [SPLASH_MIN_MS] have passed, so it
     * never flashes; then it lifts slightly and fades while the splash dissolves into the app. Purely visual; any
     * platform quirk just falls back to the default splash (never blocks the app).
     */
    private fun installLaunchSplash(coldStart: Boolean) {
        val splash = runCatching { splashScreen }.getOrNull() ?: return
        if (coldStart && android.os.Build.FINGERPRINT != "robolectric") {
            val start = android.os.SystemClock.uptimeMillis()
            val content = findViewById<android.view.View>(android.R.id.content)
            content.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val left = SPLASH_MIN_MS - (android.os.SystemClock.uptimeMillis() - start)
                    if (left > 0) { content.postDelayed({ content.invalidate() }, left); return false }
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            })
        }
        runCatching {
            splash.setOnExitAnimationListener { view ->
                val ease = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                runCatching { view.iconView?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.translationY(-view.height * 0.015f)?.alpha(0f)
                    ?.setDuration(360)?.setInterpolator(ease)?.start() }
                view.animate().alpha(0f).setStartDelay(60).setDuration(420).setInterpolator(ease)
                    .withEndAction { runCatching { view.remove() } }.start()
            }
        }
    }

    companion object {
        /** Minimum time the launch padlock stays up on a cold start (no 100 ms "flash"). */
        private const val SPLASH_MIN_MS = 450L
        /** Tests inject an offline service so no test ever touches the network. */
        @androidx.annotation.VisibleForTesting internal var priceOverride: PriceService? = null
        /** Tests: a factory returning a fake transport, or null for offline. */
        /** Tests: a fake Seed Vault gateway. */
        @androidx.annotation.VisibleForTesting internal var seedVaultOverride: com.lockwallet.custody.seedvault.SeedVaultGateway? = null
        @androidx.annotation.VisibleForTesting internal var rpcOverride: (() -> com.lockwallet.data.rpc.RpcTransport?)? = null
        /** Tests inject a deterministic MAC provider; production always uses Android Keystore. */
        @androidx.annotation.VisibleForTesting internal var txJournalAuthenticatorOverride: com.lockwallet.data.rpc.TxJournalAuthenticator? = null

        /** Largest accepted import (keypair JSON); LWRP files are ≤ 174 bytes and size-checked exactly later. */
        const val MAX_IMPORT_BYTES = 1024
    }
}

/**
 * Secret paste (recovery phrase / private key): read the clipboard once, then wipe it so other apps and the keyboard's
 * clipboard history cannot read the secret again.
 */
internal fun readAndClearClipboard(ctx: android.content.Context, max: Int): String? {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return null
    val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.take(max)
    if (t != null) runCatching { cm.clearPrimaryClip() }
    return t
}
