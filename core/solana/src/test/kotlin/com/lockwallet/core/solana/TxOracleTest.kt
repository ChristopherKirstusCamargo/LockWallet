package com.lockwallet.core.solana

import com.google.gson.JsonObject
import com.lockwallet.core.crypto.Ed25519Signer
import com.lockwallet.core.crypto.SecretBytes
import com.lockwallet.core.crypto.Slip10Ed25519
import com.lockwallet.core.solana.analyzer.Finding
import com.lockwallet.core.solana.analyzer.Op
import com.lockwallet.core.solana.analyzer.TransactionAnalyzer
import com.lockwallet.core.solana.ix.AtaIx
import com.lockwallet.core.solana.ix.ComputeBudgetIx
import com.lockwallet.core.solana.ix.StakeIx
import com.lockwallet.core.solana.ix.SystemIx
import com.lockwallet.core.solana.ix.TokenIx
import com.lockwallet.core.solana.state.StakeLifecycle
import com.lockwallet.core.solana.state.StakeState
import com.lockwallet.core.solana.state.TokenPolicy
import com.lockwallet.core.solana.state.TokenState
import com.lockwallet.core.solana.tx.Blockhash
import com.lockwallet.core.solana.tx.Instruction
import com.lockwallet.core.solana.tx.LegacyMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte-for-byte against `tx-oracle.json` (solders + struct encoders, every positive executed in LiteSVM). */
class TxOracleTest {
    private val f = SolanaFixtures.json("tx-oracle.json")
    private val k: JsonObject = f.getAsJsonObject("keys")
    private fun pk(n: String) = SolanaPublicKey.fromBase58(k[n].asString)
    private val payer = pk("payer"); private val bob = pk("bob"); private val mint = pk("mint"); private val mint22 = pk("mint22")
    private val bh = Blockhash.fromBase58(k["fixture_blockhash"].asString)
    private val rent = k["stake_rent"].asLong
    private fun seedAddr(i: Int) = Pda.createWithSeed(payer, "stake:$i", ProgramIds.STAKE)

    private val built: Map<String, List<Instruction>> = mapOf(
        "sol_transfer" to listOf(SystemIx.transfer(payer, bob, 1_234_567)),
        "sol_transfer_cb" to listOf(ComputeBudgetIx.setUnitLimit(450), ComputeBudgetIx.setUnitPrice(0), SystemIx.transfer(payer, bob, 5000)),
        "token_transfer_create_ata" to listOf(
            AtaIx.createIdempotent(payer, Pda.associatedTokenAddress(bob, mint, ProgramIds.SPL_TOKEN_LEGACY), bob, mint, ProgramIds.SPL_TOKEN_LEGACY),
            TokenIx.transferChecked(ProgramIds.SPL_TOKEN_LEGACY, pk("payer_ata"), mint, pk("bob_ata"), payer, 1_500_000, 6)),
        "token_close_empty" to listOf(TokenIx.closeAccount(ProgramIds.SPL_TOKEN_LEGACY, pk("payer_ata"), payer, payer)),
        "token2022_transfer_create_ata" to listOf(
            AtaIx.createIdempotent(payer, pk("bob_ata22"), bob, mint22, ProgramIds.TOKEN_2022),
            TokenIx.transferChecked(ProgramIds.TOKEN_2022, pk("payer_ata22"), mint22, pk("bob_ata22"), payer, 250_000_000, 9)),
        "stake_create_delegate" to listOf(
            SystemIx.createAccountWithSeed(payer, seedAddr(0), "stake:0", rent + 3_000_000_000, StakeIx.STAKE_ACCOUNT_SIZE, ProgramIds.STAKE),
            StakeIx.initialize(seedAddr(0), payer), StakeIx.delegate(seedAddr(0), pk("vote"), payer)),
        "stake_split" to listOf(
            SystemIx.createAccountWithSeed(payer, seedAddr(1), "stake:1", rent, StakeIx.STAKE_ACCOUNT_SIZE, ProgramIds.STAKE),
            StakeIx.split(seedAddr(0), seedAddr(1), payer, 1_000_000_000)),
        "stake_deactivate" to listOf(StakeIx.deactivate(seedAddr(0), payer)),
        "stake_merge" to listOf(StakeIx.merge(seedAddr(2), seedAddr(3), payer)),
    )

    @Test fun builtMessagesMatchOracleByteForByte() {
        val pos = f.getAsJsonArray("positive").map { it.asJsonObject }
        for (c in pos) {
            val id = c["id"].asString
            val hex = c["message_hex"].asString
            val parsed = LegacyMessage.parse(hex.hex())
            assertEquals(id, hex, parsed.serialize().hex())
            val ixs = built[id] ?: continue // stake_withdraw amount comes from chain state; round-trip only
            assertEquals(id, hex, LegacyMessage.compile(payer, bh, ixs).serialize().hex())
        }
        assertEquals(9, built.size)
    }

    @Test fun allPositivesAreSignableStatically() {
        for (c in f.getAsJsonArray("positive").map { it.asJsonObject }) {
            val a = TransactionAnalyzer.analyze(c["message_hex"].asString.hex(), payer)
            assertTrue("${c["id"]} ${a.blockers}", a.signable)
            assertTrue(a.ops.isNotEmpty())
        }
        val a = TransactionAnalyzer.analyze(f.getAsJsonArray("positive")[0].asJsonObject["message_hex"].asString.hex(), payer)
        assertEquals(listOf(Op.SolTransfer(payer, bob, 1_234_567)), a.ops)
        assertEquals(1_234_567, a.explicitOutflowLamports)
    }

    @Test fun everyNegativeBlocksWithTheExpectedFinding() {
        for (c in f.getAsJsonArray("negative").map { it.asJsonObject }) {
            val a = TransactionAnalyzer.analyze(c["message_hex"].asString.hex(), payer)
            assertFalse(c["id"].asString, a.signable)
            assertTrue("${c["id"]}: ${a.findings}", Finding.valueOf(c["finding"].asString) in a.findings)
        }
    }

    @Test fun wrongFeePayerBlocks() {
        val a = TransactionAnalyzer.analyze(f.getAsJsonArray("positive")[0].asJsonObject["message_hex"].asString.hex(), bob)
        assertTrue(Finding.FEE_PAYER_MISMATCH in a.blockers)
    }

    @Test fun everySingleByteFlipEitherBlocksOrChangesTheSummary() {
        val good = f.getAsJsonArray("positive")[0].asJsonObject["message_hex"].asString.hex()
        val base = TransactionAnalyzer.analyze(good, payer)
        for (i in good.indices) {
            val bad = good.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val a = TransactionAnalyzer.analyze(bad, payer)
            // Either refused, or a different (visible) semantic summary/blockhash — never silently identical.
            assertTrue("byte $i", !a.signable || a.ops != base.ops || a.message!!.recentBlockhash != base.message!!.recentBlockhash)
        }
    }

    @Test fun signatureMatchesOracle() {
        val s = f.getAsJsonObject("signature")
        val seed = k["payer_seed_hex"].asString.hex()
        assertArrayEquals(payer.toByteArray(), Slip10Ed25519.publicKeyOf(seed))
        val sig = Ed25519Signer.sign(SecretBytes.adopt(seed), s["message_hex"].asString.hex())
        assertEquals(s["signature_hex"].asString, sig.hex())
    }

    @Test fun derivedAddressesMatchOracle() {
        f.getAsJsonArray("ata").map { it.asJsonObject }.forEach {
            assertEquals(it["ata"].asString, Pda.associatedTokenAddress(SolanaPublicKey.fromBase58(it["owner"].asString),
                SolanaPublicKey.fromBase58(it["mint"].asString), SolanaPublicKey.fromBase58(it["token_program"].asString)).toBase58())
        }
        f.getAsJsonArray("with_seed").map { it.asJsonObject }.forEach {
            assertEquals(it["address"].asString, Pda.createWithSeed(SolanaPublicKey.fromBase58(it["base"].asString), it["seed"].asString,
                SolanaPublicKey.fromBase58(it["owner"].asString)).toBase58())
        }
        f.getAsJsonArray("metaplex_pda").map { it.asJsonObject }.forEach {
            assertEquals(it["metadata"].asString, Pda.metaplexMetadata(SolanaPublicKey.fromBase58(it["mint"].asString)).toBase58())
        }
        f.getAsJsonArray("curve").map { it.asJsonObject }.forEach {
            assertEquals(it["on_curve"].asBoolean, Ed25519Curve.isOnCurve(it["bytes_hex"].asString.hex()))
        }
    }

    @Test fun chainAccountsParse() {
        val a = f.getAsJsonObject("accounts")
        val m = TokenState.parseMint(ProgramIds.SPL_TOKEN_LEGACY, a["mint_legacy_hex"].asString.hex())
        assertEquals(6, m.decimals); assertEquals(5_000_000, m.supply); assertNull(m.freezeAuthority); assertTrue(m.extensions.isEmpty())
        val acc = TokenState.parseAccount(ProgramIds.SPL_TOKEN_LEGACY, a["bob_ata_legacy_hex"].asString.hex())
        assertEquals(mint, acc.mint); assertEquals(bob, acc.owner); assertEquals(5_000_000, acc.amount); assertFalse(acc.frozen)

        val m22 = TokenState.parseMint(ProgramIds.TOKEN_2022, a["mint22_hex"].asString.hex())
        assertEquals(9, m22.decimals); assertEquals(listOf(18), m22.extensions); assertEquals(mint22, m22.metadataPointer)
        val a22 = TokenState.parseAccount(ProgramIds.TOKEN_2022, a["payer_ata22_hex"].asString.hex())
        assertEquals(listOf(7), a22.extensions); assertEquals(750_000_000, a22.amount)
        assertTrue(TokenPolicy.judge(m22.extensions, a22.extensions).allowed)

        val st = StakeState.parse(a["stake0_hex"].asString.hex())
        assertEquals(2, st.kind); assertEquals(payer, st.staker); assertEquals(payer, st.withdrawer); assertEquals(pk("vote"), st.voter)
        assertEquals(2_000_000_000, st.delegatedStake); assertFalse(st.hasLockup)
        assertEquals(StakeLifecycle.DEACTIVATING, StakeState.lifecycle(st, st.deactivationEpoch))
        assertEquals(StakeLifecycle.INACTIVE, StakeState.lifecycle(st, st.deactivationEpoch + 1))
    }
}
