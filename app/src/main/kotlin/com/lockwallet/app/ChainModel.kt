package com.lockwallet.app

import java.util.concurrent.atomic.AtomicLong

import com.lockwallet.app.ui.Fmt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lockwallet.app.ui.S
import com.lockwallet.app.ui.TxText
import com.lockwallet.core.solana.Amounts
import com.lockwallet.core.solana.Ed25519Curve
import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.ix.SystemIx
import com.lockwallet.custody.local.UnlockedWallet
import com.lockwallet.custody.local.api.CustodyProviderId
import com.lockwallet.custody.local.storage.WalletRole
import com.lockwallet.custody.local.api.WalletInfo
import com.lockwallet.custody.local.storage.WalletState
import com.lockwallet.data.rpc.ChainReader
import com.lockwallet.data.rpc.Cluster
import com.lockwallet.data.rpc.EndpointCheck
import com.lockwallet.data.rpc.NetworkConfig
import com.lockwallet.data.rpc.NetworkManager
import com.lockwallet.data.rpc.PreparedTx
import com.lockwallet.data.rpc.PreparedPrivacyTx
import com.lockwallet.data.rpc.RpcClient
import com.lockwallet.data.rpc.RpcException
import com.lockwallet.data.rpc.RpcTransport
import com.lockwallet.data.rpc.TransactionDetail
import com.lockwallet.data.rpc.TxCoordinator
import com.lockwallet.data.rpc.withAutoBudget
import com.lockwallet.data.rpc.TxIntent
import com.lockwallet.data.rpc.TxJournal
import com.lockwallet.data.rpc.TxKind
import com.lockwallet.data.rpc.TxRecord
import com.lockwallet.data.rpc.TokenHolding
import com.lockwallet.data.rpc.StakeView
import com.lockwallet.data.rpc.EpochInfo
import com.lockwallet.data.rpc.ValidatorInfo
import com.lockwallet.core.solana.ix.StakeIx
import com.lockwallet.core.solana.state.StakeLifecycle
import com.lockwallet.core.solana.Pda
import com.lockwallet.core.solana.ix.AtaIx
import com.lockwallet.core.solana.ix.TokenIx
import com.lockwallet.core.solana.analyzer.AnalysisContext
import com.lockwallet.core.solana.state.TokenState
import com.lockwallet.core.solana.MalformedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject

/** Something loaded from the chain: never a placeholder value (spec: nothing invented). */
sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T, val atMs: Long) : Loadable<T>
    data class Failed<T>(val message: String, val last: T?) : Loadable<T>
}
fun <T> Loadable<T>.valueOrNull(): T? = when (this) { is Loadable.Ready -> value; is Loadable.Failed -> last; else -> null }

/** Extra review context computed by the app (address poisoning, new account, residual balance…). */
data class ReviewInfo(val title: String, val lines: List<Pair<String, String>>, val warnings: List<String>, val ackText: String?)

data class PrivacyReviewInfo(val operation: String, val amountLamports: Long, val counterparty: String, val visibility: String, val lastValidBlockHeight: Long, val typedCounterparty: String? = null)

data class Activity(val signature: String, val title: String, val amount: String?, val status: String, val timeMs: Long?, val url: String?, val failed: Boolean, val local: TxRecord?)

/**
 * Chain-facing state of the app (Phase D/E/F): network, balances, activity, prepared transaction and records.
 * All network work runs on Dispatchers.IO; Compose state is assigned on the caller (main) side.
 */
/** How long a validator snapshot stays fresh on screen before it is re-read. */
const val VALIDATORS_TTL_MS = 60_000L

class ChainModel(private val c: AppController, private val prefs: Prefs, transport: RpcTransport?, dataDir: File?) {
    class UserError(message: String) : Exception(message)

    private val offline = RpcTransport { _, _, _ -> throw RpcException(RpcException.Kind.TRANSPORT, "offline") }
    val net = NetworkManager(prefs.network, transport ?: offline)
    private val journal = TxJournal(
        File(dataDir ?: File(System.getProperty("java.io.tmpdir"), "lw-${System.nanoTime()}"), "tx-journal.json"),
        mac = { payload -> c.provider.transactionJournalMac(payload) },
    )
    val coordinator = TxCoordinator(net, journal)
    val reader = ChainReader(net)

    var config by mutableStateOf(net.config)
    var balance by mutableStateOf<Loadable<Long>>(Loadable.Idle)
    var activity by mutableStateOf<List<Activity>>(emptyList())
    var prepared by mutableStateOf<PreparedTx?>(null)
    var review by mutableStateOf<ReviewInfo?>(null)
    var privacyAddress by mutableStateOf<String?>(null)
        private set
    var privacyBalanceLamports by mutableStateOf<Long?>(null)
        private set
    var privacyUtxoCount by mutableStateOf<Int?>(null)
        private set
    var privacyPrepared by mutableStateOf<PreparedPrivacyTx?>(null)
        private set
    var privacyReview by mutableStateOf<PrivacyReviewInfo?>(null)
        private set
    var privacyNotice by mutableStateOf<String?>(null)
        private set
    private var privacySession: UnlockedWallet? = null
    /** Invalidates an in-flight Default Ring open whenever the app locks or the screen is left. */
    private val privacyLifecycleEpoch = AtomicLong(0L)
    var notice by mutableStateOf<String?>(null)
    var tokens by mutableStateOf<Loadable<List<TokenHolding>>>(Loadable.Idle)
    var hidden by mutableStateOf(prefs.hiddenTokens)
    var stakes by mutableStateOf<Loadable<Pair<List<StakeView>, EpochInfo>>>(Loadable.Idle)
    var validators by mutableStateOf<Loadable<List<ValidatorInfo>>>(Loadable.Idle)
    val records = mutableStateMapOf<String, TxRecord>()
    /** mint → symbol, from verified on-chain metadata only (Phase E). */
    val tokenSymbols = mutableStateMapOf<String, String>()
    val tokenLabel: (SolanaPublicKey) -> String = { m -> tokenSymbols[m.toBase58()] ?: (m.toBase58().take(4) + "…") }
    private var retry: (suspend () -> Unit)? = null
    private val details = HashMap<String, TransactionDetail?>()

    suspend fun <T> net(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    fun wallet(): WalletInfo? {
        val active = c.wallets.filter { it.state == WalletState.ACTIVE }
        return active.firstOrNull { it.id == prefs.selectedWallet } ?: active.firstOrNull()
    }
    /**
     * Bumped whenever the wallet or network changes. A refresh that started before the change must not write its
     * (other wallet's / other network's) answer into the new state — found in the 5.0 race review.
     */
    @Volatile var generation = 0
        private set

    fun select(id: String) { leavePrivacy(); generation++; prefs.selectedWallet = id; balance = Loadable.Idle; activity = emptyList(); tokens = Loadable.Idle; stakes = Loadable.Idle; validators = Loadable.Idle; details.clear(); onSelect() }
    /** Hook for token/stake state reset (Phase E/F). */
    internal var onSelect: () -> Unit = {}

    fun onLock() {
        prepared?.let { coordinator.cancel(it) }
        leavePrivacy()
        prepared = null; review = null; retry = null
    }

    private fun closePrivacySession() {
        privacySession?.close()
        privacySession = null
    }

    /** Clears all private state from Compose and closes the Rust key session. */
    fun leavePrivacy() {
        // Invalidate any openPrivacy() coroutine before releasing the candidate session.
        privacyLifecycleEpoch.incrementAndGet()
        privacyPrepared?.let { coordinator.cancelPrivacy(it) }
        closePrivacySession()
        privacyAddress = null
        privacyBalanceLamports = null
        privacyUtxoCount = null
        privacyPrepared = null
        privacyReview = null
        privacyNotice = null
    }

    fun enterPrivacy() {
        val w = wallet()
        if (w == null) { c.error = S.NO_WALLET; return }
        if (config.cluster != Cluster.MAINNET) {
            c.error = "Default Ring está habilitado somente na Mainnet Solana."
            return
        }
        if (w.provider != CustodyProviderId.LOCAL_SECURE || w.role == WalletRole.CONNECT) {
            c.error = "Saldo confidencial está disponível apenas para carteiras Local Secure."
            return
        }
        // Fail-closed before asking for the PIN: no dedicated Helius endpoint, no Default Ring.
        if (prefs.sprEndpoint == null) { c.error = S.SPR_NOT_SET; return }
        c.go(Screen.Authorize(AuthPurpose.PrivacyOpen(w.id)))
    }

    /** PIN-unlocked Local Secure session used only while the privacy screen is active. */
    internal suspend fun openPrivacy(session: UnlockedWallet) {
        val w = wallet()
        if (w == null || w.id != session.info.id || w.provider != CustodyProviderId.LOCAL_SECURE ||
            session.info.role == WalletRole.CONNECT
        ) {
            session.close()
            throw UserError("Selecione uma carteira Local Secure para usar o Default Ring.")
        }
        leavePrivacy()
        val openingEpoch = privacyLifecycleEpoch.get()
        // Register before the first suspension so onLock()/leavePrivacy() can close this candidate too.
        privacySession = session
        try {
            if (config.cluster != Cluster.MAINNET) throw UserError("Default Ring está habilitado somente na Mainnet Solana.")
            // Dedicated SPR endpoint (Helius): Mainnet-verified and fail-closed before any SDK RPC/indexer call.
            val spr = sprUrl()
            val endpoint = net { net.check(spr, Cluster.MAINNET) }
            requirePrivacyOpeningCurrent(session, openingEpoch)
            when (val r = endpoint) {
                EndpointCheck.Ok -> {}
                EndpointCheck.BadUrl -> throw UserError(S.SPR_BAD_URL)
                EndpointCheck.Unreachable -> throw UserError(S.SPR_UNREACHABLE)
                is EndpointCheck.OtherNetwork -> throw UserError(S.urlOther(r.known))
            }
            val address = net { session.privacyShieldedAddress() }
            requirePrivacyOpeningCurrent(session, openingEpoch)
            val scanJson = net { session.privacyScanSol(spr) }
            requirePrivacyOpeningCurrent(session, openingEpoch)
            val scanned = JSONObject(scanJson)
            val amount = scanned.getLong("amountLamports")
            val count = scanned.getInt("utxoCount")
            if (amount < 0L || count < 0) throw UserError("Resposta inválida do saldo confidencial.")
            privacyAddress = address
            privacyBalanceLamports = amount
            privacyUtxoCount = count
            privacyNotice = null
            c.go(Screen.Privacy)
        } catch (e: Exception) {
            // A lifecycle path may already have closed and cleared this session. Do not close a newer session.
            if (privacySession === session) {
                privacySession = null
                session.close()
            }
            val safe = e.message?.takeIf { it.isNotBlank() } ?: "Não foi possível sincronizar o Default Ring."
            throw UserError(safe)
        }
    }

    private fun requirePrivacyOpeningCurrent(session: UnlockedWallet, epoch: Long) {
        if (privacyLifecycleEpoch.get() != epoch || privacySession !== session) {
            throw UserError("A sessão confidencial foi encerrada.")
        }
    }

    fun refreshPrivacySol() = c.launchOp {
        val session = privacySession ?: throw UserError("A sessão confidencial foi encerrada.")
        val scanned = JSONObject(net { session.privacyScanSol(sprUrl()) })
        val amount = scanned.getLong("amountLamports")
        val count = scanned.getInt("utxoCount")
        if (amount < 0L || count < 0) throw UserError("Resposta inválida do saldo confidencial.")
        privacyBalanceLamports = amount
        privacyUtxoCount = count
        privacyNotice = null
    }

    private fun discardPendingPrivacy(session: UnlockedWallet) {
        privacyPrepared?.let { coordinator.cancelPrivacy(it) }
        privacyPrepared = null
        privacyReview = null
        session.privacyCancelPrepared()
    }

    fun preparePrivacyDeposit(amountText: String) = c.launchOp {
        val amount = UserAmount.sol(amountText)
        val session = privacySession ?: throw UserError("A sessão confidencial foi encerrada.")
        discardPendingPrivacy(session)
        preparePrivacyOperation(
            session.privacyPrepareDepositSol(sprUrl(), amount),
            amount,
        )
    }

    fun preparePrivacyTransfer(recipient: String, amountText: String) = c.launchOp {
        val amount = UserAmount.sol(amountText)
        val session = privacySession ?: throw UserError("A sessão confidencial foi encerrada.")
        val to = recipient.trim()
        if (to.isEmpty()) throw UserError(S.BAD_ADDRESS)
        discardPendingPrivacy(session)
        preparePrivacyOperation(
            session.privacyPrepareTransferSol(sprUrl(), to, amount),
            amount,
        )
    }

    fun preparePrivacyWithdraw(recipient: String, amountText: String) = c.launchOp {
        val amount = UserAmount.sol(amountText)
        val session = privacySession ?: throw UserError("A sessão confidencial foi encerrada.")
        val to = SolanaPublicKey.fromBase58OrNull(recipient.trim()) ?: throw UserError(S.BAD_ADDRESS)
        if (to == wallet()?.address) throw UserError(S.SELF_SEND)
        discardPendingPrivacy(session)
        preparePrivacyOperation(
            session.privacyPrepareWithdrawSol(sprUrl(), to.toBase58(), amount),
            amount,
        )
    }

    private suspend fun preparePrivacyOperation(message: ByteArray, amountLamports: Long) {
        val session = privacySession ?: run { message.fill(0); throw UserError("A sessão confidencial foi encerrada.") }
        try {
            val parsed = JSONObject(net { session.privacyPreparedReview() })
            val operation = parsed.getString("operation")
            val amount = parsed.getLong("amountLamports")
            val counterparty = parsed.optString("counterparty").takeIf { it.isNotBlank() }
            // The destination exactly as the user typed it (shielded address), so the review can show it verbatim.
            val typedCounterparty = parsed.optString("counterpartyTyped").takeIf { it.isNotBlank() }
            val visibility = parsed.getString("visibility")
            val lastValid = parsed.getLong("lastValidBlockHeight")
            if (amount != amountLamports || amount <= 0L) throw UserError("O valor preparado não corresponde ao pedido.")
            val w = wallet() ?: throw UserError(S.NO_WALLET)
            if (w.id != session.info.id) throw UserError("A carteira ativa mudou durante a preparação.")
            val title = when (operation) {
                "deposit_sol" -> "Depósito público de SOL"
                "transfer_sol" -> "Transferência confidencial de SOL"
                "withdraw_sol" -> "Retirada pública de SOL"
                else -> throw UserError("Operação Default Ring não reconhecida.")
            }
            val prepared = net {
                coordinator.preparePrivacyV1(
                    w.address, message, operation, title, counterparty, amount, lastValid,
                )
            }
            privacyPrepared = prepared
            privacyReview = PrivacyReviewInfo(operation, amount, counterparty ?: "", visibility, lastValid, typedCounterparty)
            privacyNotice = null
        } catch (e: Exception) {
            // The native side retains the exact reviewed message until it is
            // signed or explicitly cancelled. Any failed review path discards it.
            runCatching { session.privacyCancelPrepared() }
            throw e
        } finally {
            // The coordinator owns its defensive copy; wipe the JNI-returned buffer.
            message.fill(0)
        }
    }

    fun cancelPrivacyReview() {
        privacyPrepared?.let { coordinator.cancelPrivacy(it) }
        privacyPrepared = null
        privacyReview = null
        privacySession?.let { session -> runCatching { session.privacyCancelPrepared() } }
    }

    fun authorizePrivacy() = c.launchOp {
        val p = privacyPrepared ?: throw UserError("Nenhuma operação confidencial está pronta para assinar.")
        val w = wallet() ?: throw UserError(S.NO_WALLET)
        if (!p.signable || p.wallet != w.address) throw UserError("A operação confidencial não está autorizável.")
        if (!net { coordinator.stillValidPrivacy(p) }) {
            coordinator.cancelPrivacy(p)
            privacyPrepared = null
            privacyReview = null
            throw UserError(S.BLOCKHASH_EXPIRED)
        }
        c.go(Screen.Authorize(AuthPurpose.PrivacySign(w.id)))
    }

    internal suspend fun signPrivacyAndSubmit(session: UnlockedWallet) {
        val prepared = privacyPrepared
        val opened = privacySession
        val w = wallet()
        if (prepared == null || opened == null || w == null ||
            session.info.id != w.id ||
            session.info.address != prepared.wallet || opened.info.id != session.info.id
        ) {
            session.close()
            throw UserError("A autorização não corresponde à carteira e operação revisadas.")
        }
        if (!net { coordinator.stillValidPrivacy(prepared) }) {
            session.close()
            coordinator.cancelPrivacy(prepared)
            privacyPrepared = null
            privacyReview = null
            throw UserError(S.BLOCKHASH_EXPIRED)
        }
        val signature = try {
            val handle = net { opened.privacySessionHandle() }
            val messageBytes = prepared.messageBytes
            val approvedHash = prepared.messageHash
            try {
                c.io { session.signPrivacyPrepared(handle, messageBytes, approvedHash, prepared.operation) }
            } finally {
                messageBytes.fill(0)
                approvedHash.fill(0)
            }
        } catch (e: Exception) {
            leavePrivacy()
            throw e
        } finally {
            closePrivacySession()
            session.close()
        }
        val record = try {
            net { coordinator.submitPrivacyV1(prepared, signature) }
        } catch (e: Exception) {
            leavePrivacy()
            throw e
        } finally {
            signature.fill(0)
        }
        records[record.signature] = record
        privacyPrepared = null
        privacyReview = null
        privacyAddress = null
        privacyBalanceLamports = null
        privacyUtxoCount = null
        c.go(Screen.TxStatus(record.signature))
    }

    // ------------------------------------------------------------------ refresh

    /** Background loops: a hostile/garbled RPC answer must never crash the app; the next tick retries. */
    suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
    }

    suspend fun refreshHome() {
        val w = wallet() ?: return
        val g = generation
        if (balance !is Loadable.Ready) balance = if (balance is Loadable.Failed) balance else Loadable.Loading
        val b: Loadable<Long> = try { Loadable.Ready(net { reader.balance(w.address) }, System.currentTimeMillis()) }
            catch (e: RpcException) { Loadable.Failed(S.rpc(e), balance.valueOrNull()) }
        if (g != generation) return
        balance = b
        val a = net { loadActivity(w.address) }
        if (g != generation) return
        activity = a
        refreshTokens()
        refreshStakes() // the total balance includes stake accounts
    }

    suspend fun refreshTokens() {
        val w = wallet() ?: return
        val g = generation
        val t = try {
            val list = net { reader.tokens(w.address) }
            if (g == generation) list.forEach { h -> h.symbol?.let { tokenSymbols[h.state.mint.toBase58()] = it } }
            Loadable.Ready(list, System.currentTimeMillis())
        } catch (e: RpcException) { Loadable.Failed(S.rpc(e), tokens.valueOrNull()) }
        if (g == generation) tokens = t
    }

    private fun loadActivity(me: SolanaPublicKey): List<Activity> {
        val cluster = net.config.cluster
        val local = coordinator.records(me)
        val remote = try { reader.history(me, 15) } catch (_: RpcException) { emptyList() }
        var fetched = 0
        val items = remote.map { s ->
            val d = if (details.containsKey(s.signature)) details[s.signature] else if (fetched++ < 6) {
                (try { reader.detail(s.signature) } catch (_: RpcException) { null }).also { details[s.signature] = it }
            } else null
            val loc = local.firstOrNull { it.signature == s.signature }
            val idx = d?.accountKeys?.indexOf(me.toBase58()) ?: -1
            val delta = if (d != null && idx >= 0) d.postBalances[idx] - d.preBalances[idx] else null
            val tokenDelta = d?.tokenChanges?.firstOrNull { it.owner == me.toBase58() && it.postUnits != it.preUnits }
            val title = loc?.summary ?: when {
                tokenDelta != null -> if (tokenDelta.postUnits > tokenDelta.preUnits) "Token recebido" else "Token enviado"
                delta == null -> "Transação"
                delta > 0 -> "Recebido"
                else -> "Enviado / taxa"
            }
            val amount = tokenDelta?.let { (if (it.postUnits > it.preUnits) "+" else "−") + Fmt.units(kotlin.math.abs(it.postUnits - it.preUnits), it.decimals) + " " + shortMint(it.mint) }
                ?: delta?.let { (if (it >= 0) "+" else "−") + Fmt.sol(kotlin.math.abs(it)) + " SOL" }
            Activity(s.signature, title, amount, if (s.err != null) "✕ falhou" else (s.confirmationStatus ?: "confirmed").let { TxText.commitment(it) },
                s.blockTime?.times(1000), cluster.explorerTx(s.signature), s.err != null, loc)
        }
        val pendingLocal = local.filter { r -> remote.none { it.signature == r.signature } }.map { r ->
            Activity(r.signature, r.summary, r.amountUnits?.let { a -> "−" + Fmt.units(a, r.decimals ?: 9) + if (r.mint == null) " SOL" else "" },
                TxText.state(r.state), r.createdAtMs, cluster.explorerTx(r.signature), r.state.name == "FAILED" || r.state.name == "EXPIRED", r)
        }
        return (pendingLocal + items).take(30)
    }

    private fun shortMint(m: String) = tokenSymbols[m] ?: (m.take(4) + "…")

    /** Reconcile every unresolved local record (same signed bytes only). Returns true if something changed. */
    suspend fun reconcileTick(): Boolean {
        val pending = net { journal.all().filter { it.unresolved } }
        if (pending.isEmpty()) return false
        var changed = false
        for (r in pending) {
            val n = net { coordinator.reconcile(r) }
            records[n.signature] = n
            if (n.state != r.state) changed = true
        }
        if (changed) { balance = Loadable.Idle; refreshHome(); if (stakes !is Loadable.Idle) refreshStakes() }
        return changed
    }

    // ------------------------------------------------------------------ send SOL

    fun reviewSol(toText: String, amountText: String, max: Boolean) = c.launchOp { doReviewSol(toText, amountText, max) }

    private suspend fun doReviewSol(toText: String, amountText: String, max: Boolean) {
        val w = wallet() ?: throw UserError(S.NO_WALLET)
        val to = SolanaPublicKey.fromBase58OrNull(toText.trim()) ?: throw UserError(S.BAD_ADDRESS)
        if (to == w.address) throw UserError(S.SELF_SEND)
        val (intent, info) = net { buildSol(w, to, amountText, max) }
        prepare(intent, info) { doReviewSol(toText, amountText, max) }
    }

    private fun buildSol(w: WalletInfo, to: SolanaPublicKey, amountText: String, max: Boolean): Pair<TxIntent, ReviewInfo> {
        val me = w.address
        val bal = reader.balance(me)
        val rent0 = reader.rentExempt(0)
        val dest = reader.account(to)
        if (dest?.executable == true) throw UserError(S.DEST_EXECUTABLE)
        // Fixed compute budget so the exact fee (base + priority) is known before the amount ("send max").
        val budget = coordinator.budgetFor(com.lockwallet.data.rpc.TxCoordinator.SOL_TRANSFER_UNITS, listOf(me, to))
        val fee = budget.totalFeeLamports
        val amount = if (max) bal - fee else UserAmount.sol(amountText)
        // Subtraction form: a huge typed amount can never overflow past the balance check.
        if (amount <= 0 || amount > bal - fee) throw UserError(S.insufficient(bal, fee))
        val remaining = bal - amount - fee
        if (remaining in 1 until rent0) throw UserError(S.residual(rent0, bal - fee))
        if (dest == null && amount < rent0) throw UserError(S.newAccountMin(rent0))
        val warnings = mutableListOf<String>()
        poisoning(to, w)?.let { warnings += it }
        if (dest != null && dest.owner != ProgramIds.SYSTEM) warnings += S.destOwnedBy(dest.owner.toBase58())
        if (!Ed25519Curve.isOnCurve(to.toByteArray())) warnings += S.DEST_OFF_CURVE
        if (dest == null) warnings += S.DEST_NEW
        val isNew = to.toBase58() !in prefs.recipients
        val intent = TxIntent(TxKind.SEND_SOL, me, "${Fmt.sol(amount)} SOL → ${to.toBase58().take(4)}…${to.toBase58().takeLast(4)}", to.toBase58(), amount, 9, null,
            listOf(SystemIx.transfer(me, to, amount)), autoBudget = true, budget = budget)
        val info = ReviewInfo("Enviar SOL", listOf(
            "Valor" to "${Fmt.sol(amount)} SOL" + if (max) " (máximo: saldo final 0)" else "",
            "Saldo depois" to "${Fmt.sol(remaining)} SOL",
        ), warnings, if (isNew) S.ACK_NEW_RECIPIENT else null)
        return intent to info
    }

    /** Address poisoning (spec §13.6): same 4-char prefix and suffix as a known address but not identical. */
    internal fun poisoning(to: SolanaPublicKey, w: WalletInfo): String? {
        val t = to.toBase58()
        val known = prefs.recipients + c.wallets.map { it.address.toBase58() }
        val look = known.firstOrNull { it != t && it.take(4) == t.take(4) && it.takeLast(4) == t.takeLast(4) }
        return look?.let { S.similarTo(it) }
    }

    /** Builds, analyses, prices and simulates; then shows the Review screen (refusals are shown there too). */
    internal suspend fun prepare(intent: TxIntent, info: ReviewInfo, again: suspend () -> Unit) {
        prepared?.let { coordinator.cancel(it) }
        val p = net { coordinator.prepare(if (intent.autoBudget) intent else intent.withAutoBudget()) }
        prepared = p; review = info
        retry = again
        c.go(Screen.Review)
    }

    fun cancelReview() { prepared?.let { coordinator.cancel(it) }; prepared = null; review = null; c.home() }

    /** "Assinar": refresh validity first; the PIN screen follows. */
    fun authorize() = c.launchOp {
        val p = prepared ?: return@launchOp
        val w = wallet() ?: return@launchOp
        if (!p.signable || p.intent.wallet != w.address) return@launchOp
        if (!net { coordinator.stillValid(p) }) { notice = S.BLOCKHASH_EXPIRED; return@launchOp }
        if (w.provider == com.lockwallet.custody.local.api.CustodyProviderId.SEED_VAULT) { signWithSeedVault(p, w); return@launchOp }
        c.go(Screen.Authorize(AuthPurpose.Sign(w.id)))
    }

    fun reviewAgain() { val r = retry ?: return; notice = null; c.launchOp { r() } }

    /** Called with the freshly unlocked session (closed here). Signs the approved bytes, persists, sends. */
    internal suspend fun signAndSubmit(s: UnlockedWallet) {
        val p = prepared
        val sig: ByteArray
        try {
            if (p == null || !p.signable || s.info.address != p.intent.wallet) { c.go(Screen.Home); return }
            if (!net { coordinator.stillValid(p) }) { notice = S.BLOCKHASH_EXPIRED; c.go(Screen.Review); return }
            sig = c.io { s.signApproved(p.messageBytes, p.messageHash) }
        } finally { s.close() }
        submitSigned(p, sig)
    }

    /**
     * Seed Vault path (Phase G): the custody layer re-checks the bytes with the analyzer and the reviewed hash,
     * Seed Vault authenticates the user and signs, the signature is verified against the wallet key, then the same
     * coordinator persists-before-send. The Lock Wallet PIN never replaces Seed Vault's authentication (spec §23.3).
     */
    private suspend fun signWithSeedVault(p: PreparedTx, w: WalletInfo) {
        val sv = c.seedVault ?: throw com.lockwallet.custody.local.CustodyException(com.lockwallet.custody.local.CustodyException.Reason.SEED_VAULT_UNAVAILABLE)
        val intent = c.io { sv.signTransactionRequest(w.id, p.messageBytes, p.messageHash) }
        val (code, data) = c.runExternal(intent)
        val sig = c.io { sv.finishSignature(w.id, p.messageBytes, code, data) }
        if (!net { coordinator.stillValid(p) }) { notice = S.BLOCKHASH_EXPIRED; return } // signature discarded, never sent
        submitSigned(p, sig)
    }

    private suspend fun submitSigned(p: PreparedTx, sig: ByteArray) {
        val rec = net { coordinator.submit(p, sig) }
        p.intent.counterparty?.let { prefs.recipients = prefs.recipients + it }
        records[rec.signature] = rec
        prepared = null; review = null
        c.go(Screen.TxStatus(rec.signature))
    }

    fun record(sig: String): TxRecord? = records[sig] ?: journal.all().lastOrNull { it.signature == sig }?.also { records[sig] = it }

    // ------------------------------------------------------------------ network

    /**
     * Only the primary RPC is user-configurable. The secondary remains app-controlled to preserve the
     * independent cross-check; two user-controlled endpoints are not independent evidence.
     */
    fun saveEndpoints(primary: String, secondary: String = "") = c.launchOp {
        val cl = Cluster.MAINNET
        val fixedSecondary = requireNotNull(cl.defaultSecondaryUrl)
        val requestedSecondary = secondary.trim()
        if (requestedSecondary.isNotEmpty() && requestedSecondary != fixedSecondary) throw UserError(S.URL_BAD)
        val p = primary.trim().ifEmpty { cl.defaultUrl!! }
        if (RpcClient.validateUrl(p) == null ||
            runCatching { java.net.URI(p).host.equals(java.net.URI(fixedSecondary).host, ignoreCase = true) }.getOrDefault(true)
        ) throw UserError(S.URL_BAD)
        for (u in listOf(p, fixedSecondary)) {
            when (val r = net { net.check(u, cl) }) {
                EndpointCheck.Ok -> {}
                EndpointCheck.BadUrl -> throw UserError(S.URL_BAD)
                EndpointCheck.Unreachable -> throw UserError(S.URL_UNREACHABLE)
                is EndpointCheck.OtherNetwork -> throw UserError(S.urlOther(r.known))
            }
        }
        applyConfig(NetworkConfig(cl, p, fixedSecondary))
        notice = S.URL_SAVED
    }

    fun resetEndpoints() { applyConfig(NetworkConfig.MAINNET_DEFAULT); notice = S.URL_RESET }

    /** Privacy Rings endpoint (Helius HTTPS + api-key). Never the app-wide primary (ADR 0026, decision 6):
     *  /v1/spr traffic (indexer, prover) uses the dedicated, Mainnet-verified service and fails closed. */
    private fun sprUrl(): String = prefs.sprEndpoint ?: throw UserError(S.SPR_NOT_SET)

    /** Validates and stores the dedicated Privacy Rings endpoint; any active confidential session is closed first. */
    fun saveSprEndpoint(url: String) = c.launchOp {
        val u = url.trim()
        if (RpcClient.validateUrl(u) == null || !isHeliusSprUrl(u)) throw UserError(S.SPR_BAD_URL)
        when (val r = net { net.check(u, Cluster.MAINNET) }) {
            EndpointCheck.Ok -> {}
            EndpointCheck.BadUrl -> throw UserError(S.SPR_BAD_URL)
            EndpointCheck.Unreachable -> throw UserError(S.SPR_UNREACHABLE)
            is EndpointCheck.OtherNetwork -> throw UserError(S.urlOther(r.known))
        }
        leavePrivacy()
        prefs.sprEndpoint = u
        notice = S.SPR_SAVED
    }

    fun resetSprEndpoint() { leavePrivacy(); prefs.sprEndpoint = null; notice = S.SPR_REMOVED }

    private fun applyConfig(cfg: NetworkConfig) {
        leavePrivacy()
        generation++
        prefs.network = cfg; net.select(cfg); config = cfg
        balance = Loadable.Idle; tokens = Loadable.Idle; stakes = Loadable.Idle; validators = Loadable.Idle; details.clear(); onSelect()
    }

    // ------------------------------------------------------------------ Phase E: SPL / Token-2022

    fun holding(account: String): TokenHolding? = tokens.valueOrNull()?.firstOrNull { it.account.toBase58() == account }
    fun tokenName(h: TokenHolding) = h.symbol ?: h.name ?: (h.state.mint.toBase58().take(4) + "…" + h.state.mint.toBase58().takeLast(4))

    fun setHidden(mint: String, hide: Boolean) {
        val n = if (hide) hidden + mint else hidden - mint
        prefs.hiddenTokens = n; hidden = n
    }

    fun reviewToken(account: String, toText: String, amountText: String) = c.launchOp { doReviewToken(account, toText, amountText) }

    private suspend fun doReviewToken(account: String, toText: String, amountText: String) {
        val w = wallet() ?: throw UserError(S.NO_WALLET)
        val to = SolanaPublicKey.fromBase58OrNull(toText.trim()) ?: throw UserError(S.BAD_ADDRESS)
        if (to == w.address) throw UserError(S.SELF_SEND)
        val (intent, info) = net { buildToken(w, account, to, amountText) }
        prepare(intent, info) { doReviewToken(account, toText, amountText) }
    }

    private fun buildToken(w: WalletInfo, account: String, to: SolanaPublicKey, amountText: String): Pair<TxIntent, ReviewInfo> {
        val me = w.address
        // Re-read the source and the mint now; the list on screen may be stale.
        val srcAcc = reader.account(SolanaPublicKey.fromBase58(account)) ?: throw UserError(S.TOKEN_GONE)
        val h = reader.tokens(me).firstOrNull { it.account == srcAcc.address } ?: throw UserError(S.TOKEN_GONE)
        if (!h.verdict.allowed) throw UserError(S.TOKEN_BLOCKED + " " + h.verdict.reasons.joinToString("; "))
        if (h.state.frozen) throw UserError(S.TOKEN_FROZEN)
        val mint = h.mint ?: throw UserError(S.TOKEN_GONE)
        val amount = UserAmount.units(amountText, mint.decimals)
        if (amount > h.state.amount) throw UserError(S.tokenInsufficient(Fmt.units(h.state.amount, mint.decimals), tokenName(h)))
        val program = h.program
        val warnings = mutableListOf<String>()
        poisoning(to, w)?.let { warnings += it }
        val toAcc = reader.account(to)
        if (toAcc?.executable == true) throw UserError(S.DEST_EXECUTABLE)
        val ixs = mutableListOf<com.lockwallet.core.solana.tx.Instruction>()
        val ctxAccounts = HashMap<SolanaPublicKey, com.lockwallet.core.solana.analyzer.TokenAccountFacts>()
        ctxAccounts[h.account] = h.accountFacts()
        val lines = mutableListOf<Pair<String, String>>()
        val destTokenAccount: SolanaPublicKey
        var ataRent = 0L
        if (toAcc != null && toAcc.owner == program) {
            // The user pasted a token account: accept only an initialised account of this same mint.
            val d = try { TokenState.parseAccount(program, toAcc.data) } catch (_: MalformedException) { throw UserError(S.BAD_TOKEN_DEST) }
            if (d.mint != h.state.mint) throw UserError(S.BAD_TOKEN_DEST)
            if (d.frozen) throw UserError(S.TOKEN_FROZEN_DEST)
            destTokenAccount = to
            ctxAccounts[to] = com.lockwallet.core.solana.analyzer.TokenAccountFacts(to, program, d.mint, d.owner, d.amount, d.delegate != null, d.frozen)
            warnings += S.destIsTokenAccount(d.owner.toBase58())
        } else {
            if (toAcc != null && toAcc.owner in ProgramIds.TOKEN_PROGRAMS) throw UserError(S.BAD_TOKEN_DEST)
            if (!Ed25519Curve.isOnCurve(to.toByteArray())) warnings += S.DEST_OFF_CURVE
            if (toAcc != null && toAcc.owner != ProgramIds.SYSTEM) warnings += S.destOwnedBy(toAcc.owner.toBase58())
            val ata = Pda.associatedTokenAddress(to, h.state.mint, program)
            val ataAcc = reader.account(ata)
            if (ataAcc == null) {
                ataRent = reader.rentExempt(if (program == ProgramIds.TOKEN_2022) 170 else 165)
                ixs += AtaIx.createIdempotent(me, ata, to, h.state.mint, program)
                warnings += S.ataCreate(Fmt.sol(ataRent))
                lines += "Depósito da nova conta" to "≈ ${Fmt.sol(ataRent)} SOL (fica com o destinatário)"
            } else {
                val d = try { TokenState.parseAccount(program, ataAcc.data) } catch (_: MalformedException) { throw UserError(S.BAD_TOKEN_DEST) }
                if (ataAcc.owner != program || d.mint != h.state.mint || d.owner != to) throw UserError(S.BAD_TOKEN_DEST)
                if (d.frozen) throw UserError(S.TOKEN_FROZEN_DEST)
                ctxAccounts[ata] = com.lockwallet.core.solana.analyzer.TokenAccountFacts(ata, program, d.mint, d.owner, d.amount, d.delegate != null, d.frozen)
            }
            destTokenAccount = ata
        }
        ixs += TokenIx.transferChecked(program, h.account, h.state.mint, destTokenAccount, me, amount, mint.decimals)
        val fee = reader.fee(me, ixs) ?: throw UserError(S.FEE_UNKNOWN)
        val bal = reader.balance(me)
        if (fee + ataRent > bal) throw UserError(S.insufficient(bal, fee + ataRent))
        val name = tokenName(h)
        val isNew = to.toBase58() !in prefs.recipients
        val ctx = AnalysisContext(ctxAccounts, mapOf(h.state.mint to h.mintFacts()!!))
        val intent = TxIntent(TxKind.SEND_TOKEN, me, "${Fmt.units(amount, mint.decimals)} $name → ${TxText.short(to)}", to.toBase58(), amount, mint.decimals,
            h.state.mint.toBase58(), ixs, ctx)
        val info = ReviewInfo("Enviar $name", listOf(
            "Valor" to "${Fmt.units(amount, mint.decimals)} $name",
            "Token" to "${TxText.program(program)} · mint ${TxText.short(h.state.mint)}" + if (h.name != null || h.symbol != null) " · nome do emissor, não verificado" else "",
            "Saldo do token depois" to Fmt.units(h.state.amount - amount, mint.decimals),
        ) + lines, warnings, if (isNew) S.ACK_NEW_RECIPIENT else null)
        tokenSymbols[h.state.mint.toBase58()] = name
        return intent to info
    }

    fun reviewClose(account: String) = c.launchOp { doReviewClose(account) }

    private suspend fun doReviewClose(account: String) {
        val w = wallet() ?: throw UserError(S.NO_WALLET)
        val (intent, info) = net {
            val me = w.address
            val acc = reader.account(SolanaPublicKey.fromBase58(account)) ?: throw UserError(S.TOKEN_GONE)
            val h = reader.tokens(me).firstOrNull { it.account == acc.address } ?: throw UserError(S.TOKEN_GONE)
            if (!h.closable) throw UserError(S.NOT_CLOSABLE)
            val ixs = listOf(TokenIx.closeAccount(h.program, h.account, me, me))
            val fee = reader.fee(me, ixs) ?: throw UserError(S.FEE_UNKNOWN)
            val ctx = AnalysisContext(mapOf(h.account to h.accountFacts()), h.mintFacts()?.let { mapOf(h.state.mint to it) } ?: emptyMap())
            TxIntent(TxKind.CLOSE_TOKEN, me, "Fechar conta ${tokenName(h)} (+${Fmt.sol(acc.lamports)} SOL)", null, acc.lamports, 9, h.state.mint.toBase58(), ixs, ctx) to
                ReviewInfo("Fechar conta de token", listOf(
                    "Token" to tokenName(h),
                    "Depósito devolvido a você" to "${Fmt.sol(acc.lamports)} SOL",
                    "Ganho líquido" to "${Fmt.sol(acc.lamports - fee)} SOL",
                ), listOf(S.CLOSE_NOTE), null)
        }
        prepare(intent, info) { doReviewClose(account) }
    }

    // ------------------------------------------------------------------ Phase F: native staking (never liquid)

    suspend fun refreshStakes() {
        val w = wallet() ?: return
        val g = generation
        if (stakes is Loadable.Idle) stakes = Loadable.Loading
        val st = try { Loadable.Ready(net { reader.stakes(w.address) }, System.currentTimeMillis()) }
            catch (e: RpcException) { Loadable.Failed(S.rpc(e), stakes.valueOrNull()) }
        if (g == generation) stakes = st
    }

    /** Live health of the curated block (ADR 0025); computed off the main thread together with the list. */
    var curated by mutableStateOf<List<CuratedView>>(emptyList())
        private set

    /** Validator list, re-read when older than [VALIDATORS_TTL_MS] so commission/delinquency on screen stay live. */
    suspend fun loadValidators(force: Boolean = false) {
        val cur = validators
        if (!force && cur is Loadable.Ready && System.currentTimeMillis() - cur.atMs < VALIDATORS_TTL_MS) return
        if (cur !is Loadable.Ready) validators = Loadable.Loading
        validators = try {
            val (v, cv) = net {
                val all = reader.validators()
                all.sortedWith(compareBy<ValidatorInfo> { it.delinquent }.thenByDescending { it.activatedStake }) to Curated.evaluate(all)
            }
            curated = cv
            Loadable.Ready(v, System.currentTimeMillis())
        } catch (e: RpcException) { Loadable.Failed(S.rpc(e), cur.valueOrNull()) }
    }

    fun stake(address: String): StakeView? = stakes.valueOrNull()?.first?.firstOrNull { it.address.toBase58() == address }
    fun validator(vote: SolanaPublicKey?): ValidatorInfo? = vote?.let { v -> validators.valueOrNull()?.firstOrNull { it.vote == v } }

    /** Fresh validator map for the analyzer (vote → delinquent). Always re-read: the list on screen may be stale. */
    private fun validatorContext(): Map<SolanaPublicKey, Boolean> = reader.validators().associate { it.vote to it.delinquent }

    /** Re-reads the stake account now and requires full control by this wallet and no lockup. */
    private fun freshStake(me: SolanaPublicKey, address: String): Pair<StakeView, EpochInfo> {
        val (list, ep) = reader.stakes(me)
        val v = list.firstOrNull { it.address.toBase58() == address } ?: throw UserError(S.STAKE_GONE)
        if (!v.controlledBy(me)) throw UserError(S.STAKE_NOT_CONTROLLED)
        if (v.state.hasLockup || v.state.custodian != ProgramIds.SYSTEM) throw UserError(S.STAKE_LOCKUP)
        return v to ep
    }

    private fun stakeIntent(kind: TxKind, me: SolanaPublicKey, summary: String, amount: Long?, ixs: List<com.lockwallet.core.solana.tx.Instruction>, counterparty: String? = null) =
        TxIntent(kind, me, summary, counterparty, amount, 9, null, ixs, AnalysisContext(validators = validatorContext()))

    fun reviewStakeCreate(amountText: String, vote: String) = c.launchOp { doStakeCreate(amountText, vote) }

    private suspend fun doStakeCreate(amountText: String, vote: String) {
        val w = wallet() ?: throw UserError(S.NO_WALLET)
        val (intent, info) = net {
            val me = w.address
            val voteKey = SolanaPublicKey.fromBase58OrNull(vote) ?: throw UserError(S.PICK_VALIDATOR)
            val all = reader.validators()
            val v = all.firstOrNull { it.vote == voteKey } ?: throw UserError(S.UNKNOWN_VALIDATOR)
            // Curated entries are re-checked against fresh chain data at review time, not the list on screen.
            val curatedView = Curated.evaluate(all).firstOrNull { it.entry.vote == vote }
            if (curatedView != null && !curatedView.selectable) throw UserError(S.curatedBlocked(curatedView.entry.name, curatedView.status))
            val amount = UserAmount.sol(amountText)
            val minDel = reader.minimumDelegation()
            if (amount < minDel) throw UserError(S.minDelegation(Fmt.sol(minDel)))
            val rent = reader.rentExempt(StakeIx.STAKE_ACCOUNT_SIZE)
            val rent0 = reader.rentExempt(0)
            val seedIdx = reader.nextStakeSeed(me)
            val seed = ChainReader.stakeSeed(seedIdx)
            val addr = ChainReader.stakeAddress(me, seedIdx)
            val lamports = amount + rent
            val ixs = listOf(SystemIx.createAccountWithSeed(me, addr, seed, lamports, StakeIx.STAKE_ACCOUNT_SIZE, ProgramIds.STAKE),
                StakeIx.initialize(addr, me), StakeIx.delegate(addr, voteKey, me))
            val fee = reader.fee(me, ixs) ?: throw UserError(S.FEE_UNKNOWN)
            val bal = reader.balance(me)
            if (lamports + fee > bal) throw UserError(S.insufficient(bal, fee + rent))
            val remaining = bal - lamports - fee
            if (remaining in 1 until rent0) throw UserError(S.residual(rent0, bal - fee - rent))
            val warnings = mutableListOf(S.STAKE_WARMUP)
            if (v.delinquent) warnings += S.VALIDATOR_DELINQUENT
            if (v.commission >= 10) warnings += S.highCommission(v.commission)
            if (curatedView == null) warnings += S.NOT_CURATED
            stakeIntent(TxKind.STAKE_CREATE, me, "Stake de ${Fmt.sol(amount)} SOL → ${TxText.short(voteKey)}", amount, ixs, vote) to ReviewInfo("Fazer stake", listOf(
                "Valor delegado" to "${Fmt.sol(amount)} SOL",
                "Reserva da conta (devolvida ao sacar)" to "${Fmt.sol(rent)} SOL",
                "Validador" to "${curatedView?.entry?.name?.let { "$it · " } ?: ""}${TxText.short(voteKey)} · comissão ${v.commission}%",
                "Conta de stake" to "${TxText.short(addr)} (semente \"$seed\")",
                "Autoridades" to "stake e saque = você · sem lockup",
                "Saldo depois" to "${Fmt.sol(remaining)} SOL",
            ), warnings, null)
        }
        prepare(intent, info) { doStakeCreate(amountText, vote) }
    }

    fun reviewDeactivate(address: String) = c.launchOp { doDeactivate(address) }

    private suspend fun doDeactivate(address: String) {
        val w = wallet() ?: throw UserError(S.NO_WALLET)
        val (intent, info) = net {
            val me = w.address
            val (v, ep) = freshStake(me, address)
            if (v.lifecycle != StakeLifecycle.ACTIVE && v.lifecycle != StakeLifecycle.ACTIVATING) throw UserError(S.STAKE_NOT_ACTIVE)
            val ixs = listOf(StakeIx.deactivate(v.address, me))
            stakeIntent(TxKind.STAKE_DEACTIVATE, me, "Desativar stake ${TxText.short(v.address)}", v.state.delegatedStake, ixs) to ReviewInfo("Desativar stake", listOf(
                "Conta" to TxText.short(v.address),
                "Em stake" to "${Fmt.sol(v.state.delegatedStake)} SOL",
                "Época atual" to "${ep.epoch}",
            ), listOf(S.STAKE_COOLDOWN), null)
        }
        prepare(intent, info) { doDeactivate(address) }
    }

    fun reviewWithdraw(address: String) = c.launchOp { doWithdraw(address) }

    private suspend fun doWithdraw(address: String) {
        val w = wallet() ?: throw UserError(S.NO_WALLET)
        val (intent, info) = net {
            val me = w.address
            val (v, _) = freshStake(me, address)
            if (v.lifecycle != StakeLifecycle.INACTIVE && v.lifecycle != StakeLifecycle.INITIALIZED) throw UserError(S.STAKE_NOT_INACTIVE)
            val ixs = listOf(StakeIx.withdraw(v.address, me, me, v.lamports))
            val fee = reader.fee(me, ixs) ?: throw UserError(S.FEE_UNKNOWN)
            stakeIntent(TxKind.STAKE_WITHDRAW, me, "Sacar ${Fmt.sol(v.lamports)} SOL do stake", v.lamports, ixs) to ReviewInfo("Sacar stake", listOf(
                "Valor sacado (tudo, inclui a reserva)" to "${Fmt.sol(v.lamports)} SOL",
                "Destino" to "você",
                "Taxa" to "${Fmt.sol(fee)} SOL",
            ), listOf(S.WITHDRAW_CLOSES), null)
        }
        prepare(intent, info) { doWithdraw(address) }
    }

    fun reviewSplit(address: String, amountText: String) = c.launchOp { doSplit(address, amountText) }

    private suspend fun doSplit(address: String, amountText: String) {
        val w = wallet() ?: throw UserError(S.NO_WALLET)
        val (intent, info) = net {
            val me = w.address
            val (v, _) = freshStake(me, address)
            val amount = UserAmount.sol(amountText)
            val rent = reader.rentExempt(StakeIx.STAKE_ACCOUNT_SIZE)
            val delegated = v.state.kind == 2 && v.lifecycle != StakeLifecycle.INACTIVE
            val minDel = if (delegated) reader.minimumDelegation() else 0L
            if (delegated && amount < minDel) throw UserError(S.minDelegation(Fmt.sol(minDel)))
            val left = v.lamports - amount
            if (left < v.state.rentExemptReserve + minDel) throw UserError(S.splitTooMuch(Fmt.sol(v.lamports - v.state.rentExemptReserve - minDel)))
            val seedIdx = reader.nextStakeSeed(me)
            val seed = ChainReader.stakeSeed(seedIdx)
            val dest = ChainReader.stakeAddress(me, seedIdx)
            val ixs = listOf(SystemIx.createAccountWithSeed(me, dest, seed, rent, StakeIx.STAKE_ACCOUNT_SIZE, ProgramIds.STAKE), StakeIx.split(v.address, dest, me, amount))
            val fee = reader.fee(me, ixs) ?: throw UserError(S.FEE_UNKNOWN)
            val bal = reader.balance(me)
            if (rent + fee > bal) throw UserError(S.insufficient(bal, fee + rent))
            stakeIntent(TxKind.STAKE_SPLIT, me, "Dividir ${Fmt.sol(amount)} SOL de ${TxText.short(v.address)}", amount, ixs) to ReviewInfo("Dividir stake", listOf(
                "Movido para a nova conta" to "${Fmt.sol(amount)} SOL",
                "Fica na conta original" to "${Fmt.sol(left)} SOL",
                "Nova conta" to "${TxText.short(dest)} (semente \"$seed\")",
                "Reserva da nova conta (paga por você)" to "${Fmt.sol(rent)} SOL",
            ), emptyList(), null)
        }
        prepare(intent, info) { doSplit(address, amountText) }
    }

    /** Merge candidates: same voter and both active, or both inactive/initialised (the simulation is the final arbiter). */
    fun mergeCandidates(dest: StakeView, me: SolanaPublicKey): List<StakeView> = stakes.valueOrNull()?.first.orEmpty().filter { s ->
        s.address != dest.address && s.controlledBy(me) && !s.state.hasLockup && compatible(dest, s)
    }

    private fun compatible(a: StakeView, b: StakeView): Boolean {
        val idle = setOf(StakeLifecycle.INACTIVE, StakeLifecycle.INITIALIZED)
        return when {
            a.lifecycle in idle && b.lifecycle in idle -> true
            a.lifecycle == StakeLifecycle.ACTIVE && b.lifecycle == StakeLifecycle.ACTIVE -> a.state.voter == b.state.voter
            a.lifecycle == StakeLifecycle.ACTIVATING && b.lifecycle == StakeLifecycle.ACTIVATING -> a.state.voter == b.state.voter && a.state.activationEpoch == b.state.activationEpoch
            else -> false
        }
    }

    fun reviewMerge(dest: String, source: String) = c.launchOp { doMerge(dest, source) }

    private suspend fun doMerge(dest: String, source: String) {
        val w = wallet() ?: throw UserError(S.NO_WALLET)
        val (intent, info) = net {
            val me = w.address
            val (d, _) = freshStake(me, dest)
            val (s, _) = freshStake(me, source)
            if (d.address == s.address || !compatible(d, s)) throw UserError(S.MERGE_INCOMPATIBLE)
            val ixs = listOf(StakeIx.merge(d.address, s.address, me))
            stakeIntent(TxKind.STAKE_MERGE, me, "Unir ${TxText.short(s.address)} em ${TxText.short(d.address)}", s.lamports, ixs) to ReviewInfo("Unir contas de stake", listOf(
                "Conta que recebe" to "${TxText.short(d.address)} · ${Fmt.sol(d.lamports)} SOL",
                "Conta unida (fechada)" to "${TxText.short(s.address)} · ${Fmt.sol(s.lamports)} SOL",
                "Total depois" to "${Fmt.sol(d.lamports + s.lamports)} SOL",
            ), emptyList(), null)
        }
        prepare(intent, info) { doMerge(dest, source) }
    }
}
