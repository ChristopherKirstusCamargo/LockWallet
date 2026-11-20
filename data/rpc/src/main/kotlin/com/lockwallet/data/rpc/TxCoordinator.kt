package com.lockwallet.data.rpc

import com.lockwallet.core.solana.Base58
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.analyzer.Analysis
import com.lockwallet.core.solana.analyzer.AnalysisContext
import com.lockwallet.core.solana.analyzer.TransactionAnalyzer
import com.lockwallet.core.solana.tx.ExpiryEvidence
import com.lockwallet.core.solana.tx.Instruction
import com.lockwallet.core.solana.tx.LegacyMessage
import com.lockwallet.core.solana.tx.TransactionState
import com.lockwallet.core.solana.tx.TransactionState.AWAITING_AUTHORIZATION
import com.lockwallet.core.solana.tx.TransactionState.CANCELLED
import com.lockwallet.core.solana.tx.TransactionState.CONFIRMED
import com.lockwallet.core.solana.tx.TransactionState.DRAFT
import com.lockwallet.core.solana.tx.TransactionState.EXPIRED
import com.lockwallet.core.solana.tx.TransactionState.FAILED
import com.lockwallet.core.solana.tx.TransactionState.FINALIZED
import com.lockwallet.core.solana.tx.TransactionState.PREPARED
import com.lockwallet.core.solana.tx.TransactionState.PROCESSED
import com.lockwallet.core.solana.tx.TransactionState.SIGNED
import com.lockwallet.core.solana.tx.TransactionState.SIMULATED
import com.lockwallet.core.solana.tx.TransactionState.SUBMITTED
import com.lockwallet.core.solana.tx.TransactionState.UNKNOWN
import com.lockwallet.core.solana.tx.TransactionStateMachine
import com.lockwallet.core.solana.tx.WireTransaction
import java.util.Base64

/** What the user asked for, plus the exact instructions that implement it. */
class TxIntent(
    val kind: TxKind,
    val wallet: SolanaPublicKey,
    val summary: String,
    val counterparty: String?,
    val amountUnits: Long?,
    val decimals: Int?,
    val mint: String?,
    val instructions: List<Instruction>,
    val context: AnalysisContext = AnalysisContext(),
    /**
     * Mainnet landing: when true the coordinator prepends SetComputeUnitLimit + SetComputeUnitPrice. [budget] fixes
     * both (so the caller can pre-compute the exact fee, e.g. "send max"); null = limit measured by a probe simulation
     * and price from recent prioritization fees.
     */
    val autoBudget: Boolean = false,
    val budget: ComputeBudget? = null,
)

/** Compute budget the wallet attaches to its own transactions. */
data class ComputeBudget(val unitLimit: Long, val microLamportsPerUnit: Long) {
    init { require(unitLimit in 1..com.lockwallet.core.solana.tx.FeeMath.MAX_UNITS && microLamportsPerUnit >= 0) }
    val priorityLamports: Long get() = com.lockwallet.core.solana.tx.FeeMath.priority(unitLimit, microLamportsPerUnit)
    /** Exact network fee of a single-signature message carrying this budget. */
    val totalFeeLamports: Long get() = com.lockwallet.core.solana.tx.FeeMath.LAMPORTS_PER_SIGNATURE + priorityLamports
    fun instructions(): List<Instruction> = listOf(
        com.lockwallet.core.solana.ix.ComputeBudgetIx.setUnitLimit(unitLimit),
        com.lockwallet.core.solana.ix.ComputeBudgetIx.setUnitPrice(microLamportsPerUnit),
    )
}

enum class PrepBlocker { BUSY, TOO_LARGE, ANALYZER, FEE_UNAVAILABLE, SIMULATION_FAILED, SIMULATION_UNAVAILABLE, BLOCKHASH_INVALID, SIMULATION_SINGLE_SOURCE, SIMULATION_DIVERGED, SIMULATION_ACCOUNT_LIMIT }

/** A built, analysed and simulated transaction awaiting the user's authorization. Never persisted unsigned. */
class PreparedTx internal constructor(
    val intent: TxIntent,
    val cluster: Cluster,
    val analysis: Analysis,
    val feeLamports: Long?,
    val simulation: SimulationResult?,
    val lastValidBlockHeight: Long,
    val blockers: List<PrepBlocker>,
    state: TransactionState,
) {
    var state: TransactionState = state
        internal set
    val signable: Boolean get() = blockers.isEmpty() && state == AWAITING_AUTHORIZATION
    /** Exact bytes the custody layer must sign (identical to what was analysed and shown). */
    val messageBytes: ByteArray get() = analysis.messageBytes
    val messageHash: ByteArray get() = analysis.messageHash
    /** Payer lamports after the simulated execution, if the RPC returned it. */
    val simulatedPayerLamports: Long? get() = simulation?.accounts?.firstOrNull()?.lamports
}

/**
 * Drives the unified state machine (ADR 0011) against RPC: build → analyse → fee → simulate → (sign in custody)
 * → persist → send → reconcile. Never rebuilds a transaction whose outcome is uncertain; rebroadcasts only
 * the same signed bytes while the blockhash is valid; EXPIRED only with evidence. Blocking API.
 */
class TxCoordinator(private val net: NetworkManager, private val journal: TxJournal, private val now: () -> Long = System::currentTimeMillis) {
    private val enc = Base64.getEncoder()

    companion object {
        /** Micro-lamports per compute unit. Floor keeps landing when fee markets report 0; cap bounds cost. */
        const val PRICE_FLOOR = 20_000L
        const val PRICE_CAP = 2_000_000L
        /** Ceiling of the priority fee on the wallet's own transactions: 0.001 SOL. */
        const val MAX_OWN_PRIORITY = 1_000_000L
        /** Limit used when the probe simulation does not report consumed units. */
        const val DEFAULT_UNITS = 200_000L
        /** Fixed limit for a plain SOL transfer (system transfer + 2 compute-budget instructions ≈ 450 CU). */
        const val SOL_TRANSFER_UNITS = 1_500L
        /** Wallet + at most 15 additional writable accounts can be post-state checked in one RPC simulation. */
        const val MAX_EXTERNAL_WATCH_ACCOUNTS = 16
    }

    fun pendingFor(wallet: SolanaPublicKey): TxRecord? =
        journal.all().lastOrNull { it.wallet == wallet.toBase58() && it.cluster == net.config.cluster && it.unresolved }

    /**
     * Price per compute unit for a transaction writing [writable]: 75th percentile of recent non-zero per-slot
     * minimums, at least [PRICE_FLOOR], at most [PRICE_CAP]. Never fails (fallback = floor): fee data is advisory.
     */
    fun priorityPrice(writable: List<SolanaPublicKey>): Long {
        val fees = try { net.withFallback { it.getRecentPrioritizationFees(writable.distinct().take(64)) } } catch (_: RpcException) { emptyList() }
        val nz = fees.filter { it > 0 }.sorted()
        val p75 = if (nz.isEmpty()) 0L else nz[minOf(nz.size * 3 / 4, nz.size - 1)]
        return p75.coerceIn(PRICE_FLOOR, PRICE_CAP)
    }

    /** Budget with [unitLimit] and the current price, clamped so the priority fee never exceeds [MAX_OWN_PRIORITY]. */
    fun budgetFor(unitLimit: Long, writable: List<SolanaPublicKey>): ComputeBudget {
        val price = priorityPrice(writable)
        val maxPrice = MAX_OWN_PRIORITY * 1_000_000 / unitLimit
        return ComputeBudget(unitLimit, minOf(price, maxPrice))
    }

    private fun writableKeys(ixs: List<Instruction>, payer: SolanaPublicKey) =
        (listOf(payer) + ixs.flatMap { ix -> ix.accounts.filter { it.writable }.map { it.key } }).distinct()

    /** Probe-simulates with the maximum limit to measure compute units, then sizes the limit with a 20 % margin. */
    private fun measuredBudget(intent: TxIntent, bh: com.lockwallet.core.solana.tx.Blockhash): ComputeBudget {
        val writable = writableKeys(intent.instructions, intent.wallet)
        val probe = ComputeBudget(com.lockwallet.core.solana.tx.FeeMath.MAX_UNITS, 0).instructions() + intent.instructions
        val units = try {
            val m = LegacyMessage.compile(intent.wallet, bh, probe)
            net.withFallback { it.simulate(WireTransaction.unsigned(m), listOf(intent.wallet)) }.unitsConsumed
        } catch (_: RpcException) { null } catch (_: IllegalArgumentException) { null }
        val limit = ((units ?: DEFAULT_UNITS) * 6 / 5 + 1_000).coerceIn(1_000, com.lockwallet.core.solana.tx.FeeMath.MAX_UNITS)
        return budgetFor(limit, writable)
    }

    fun prepare(intent: TxIntent): PreparedTx {
        var st = TransactionStateMachine.transition(DRAFT, PREPARED)
        val cluster = net.config.cluster
        val bh = net.withFallback { it.getLatestBlockhash() }
        val budget = if (!intent.autoBudget) null else intent.budget ?: measuredBudget(intent, bh.blockhash)
        val ixs = (budget?.instructions() ?: emptyList()) + intent.instructions
        val msg = LegacyMessage.compile(intent.wallet, bh.blockhash, ixs).serialize()
        val analysis = TransactionAnalyzer.analyze(msg, intent.wallet, intent.context)
        val blockers = mutableListOf<PrepBlocker>()
        if (pendingFor(intent.wallet) != null) blockers += PrepBlocker.BUSY
        if (msg.size + 1 + 64 > LegacyMessage.MAX_TX_SIZE) blockers += PrepBlocker.TOO_LARGE
        if (!analysis.signable) blockers += PrepBlocker.ANALYZER
        if (blockers.isNotEmpty()) return PreparedTx(intent, cluster, analysis, null, null, bh.lastValidBlockHeight, blockers, TransactionStateMachine.transition(st, CANCELLED))
        val fee = shownFee(analysis.maxFeeLamports, try { net.withFallback { it.getFeeForMessage(msg) } } catch (_: RpcException) { null })
        if (fee == null) blockers += PrepBlocker.FEE_UNAVAILABLE
        val sim = try {
            net.withFallback { it.simulate(WireTransaction.unsigned(LegacyMessage.parse(msg)), listOf(intent.wallet)) }
        } catch (_: RpcException) { null }
        when {
            sim == null -> blockers += PrepBlocker.SIMULATION_UNAVAILABLE
            sim.err != null -> blockers += PrepBlocker.SIMULATION_FAILED
        }
        if (blockers.isNotEmpty()) return PreparedTx(intent, cluster, analysis, fee, sim, bh.lastValidBlockHeight, blockers, TransactionStateMachine.transition(st, CANCELLED))
        st = TransactionStateMachine.transition(st, SIMULATED)
        st = TransactionStateMachine.transition(st, AWAITING_AUTHORIZATION)
        return PreparedTx(intent, cluster, analysis, fee, sim, bh.lastValidBlockHeight, emptyList(), st)
    }

    /**
     * Lock Connect (Phase H): a message built by a dApp. The bytes are taken as-is (never rebuilt); the dApp firewall
     * is the semantic gate (the caller passes its verdict), here we add: blockhash still valid at the node, fee,
     * and a mandatory successful simulation that reports [watch] accounts' post-state (wallet first).
     * lastValidBlockHeight is not knowable from a foreign blockhash: we use current height + 150 (upper bound; only
     * used to stop rebroadcasting and never to declare EXPIRED without evidence).
     */
    fun prepareExternal(wallet: SolanaPublicKey, message: ByteArray, summary: String, watch: List<SolanaPublicKey>, firewallOk: Boolean, crossCheck: Boolean = false): PreparedTx {
        var st = TransactionStateMachine.transition(DRAFT, PREPARED)
        val cluster = net.config.cluster
        val analysis = TransactionAnalyzer.analyze(message, wallet)
        val intent = TxIntent(TxKind.DAPP, wallet, summary, null, null, null, null, emptyList())
        val blockers = mutableListOf<PrepBlocker>()
        val msg = analysis.message ?: LegacyMessage.parse(message)
        if (pendingFor(wallet) != null) blockers += PrepBlocker.BUSY
        if (message.size + 1 + 64 > LegacyMessage.MAX_TX_SIZE) blockers += PrepBlocker.TOO_LARGE
        if (!firewallOk || msg.feePayer != wallet || msg.numRequiredSignatures != 1) blockers += PrepBlocker.ANALYZER
        // Never silently truncate the set whose post-state the review promises to inspect.
        val w = (listOf(wallet) + watch.filter { it != wallet }).distinct()
        if (w.size > MAX_EXTERNAL_WATCH_ACCOUNTS) blockers += PrepBlocker.SIMULATION_ACCOUNT_LIMIT
        val valid = try { net.withFallback { it.isBlockhashValid(msg.recentBlockhash) } } catch (_: RpcException) { false }
        val height = try { net.withFallback { it.getBlockHeight() } } catch (_: RpcException) { null }
        if (!valid || height == null) blockers += PrepBlocker.BLOCKHASH_INVALID
        val lvbh = (height ?: 0L) + 150
        if (blockers.isNotEmpty()) return PreparedTx(intent, cluster, analysis, null, null, lvbh, blockers, TransactionStateMachine.transition(st, CANCELLED))
        val fee = shownFee(com.lockwallet.core.solana.tx.FeeMath.maxFee(msg), try { net.withFallback { it.getFeeForMessage(message) } } catch (_: RpcException) { null })
        if (fee == null) blockers += PrepBlocker.FEE_UNAVAILABLE
        val sim: SimulationResult?
        if (crossCheck) {
            // Not fully decoded: one node's word is not enough. Two independent, genesis-verified endpoints must both
            // simulate and agree on every watched account's post-state (SOL, owner, token data); otherwise blocked.
            val wire = WireTransaction.unsigned(msg)
            val p = try { net.primary().simulate(wire, w) } catch (_: RpcException) { null }
            val s2 = if (net.config.secondaryUrl == null || net.config.secondaryUrl == net.config.primaryUrl) null
                else try { net.secondaryOrNull()?.simulate(wire, w) } catch (_: RpcException) { null }
            sim = p ?: s2
            if (p == null || s2 == null) { if (p != null || s2 != null) blockers += PrepBlocker.SIMULATION_SINGLE_SOURCE }
            else if (p.err != s2.err || p.accounts != s2.accounts) blockers += PrepBlocker.SIMULATION_DIVERGED
        } else sim = try { net.withFallback { it.simulate(WireTransaction.unsigned(msg), w) } } catch (_: RpcException) { null }
        when {
            PrepBlocker.SIMULATION_DIVERGED in blockers || PrepBlocker.SIMULATION_SINGLE_SOURCE in blockers -> Unit
            sim == null -> blockers += PrepBlocker.SIMULATION_UNAVAILABLE
            sim.err != null -> blockers += PrepBlocker.SIMULATION_FAILED
            sim.accounts.size != w.size -> blockers += PrepBlocker.SIMULATION_UNAVAILABLE
        }
        if (blockers.isNotEmpty()) return PreparedTx(intent, cluster, analysis, fee, sim, lvbh, blockers, TransactionStateMachine.transition(st, CANCELLED))
        st = TransactionStateMachine.transition(st, SIMULATED)
        st = TransactionStateMachine.transition(st, AWAITING_AUTHORIZATION)
        return PreparedTx(intent, cluster, analysis, fee, sim, lvbh, emptyList(), st)
    }

    /**
     * Fee shown and budgeted: the larger of the bound computed from the bytes and the RPC's answer, so a lying RPC
     * can never make a transaction look cheaper than the protocol allows. Unknown only if neither is available.
     */
    private fun shownFee(local: Long?, rpc: Long?): Long? = when {
        local == null -> rpc
        rpc == null -> local
        else -> maxOf(local, rpc)
    }

    /** True while the prepared blockhash can still land; checked right before asking for authorization. */
    fun stillValid(p: PreparedTx): Boolean = try { net.withFallback { it.getBlockHeight() } <= p.lastValidBlockHeight } catch (_: RpcException) { false }

    fun cancel(p: PreparedTx) { if (!p.state.terminal && !p.state.signed) p.state = TransactionStateMachine.transition(p.state, CANCELLED) }

    /** Persists the signed bytes (SIGNED) before the first submit, then sends. Returns the stored record. */
    fun submit(p: PreparedTx, signature: ByteArray): TxRecord {
        check(p.signable) { "not authorizable" }
        check(net.config.cluster == p.cluster) { "network changed" }
        require(signature.size == 64)
        val tx = WireTransaction.assemble(p.messageBytes, listOf(signature))
        p.state = TransactionStateMachine.transition(p.state, SIGNED)
        val t = now()
        var rec = TxRecord(Base58.encode(signature), p.cluster, p.intent.wallet.toBase58(), p.intent.kind, p.intent.summary, p.intent.counterparty,
            p.intent.amountUnits, p.intent.decimals, p.intent.mint, enc.encodeToString(tx), p.lastValidBlockHeight, p.feeLamports,
            SIGNED, t, t, 0, 0, null)
        journal.put(rec)
        rec = broadcast(rec, tx)
        p.state = rec.state
        return rec
    }

    /**
     * Sends the same signed bytes to every configured endpoint (primary + secondary) to improve landing on Mainnet.
     * Accepted by at least one with the expected signature → SUBMITTED; otherwise UNKNOWN (reconciled later).
     */
    private fun broadcast(r: TxRecord, tx: ByteArray): TxRecord {
        val t = now()
        var accepted = false; var diverged = false; var lastError: String? = null
        for (c in net.broadcastClients()) {
            try {
                val sig = c.sendTransaction(tx)
                if (sig == r.signature) accepted = true else diverged = true
            } catch (e: RpcException) { if (lastError == null) lastError = e.message?.take(200) }
        }
        val next = when {
            // Even a preflight rejection is not proof of non-inclusion against a hostile RPC: stay UNKNOWN and reconcile.
            accepted -> r.copy(state = if (r.state == SIGNED) move(r.state, SUBMITTED) else r.state, detail = null)
            diverged -> r.copy(state = if (r.state == SIGNED) move(r.state, UNKNOWN) else r.state, detail = "assinatura divergente do RPC")
            else -> r.copy(state = if (r.state == SIGNED) move(r.state, UNKNOWN) else r.state, detail = lastError ?: "sem endpoint disponível")
        }.copy(updatedAtMs = t, lastBroadcastMs = t, broadcasts = r.broadcasts + 1)
        journal.put(next)
        return next
    }

    /** One reconciliation step for a signed, non-final record. */
    fun reconcile(r: TxRecord): TxRecord {
        if (!r.state.signed) return r
        if (r.state == EXPIRED || (r.state.terminal && r.statusVerified)) return r
        val t = now()
        // Legacy releases accepted terminal status from one RPC. Reopen any such record until two sources
        // independently re-confirm it; this migration deliberately prefers UNKNOWN over a possible double-send.
        val base = if (r.state in setOf(FAILED, FINALIZED, CONFIRMED) && !r.statusVerified) {
            r.copy(state = UNKNOWN, detail = "status antigo aguardando confirmação independente").also { journal.put(it) }
        } else r
        val primary = try { net.primary() } catch (_: RpcException) { return base }
        val primaryStatus = try { primary.getSignatureStatuses(listOf(base.signature), true).single() } catch (_: RpcException) { return base }
        // A single endpoint may lie about inclusion or failure. No status is trusted unless the configured
        // secondary is genesis-verified and returns the same slot, err and confirmation state.
        val secondary = net.secondaryOrNull() ?: return base
        val secondaryStatus = try { secondary.getSignatureStatuses(listOf(base.signature), true).single() } catch (_: RpcException) { return base }
        if (primaryStatus != secondaryStatus) return base
        if (primaryStatus != null) {
            val target = when {
                primaryStatus.err != null -> FAILED
                primaryStatus.confirmationStatus == "finalized" -> FINALIZED
                primaryStatus.confirmationStatus == "confirmed" -> CONFIRMED
                else -> PROCESSED
            }
            if (target == base.state) {
                if (base.statusVerified) return base
                return base.copy(statusVerified = true, detail = primaryStatus.err ?: base.detail, updatedAtMs = t).also { journal.put(it) }
            }
            val from = if (base.state == SIGNED) move(base.state, UNKNOWN) else base.state
            if (!TransactionStateMachine.canTransition(from, target)) return base
            return base.copy(state = move(from, target), statusVerified = true,
                detail = primaryStatus.err ?: base.detail, updatedAtMs = t).also { journal.put(it) }
        }
        val lvbh = base.lastValidBlockHeight ?: return base
        val height = try { primary.getBlockHeight() } catch (_: RpcException) { return base }
        val tx = base.signedTxB64?.let { Base64.getDecoder().decode(it) }
        if (TransactionStateMachine.mayRebroadcastSameBytes(base.state, height, lvbh)) {
            if (tx != null && t - base.lastBroadcastMs >= 2_000) return broadcast(base, tx)
            return base
        }
        if (height <= lvbh) return base
        // Both sources returned no inclusion. Expiry still requires both to report height past the blockhash
        // and blockhash invalidity; no single-source negative status can authorize a replacement payment.
        val bh = tx?.let { b -> runCatching { LegacyMessage.parse(WireTransaction.split(b).second).recentBlockhash }.getOrNull() } ?: return base
        val primaryDead = try { !primary.isBlockhashValid(bh) } catch (_: RpcException) { return base }
        if (!primaryDead) return base
        val secondaryHeight: Long
        val secondaryDead: Boolean
        try {
            secondaryHeight = secondary.getBlockHeight()
            secondaryDead = !secondary.isBlockhashValid(bh)
        } catch (_: RpcException) { return base }
        if (secondaryHeight <= lvbh || !secondaryDead) return base
        val ev = ExpiryEvidence(
            currentBlockHeight = minOf(height, secondaryHeight),
            lastValidBlockHeight = lvbh,
            historySearched = true,
            independentSourcesWithoutInclusion = 2,
            sourcesDiverged = false,
        )
        val from = if (base.state == SIGNED) move(base.state, UNKNOWN) else base.state
        if (!ev.sufficient || !TransactionStateMachine.canTransition(from, EXPIRED)) {
            return if (from != base.state) base.copy(state = from, updatedAtMs = t).also { journal.put(it) } else base
        }
        return base.copy(state = TransactionStateMachine.transition(from, EXPIRED, ev), detail = "blockhash expirou sem inclusão", updatedAtMs = t).also { journal.put(it) }
    }

    fun records(wallet: SolanaPublicKey): List<TxRecord> =
        journal.all().filter { it.wallet == wallet.toBase58() && it.cluster == net.config.cluster }.sortedByDescending { it.createdAtMs }

    private fun move(from: TransactionState, to: TransactionState) = TransactionStateMachine.transition(from, to)
}

/** Same intent with the Mainnet compute budget attached (fixed [budget], or measured when null). */
fun TxIntent.withAutoBudget(budget: ComputeBudget? = this.budget) =
    TxIntent(kind, wallet, summary, counterparty, amountUnits, decimals, mint, instructions, context, autoBudget = true, budget = budget)
