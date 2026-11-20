package com.lockwallet.core.solana

import com.lockwallet.core.solana.dapp.DappBlock
import com.lockwallet.core.solana.dapp.DappFirewall
import com.lockwallet.core.solana.dapp.DappWarning
import com.lockwallet.core.solana.dapp.IxKind
import com.lockwallet.core.solana.dapp.MessagePolicy
import com.lockwallet.core.solana.ix.ComputeBudgetIx
import com.lockwallet.core.solana.ix.SystemIx
import com.lockwallet.core.solana.ix.StakeIx
import com.lockwallet.core.solana.ix.TokenIx
import com.lockwallet.core.solana.tx.AccountMeta
import com.lockwallet.core.solana.tx.Blockhash
import com.lockwallet.core.solana.tx.Instruction
import com.lockwallet.core.solana.tx.LegacyMessage
import com.lockwallet.core.solana.tx.WireTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DappFirewallTest {
    private val me = SolanaPublicKey.fromBase58("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv")
    private val bob = SolanaPublicKey.fromBase58("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")
    private val dex = SolanaPublicKey.fromBase58("9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin")
    private val bh = Blockhash(ByteArray(32) { 9 })
    private fun msg(payer: SolanaPublicKey, vararg ix: Instruction) = LegacyMessage.compile(payer, bh, ix.toList()).serialize()

    @Test fun decodedTransferWithMemoIsSignableWithoutRawAck() {
        val memo = Instruction(DappFirewall.MEMO_V2, listOf(AccountMeta(me, true, false)), "pedido #42".toByteArray())
        val m = msg(me, ComputeBudgetIx.setUnitLimit(200_000), SystemIx.transfer(me, bob, 1000), memo)
        val v = DappFirewall.inspect(m, me)
        assertTrue(v.blockers.toString(), v.signable)
        assertFalse(v.needsRawAck)
        assertEquals(listOf(IxKind.DECODED, IxKind.DECODED, IxKind.MEMO), v.instructions.map { it.kind })
        assertEquals("pedido #42", v.instructions[2].memo)
        assertTrue(DappFirewall.permits(m, me, v.messageHash, rawAcknowledged = false))
        assertFalse(DappFirewall.permits(m, me, ByteArray(32), rawAcknowledged = true))
    }

    @Test fun unknownProgramNeedsRawAckAndFlagsWalletSigner() {
        val ix = Instruction(dex, listOf(AccountMeta(me, true, true), AccountMeta(bob, false, true)), byteArrayOf(1, 2, 3))
        val m = msg(me, ix)
        val v = DappFirewall.inspect(m, me)
        assertTrue(v.signable)
        assertTrue(v.needsRawAck)
        assertEquals(IxKind.UNKNOWN, v.instructions.single().kind)
        assertTrue(DappWarning.WALLET_SIGNS_FOR_RAW_PROGRAM in v.warnings)
        assertFalse(DappFirewall.permits(m, me, v.messageHash, rawAcknowledged = false))
        assertTrue(DappFirewall.permits(m, me, v.messageHash, rawAcknowledged = true))
    }

    @Test fun dangerousCoreInstructionsAreForbidden() {
        // SPL Token Approve (4), SetAuthority (6), System Assign (1): never for a dApp.
        val approve = Instruction(ProgramIds.SPL_TOKEN_LEGACY, listOf(AccountMeta(bob, false, true), AccountMeta(dex, false, false), AccountMeta(me, true, false)),
            byteArrayOf(4, 1, 0, 0, 0, 0, 0, 0, 0))
        val assign = Instruction(ProgramIds.SYSTEM, listOf(AccountMeta(me, true, true)), ByteArray(4).also { it[0] = 1 } + dex.toByteArray())
        for (ix in listOf(approve, assign)) {
            val v = DappFirewall.inspect(msg(me, ix), me)
            assertFalse(v.signable); assertTrue(DappBlock.FORBIDDEN_INSTRUCTION in v.blockers)
        }
        val upgrade = Instruction(SolanaPublicKey.fromBase58("BPFLoaderUpgradeab1e11111111111111111111111"), listOf(AccountMeta(me, true, true)), byteArrayOf(3, 0, 0, 0))
        assertFalse(DappFirewall.inspect(msg(me, upgrade), me).signable)
        // Token transfer whose authority is someone else is also forbidden.
        val foreign = TokenIx.transferChecked(ProgramIds.SPL_TOKEN_LEGACY, bob, dex, bob, bob, 1, 6)
        assertFalse(DappFirewall.inspect(LegacyMessage.compile(me, bh, listOf(foreign)).serialize(), me).signable)
    }

    @Test fun dappStakeWithdrawalToThirdPartyIsBlocked() {
        val withdraw = StakeIx.withdraw(dex, bob, me, 1_000_000)
        val verdict = DappFirewall.inspect(msg(me, withdraw), me)
        assertFalse(verdict.signable)
        assertTrue(DappBlock.FORBIDDEN_INSTRUCTION in verdict.blockers)
    }

    @Test fun instructionCountIsBoundedAndLongReviewsWarn() {
        val memo = Instruction(DappFirewall.MEMO_V2, listOf(AccountMeta(me, true, false)), "ok".toByteArray())
        val five = DappFirewall.inspect(msg(me, *List(5) { memo }.toTypedArray()), me)
        assertTrue(five.signable)
        assertTrue(DappWarning.MANY_INSTRUCTIONS in five.warnings)
        val nine = DappFirewall.inspect(msg(me, *List(9) { memo }.toTypedArray()), me)
        assertFalse(nine.signable)
        assertTrue(DappBlock.TOO_MANY_INSTRUCTIONS in nine.blockers)
    }

    @Test fun foreignFeePayerExtraSignerAndVersionedAreRejected() {
        assertTrue(DappBlock.FEE_PAYER_MISMATCH in DappFirewall.inspect(msg(bob, SystemIx.transfer(bob, me, 1)), me).blockers)
        val cosign = Instruction(dex, listOf(AccountMeta(me, true, true), AccountMeta(bob, true, false)), byteArrayOf(0))
        assertTrue(DappBlock.EXTRA_SIGNER in DappFirewall.inspect(msg(me, cosign), me).blockers)
        val v0 = byteArrayOf(0x80.toByte()) + msg(me, SystemIx.transfer(me, bob, 1))
        assertEquals(listOf(DappBlock.UNSUPPORTED_FORMAT), DappFirewall.inspect(v0, me).blockers)
        assertEquals(listOf(DappBlock.MALFORMED), DappFirewall.inspect(byteArrayOf(1, 2, 3), me).blockers)
    }

    @Test fun messagePolicyOnlyAcceptsPresentableText() {
        assertNull(MessagePolicy.check("example.com wants you to sign in with your Solana account:\n$me\n\nNonce: 1".toByteArray()))
        val tx = msg(me, SystemIx.transfer(me, bob, 1))
        assertEquals(MessagePolicy.Refusal.LOOKS_LIKE_TRANSACTION, MessagePolicy.check(tx))
        assertEquals(MessagePolicy.Refusal.LOOKS_LIKE_TRANSACTION, MessagePolicy.check(WireTransaction.assemble(tx, listOf(ByteArray(64)))))
        assertEquals(MessagePolicy.Refusal.NOT_TEXT, MessagePolicy.check(byteArrayOf(0x41, 0x00, 0x42)))
        assertEquals(MessagePolicy.Refusal.NOT_TEXT, MessagePolicy.check("abc\u202Edef".toByteArray()))
        assertEquals(MessagePolicy.Refusal.NOT_TEXT, MessagePolicy.check(byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals(MessagePolicy.Refusal.TOO_LONG, MessagePolicy.check(ByteArray(3000) { 0x61 }))
        assertEquals(MessagePolicy.Refusal.NOT_TEXT, MessagePolicy.check(("hello\\n".repeat(9)).toByteArray()))
        assertEquals(MessagePolicy.Refusal.NOT_TEXT, MessagePolicy.check(("a ".repeat(200)).toByteArray()))
        assertEquals(MessagePolicy.Refusal.EMPTY, MessagePolicy.check(ByteArray(0)))
    }
}
