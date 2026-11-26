package com.lockwallet.app

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var pendingPermission: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /**
     * Touches that pass through a window partly covered by another one (overlay, accessibility overlay) are dropped
     * on screens that authorize or sign. filterTouchesWhenObscured only covers fully obscured touches.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.flags and android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0 && ::controller.isInitialized &&
            sensitiveScreen(controller.screen)) {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) controller.error = S.OBSCURED_TOUCH
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!screenCaptureAllowed(BuildConfig.SCREENSHOT_CAPTURE_ALLOWED, BuildConfig.DEBUG, BuildConfig.APPLICATION_ID)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Normal permission, granted at install; if a ROM refuses it we keep running and say so in the Security Center.
        val overlaysHidden = try { window.setHideOverlayWindows(true); true } catch (_: SecurityException) { false }
        super.onCreate(savedInstanceState)
        window.decorView.filterTouchesWhenObscured = true
        // Android 14+: only services declared as accessibility tools (screen readers, switch access) can read or act on
        // the wallet's views; other accessibility services see nothing and cannot inject taps.
        if (android.os.Build.VERSION.SDK_INT >= 34) window.decorView.setAccessibilityDataSensitive(android.view.View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        installLaunchSplash(coldStart = savedInstanceState == null)

        val provider = LocalCustody.create(this)
        // Local data of features that are no longer part of the app is removed, never read.
        for (retired in listOf("connect", "seedvault")) java.io.File(noBackupFilesDir, retired).takeIf { it.exists() }?.deleteRecursively()
        val docs = RecoveryDocumentIo(contentResolver, provider)
        val prefs = Prefs(this)
        com.lockwallet.app.ui.C.dark = prefs.dark
        com.lockwallet.app.ui.Motion.enabled = prefs.animations
        val price = priceOverride ?: PriceService.default { android.os.SystemClock.elapsedRealtime() }
        val rpc = rpcOverride.let { f -> if (f != null) f() else com.lockwallet.data.rpc.HttpsRpcTransport() }
        val caps = DeviceSecurityCapabilityManager(this, provider)
        controller = AppController(provider, caps, lifecycleScope, prefs, price, ::authenticate,
            transport = rpc, dataDir = noBackupFilesDir)
        caps.clusterLabel = { controller.chain.config.cluster.label }
        controller.overlaysHidden = overlaysHidden
        controller.start()

        setContent {
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                pendingPermission?.complete(ok); pendingPermission = null
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
            val onProfilePhotoPicked = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf<((ByteArray?) -> Unit)?>(null)
            }
            val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
                val callback = onProfilePhotoPicked.value
                onProfilePhotoPicked.value = null
                if (uri == null) {
                    callback?.invoke(null)
                } else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val bytes = readProfilePhotoBounded(uri)
                        withContext(Dispatchers.Main) {
                            callback?.invoke(bytes)
                        }
                    }
                }
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
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("Endereço Solana", addr))
                    // Clears our own copy after 2 minutes (only if the clipboard still holds exactly it), so a stale
                    // address does not linger for a later paste or for clipboard-reading apps.
                    window.decorView.postDelayed({
                        runCatching {
                            val now = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                            if (now == addr) cm.clearPrimaryClip()
                        }
                    }, 120_000L)
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
                pickProfilePhoto = { walletId, callback ->
                    // The picker may background the app; return to the profile only after fresh biometric or PIN authentication.
                    controller.profilePhotoReturnWalletId = walletId
                    onProfilePhotoPicked.value = { bytes -> callback(bytes) }
                    try {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: Exception) {
                        onProfilePhotoPicked.value = null
                        controller.profilePhotoReturnWalletId = null
                        callback(null)
                    }
                },
            )
            App(controller, platform)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (::controller.isInitialized) controller.touch()
    }

    override fun onStart() {
        super.onStart()
        controller.onStart()
    }

    override fun onPause() {
        super.onPause()
        if (::controller.isInitialized) controller.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (::controller.isInitialized) controller.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) controller.onResume()
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
            .setTitle(title).setSubtitle(S.PROMPT_SUB)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText(S.USE_PIN)
            .setConfirmationRequired(true)
            .build()
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
        /** Inspection package; the only one where screen capture may ever be allowed. */
        const val INSPECTION_APPLICATION_ID = "com.lockwallet.app.test"
        /**
         * Screen capture policy. Allowed only when all three hold: the inspection flag compiled in, a debuggable build
         * and the separate inspection package. Release builds compile the flag as false, so FLAG_SECURE is always set.
         */
        fun screenCaptureAllowed(inspectionFlag: Boolean, debugBuild: Boolean, applicationId: String): Boolean =
            inspectionFlag && debugBuild && applicationId == INSPECTION_APPLICATION_ID

        /** Minimum time the launch padlock stays up on a cold start (no 100 ms "flash"). */
        private const val SPLASH_MIN_MS = 450L
        /** Tests inject an offline service so no test ever touches the network. */
        @androidx.annotation.VisibleForTesting internal var priceOverride: PriceService? = null
        /** Tests: a factory returning a fake transport, or null for offline. */
        @androidx.annotation.VisibleForTesting internal var rpcOverride: (() -> com.lockwallet.data.rpc.RpcTransport?)? = null

        /** Largest accepted import (keypair JSON); LWRP files are ≤ 174 bytes and size-checked exactly later. */
        const val MAX_IMPORT_BYTES = 1024
    }
}

/** Bounded read from the system Photo Picker; no broad storage permission or persisted URI grant is used. */
private const val MAX_PROFILE_PHOTO_INPUT_BYTES = 10 * 1024 * 1024

private fun MainActivity.readProfilePhotoBounded(uri: Uri): ByteArray? = try {
    contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            val remaining = MAX_PROFILE_PHOTO_INPUT_BYTES + 1 - out.size()
            if (remaining <= 0) break
            out.write(buffer, 0, minOf(n, remaining))
            if (out.size() > MAX_PROFILE_PHOTO_INPUT_BYTES) break
        }
        out.toByteArray()
    }
} catch (_: Exception) { null }

/**
 * Secret paste (recovery phrase / private key): read the clipboard once, then wipe it so other apps and the keyboard's
 * clipboard history cannot read the secret again.
 */
/** Screens where a tap authorizes, signs or reveals: touches through partial overlays are refused there. */
internal fun sensitiveScreen(s: Screen): Boolean =
    s is Screen.Authorize || s == Screen.Lock || s == Screen.Review || s == Screen.Privacy || s is Screen.Reveal || s is Screen.Export

internal fun readAndClearClipboard(ctx: android.content.Context, max: Int): String? {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return null
    val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.take(max)
    if (t != null) runCatching { cm.clearPrimaryClip() }
    return t
}
