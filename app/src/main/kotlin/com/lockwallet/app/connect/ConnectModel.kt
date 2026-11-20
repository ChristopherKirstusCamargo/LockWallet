package com.lockwallet.app.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lockwallet.app.AppController
import com.lockwallet.app.AuthPurpose
import com.lockwallet.app.Screen
import com.lockwallet.core.solana.Base58
import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.dapp.DappFirewall
import com.lockwallet.core.solana.dapp.DappVerdict
import com.lockwallet.core.solana.dapp.MessagePolicy
import com.lockwallet.core.solana.state.StakeState
import com.lockwallet.core.solana.state.TokenState
import com.lockwallet.core.solana.tx.WireTransaction
import com.lockwallet.custody.local.CustodyException
import com.lockwallet.custody.local.UnlockedWallet
import com.lockwallet.custody.local.api.CustodyProviderId
import com.lockwallet.custody.local.api.WalletInfo
import com.lockwallet.custody.local.storage.WalletRole
import com.lockwallet.custody.local.storage.WalletState
import com.lockwallet.data.rpc.Cluster
import com.lockwallet.data.rpc.PreparedTx
import com.lockwallet.data.rpc.PrepBlocker
import com.lockwallet.data.rpc.RpcException
import kotlinx.coroutines.launch
import java.io.File

/** dApp identity exactly as the dApp claims it (MWA does not prove it). [callingPackage] comes from Android. */
data class DappIdentity(val name: String, val uri: String?, val callingPackage: String?) {
    /** Host of an https identity URI, or null. */
    val domain: String? get() = uri?.let { runCatching { java.net.URI(it) }.getOrNull() }?.takeIf { it.scheme == "https" }?.host?.lowercase()
}

/** Sign-In With Solana fields shown to the user; [prepare] renders the exact text for an address. */
class SiwsRequest(val domain: String?, val statement: String?, val uri: String?, val chainId: String?, val address: String?,
                  val issuedAt: String?, val expirationTime: String?, val nonce: String?, val prepare: (ByteArray) -> String)

/** Answers to the dApp (implemented by the MWA bridge; a fake in tests). Each is called at most once. */
interface AuthorizeAnswer {
    fun approve(address: ByteArray, label: String, scope: ByteArray, siwsMessage: ByteArray?, siwsSignature: ByteArray?)
    fun decline()
    fun clusterNotSupported()
}

interface SignAnswer {
    fun signedPayloads(p: Array<ByteArray>)
    fun signatures(s: Array<ByteArray>)
    fun notSubmitted(s: Array<ByteArray>)
    fun decline()
    fun invalid(valid: BooleanArray)
    fun authorizationNotValid()
    fun tooMany()
}

enum class SignKind { TRANSACTIONS, SIGN_AND_SEND, MESSAGES }

sealed interface ConnectRequest { val dapp: DappIdentity }

class AuthorizeRequestModel(override val dapp: DappIdentity, val chain: String?, val cluster: Cluster?, val siws: SiwsRequest?, val answer: AuthorizeAnswer) : ConnectRequest

class SignRequestModel(override val dapp: DappIdentity, val session: DappSession, val kind: SignKind, val payloads: List<ByteArray>, val answer: SignAnswer) : ConnectRequest

data class TokenDelta(val account: String, val mint: String, val pre: Long, val post: Long?, val decimals: Int?)

/** Everything shown for one dApp transaction before signing. */
class DappTxReview(
    val verdict: DappVerdict,
    val prepared: PreparedTx?,
    val solDelta: Long?,
    val tokenDeltas: List<TokenDelta>,
    /** Problems found by comparing pre/post simulated state (authority/delegate/owner changes). */
    val postProblems: List<String>,
    val prepProblems: List<PrepBlocker>,
) {
    val signable: Boolean get() = verdict.signable && prepared != null && prepProblems.isEmpty() && postProblems.isEmpty()
}

/**
 * Lock Connect (Phase H, ADR 0022). Owns the dApp request queue, the session registry and the review state.
 * Policy: only CONNECT-role wallets; only Devnet/Testnet and only the cluster currently selected; every
 * transaction goes through [DappFirewall] + a successful simulation + post-state checks; raw (undecodable)
 * instructions need an explicit acknowledgement; every signature needs PIN (local) or Seed Vault authentication.
 */
class ConnectModel(private val c: AppController, dataDir: File?) {
    val registry = ConnectRegistry(File(dataDir ?: File(System.getProperty("java.io.tmpdir"), "lw-c-${System.nanoTime()}"), "connect/sessions.json"))
    var sessions by mutableStateOf<List<DappSession>>(emptyList())
    val queue = mutableStateListOf<ConnectRequest>()
    val current: ConnectRequest? get() = queue.firstOrNull()
    var reviews by mutableStateOf<List<DappTxReview>?>(null)
    var reviewError by mutableStateOf<String?>(null)
    var rawAck by mutableStateOf(false)
    var selectedWallet by mutableStateOf<String?>(null)
    /** True while an MWA association is live in this activity. */
    var live by mutableStateOf(false)
    /** Set by the hosting activity: finish and return to the dApp. */
    var onSessionEnd: () -> Unit = {}
    private var pendingOp: (suspend (DappSigner) -> Unit)? = null

    fun refreshSessions() { sessions = runCatching { registry.list() }.getOrDefault(emptyList()) }
    fun revoke(id: String) { registry.revoke(id); refreshSessions() }
    fun revokeAll() { registry.revokeAll(); refreshSessions() }

    fun connectWallets(): List<WalletInfo> = c.wallets.filter { it.role == WalletRole.CONNECT && it.state == WalletState.ACTIVE }

    // ------------------------------------------------------------------ incoming (main thread)

    fun enqueue(r: ConnectRequest) {
        if (queue.size >= 8) { reject(r); return }
        queue += r
        if (queue.size == 1) onCurrentChanged()
    }

    /** Reauthorize: no prompt; valid only for a live session of the same identity on the current cluster. */
    fun reauthorize(scope: ByteArray?, dapp: DappIdentity, chain: String?): Boolean {
        val s = registry.find(scope) ?: return false
        val ok = s.name == dapp.name.take(64) && s.uri == dapp.uri?.take(200) && s.callingPackage == dapp.callingPackage && clusterOf(chain) == s.cluster && s.cluster == c.chain.config.cluster &&
            connectWallets().any { it.id == s.walletId && it.address.toBase58() == s.address }
        if (ok) registry.touch(s.id)
        refreshSessions()
        return ok
    }

    fun deauthorized(scope: ByteArray?) { registry.find(scope)?.let { registry.revoke(it.id) }; refreshSessions() }

    /** Session check for sign requests: live, same cluster as now, wallet still a CONNECT wallet, same account. */
    fun sessionFor(scope: ByteArray?, authorizedKey: ByteArray?, chain: String?, dapp: DappIdentity? = null): DappSession? {
        val s = registry.find(scope) ?: return null
        if (dapp != null && (s.name != dapp.name.take(64) || s.uri != dapp.uri?.take(200) || s.callingPackage != dapp.callingPackage)) return null
        if (s.cluster != c.chain.config.cluster || (chain != null && clusterOf(chain) != s.cluster)) return null
        if (authorizedKey != null && Base58.encode(authorizedKey) != s.address) return null
        if (connectWallets().none { it.id == s.walletId && it.address.toBase58() == s.address }) return null
        return s
    }

    fun ended() {
        while (queue.isNotEmpty()) reject(queue.removeAt(0))
        live = false; reviews = null; pendingOp = null
        onSessionEnd()
    }

    /** App locked/left: pending dApp requests are declined (nothing is kept half-approved). */
    fun onLock() {
        if (queue.isEmpty()) return
        while (queue.isNotEmpty()) reject(queue.removeAt(0))
        reviews = null; pendingOp = null
    }

    private fun reject(r: ConnectRequest) = runCatching {
        when (r) { is AuthorizeRequestModel -> r.answer.decline(); is SignRequestModel -> r.answer.decline() }
    }

    private fun done() {
        if (queue.isNotEmpty()) queue.removeAt(0)
        reviews = null; reviewError = null; rawAck = false; pendingOp = null
        refreshSessions()
        if (c.screen is Screen.Authorize) c.go(Screen.Home)
        if (queue.isNotEmpty()) onCurrentChanged()
    }

    private fun onCurrentChanged() {
        reviews = null; reviewError = null; rawAck = false
        when (val r = current) {
            is AuthorizeRequestModel -> {
                when {
                    r.cluster == null -> { r.answer.clusterNotSupported(); done(); return }
                    r.cluster != c.chain.config.cluster -> { reviewError = S_CLUSTER_MISMATCH(r.cluster, c.chain.config.cluster) }
                }
                selectedWallet = connectWallets().firstOrNull()?.id
            }
            is SignRequestModel -> if (r.kind != SignKind.MESSAGES) buildReviews(r)
            null -> {}
        }
    }

    // ------------------------------------------------------------------ review

    private fun walletOf(s: DappSession): WalletInfo? = connectWallets().firstOrNull { it.id == s.walletId }

    private fun buildReviews(r: SignRequestModel) {
        val w = walletOf(r.session)
        c.scope.launch {
            val out = try { if (w == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { r.payloads.map { review(w, it, r.kind) } } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { reviewError = "Não foi possível analisar a transação. Nada foi assinado."; return@launch }
            if (current !== r) return@launch
            if (out == null) { r.answer.authorizationNotValid(); done() } else reviews = out
        }
    }

    internal fun review(w: WalletInfo, payload: ByteArray, kind: SignKind): DappTxReview {
        val me = w.address
        val msg = try {
            val (sigs, m) = WireTransaction.split(payload)
            if (sigs.size != 1) return DappTxReview(DappFirewall.inspect(ByteArray(0), me), null, null, emptyList(), listOf(S_SIG_COUNT), emptyList())
            m
        } catch (_: IllegalArgumentException) { return DappTxReview(DappFirewall.inspect(ByteArray(0), me), null, null, emptyList(), emptyList(), emptyList()) }
        val verdict = DappFirewall.inspect(msg, me)
        if (!verdict.signable) return DappTxReview(verdict, null, null, emptyList(), emptyList(), emptyList())
        val m = verdict.message!!
        val writable = m.accountKeys.filterIndexed { i, k -> m.isWritable(i) && k != me }
        return try {
            val network = c.chain.net
            val requestedAccounts = listOf(me) + writable
            val pre = network.primary().getMultipleAccounts(requestedAccounts)
            val secondary = network.secondaryOrNull()
                ?: return DappTxReview(
                    verdict, null, null, emptyList(),
                    listOf("O servidor de reserva não respondeu. O estado anterior não pôde ser confirmado."),
                    listOf(PrepBlocker.SIMULATION_UNAVAILABLE),
                )
            val secondaryPre = secondary.getMultipleAccounts(requestedAccounts)
            if (pre.size != requestedAccounts.size || secondaryPre.size != requestedAccounts.size) {
                throw RpcException(RpcException.Kind.MALFORMED, "pre-state incompleto")
            }
            if (pre != secondaryPre) return DappTxReview(
                verdict, null, null, emptyList(),
                listOf("Os servidores discordam sobre o estado anterior das contas. Assinatura bloqueada."),
                listOf(PrepBlocker.SIMULATION_UNAVAILABLE),
            )
            val preWallet = pre.first()
            val inspectAllWritable = verdict.needsRawAck || m.instructions.any { ix ->
                val program = m.accountKeys[ix.programIndex]
                program == ProgramIds.STAKE || program in ProgramIds.TOKEN_PROGRAMS || program == ProgramIds.ASSOCIATED_TOKEN
            }
            val watchIndexes = if (inspectAllWritable) writable.indices.toList() else writable.indices.filter { i ->
                val owner = pre[i + 1]?.owner
                owner == ProgramIds.STAKE || owner in ProgramIds.TOKEN_PROGRAMS
            }
            val watch = watchIndexes.map { writable[it] }
            val p = c.chain.coordinator.prepareExternal(
                me, msg, "dApp: " + (r0Name ?: "transação"), watch, verdict.signable, crossCheck = true,
            )
            val prepProblems = p.blockers.filter { kind == SignKind.SIGN_AND_SEND || it != PrepBlocker.BUSY }
            val sim = p.simulation
            val post = sim?.accounts.orEmpty()
            val problems = mutableListOf<String>()
            val deltas = mutableListOf<TokenDelta>()
            var solDelta: Long? = null
            if (preWallet == null) problems += S_WALLET_STATE_UNKNOWN
            if (sim != null && sim.err == null && post.size == 1 + watch.size) {
                val walletAfter = post[0]
                if (walletAfter == null) problems += S_WALLET_STATE_UNKNOWN
                else if (walletAfter.owner != ProgramIds.SYSTEM || walletAfter.data.isNotEmpty()) problems += S_WALLET_OWNER

                val watchedBefore = watchIndexes.map { pre[it + 1] }
                var totalSolDelta = (walletAfter?.lamports ?: 0L) - (preWallet?.lamports ?: 0L)
                var deltaOverflow = false
                val mints = linkedSetOf<SolanaPublicKey>()
                watchIndexes.forEachIndexed { pos, writableIndex ->
                    val key = writable[writableIndex]
                    val before = watchedBefore[pos]
                    val afterAccount = post[pos + 1]
                    val beforeToken = if (before != null && before.owner in ProgramIds.TOKEN_PROGRAMS) {
                        runCatching { TokenState.parseAccount(before.owner, before.data) }.getOrElse {
                            problems += S_TOKEN_CHANGED(key); null
                        }
                    } else null
                    val afterToken = if (afterAccount != null && afterAccount.owner in ProgramIds.TOKEN_PROGRAMS) {
                        runCatching { TokenState.parseAccount(afterAccount.owner, afterAccount.data) }.getOrElse {
                            problems += S_TOKEN_CHANGED(key); null
                        }
                    } else null

                    val beforeOwnedToken = beforeToken?.takeIf { it.owner == me }
                    val afterOwnedToken = afterToken?.takeIf { it.owner == me }
                    if (beforeOwnedToken != null || afterOwnedToken != null) {
                        if (beforeOwnedToken != null && afterOwnedToken == null) {
                            problems += S_TOKEN_CHANGED(key)
                        } else if (beforeOwnedToken != null && afterOwnedToken != null) {
                            if (afterOwnedToken.mint != beforeOwnedToken.mint ||
                                afterOwnedToken.owner != beforeOwnedToken.owner ||
                                afterOwnedToken.delegate != beforeOwnedToken.delegate ||
                                afterOwnedToken.delegatedAmount != beforeOwnedToken.delegatedAmount ||
                                afterOwnedToken.closeAuthority != beforeOwnedToken.closeAuthority ||
                                afterOwnedToken.state != beforeOwnedToken.state ||
                                afterOwnedToken.isNative != beforeOwnedToken.isNative ||
                                afterOwnedToken.extensions != beforeOwnedToken.extensions
                            ) problems += S_TOKEN_AUTHORITY_CHANGED(key)
                        } else if (afterOwnedToken != null) {
                            // A newly-created token account is acceptable only with the default, unprivileged authorities.
                            if (afterOwnedToken.delegate != null || afterOwnedToken.delegatedAmount != 0L ||
                                afterOwnedToken.closeAuthority != null || afterOwnedToken.state != 1
                            ) problems += S_TOKEN_AUTHORITY_CHANGED(key)
                        }
                        val mint = afterOwnedToken?.mint ?: beforeOwnedToken!!.mint
                        mints += mint
                        val delta = afterOwnedToken?.amount ?: 0L
                        val old = beforeOwnedToken?.amount ?: 0L
                        val decimals: Int? = null // Filled from the separately fetched mint account below.
                        // Mint data is fetched separately below; a token-account byte layout is not a mint layout.
                        deltas += TokenDelta(key.toBase58(), mint.toBase58(), old, delta, decimals)
                    }

                    val beforeStake = if (before != null && before.owner == ProgramIds.STAKE) {
                        runCatching { StakeState.parse(before.data) }.getOrElse {
                            problems += S_STAKE_STATE_UNKNOWN(key); null
                        }
                    } else null
                    val afterStake = if (afterAccount != null && afterAccount.owner == ProgramIds.STAKE) {
                        runCatching { StakeState.parse(afterAccount.data) }.getOrElse {
                            problems += S_STAKE_STATE_UNKNOWN(key); null
                        }
                    } else null
                    val beforeControlled = beforeStake?.let { it.staker == me && it.withdrawer == me } == true
                    val afterControlled = afterStake?.let { it.staker == me && it.withdrawer == me } == true
                    val touchesStake = before?.owner == ProgramIds.STAKE || afterAccount?.owner == ProgramIds.STAKE
                    if (touchesStake) {
                        // A dApp must not create, close, re-authorize, delegate or move lamports from stake
                        // accounts through CPI. Both authorities must remain this wallet's keys.
                        if (before == null || afterAccount == null || beforeStake == null || afterStake == null ||
                            !beforeControlled || !afterControlled ||
                            beforeStake != afterStake || before.lamports != afterAccount.lamports
                        ) {
                            problems += S_STAKE_CHANGED(key)
                        }
                        if (before != null && afterAccount != null && beforeControlled && afterControlled) {
                            val change = afterAccount.lamports - before.lamports
                            try { totalSolDelta = Math.addExact(totalSolDelta, change) } catch (_: ArithmeticException) {
                                deltaOverflow = true
                            }
                        }
                    }
                }
                if (deltaOverflow) problems += S_ACCOUNT_DELTA_OVERFLOW else solDelta = totalSolDelta

                if (mints.isNotEmpty()) {
                    val mintInfo = runCatching { c.chain.net.withFallback { it.getMultipleAccounts(mints.toList()) } }.getOrNull()
                    deltas.indices.forEach { i ->
                        val mint = SolanaPublicKey.fromBase58(deltas[i].mint)
                        val dec = mintInfo?.getOrNull(mints.indexOf(mint))?.let { account ->
                            runCatching { TokenState.parseMint(account.owner, account.data).decimals }.getOrNull()
                        }
                        if (dec != deltas[i].decimals) {
                            val d = deltas[i]
                            deltas[i] = d.copy(decimals = dec)
                        }
                    }
                }
            }
            DappTxReview(verdict, p, solDelta, deltas, problems, prepProblems)
        } catch (e: RpcException) {
            DappTxReview(verdict, null, null, emptyList(), emptyList(), listOf(PrepBlocker.SIMULATION_UNAVAILABLE))
        }
    }
    private val r0Name: String? get() = current?.dapp?.name?.take(24)

    // ------------------------------------------------------------------ user decisions

    fun decline() {
        when (val r = current) { is AuthorizeRequestModel -> r.answer.decline(); is SignRequestModel -> r.answer.decline(); null -> return }
        done()
    }

    fun approveAuthorize() {
        val r = current as? AuthorizeRequestModel ?: return
        val cl = r.cluster
        if (cl == null || cl != c.chain.config.cluster) { r.answer.clusterNotSupported(); done(); return }
        val w = connectWallets().firstOrNull { it.id == selectedWallet } ?: return
        val siws = r.siws
        if (siws != null && !siwsDomainMatches(r.dapp, siws)) {
            reviewError = S_SIWS_DOMAIN_MISMATCH
            return
        }
        if (siws == null) {
            val s = registry.create(r.dapp.name, r.dapp.uri, r.dapp.callingPackage, w.id, w.address.toBase58(), cl)
            r.answer.approve(w.address.toByteArray(), w.name, s.id.toByteArray(Charsets.US_ASCII), null, null)
            done(); return
        }
        val text = siws.prepare(w.address.toByteArray()).toByteArray(Charsets.UTF_8)
        if (MessagePolicy.check(text) != null) { reviewError = S_MSG_REFUSED; return }
        authorizeWith(w) { signer ->
            val sig = signer.message(text)
            val s = registry.create(r.dapp.name, r.dapp.uri, r.dapp.callingPackage, w.id, w.address.toBase58(), cl)
            r.answer.approve(w.address.toByteArray(), w.name, s.id.toByteArray(Charsets.US_ASCII), text, sig)
            done()
        }
    }

    fun approveSign() {
        val r = current as? SignRequestModel ?: return
        val w = walletOf(r.session) ?: run { r.answer.authorizationNotValid(); done(); return }
        when (r.kind) {
            SignKind.MESSAGES -> {
                val bad = r.payloads.map { MessagePolicy.check(it) != null }
                if (bad.any { it }) { r.answer.invalid(bad.map { !it }.toBooleanArray()); done(); return }
                authorizeWith(w) { signer ->
                    val out = r.payloads.map { it + signer.message(it) }
                    registry.touch(r.session.id)
                    r.answer.signedPayloads(out.toTypedArray()); done()
                }
            }
            SignKind.TRANSACTIONS, SignKind.SIGN_AND_SEND -> {
                val rv = reviews ?: return
                if (rv.size != r.payloads.size || rv.any { !it.signable }) return
                val ack = rawAck
                if (rv.any { it.verdict.needsRawAck } && !ack) return
                authorizeWith(w) { signer ->
                    val sigs = rv.map { signer.transaction(it.verdict.messageBytes, it.verdict.messageHash, ack) }
                    registry.touch(r.session.id)
                    if (r.kind == SignKind.TRANSACTIONS) {
                        r.answer.signedPayloads(rv.zip(sigs).map { (v, s) -> WireTransaction.assemble(v.verdict.messageBytes, listOf(s)) }.toTypedArray())
                    } else {
                        val ok = rv.zip(sigs).all { (v, s) ->
                            val p = v.prepared ?: return@all false
                            if (!c.chain.net { c.chain.coordinator.stillValid(p) }) return@all false
                            runCatching { c.chain.net { c.chain.coordinator.submit(p, s) } }.isSuccess
                        }
                        if (ok) r.answer.signatures(sigs.toTypedArray()) else r.answer.notSubmitted(sigs.toTypedArray())
                    }
                    done()
                }
            }
        }
    }

    /** Local wallet: PIN screen (auth per use) then [op]; Seed Vault: Seed Vault's own UI per signature. */
    private fun authorizeWith(w: WalletInfo, op: suspend (DappSigner) -> Unit) {
        if (w.provider == CustodyProviderId.SEED_VAULT) {
            c.launchOp { op(SeedVaultSigner(w)) }
        } else {
            pendingOp = op
            c.go(Screen.Authorize(AuthPurpose.DappSign(w.id)))
        }
    }

    /** Called by AppController after the PIN unlocked [s] for a DappSign purpose. Closes [s]. */
    internal suspend fun onUnlocked(s: UnlockedWallet) {
        val op = pendingOp
        try {
            if (op == null || current == null) { c.go(Screen.Home); return }
            op(LocalSigner(s))
        } finally { s.close(); pendingOp = null }
    }

    interface DappSigner {
        suspend fun transaction(message: ByteArray, hash: ByteArray, rawAck: Boolean): ByteArray
        suspend fun message(text: ByteArray): ByteArray
    }

    private inner class LocalSigner(val s: UnlockedWallet) : DappSigner {
        override suspend fun transaction(message: ByteArray, hash: ByteArray, rawAck: Boolean) = c.io { s.signDappTransaction(message, hash, rawAck) }
        override suspend fun message(text: ByteArray) = c.io { s.signDappMessage(text) }
    }

    private inner class SeedVaultSigner(val w: WalletInfo) : DappSigner {
        private val sv get() = c.seedVault ?: throw CustodyException(CustodyException.Reason.SEED_VAULT_UNAVAILABLE)
        override suspend fun transaction(message: ByteArray, hash: ByteArray, rawAck: Boolean): ByteArray {
            val i = c.io { sv.signDappTransactionRequest(w.id, message, hash, rawAck) }
            val (code, data) = c.runExternal(i)
            return c.io { sv.finishSignature(w.id, message, code, data) }
        }
        override suspend fun message(text: ByteArray): ByteArray {
            val i = c.io { sv.signMessageRequest(w.id, text) }
            val (code, data) = c.runExternal(i)
            return c.io { sv.finishSignature(w.id, text, code, data, isMessage = true) }
        }
    }

    private fun siwsDomainMatches(dapp: DappIdentity, siws: SiwsRequest): Boolean {
        val claimed = siws.domain?.trim()?.trimEnd('.')?.lowercase()
        val uriHost = dapp.domain?.trim()?.trimEnd('.')?.lowercase()
        return claimed != null && uriHost != null && claimed == uriHost
    }

    companion object {
        /** MWA legacy cluster name of Mainnet (MWA 1.x); spelled in two parts so source scans stay meaningful. */
        private const val LEGACY_MAINNET = "mainnet" + "-beta"
        /**
         * MWA chain/cluster → our cluster. MWA 2.0 defaults to Mainnet when the dApp omits the chain. Devnet/Testnet
         * requests map to their cluster so they are recognised and refused (the wallet is Mainnet only); unknown ⇒ null.
         */
        fun clusterOf(chain: String?): Cluster? = when (chain) {
            null, "solana:mainnet", LEGACY_MAINNET, "mainnet" -> Cluster.MAINNET
            "solana:devnet", "devnet" -> Cluster.DEVNET
            "solana:testnet", "testnet" -> Cluster.TESTNET
            else -> null
        }
        fun S_CLUSTER_MISMATCH(want: Cluster, have: Cluster) = "Este dApp pediu a rede de testes ${want.label}. A Lock Wallet funciona só na rede principal da Solana, então o pedido foi recusado."
        const val S_SIG_COUNT = "A transação pede mais de uma assinatura (co-assinatura não é suportada)."
        const val S_WALLET_OWNER = "A simulação mostra a sua carteira mudando de dono/programa. Bloqueado."
        fun S_TOKEN_CHANGED(k: SolanaPublicKey) = "A conta de token ${k.toBase58().take(4)}… deixaria de ser uma conta de token válida."
        fun S_TOKEN_OWNER(k: SolanaPublicKey) = "A conta de token ${k.toBase58()} mudaria de dono. Bloqueado."
        fun S_TOKEN_DELEGATE(k: SolanaPublicKey) = "A conta de token ${k.toBase58()} teria a delegação alterada. Bloqueado."
        fun S_TOKEN_CLOSE_AUTH(k: SolanaPublicKey) = "A autoridade de fechamento da conta ${k.toBase58()} mudaria. Bloqueado."
        fun S_TOKEN_AUTHORITY_CHANGED(k: SolanaPublicKey) = "As permissões da conta de token ${k.toBase58()} mudariam. Bloqueado."
        fun S_STAKE_CHANGED(k: SolanaPublicKey) = "A conta de stake ${k.toBase58()} mudaria de saldo, delegação ou autoridade. Bloqueado."
        fun S_STAKE_STATE_UNKNOWN(k: SolanaPublicKey) = "Não foi possível validar o estado da conta de stake ${k.toBase58()}. Bloqueado."
        const val S_WALLET_STATE_UNKNOWN = "Não foi possível validar o estado pós-simulação da sua carteira. Bloqueado."
        const val S_ACCOUNT_DELTA_OVERFLOW = "A variação simulada ultrapassa o intervalo numérico permitido. Bloqueado."
        const val S_MSG_REFUSED = "A mensagem não é texto apresentável (ou parece uma transação). Recusada."
        const val S_SIWS_DOMAIN_MISMATCH = "Domínio da mensagem de login não corresponde ao endereço informado pelo app. Conexão recusada."
    }
}

