package com.lockwallet.app

import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.data.rpc.ValidatorInfo

/**
 * Curated mainnet validators shown first on "Novo stake" (ADR 0022).
 *
 * The list is a fixed, reviewed constant — never fetched from a server. What IS live is each entry's
 * health, re-read from getVoteAccounts: an entry that is delinquent or missing from the vote list is
 * hidden, and one whose commission rose above its recorded baseline (+[JUMP_POINTS]) or above
 * [MAX_COMMISSION] is shown but cannot be picked. Native staking only — none of these is a pool.
 */
data class CuratedValidator(val name: String, val vote: String, val node: String, val baselineCommission: Int, val since: String)

enum class CuratedStatus { OK, CONCENTRATED, COMMISSION_UP, DELINQUENT, MISSING }

/**
 * [effectiveBps]: commission actually charged on inflation rewards, in basis points (worst of the two on-chain fields).
 * [creditRatio]: reward per staked lamport over the last completed epochs ÷ the network reference (0..1); null = unknown.
 * [returnIndex]: (1 − commission) × creditRatio — share of the maximum possible reward the delegator keeps; null = unknown.
 * All from getVoteAccounts only. MEV commission is not exposed by that RPC, so it is not included (never guessed).
 */
data class CuratedView(val entry: CuratedValidator, val live: ValidatorInfo?, val status: CuratedStatus,
                       val effectiveBps: Int? = null, val creditRatio: Double? = null, val returnIndex: Double? = null) {
    /** Can be picked from the curated block. CONCENTRATED stays pickable but is flagged. */
    val selectable get() = status == CuratedStatus.OK || status == CuratedStatus.CONCENTRATED
    val hidden get() = status == CuratedStatus.DELINQUENT || status == CuratedStatus.MISSING
}

object Curated {
    /** Absolute ceiling, in percent. Above it an entry is never offered as "recommended". */
    const val MAX_COMMISSION = 10
    /** Rise over the recorded baseline (percentage points) that counts as a "commission jump". */
    const val JUMP_POINTS = 2

    /** Verified on-chain 2026-11-19 (epoch 1091) via getVoteAccounts — see docs/adr/0022-validadores-curados.md. */
    val LIST = listOf(
        CuratedValidator("Shinobi Systems", "BLADE1qNA1uNjRgER6DtUFf7FU3c1TWLLdpPeEcKatZ2", "Ninja1spj6n9t5hVYgF3PdnYz2PLnkt7rvaw3firmjs", 0, "desde a época 160"),
        CuratedValidator("Solana Compass", "EARNynHRWg6GfyJCmrrizcZxARB3HVzcaasvNa8kBS72", "CMPSSdrTnRQBiBGTyFpdCc3VMNuLWYWaSkE8Zh5z6gbd", 0, "desde a época 242"),
        CuratedValidator("Chainflow", "CAf8jfgqhia5VNrEF4A7Y9VLD3numMq9DVSceq7cPhNY", "2mMGsb5uy1Q4Dvezr8HK2E8SJoChcb2X7b61tJPaVHHd", 5, "desde a época 67"),
        CuratedValidator("Overclock", "AS3nKBQfKs8fJ8ncyHrdvo4FDT6S8HMRhD75JjCcyr1t", "C1ocKDYMCm2ooWptMMnpd5VEB2Nx4UMJgRuYofysyzcA", 0, "desde a época 318"),
        CuratedValidator("Stakeconomy", "GNZ1PAAS33davY4Q1BMEpZEpVBtRtGvSpcTH5wYVkkVt", "4QNekaDqrLmUENqkVhGCJrgHziPxkX9kridbKwunx9su", 2, "desde a época 174"),
        CuratedValidator("Nansen", "2NxEEbhqqj1Qptq5LXLbDTP5tLa9f7PqkU8zNgxbGU9P", "CoG8d9Fp2TFJRkAmrPMiPsGhQWHzdTTVoegEp9svRgmJ", 0, "desde a época 302"),
    )

    fun find(vote: String): CuratedValidator? = LIST.firstOrNull { it.vote == vote }

    /** Smallest set of the largest validators that together hold more than 1/3 of active stake. */
    fun superminority(live: List<ValidatorInfo>): Set<SolanaPublicKey> {
        // Doubles, not Longs: a hostile RPC sending absurd stakes must not overflow into a wrong (or empty) answer.
        val active = live.filter { !it.delinquent && it.activatedStake > 0 }.sortedByDescending { it.activatedStake }
        val total = active.sumOf { it.activatedStake.toDouble() }
        if (total <= 0.0) return emptySet()
        val out = HashSet<SolanaPublicKey>(); var acc = 0.0
        for (v in active) { if (acc * 3 > total) break; out += v.vote; acc += v.activatedStake.toDouble() }
        return out
    }

    /** Completed epochs used for the performance measure (the newest epoch in the answer is still running). */
    const val PERF_EPOCHS = 3

    /**
     * Alpenglow activation on mainnet (epoch 1064, 2026-10-21). Since then the vote account's `epochCredits` counter
     * holds **reward lamports** (voter + leader share), which grow with stake — no longer vote credits. Epochs up to
     * and including the migration epoch are in other units (and carry a sentinel row) and are never used.
     */
    const val ALPENGLOW_EPOCH = 1064L

    /** Worst-case commission in bps from the two on-chain fields (percent and, on newer nodes, bps). */
    fun effectiveBps(v: ValidatorInfo): Int = maxOf(v.commission.coerceIn(0, 100) * 100, v.commissionBps?.coerceIn(0, 10_000) ?: 0)

    /**
     * Network reference reward rate per completed epoch: the **stake-weighted median** of (earned ÷ active stake)
     * across healthy validators. Weighting by stake means a hostile entry with tiny stake and a huge number cannot
     * move the bar; the median means half the network's stake earns at least this rate. Epochs with fewer than 3
     * samples are ignored. Active stake is today's value (rewards use the stake snapshot of the previous epoch — an
     * approximation that is equal for everybody).
     */
    fun referenceRates(live: List<ValidatorInfo>): Map<Long, Double> {
        val running = live.flatMap { v -> v.epochCredits.map { it.epoch } }.filter { it > ALPENGLOW_EPOCH }.maxOrNull() ?: return emptyMap()
        val byEpoch = HashMap<Long, MutableList<Pair<Double, Double>>>()
        for (v in live) if (!v.delinquent && v.activatedStake > 0) for (c in v.epochCredits)
            if (c.epoch < running && c.epoch >= running - PERF_EPOCHS && c.epoch > ALPENGLOW_EPOCH && c.earned > 0) {
                val stake = v.activatedStake.toDouble()
                byEpoch.getOrPut(c.epoch) { ArrayList() } += (c.earned.toDouble() / stake) to stake
            }
        return byEpoch.filterValues { it.size >= 3 }.mapValues { (_, l) ->
            val sorted = l.sortedBy { it.first }; val half = sorted.sumOf { it.second } / 2
            var acc = 0.0; var m = sorted.last().first
            for ((r, w) in sorted) { acc += w; if (acc >= half) { m = r; break } }
            m
        }.filterValues { it > 0.0 && it.isFinite() }
    }

    /**
     * Reward rate ÷ network reference over the reference epochs (missing epoch = nothing earned), capped at 100 %
     * (luck in the leader schedule is not skill). Inconsistent data ⇒ null (unknown, never an advantage).
     */
    fun creditRatio(v: ValidatorInfo, ref: Map<Long, Double>): Double? {
        if (ref.isEmpty() || v.epochCredits.isEmpty() || v.activatedStake <= 0) return null // field omitted ⇒ unknown
        val mine = HashMap<Long, Long>()
        for (c in v.epochCredits) {
            if (c.earned < 0 || c.credits < 0 || c.previous < 0) return null
            if (mine.put(c.epoch, c.earned) != null) return null // same epoch twice: contradictory
        }
        val stake = v.activatedStake.toDouble()
        var got = 0.0; var want = 0.0
        for ((e, r) in ref) { got += minOf((mine[e] ?: 0L).toDouble() / stake, r); want += r }
        return if (want > 0 && got.isFinite()) (got / want).coerceIn(0.0, 1.0) else null
    }

    fun status(e: CuratedValidator, v: ValidatorInfo?, superminority: Set<SolanaPublicKey>): CuratedStatus = when {
        v == null -> CuratedStatus.MISSING
        // Node identity must still match what was reviewed; a swapped identity is treated as unknown.
        v.node.toBase58() != e.node -> CuratedStatus.MISSING
        v.commission !in 0..100 -> CuratedStatus.MISSING // impossible value ⇒ untrusted answer
        v.delinquent -> CuratedStatus.DELINQUENT
        effectiveBps(v) > MAX_COMMISSION * 100 || effectiveBps(v) > (e.baselineCommission + JUMP_POINTS) * 100 -> CuratedStatus.COMMISSION_UP
        v.vote in superminority -> CuratedStatus.CONCENTRATED
        else -> CuratedStatus.OK
    }

    /** Safety tier: always decides first (return never lifts an unsafe validator above a safe one). */
    fun tier(st: CuratedStatus) = when (st) { CuratedStatus.OK -> 0; CuratedStatus.CONCENTRATED -> 1; CuratedStatus.COMMISSION_UP -> 2; else -> 3 }

    /**
     * Order: safety tier → known return before unknown → higher return index (rounded to 0,1 %, so noise is a tie) →
     * lower commission → better performance → fixed list order (deterministic). Pure: safe off the main thread.
     */
    val ORDER: Comparator<CuratedView> = compareBy<CuratedView> { tier(it.status) }
        .thenBy { if (it.returnIndex == null) 1 else 0 }
        .thenByDescending { it.returnIndex?.let { r -> Math.round(r * 1000) } ?: 0L }
        .thenBy { it.effectiveBps ?: Int.MAX_VALUE }
        .thenByDescending { it.creditRatio ?: -1.0 }
        .thenBy { LIST.indexOf(it.entry) }

    fun evaluate(live: List<ValidatorInfo>): List<CuratedView> {
        val groups = live.groupBy { it.vote.toBase58() }
        val sm = superminority(live)
        val ref = referenceRates(live)
        return LIST.map { e ->
            val seen = groups[e.vote].orEmpty()
            // The same vote account twice (e.g. once delinquent, once healthy) is a contradictory answer: fail closed.
            val v = seen.singleOrNull()
            val st = if (seen.size > 1) CuratedStatus.MISSING else status(e, v, sm)
            if (v == null || st == CuratedStatus.MISSING) CuratedView(e, null, st)
            else {
                val bps = effectiveBps(v); val cr = creditRatio(v, ref)
                CuratedView(e, v, st, bps, cr, cr?.let { (1.0 - bps / 10_000.0) * it })
            }
        }.sortedWith(ORDER)
    }
}
