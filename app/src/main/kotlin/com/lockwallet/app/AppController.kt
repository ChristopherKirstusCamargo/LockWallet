package com.lockwallet.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lockwallet.app.ui.S
import com.lockwallet.custody.local.CustodyException
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.KekTicket
import com.lockwallet.custody.local.LocalSecureProvider
import com.lockwallet.custody.local.PinVerification
import com.lockwallet.custody.local.UnlockResult
import com.lockwallet.custody.local.UnlockedWallet
import com.lockwallet.custody.local.api.RecoveryException
import com.lockwallet.custody.local.api.CustodyProviderId
import com.lockwallet.custody.local.api.WalletInfo
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.material.DiscoveredAccount
import com.lockwallet.custody.local.material.MaterialException
import com.lockwallet.custody.local.material.PendingMaterial
import com.lockwallet.custody.local.material.QuizQuestion
import com.lockwallet.custody.local.storage.WalletRole
import com.lockwallet.custody.local.storage.WalletState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import com.lockwallet.data.price.Fiat
import com.lockwallet.data.price.PriceService
import com.lockwallet.data.price.PriceState

enum class FlowMode { CREATE, RESTORE }
enum class RestoreKind { PHRASE, LWRP, PRIVATE_KEY, KEYPAIR_JSON }

/** What an Authorize screen unlocks the vault for. */
sealed interface AuthPurpose {
    /** Verify the PIN of the existing vault, then seal the draft. */
    data object SealDraft : AuthPurpose
    data class RevealPhrase(val walletId: String) : AuthPurpose
    data class ExportLwrp(val walletId: String) : AuthPurpose
    data class AddAccount(val walletId: String) : AuthPurpose
    /** Sign the prepared transaction shown on the Review screen (exact bytes, bound by hash). */
    data class Sign(val walletId: String) : AuthPurpose
    /** Lock Connect: sign a dApp request (transaction(s), message(s) or SIWS) with a CONNECT wallet. */
    data class DappSign(val walletId: String) : AuthPurpose
    /** Turn on biometric unlock: needs the PIN right now (ADR 0024). */
    /** Delete a local wallet: PIN only, never biometrics (not in [biometricAllowed]). */
    data class DeleteWallet(val walletId: String) : AuthPurpose
    data object EnableBiometric : AuthPurpose
    /** Lengthen the max time without the PIN (shortening needs no PIN). PIN only — never biometrics. */
    data class SetBioWindow(val hours: Int) : AuthPurpose
}

/** Purposes biometrics may authorise instead of the PIN (ADR 0024). Phrase, export, new accounts and wallets: PIN only. */
fun AuthPurpose?.biometricAllowed(): Boolean = this == null || this is AuthPurpose.Sign || this is AuthPurpose.DappSign

enum class Tab { HOME, STAKE, CONNECT, SETTINGS }

sealed interface Screen {
    data object Home : Screen
    data object Receive : Screen
    data object Lock : Screen
    data object SecurityCenter : Screen
    data object Custody : Screen
    data object Role : Screen
    data object Length : Screen
    data object Warning : Screen
    data object ShowPhrase : Screen
    data object Quiz : Screen
    data object RestoreMethod : Screen
    data class RestoreInput(val kind: RestoreKind) : Screen
    data object Discovery : Screen
    data object Name : Screen
    data object PinCreate : Screen
    data object BioChoice : Screen
    data class Authorize(val purpose: AuthPurpose) : Screen
    data class Detail(val walletId: String) : Screen
    data class Reveal(val walletId: String) : Screen
    data class Export(val walletId: String) : Screen
    /** Send SOL ([tokenAccount] null) or the token held in [tokenAccount]. */
    data class Send(val tokenAccount: String? = null) : Screen
    data object Review : Screen
    data class TxStatus(val signature: String) : Screen
    data object Network : Screen
    data class Token(val account: String) : Screen
    data object StakeNew : Screen
    data class StakeAccount(val address: String) : Screen
    data object SeedVaultSetup : Screen
}

/** In-progress create/restore. Holds secret material only until sealed or discarded. */
class Draft(val mode: FlowMode) {
    var role: WalletRole = WalletRole.VAULT
    var words: Int = 12
    var material: PendingMaterial? = null
    var quiz: List<QuizQuestion> = emptyList()
    var account: DiscoveredAccount? = null
    var name: String = ""
    var newPin: CharArray? = null

    fun discard() {
        material?.close(); material = null
        quiz.forEach { it.wipe() }; quiz = emptyList()
        newPin?.fill('\u0000'); newPin = null
    }
}

/** Opened wallet handed to a screen (reveal/export). The screen closes it. */
class OpenedSession(val session: UnlockedWallet)

/**
 * Single-activity state machine. All custody calls run on Dispatchers.Default; the UI only sees public
 * data, display CharArrays and opaque tickets. Leaving the app (onStop) locks and discards every secret.
 */
class AppController(
    val provider: LocalSecureProvider,
    val capabilities: DeviceSecurityCapabilityManager,
    internal val scope: CoroutineScope,
    val prefs: Prefs,
    private val price: PriceService?,
    private val biometric: suspend (Cipher, String) -> Boolean,
    /** Null = offline (tests): every chain call reports "sem conexão"; nothing is invented. */
    transport: com.lockwallet.data.rpc.RpcTransport? = null,
    dataDir: java.io.File? = null,
    /** Seed Vault provider (Phase G). Null in tests that do not exercise it. */
    val seedVault: com.lockwallet.custody.seedvault.SeedVaultProvider? = null,
) {
    /** Starts an external activity (Seed Vault) and suspends until its result. Installed by MainActivity. */
    var activityRunner: (suspend (android.content.Intent) -> Pair<Int, android.content.Intent?>)? = null
    /** Requests a runtime permission at use time (Seed Vault access, camera). Installed by MainActivity. */
    var permissionRunner: (suspend (String) -> Boolean)? = null

    val chain = ChainModel(this, prefs, transport, dataDir)
    val connect = com.lockwallet.app.connect.ConnectModel(this, dataDir)

    var tab by mutableStateOf(Tab.HOME)
    var actionsOpen by mutableStateOf(false)
    /** Home: 0 = Tokens, 1 = Atividade. */
    var homeSegment by mutableStateOf(0)
    /** Open the QR scanner as soon as the Send screen appears (home scan shortcut). */
    var scanOnOpen = false
    /** True between onStart and onStop: price polling only runs while visible. */
    var foreground by mutableStateOf(true)
    var priceState by mutableStateOf<PriceState>(PriceState.Loading)
    var priceEnabled by mutableStateOf(prefs.priceEnabled)
    var fiat by mutableStateOf(prefs.fiat)

    /** One poll (off the main thread). Never fabricates: failures keep the last real quote, labelled by age. */
    suspend fun pollPrice() {
        val svc = price ?: run { priceState = PriceState.Unavailable; return }
        priceState = withContext(Dispatchers.IO) { svc.refresh() }
    }
    fun tickPrice() { price?.let { priceState = it.state() } }

    fun setDarkTheme(dark: Boolean) { prefs.dark = dark; com.lockwallet.app.ui.C.dark = dark }
    fun setPriceOn(on: Boolean) { prefs.priceEnabled = on; priceEnabled = on; if (!on) priceState = PriceState.Unavailable }
    fun setFiatPref(f: Fiat) { prefs.fiat = f; fiat = f }
    var hideBalance by mutableStateOf(prefs.hideBalance)
        private set
    fun setHideBalancePref(on: Boolean) { prefs.hideBalance = on; hideBalance = on }
    var haptics by mutableStateOf(prefs.haptics)
        private set
    fun setHapticsPref(on: Boolean) { prefs.haptics = on; haptics = on }
    var backedUp by mutableStateOf(prefs.backedUp)
        private set
    /** True when this wallet has no recovery outside this device yet (imported key without a verified LWRP export). */
    fun needsBackup(w: WalletInfo): Boolean =
        w.state == WalletState.ACTIVE && !w.isMnemonic && w.provider == CustodyProviderId.LOCAL_SECURE && w.id !in backedUp

    var screen by mutableStateOf<Screen>(Screen.Home)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var wallets by mutableStateOf<List<WalletInfo>>(emptyList())
    var unlocked by mutableStateOf(false)
    /** Set while the system document picker is in front, so onStop does not lock mid-export/import. */
    var externalPicker = false
    /** Whether Window.setHideOverlayWindows took effect (shown in the Security Center). */
    var overlaysHidden = true
    var draft: Draft? = null
    var opened: OpenedSession? = null
    /** Encrypted LWRP bytes awaiting a destination (not secret). Password kept only until verification. */
    var pendingExport: Pair<ByteArray, CharArray>? = null

    fun start() {
        refresh()
        screen = if (hasVault()) Screen.Lock else Screen.Home
    }

    fun hasVault(): Boolean = runCatching { provider.hasVault() }.getOrDefault(false)

    /** Last Seed Vault wallet list; refreshed off the main thread (its token check is a cross-process query). */
    private var svWallets: List<WalletInfo> = emptyList()

    /** True when a vault exists on this device (snapshot, refreshed with the wallet list). Drives the lock gate. */
    var vaultPresent by mutableStateOf(false)
        private set
    /** Locked = a vault exists and no session is open. While locked only the lock screen and the Security Center render. */
    val locked: Boolean get() = vaultPresent && !unlocked

    /** Biometric unlock state (refreshed with the wallet list; the custody gate is authoritative). */
    var bioEnabled by mutableStateOf(false)
        private set
    var bioReady by mutableStateOf(false)
        private set

    /** A BIOMETRIC_STRONG sensor with an enrolled biometric exists (the toggle is only offered then). */
    val bioCapable: Boolean get() = runCatching { capabilities.biometricAvailability() == com.lockwallet.custody.local.BiometricAvailability.AVAILABLE }.getOrDefault(false)

    /** Keystore lookups + HMAC check run off the main thread (no jank/ANR on slow keystores). */
    fun refreshBiometric() {
        scope.launch {
            val (on, ready) = io {
                val on = runCatching { provider.biometricEnabled() }.getOrDefault(false)
                Triple(on, on && runCatching { provider.biometricGate() == com.lockwallet.custody.local.bio.BioGate.READY }.getOrDefault(false),
                    if (on) provider.biometricWindowHours() else null)
            }.let { (a, b, w) -> Pair(a, b).also { bioWindowHours = w } }
            bioEnabled = on; bioReady = ready
        }
    }

    fun refresh() {
        vaultPresent = hasVault()
        refreshBiometric()
        val local = try { provider.wallets() } catch (e: CustodyException) { error = S.reason(e.reason); emptyList() }
        wallets = local + svWallets
        val sv = seedVault ?: return
        scope.launch {
            val r = try { io { sv.wallets() } } catch (e: CustodyException) { error = S.reason(e.reason); null } ?: return@launch
            svWallets = r
            wallets = wallets.filter { it.provider != CustodyProviderId.SEED_VAULT } + r
        }
    }

    /** Cached per controller: a package-manager lookup, not something to repeat on every recomposition. */
    val seedVaultAvailable: Boolean by lazy { runCatching { seedVault?.capabilities()?.available == true }.getOrDefault(false) }

    /** Runs Seed Vault's own activity. The app is not locked while it is in front (it authenticates the user itself). */
    internal suspend fun runExternal(intent: android.content.Intent): Pair<Int, android.content.Intent?> {
        val runner = activityRunner ?: throw CustodyException(CustodyException.Reason.SEED_VAULT_UNAVAILABLE)
        externalPicker = true
        try { return runner(intent) } finally { externalPicker = false }
    }

    /** Phase G: obtain a seed from Seed Vault (authorize existing / create / import) and register account 0. */
    fun addSeedVaultWallet(role: WalletRole, name: String, source: com.lockwallet.custody.seedvault.SeedSource) = launchOp {
        val sv = seedVault?.takeIf { it.gateway.available() } ?: throw CustodyException(CustodyException.Reason.SEED_VAULT_UNAVAILABLE)
        val t = name.trim()
        if (t.isEmpty() || t.length > 32) throw CustodyException(CustodyException.Reason.BAD_NAME)
        sv.gateway.permission()?.let { perm -> if (permissionRunner?.invoke(perm) != true) throw CustodyException(CustodyException.Reason.SEED_VAULT_DECLINED) }
        val (code, data) = runExternal(sv.gateway.seedIntent(source))
        val token = try { sv.gateway.parseSeedResult(source, code, data) }
            catch (_: com.lockwallet.custody.seedvault.SeedVaultFailure) { throw CustodyException(CustodyException.Reason.SEED_VAULT_DECLINED) }
        val path = sv.gateway.accountPath(0)
        val (c2, d2) = runExternal(sv.gateway.publicKeyIntent(token, path))
        val pk = try { sv.gateway.parsePublicKey(c2, d2).first }
            catch (_: com.lockwallet.custody.seedvault.SeedVaultFailure) { throw CustodyException(CustodyException.Reason.SEED_VAULT_DECLINED) }
        val info = io { sv.add(t, role, token, pk, path, wallets) }
        discardDraft(); refresh(); go(Screen.Detail(info.id))
    }

    fun removeSeedVaultWallet(id: String) = launchOp { io { seedVault?.remove(id) }; refresh(); go(Screen.Home) }

    /** CAMERA is requested only here, when the user taps "Ler QR" (never at install or start). */
    fun requestCamera(onGranted: () -> Unit) {
        scope.launch {
            val ok = permissionRunner?.invoke(android.Manifest.permission.CAMERA) == true
            if (ok) onGranted() else error = S.CAMERA_DENIED
        }
    }

    fun go(s: Screen) { error = null; if (s is Screen.Authorize || s is Screen.Lock) bioAutoPrompted = false; screen = s }

    fun home() { discardDraft(); closeOpened(); actionsOpen = false; go(Screen.Home); refresh() }

    fun lockNow() {
        discardDraft(); closeOpened(); clearExport(); chain.onLock(); connect.onLock()
        unlocked = false
        bioAutoPrompted = false
        refresh()
        screen = if (hasVault()) Screen.Lock else Screen.Home
    }

    fun onStop() { foreground = false; actionsOpen = false; if (!externalPicker) lockNow() }
    fun onStart() { foreground = true }

    private fun discardDraft() { draft?.discard(); draft = null }
    private fun closeOpened() { opened?.session?.close(); opened = null }
    fun clearExport() { pendingExport?.second?.fill('\u0000'); pendingExport = null }

    internal fun launchOp(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (e: CustodyException) { error = S.reason(e.reason) }
            catch (e: MaterialException) { error = S.material(e.error, e.wordPosition) }
            catch (e: RecoveryException) { error = S.recovery(e.reason) }
            catch (e: com.lockwallet.data.rpc.RpcException) { error = S.rpc(e) }
            catch (e: ChainModel.UserError) { error = e.message }
            catch (e: com.lockwallet.core.solana.MalformedException) { error = "Dados inválidos recebidos da rede. Nada foi assinado." }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            // Last line of defence: an unexpected answer (hostile/garbled RPC, platform quirk) must never crash the
            // wallet mid-flow. Nothing is signed on this path; the message carries no secret.
            catch (e: Exception) { error = S.UNEXPECTED }
            finally { busy = false }
        }
    }

    internal suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    // ---------------------------------------------------------------- create / restore

    fun startFlow(mode: FlowMode) { discardDraft(); draft = Draft(mode); go(Screen.Custody) }

    fun generatePhrase() = launchOp {
        val d = draft ?: return@launchOp
        d.material?.close()
        d.material = io { PendingMaterial.generate(d.words) }
        go(Screen.ShowPhrase)
    }

    fun beginQuiz() = launchOp {
        val d = draft ?: return@launchOp
        d.quiz.forEach { it.wipe() }
        d.quiz = io { d.material!!.quiz() }
        go(Screen.Quiz)
    }

    fun quizPassed() {
        val d = draft ?: return
        d.account = d.material!!.discover(1).first()
        d.quiz.forEach { it.wipe() }; d.quiz = emptyList()
        go(Screen.Name)
    }

    /** Takes ownership of [input] and wipes it. */
    fun submitRestore(kind: RestoreKind, input: CharArray) = launchOp {
        val d = draft ?: return@launchOp
        try {
            d.material?.close()
            d.material = io {
                when (kind) {
                    RestoreKind.PHRASE -> PendingMaterial.fromPhrase(input)
                    RestoreKind.PRIVATE_KEY -> PendingMaterial.fromBase58PrivateKey(input)
                    RestoreKind.KEYPAIR_JSON -> PendingMaterial.fromKeypairJson(input)
                    RestoreKind.LWRP -> throw IllegalStateException("LWRP uses submitLwrp")
                }
            }
            go(Screen.Discovery)
        } finally {
            input.fill('\u0000')
        }
    }

    fun submitLwrp(file: ByteArray, password: CharArray) = launchOp {
        val d = draft ?: return@launchOp
        try {
            d.material?.close()
            val m = io { provider.openRecoveryPackage(file, password) }
            d.material = m
            d.account = m.suggested
            go(Screen.Name)
        } finally {
            password.fill('\u0000')
        }
    }

    fun chooseAccount(a: DiscoveredAccount) { draft?.account = a; go(Screen.Name) }

    fun submitName(name: String) {
        val d = draft ?: return
        val t = name.trim()
        if (t.isEmpty() || t.length > 32) { error = S.reason(CustodyException.Reason.BAD_NAME); return }
        d.name = t
        go(if (hasVault()) Screen.Authorize(AuthPurpose.SealDraft) else Screen.PinCreate)
    }

    fun setNewPin(pin: CharArray) { draft?.newPin?.fill('\u0000'); draft?.newPin = pin; go(Screen.BioChoice) }

    /** First vault: create KEK with [mode], biometric if required, seal. */
    fun sealNewVault(mode: AuthMode) = launchOp {
        val d = draft ?: return@launchOp
        val ticket = io { provider.beginNewVault(mode) }
        if (!passBiometric(ticket, S.PROMPT_SEAL)) { io { provider.cancel(ticket) }; return@launchOp }
        val info = try {
            io { provider.seal(ticket, d.material!!, d.account!!, d.name, d.role, newPin = d.newPin, enableBiometric = mode == AuthMode.PIN_AND_BIOMETRIC) }
        } catch (e: CustodyException) { io { provider.cancel(ticket) }; throw e }
        finished(info)
    }

    private suspend fun sealWithVerification(v: PinVerification) {
        val d = draft ?: return
        val ticket = io { provider.beginSealExisting() }
        if (!passBiometric(ticket, S.PROMPT_SEAL)) { v.close(); return }
        val info = io { provider.seal(ticket, d.material!!, d.account!!, d.name, d.role, verification = v) }
        finished(info)
    }

    private fun finished(info: WalletInfo) {
        discardDraft(); unlocked = true; refresh(); go(Screen.Detail(info.id))
    }

    private suspend fun passBiometric(ticket: KekTicket, title: String): Boolean {
        if (!ticket.needsBiometric) return true
        val ok = biometric(ticket.cipher!!, title)
        if (!ok) error = S.BIO_CANCELLED
        return ok
    }

    // ---------------------------------------------------------------- unlock / authorize

    var lockMessage by mutableStateOf<String?>(null)
    var lockoutMs by mutableStateOf(0L)

    fun refreshLockout() { lockoutMs = runCatching { provider.attempts.lockoutRemainingMs() }.getOrDefault(0L) }

    /** PIN typed on the lock or authorize screen. Takes ownership of [pin]. */
    fun submitPin(pin: CharArray, purpose: AuthPurpose?) = launchOp {
        try {
            val walletId = when (purpose) {
                is AuthPurpose.RevealPhrase -> purpose.walletId
                is AuthPurpose.ExportLwrp -> purpose.walletId
                is AuthPurpose.DeleteWallet -> purpose.walletId
                is AuthPurpose.AddAccount -> purpose.walletId
                is AuthPurpose.Sign -> purpose.walletId
                is AuthPurpose.DappSign -> purpose.walletId
                else -> null
            }
            val ticket = io { if (walletId != null) provider.beginUnlock(walletId) else provider.beginUnlockAny() }
            if (!passBiometric(ticket, S.PROMPT_UNLOCK)) return@launchOp
            when (val r = io { provider.completeUnlock(ticket, pin) }) {
                is UnlockResult.Failed -> { lockMessage = S.wrongPin(r.attemptsBeforeLockout); refreshLockout() }
                is UnlockResult.LockedOut -> { lockoutMs = r.remainingMs; lockMessage = null }
                is UnlockResult.Success -> { lockMessage = null; lockoutMs = 0; onUnlocked(r.session, purpose) }
            }
        } catch (e: CustodyException) {
            refresh(); throw e
        } finally {
            pin.fill('\u0000')
        }
    }

    /**
     * Biometric instead of the PIN (lock screen, signing). Custody decides whether it is allowed right now; any refusal
     * simply leaves the PIN pad (never counted as a PIN attempt, never wipes anything).
     */
    fun submitBiometric(purpose: AuthPurpose?) {
        if (!purpose.biometricAllowed() || !bioReady) return
        launchOp {
            val walletId = when (purpose) { is AuthPurpose.Sign -> purpose.walletId; is AuthPurpose.DappSign -> purpose.walletId; else -> null }
            try {
                val t = io { provider.beginBiometricUnlock(walletId) }
                if (!biometric(t.cipher, if (purpose == null) S.PROMPT_UNLOCK else S.PROMPT_SIGN)) return@launchOp
                val session = io { provider.completeBiometricUnlock(t) }
                lockMessage = null; onUnlocked(session, purpose)
            } catch (e: CustodyException) {
                refreshBiometric()
                when (e.reason) {
                    CustodyException.Reason.BIO_INVALIDATED -> lockMessage = S.BIO_INVALIDATED
                    CustodyException.Reason.BIO_PIN_REQUIRED, CustodyException.Reason.BIO_NOT_ENROLLED, CustodyException.Reason.AUTH_REQUIRED -> {}
                    else -> throw e
                }
            }
        }
    }

    /** Auto prompt only once per lock/authorize screen: cancelling it must leave the PIN pad, not re-prompt in a loop. */
    private var bioAutoPrompted = false
    fun autoBiometric(purpose: AuthPurpose?) { if (bioAutoPrompted) return; bioAutoPrompted = true; submitBiometric(purpose) }

    /** Current max hours without the PIN (null = biometrics off). Read off the main thread in [refreshBiometric]. */
    var bioWindowHours by mutableStateOf<Int?>(null)
        private set

    /** Shorter window: applied now. Longer: asks for the PIN first (a coerced finger cannot extend its own window). */
    fun setBioWindow(hours: Int) {
        val cur = bioWindowHours ?: return
        if (hours == cur) return
        if (hours < cur) launchOp { io { provider.setBiometricWindow(hours, null) }; refreshBiometric() }
        else go(Screen.Authorize(AuthPurpose.SetBioWindow(hours)))
    }

    /** "Bloquear agora": locks the app AND refuses biometrics until the next correct PIN (coercion/panic). */
    fun lockdown() {
        scope.launch { io { provider.biometricLockdown() }; refreshBiometric() }
        lockNow()
    }

    /** Settings toggle: on ⇒ PIN screen (EnableBiometric); off ⇒ immediate. */
    fun setBiometricUnlock(on: Boolean) {
        if (on) go(Screen.Authorize(AuthPurpose.EnableBiometric))
        else launchOp { io { provider.disableBiometricUnlock() }; refreshBiometric() }
    }

    private suspend fun onUnlocked(s: UnlockedWallet, purpose: AuthPurpose?) {
        unlocked = true
        when (purpose) {
            is AuthPurpose.SetBioWindow -> {
                try { io { provider.setBiometricWindow(purpose.hours, s) } } finally { s.close() }
                refresh(); tab = Tab.SETTINGS; go(Screen.Home)
            }
            AuthPurpose.EnableBiometric -> {
                try { io { provider.enableBiometricUnlock(s) } } finally { s.close() }
                refresh(); tab = Tab.SETTINGS; go(Screen.Home)
            }
            null -> { s.close(); refresh(); go(Screen.Home) }
            AuthPurpose.SealDraft -> { val v = s.pinVerification(); s.close(); sealWithVerification(v) }
            is AuthPurpose.RevealPhrase -> { closeOpened(); opened = OpenedSession(s); go(Screen.Reveal(purpose.walletId)) }
            is AuthPurpose.ExportLwrp -> { closeOpened(); opened = OpenedSession(s); go(Screen.Export(purpose.walletId)) }
            is AuthPurpose.DeleteWallet -> {
                try { io { provider.removeWallet(s) } } finally { s.close() }
                closeOpened(); refresh(); go(Screen.Home)
            }
            is AuthPurpose.Sign -> chain.signAndSubmit(s)
            is AuthPurpose.DappSign -> connect.onUnlocked(s)
            is AuthPurpose.AddAccount -> {
                val src = s.info
                val v = s.pinVerification()
                val pending = s.toPending()
                s.close()
                val used = wallets.filter { it.seedGroup == src.seedGroup && it.profile == src.profile && it.state == WalletState.ACTIVE }.map { it.accountIndex }
                val next = (used.maxOrNull() ?: -1) + 1
                val d = Draft(FlowMode.CREATE).apply {
                    role = src.role; material = pending; account = io { pending.account(src.profile, next) }
                    name = (src.name.take(24) + " #" + (next + 1))
                }
                discardDraft(); draft = d
                sealWithVerification(v)
            }
        }
    }

    // ---------------------------------------------------------------- export

    /** Seals the opened wallet into an LWRP with [password] (ownership taken), then asks for a destination. */
    fun prepareExport(password: CharArray, launchPicker: (String) -> Unit) = launchOp {
        val s = opened?.session ?: return@launchOp
        val bytes = try { io { s.exportLwrp(password) } } catch (e: RecoveryException) { password.fill('\u0000'); throw e }
        clearExport()
        pendingExport = bytes to password
        val fileName = "lock-wallet-" + s.info.name.filter { it.isLetterOrDigit() }.take(20).ifEmpty { "carteira" } + ".lwrp"
        externalPicker = true
        launchPicker(fileName)
    }

    var exportResult by mutableStateOf<String?>(null)

    fun onExportDestination(write: (ByteArray, CharArray) -> com.lockwallet.custody.local.ExportState) = launchOp {
        externalPicker = false
        val (bytes, pw) = pendingExport ?: return@launchOp
        val st = io { write(bytes, pw) }
        if (st == com.lockwallet.custody.local.ExportState.VERIFIED) opened?.session?.info?.id?.let { id -> backedUp = backedUp + id; prefs.backedUp = backedUp }
        exportResult = when (st) {
            com.lockwallet.custody.local.ExportState.VERIFIED -> S.EXPORT_VERIFIED
            com.lockwallet.custody.local.ExportState.FAILED -> S.EXPORT_FAILED
            else -> S.EXPORT_UNVERIFIED
        }
        clearExport()
    }

    fun rename(id: String, name: String) = launchOp {
        val w = wallets.firstOrNull { it.id == id }
        io { if (w?.provider == com.lockwallet.custody.local.api.CustodyProviderId.SEED_VAULT) seedVault?.rename(id, name) else provider.rename(id, name) }
        refresh()
    }
    fun removeQuarantined(id: String) = launchOp { io { provider.removeQuarantined(id) }; refresh(); go(Screen.Home) }
}
