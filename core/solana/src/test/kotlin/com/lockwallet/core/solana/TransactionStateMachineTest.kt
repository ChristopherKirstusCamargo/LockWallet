package com.lockwallet.core.solana

import com.lockwallet.core.solana.tx.ExpiryEvidence
import com.lockwallet.core.solana.tx.IllegalTransitionException
import com.lockwallet.core.solana.tx.TransactionState
import com.lockwallet.core.solana.tx.TransactionState.*
import com.lockwallet.core.solana.tx.TransactionStateMachine as M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionStateMachineTest {
    private val proven = ExpiryEvidence(1001, 1000, historySearched = true, independentSourcesWithoutInclusion = 2, sourcesDiverged = false)

    @Test fun happyPath() {
        var s = DRAFT
        for (n in listOf(PREPARED, SIMULATED, AWAITING_AUTHORIZATION, SIGNED, SUBMITTED, PROCESSED, CONFIRMED, FINALIZED)) {
            s = M.transition(s, n)
        }
        assertEquals(FINALIZED, s)
    }

    @Test fun terminalStatesAreFinal() {
        TransactionState.entries.filter { it.terminal }.forEach { t ->
            TransactionState.entries.forEach { n -> assertThrows(IllegalTransitionException::class.java) { M.transition(t, n, proven) } }
        }
    }

    @Test fun signedTransactionsCannotBeCancelled() {
        TransactionState.entries.filter { it.signed }.forEach { assertFalse("$it -> CANCELLED", M.canTransition(it, CANCELLED)) }
    }

    @Test fun simulationIsMandatoryBeforeAuthorization() {
        assertFalse(M.canTransition(PREPARED, AWAITING_AUTHORIZATION))
        assertFalse(M.canTransition(PREPARED, SIGNED))
        assertFalse(M.canTransition(DRAFT, SIGNED))
    }

    @Test fun expiryNeedsHeightAboveLimitAndHistoricalReconciliation() {
        // currentBlockHeight == lastValidBlockHeight is still valid (spec §17.2 #13)
        val atLimit = proven.copy(currentBlockHeight = 1000)
        assertThrows(IllegalTransitionException::class.java) { M.transition(UNKNOWN, EXPIRED, atLimit) }
        assertThrows(IllegalTransitionException::class.java) { M.transition(UNKNOWN, EXPIRED, proven.copy(historySearched = false)) }
        assertThrows(IllegalTransitionException::class.java) { M.transition(UNKNOWN, EXPIRED, proven.copy(independentSourcesWithoutInclusion = 0)) }
        assertThrows(IllegalTransitionException::class.java) { M.transition(UNKNOWN, EXPIRED, proven.copy(sourcesDiverged = true)) }
        assertThrows(IllegalTransitionException::class.java) { M.transition(UNKNOWN, EXPIRED, null) }
        assertEquals(EXPIRED, M.transition(UNKNOWN, EXPIRED, proven))
        assertEquals(EXPIRED, M.transition(SUBMITTED, EXPIRED, proven))
        assertFalse(M.canTransition(SIGNED, EXPIRED)) // must pass through reconciliation (UNKNOWN)
    }

    @Test fun noReplacementWhileOutcomeUncertain() {
        listOf(SIGNED, SUBMITTED, UNKNOWN, PROCESSED, CONFIRMED, FINALIZED, AWAITING_AUTHORIZATION).forEach {
            assertFalse("$it", M.mayPrepareReplacement(it))
        }
        listOf(FAILED, EXPIRED, CANCELLED).forEach { assertTrue(M.mayPrepareReplacement(it)) }
    }

    @Test fun rebroadcastOnlySameBytesWhileValid() {
        assertTrue(M.mayRebroadcastSameBytes(UNKNOWN, 1000, 1000))
        assertFalse(M.mayRebroadcastSameBytes(UNKNOWN, 1001, 1000))
        assertFalse(M.mayRebroadcastSameBytes(FINALIZED, 10, 1000))
        assertFalse(M.mayRebroadcastSameBytes(AWAITING_AUTHORIZATION, 10, 1000))
    }
}
