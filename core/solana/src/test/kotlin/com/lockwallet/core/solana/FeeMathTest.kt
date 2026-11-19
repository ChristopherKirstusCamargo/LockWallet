package com.lockwallet.core.solana

import com.lockwallet.core.solana.analyzer.Finding
import com.lockwallet.core.solana.analyzer.TransactionAnalyzer
import com.lockwallet.core.solana.dapp.DappBlock
import com.lockwallet.core.solana.dapp.DappFirewall
import com.lockwallet.core.solana.ix.ComputeBudgetIx
import com.lockwallet.core.solana.ix.SystemIx
import com.lockwallet.core.solana.tx.Blockhash
import com.lockwallet.core.solana.tx.FeeMath
import com.lockwallet.core.solana.tx.Instruction
import com.lockwallet.core.solana.tx.LegacyMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeeMathTest {
    private val me = SolanaPublicKey.of(ByteArray(32) { 3 })
    private val bob = SolanaPublicKey.of(ByteArray(32) { 4 })
    private val bh = Blockhash(ByteArray(32) { 9 })
    private fun msg(vararg ix: Instruction) = LegacyMessage.compile(me, bh, ix.toList())

    @Test fun boundFromBytes() {
        assertEquals(5_000L, FeeMath.maxFee(msg(SystemIx.transfer(me, bob, 1))))
        val m = msg(ComputeBudgetIx.setUnitLimit(1_500), ComputeBudgetIx.setUnitPrice(100_000), SystemIx.transfer(me, bob, 1))
        assertEquals(5_150L, FeeMath.maxFee(m))
        // No explicit limit: priced at the 200k-per-instruction upper bound.
        val n = msg(ComputeBudgetIx.setUnitPrice(1_000_000), SystemIx.transfer(me, bob, 1), SystemIx.transfer(me, bob, 2))
        assertEquals(5_000L + 400_000L, FeeMath.maxFee(n))
        assertEquals(1L, FeeMath.priority(1, 1))                       // ceil
        assertEquals(Long.MAX_VALUE, FeeMath.priority(1_400_000, Long.MAX_VALUE))
    }

    @Test fun malformedOrDuplicatedBudgetCannotBePriced() {
        assertNull(FeeMath.budget(msg(ComputeBudgetIx.setUnitPrice(1), ComputeBudgetIx.setUnitPrice(2))))
        assertNull(FeeMath.budget(msg(Instruction(ProgramIds.COMPUTE_BUDGET, emptyList(), byteArrayOf(1, 0, 0, 1, 0)))))  // heap frame
        assertNull(FeeMath.budget(msg(Instruction(ProgramIds.COMPUTE_BUDGET, emptyList(), byteArrayOf(3, 1)))))
    }

    /** Fee drain: a dApp asking 0.01 SOL of priority fee for a trivial transfer is refused, by wallet and firewall. */
    @Test fun feeDrainIsBlocked() {
        val drain = msg(ComputeBudgetIx.setUnitLimit(1_400_000), ComputeBudgetIx.setUnitPrice(7_200_000), SystemIx.transfer(me, bob, 1)).serialize()
        val a = TransactionAnalyzer.analyze(drain, me)
        assertTrue(Finding.FEE_TOO_HIGH in a.blockers); assertFalse(a.signable)
        val v = DappFirewall.inspect(drain, me)
        assertTrue(DappBlock.FEE_TOO_HIGH in v.blockers); assertFalse(v.signable)
        assertFalse(DappFirewall.permits(drain, me, sha256(drain), rawAcknowledged = true))
        // Just under the cap is allowed (and shown).
        val ok = msg(ComputeBudgetIx.setUnitLimit(10_000), ComputeBudgetIx.setUnitPrice(1_000_000), SystemIx.transfer(me, bob, 1)).serialize()
        assertTrue(TransactionAnalyzer.analyze(ok, me).signable)
        assertEquals(15_000L, DappFirewall.inspect(ok, me).maxFeeLamports)
    }
}
