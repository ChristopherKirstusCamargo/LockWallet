package com.lockwallet.core.solana

import com.lockwallet.core.solana.analyzer.TransactionAnalyzer
import com.lockwallet.core.solana.ix.AtaIx
import com.lockwallet.core.solana.ix.ComputeBudgetIx
import com.lockwallet.core.solana.ix.StakeIx
import com.lockwallet.core.solana.ix.SystemIx
import com.lockwallet.core.solana.ix.TokenIx
import com.lockwallet.core.solana.state.StakeState
import com.lockwallet.core.solana.state.TokenState
import com.lockwallet.core.solana.tx.AccountMeta
import com.lockwallet.core.solana.tx.Blockhash
import com.lockwallet.core.solana.tx.Instruction
import com.lockwallet.core.solana.tx.LegacyMessage
import com.lockwallet.core.solana.tx.UnsupportedFormatException
import com.lockwallet.core.solana.tx.WireTransaction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Phase I: seeded fuzz/property tests for every parser that sees untrusted bytes (RPC, QR, pasted input).
 * Invariants: parsers either succeed or throw their documented format exception (never IndexOutOfBounds,
 * NegativeArraySize, OOM…); analyzer/firewall never throw; anything accepted re-serializes byte-identically;
 * a mutated message never keeps the original hash, so an approval can never be replayed onto other bytes.
 */
class ParserFuzzTest {
    private val rnd = Random(0x10C4)
    private val me = SolanaPublicKey.fromBase58("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv")
    private val other = SolanaPublicKey.fromBase58("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")
    private val mint = SolanaPublicKey.fromBase58("So11111111111111111111111111111111111111112")
    private fun key() = SolanaPublicKey.of(rnd.nextBytes(32))
    private fun bh() = Blockhash(rnd.nextBytes(32))

    private fun validMessages(): List<ByteArray> {
        val ata = Pda.associatedTokenAddress(other, mint, ProgramIds.SPL_TOKEN_LEGACY)
        val stake = Pda.createWithSeed(me, "stake:0", ProgramIds.STAKE)
        return listOf(
            listOf(SystemIx.transfer(me, other, 1_000)),
            listOf(ComputeBudgetIx.setUnitLimit(200_000), ComputeBudgetIx.setUnitPrice(5), SystemIx.transfer(me, other, 7)),
            listOf(AtaIx.createIdempotent(me, ata, other, mint, ProgramIds.SPL_TOKEN_LEGACY),
                TokenIx.transferChecked(ProgramIds.SPL_TOKEN_LEGACY, key(), mint, ata, me, 5, 9)),
            listOf(SystemIx.createAccountWithSeed(me, stake, "stake:0", 3_000_000_000, StakeIx.STAKE_ACCOUNT_SIZE, ProgramIds.STAKE),
                StakeIx.initialize(stake, me), StakeIx.delegate(stake, other, me)),
            listOf(Instruction(key(), listOf(AccountMeta(me, true, true), AccountMeta(key(), false, true)), rnd.nextBytes(40))),
        ).map { LegacyMessage.compile(me, bh(), it).serialize() }
    }

    private inline fun tolerated(block: () -> Unit) {
        try { block() } catch (_: MalformedException) { } catch (_: UnsupportedFormatException) { }
    }

    @Test fun randomBytesNeverCrashParsers() {
        repeat(4_000) {
            val b = rnd.nextBytes(rnd.nextInt(0, 1400))
            tolerated { LegacyMessage.parse(b) }
            tolerated { WireTransaction.split(b) }
            TransactionAnalyzer.analyze(b, me)
            tolerated { TokenState.parseAccount(if (rnd.nextBoolean()) ProgramIds.SPL_TOKEN_LEGACY else ProgramIds.TOKEN_2022, b) }
            tolerated { TokenState.parseMint(if (rnd.nextBoolean()) ProgramIds.SPL_TOKEN_LEGACY else ProgramIds.TOKEN_2022, b) }
            tolerated { StakeState.parse(b) }
            SolanaUri.parse(String(b, Charsets.ISO_8859_1))
            Base58.decodeOrNull(String(b, Charsets.ISO_8859_1).take(90))
        }
    }

    @Test fun mutatedMessagesAreRejectedOrCanonicalAndNeverReuseAnApproval() {
        val seeds = validMessages()
        for (m in seeds) {
            val parsed = LegacyMessage.parse(m)
            assertArrayEquals(m, parsed.serialize())
            val hash = TransactionAnalyzer.analyze(m, me).messageHash
            repeat(1_500) {
                val x = mutate(m)
                if (x.contentEquals(m)) return@repeat
                val a = TransactionAnalyzer.analyze(x, me)
                assertTrue(!a.messageHash.contentEquals(hash))
                try {
                    val p = LegacyMessage.parse(x)
                    assertArrayEquals("accepted bytes must be canonical", x, p.serialize())
                    if (a.signable) assertTrue(p.feePayer == me && p.numRequiredSignatures == 1)
                } catch (_: MalformedException) { assertTrue(!a.signable) } catch (_: UnsupportedFormatException) { assertTrue(!a.signable) }
            }
        }
    }

    @Test fun mutatedTokenAndStakeImagesNeverCrash() {
        val acct = ByteArray(165).also { me.toByteArray().copyInto(it, 32); mint.toByteArray().copyInto(it, 0); it[108] = 1 }
        val mintImg = ByteArray(82).also { it[44] = 6; it[45] = 1 }
        val stake = ByteArray(200).also { it[0] = 2 }
        repeat(3_000) {
            tolerated { TokenState.parseAccount(ProgramIds.SPL_TOKEN_LEGACY, mutate(acct)) }
            tolerated { TokenState.parseAccount(ProgramIds.TOKEN_2022, mutate(acct) + rnd.nextBytes(rnd.nextInt(0, 64))) }
            tolerated { TokenState.parseMint(ProgramIds.TOKEN_2022, mutate(mintImg) + rnd.nextBytes(rnd.nextInt(0, 120))) }
            tolerated { StakeState.parse(mutate(stake)) }
        }
    }

    @Test fun compileParseRoundTripProperty() {
        repeat(500) {
            val n = rnd.nextInt(1, 6)
            val ixs = List(n) {
                val accts = List(rnd.nextInt(0, 5)) { AccountMeta(if (rnd.nextInt(4) == 0) me else key(), rnd.nextInt(5) == 0, rnd.nextBoolean()) }
                Instruction(key(), accts, rnd.nextBytes(rnd.nextInt(0, 60)))
            }
            val m = try { LegacyMessage.compile(me, bh(), ixs) } catch (_: IllegalArgumentException) { return@repeat }
            val b = m.serialize()
            if (b.size > 1232) return@repeat
            val p = LegacyMessage.parse(b)
            assertArrayEquals(b, p.serialize())
            assertTrue(p.feePayer == me)
        }
    }

    private fun mutate(src: ByteArray): ByteArray {
        val b = src.copyOf()
        return when (rnd.nextInt(5)) {
            0 -> b.also { val i = rnd.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
            1 -> b.also { repeat(rnd.nextInt(1, 6)) { _ -> val i = rnd.nextInt(it.size); it[i] = rnd.nextInt(256).toByte() } }
            2 -> b.copyOf(rnd.nextInt(0, b.size))
            3 -> b + rnd.nextBytes(rnd.nextInt(1, 16))
            else -> { val i = rnd.nextInt(b.size); b.copyOfRange(0, i) + rnd.nextBytes(rnd.nextInt(0, 4)) + b.copyOfRange(i, b.size) }
        }
    }
}
