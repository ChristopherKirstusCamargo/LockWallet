package com.lockwallet.core.solana.tx

/**
 * Unified transaction state machine (spec v2.0 §13.1 + v2.1 §6 + v2.2 §7.1, errata E-05 / ADR 0011).
 * Pure and deterministic; persistence/actor/compare-and-set live in the data layer (v2.2 SEC-11).
 *
 * v2.2 AIRGAP names map onto this machine instead of forming a second one:
 *   INTENT -> DRAFT (PaymentIntent, not a transaction) · PREPARED -> PREPARED · AWAITING_SIGNATURE ->
 *   AWAITING_AUTHORIZATION · SIGNED / READY_TO_RELAY -> SIGNED (relay readiness is a flag, not a state) ·
 *   SUBMITTED/UNKNOWN, PROCESSED/CONFIRMED/FINALIZED, FAILED -> same · EXPIRED -> EXPIRED ·
 *   CONSUMED/INVALIDATED -> reserved for future authorization providers (J6), not representable today.
 */
enum class TransactionState(val terminal: Boolean, val signed: Boolean) {
    DRAFT(false, false),
    PREPARED(false, false),
    SIMULATED(false, false),
    AWAITING_AUTHORIZATION(false, false),
    SIGNED(false, true),
    SUBMITTED(false, true),
    PROCESSED(false, true),
    CONFIRMED(false, true),
    FINALIZED(true, true),
    FAILED(true, true),
    EXPIRED(true, true),
    UNKNOWN(false, true),
    /** User abandoned the intent BEFORE any signature existed. A signature can never be "cancelled". */
    CANCELLED(true, false),
}

/**
 * Evidence required to move a signed transaction to EXPIRED (v2.1 §6): height strictly above
 * lastValidBlockHeight (getBlockHeight with recorded commitment) AND a history search
 * (getSignatureStatuses searchTransactionHistory=true) that found no inclusion, with no divergence among
 * the queried RPCs. A single RPC returning null is NOT enough: [independentSourcesWithoutInclusion] >= 1
 * and [sourcesDiverged] == false are both required; ambiguity keeps UNKNOWN.
 */
data class ExpiryEvidence(
    val currentBlockHeight: Long,
    val lastValidBlockHeight: Long,
    val historySearched: Boolean,
    val independentSourcesWithoutInclusion: Int,
    val sourcesDiverged: Boolean,
) {
    val sufficient: Boolean
        get() = currentBlockHeight > lastValidBlockHeight && historySearched &&
            independentSourcesWithoutInclusion >= 1 && !sourcesDiverged
}

class IllegalTransitionException(from: TransactionState, to: TransactionState, why: String) :
    IllegalStateException("transition $from -> $to refused: $why")

object TransactionStateMachine {
    private val S = TransactionState.entries.associateBy { it.name }

    private val allowed: Map<TransactionState, Set<TransactionState>> = mapOf(
        TransactionState.DRAFT to setOf(TransactionState.PREPARED, TransactionState.CANCELLED),
        TransactionState.PREPARED to setOf(TransactionState.SIMULATED, TransactionState.FAILED, TransactionState.CANCELLED),
        TransactionState.SIMULATED to setOf(TransactionState.AWAITING_AUTHORIZATION, TransactionState.CANCELLED),
        TransactionState.AWAITING_AUTHORIZATION to setOf(TransactionState.SIGNED, TransactionState.CANCELLED),
        // Signed bytes are persisted before the first submit (spec §13.2). A crash/timeout during submit -> UNKNOWN.
        TransactionState.SIGNED to setOf(TransactionState.SUBMITTED, TransactionState.UNKNOWN),
        TransactionState.SUBMITTED to setOf(TransactionState.PROCESSED, TransactionState.CONFIRMED, TransactionState.FINALIZED,
            TransactionState.FAILED, TransactionState.UNKNOWN, TransactionState.EXPIRED),
        TransactionState.PROCESSED to setOf(TransactionState.CONFIRMED, TransactionState.FINALIZED, TransactionState.FAILED,
            TransactionState.UNKNOWN),
        TransactionState.CONFIRMED to setOf(TransactionState.FINALIZED, TransactionState.FAILED, TransactionState.UNKNOWN),
        TransactionState.UNKNOWN to setOf(TransactionState.PROCESSED, TransactionState.CONFIRMED, TransactionState.FINALIZED,
            TransactionState.FAILED, TransactionState.EXPIRED),
    )

    fun canTransition(from: TransactionState, to: TransactionState): Boolean = allowed[from]?.contains(to) == true

    /** Returns [to] or throws. EXPIRED requires sufficient [evidence]. */
    fun transition(from: TransactionState, to: TransactionState, evidence: ExpiryEvidence? = null): TransactionState {
        if (from.terminal) throw IllegalTransitionException(from, to, "terminal state")
        if (!canTransition(from, to)) throw IllegalTransitionException(from, to, "not in the allowed table")
        if (to == TransactionState.EXPIRED && evidence?.sufficient != true) {
            throw IllegalTransitionException(from, to, "expiry not proven; stay UNKNOWN and reconcile")
        }
        return to
    }

    /**
     * Whether a NEW transfer for the same intent may be prepared. Only after a known terminal outcome
     * without success; the caller must still obtain a new explicit user confirmation (v2.1 §6).
     * Never for SIGNED/SUBMITTED/UNKNOWN/PROCESSED/CONFIRMED (spec §2.3: never rebuild what may be on-chain).
     */
    fun mayPrepareReplacement(state: TransactionState): Boolean =
        state == TransactionState.FAILED || state == TransactionState.EXPIRED || state == TransactionState.CANCELLED

    /** Retransmission of the SAME signed bytes (never rebuilt) is allowed only while not terminal and signed. */
    fun mayRebroadcastSameBytes(state: TransactionState, currentBlockHeight: Long, lastValidBlockHeight: Long): Boolean =
        state in setOf(TransactionState.SIGNED, TransactionState.SUBMITTED, TransactionState.UNKNOWN) &&
            currentBlockHeight <= lastValidBlockHeight

    internal fun byName(n: String): TransactionState? = S[n]
}
