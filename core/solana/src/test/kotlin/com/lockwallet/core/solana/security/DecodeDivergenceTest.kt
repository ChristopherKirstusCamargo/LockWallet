package com.lockwallet.core.solana.security

import com.lockwallet.core.solana.Amounts
import com.lockwallet.core.solana.Base58
import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.SolanaUri
import com.lockwallet.core.solana.analyzer.Finding
import com.lockwallet.core.solana.analyzer.Op
import com.lockwallet.core.solana.analyzer.TransactionAnalyzer
import com.lockwallet.core.solana.ix.ComputeBudgetIx
import com.lockwallet.core.solana.ix.SystemIx
import com.lockwallet.core.solana.tx.Blockhash
import com.lockwallet.core.solana.tx.FeeMath
import com.lockwallet.core.solana.tx.LegacyMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.math.BigDecimal
import java.util.Random

private fun key(r: Random) = SolanaPublicKey.of(ByteArray(32).also { r.nextBytes(it) })

/**
 * "What is reviewed is what is signed": a SOL send is built, then every mutation of its bytes is either refused by
 * the analyzer, or decodes to ops that rebuild exactly the mutated bytes, so the review necessarily shows the new
 * recipient, amount and fee. A mutated message never keeps the approval hash, and its fee stays within the cap.
 */
@RunWith(Parameterized::class)
class DecodeDivergenceTest(private val seed: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "seed {0}") fun data(): List<Array<Any>> = (1..300).map { arrayOf<Any>(it) }
    }

    @Test fun mutationsAreRefusedOrFullyVisible() {
        val r = Random(seed * 1_009L)
        val me = key(r); val to = key(r)
        val lamports = 1L + (r.nextLong() and Long.MAX_VALUE) % 1_000_000_000_000_000_000L
        val limit = 1_000L + r.nextInt(1_399_000); val price = r.nextInt(5_000).toLong()
        val bh = Blockhash(ByteArray(32).also { r.nextBytes(it) })
        val ixs = listOf(ComputeBudgetIx.setUnitLimit(limit), ComputeBudgetIx.setUnitPrice(price), SystemIx.transfer(me, to, lamports))
        val msg = LegacyMessage.compile(me, bh, ixs).serialize()
        val a = TransactionAnalyzer.analyze(msg, me)
        assertTrue(a.findings.toString(), a.signable)
        assertEquals(Op.SolTransfer(me, to, lamports), a.ops.filterIsInstance<Op.SolTransfer>().single())
        assertEquals(lamports, a.explicitOutflowLamports)
        repeat(40) {
            val x = msg.copyOf()
            repeat(1 + r.nextInt(3)) { val i = r.nextInt(x.size); x[i] = (x[i].toInt() xor (1 + r.nextInt(255))).toByte() }
            if (x.contentEquals(msg)) return@repeat
            val m = TransactionAnalyzer.analyze(x, me)
            assertFalse(m.messageHash.contentEquals(a.messageHash))
            if (!m.signable) return@repeat
            val p = m.message!!
            // Canonical: the decoded instructions rebuild the exact bytes (no hidden content).
            assertArrayEquals(x, p.serialize())
            assertEquals(me, p.feePayer)
            val fee = m.maxFeeLamports!!
            assertTrue("fee $fee", fee <= FeeMath.LAMPORTS_PER_SIGNATURE + TransactionAnalyzer.MAX_PRIORITY_LAMPORTS)
            // Whatever moved is visible as a decoded op (recipient/amount are what the review shows).
            val t = m.ops.filterIsInstance<Op.SolTransfer>()
            assertTrue(t.all { it.from == me && it.lamports > 0 && it.to != me })
            assertEquals(t.sumOf { it.lamports }, m.explicitOutflowLamports)
        }
    }
}

/** Ops that are not plain transfers with exact layouts are refused (closed allowlist). */
@RunWith(Parameterized::class)
class AnalyzerRefusalTest(private val case: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun data(): List<Array<Any>> =
            listOf("unknown-program", "zero", "self", "foreign-payer", "two-signers", "dup-budget", "limit-too-high", "fee-too-high", "empty",
                "assign", "transfer-from-other", "readonly-dest").map { arrayOf<Any>(it) }
    }

    @Test fun refused() {
        val r = Random(case.hashCode().toLong())
        val me = key(r); val to = key(r); val bh = Blockhash(ByteArray(32).also { r.nextBytes(it) })
        val other = key(r)
        fun ix(p: SolanaPublicKey, data: ByteArray, vararg metas: com.lockwallet.core.solana.tx.AccountMeta) = com.lockwallet.core.solana.tx.Instruction(p, metas.toList(), data)
        val m = when (case) {
            "unknown-program" -> LegacyMessage.compile(me, bh, listOf(ix(key(r), byteArrayOf(2, 0, 0, 0), com.lockwallet.core.solana.tx.AccountMeta(me, true, true))))
            "zero" -> LegacyMessage.compile(me, bh, listOf(SystemIx.transfer(me, to, 0)))
            "self" -> LegacyMessage.compile(me, bh, listOf(SystemIx.transfer(me, me, 5)))
            "foreign-payer" -> LegacyMessage.compile(other, bh, listOf(SystemIx.transfer(other, to, 5)))
            "two-signers" -> LegacyMessage.compile(me, bh, listOf(SystemIx.transfer(me, to, 5), SystemIx.transfer(other, to, 5)))
            "dup-budget" -> LegacyMessage.compile(me, bh, listOf(ComputeBudgetIx.setUnitLimit(1000), ComputeBudgetIx.setUnitLimit(2000), SystemIx.transfer(me, to, 5)))
            "limit-too-high" -> LegacyMessage.compile(me, bh, listOf(ComputeBudgetIx.setUnitLimit(FeeMath.MAX_UNITS + 1), SystemIx.transfer(me, to, 5)))
            "fee-too-high" -> LegacyMessage.compile(me, bh, listOf(ComputeBudgetIx.setUnitLimit(1_400_000), ComputeBudgetIx.setUnitPrice(1_000_000_000L), SystemIx.transfer(me, to, 5)))
            "empty" -> LegacyMessage.compile(me, bh, emptyList())
            "assign" -> LegacyMessage.compile(me, bh, listOf(ix(ProgramIds.SYSTEM, byteArrayOf(1, 0, 0, 0) + ByteArray(32), com.lockwallet.core.solana.tx.AccountMeta(me, true, true))))
            "transfer-from-other" -> LegacyMessage.compile(me, bh, listOf(SystemIx.transfer(other, to, 5)))
            "readonly-dest" -> LegacyMessage.compile(me, bh, listOf(ix(ProgramIds.SYSTEM, byteArrayOf(2, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0),
                com.lockwallet.core.solana.tx.AccountMeta(me, true, true), com.lockwallet.core.solana.tx.AccountMeta(to, false, false))))
            else -> error(case)
        }
        val a = TransactionAnalyzer.analyze(m.serialize(), me)
        assertFalse("$case was signable: ${a.findings}", a.signable)
        assertTrue(a.blockers.isNotEmpty() && a.blockers.all(Finding::blocking))
    }
}

/** Base58: round trip at every length; any non-alphabet character (0 O I l, unicode lookalikes, spaces) is refused. */
@RunWith(Parameterized::class)
class Base58PropertyTest(private val seed: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "seed {0}") fun data(): List<Array<Any>> = (1..200).map { arrayOf<Any>(it) }
        private val BAD = listOf('0', 'O', 'I', 'l', ' ', '+', '/', '=', '\u00A0', 'Ｏ', 'О', 'Ι', 'ⅼ', '\u200B', '\u0000', '-', '_')
    }

    @Test fun roundTripAndRejection() {
        val r = Random(seed * 17L)
        val bytes = ByteArray(r.nextInt(65)).also { r.nextBytes(it) }
        if (seed % 5 == 0 && bytes.isNotEmpty()) bytes[0] = 0 // leading zeros
        val s = Base58.encode(bytes)
        assertArrayEquals(bytes, Base58.decodeOrNull(s, 128))
        if (bytes.size == 32) assertEquals(SolanaPublicKey.of(bytes), SolanaPublicKey.fromBase58OrNull(s))
        val k = key(r).toBase58()
        val i = r.nextInt(k.length)
        val bad = BAD[r.nextInt(BAD.size)]
        val poisoned = k.substring(0, i) + bad + k.substring(i + 1)
        assertNull("accepted '$poisoned'", SolanaPublicKey.fromBase58OrNull(poisoned))
        assertNull(SolanaPublicKey.fromBase58OrNull(k + bad))
        // A truncated address is never read as the original key (it may be another valid key, or nothing).
        val cut = k.drop(1 + r.nextInt(8))
        assertTrue(SolanaPublicKey.fromBase58OrNull(cut)?.toBase58() != k)
    }
}

/** Amount parser: every amount round-trips; overflow, signs, exponents, unicode digits and excess decimals are refused. */
@RunWith(Parameterized::class)
class AmountParseTest(private val text: String, private val decimals: Int, private val expected: Long?) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "''{0}'' @{1} -> {2}") fun data(): List<Array<Any?>> = listOf(
            Triple("1", 9, 1_000_000_000L), Triple("1,5", 9, 1_500_000_000L), Triple("1.5", 9, 1_500_000_000L), Triple("0,000000001", 9, 1L),
            Triple("0,0000000001", 9, null), Triple("9223372036,854775807", 9, Long.MAX_VALUE), Triple("9223372036,854775808", 9, null),
            Triple("99999999999999999999", 9, null), Triple("-1", 9, null), Triple("+1", 9, null), Triple("1e9", 9, null), Triple("1E9", 9, null),
            Triple("0", 9, null), Triple("0,0", 9, null), Triple("", 9, null), Triple(" ", 9, null), Triple(".", 9, null), Triple(",", 9, null),
            Triple("1,2,3", 9, null), Triple("1.2.3", 9, null), Triple("1 000", 9, null), Triple("１", 9, null), Triple("٣", 9, null), Triple("1\u00A0000", 9, null),
            Triple("NaN", 9, null), Triple("Infinity", 9, null), Triple("0x10", 9, null), Triple("1_000", 9, null), Triple("1'000", 9, null),
            Triple("  2,5  ", 9, 2_500_000_000L), Triple(",5", 9, 500_000_000L), Triple("5,", 9, 5_000_000_000L), Triple("1".repeat(41), 0, null),
            Triple("9223372036854775807", 0, Long.MAX_VALUE), Triple("9223372036854775808", 0, null), Triple("1,5", 0, null), Triple("1,50", 1, null),
            Triple("1,5", 1, 15L), Triple("0,000001", 6, 1L), Triple("18446744073709551615", 0, null), Triple("00000001", 9, 1_000_000_000L),
        ).map { arrayOf<Any?>(it.first, it.second, it.third) }
    }

    @Test fun parse() = assertEquals(expected, Amounts.parse(text, decimals))
}

@RunWith(Parameterized::class)
class AmountRoundTripTest(private val seed: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "seed {0}") fun data(): List<Array<Any>> = (1..200).map { arrayOf<Any>(it) }
    }

    @Test fun formatThenParseIsIdentity() {
        val r = Random(seed * 3L)
        val d = listOf(0, 1, 2, 6, 8, 9, 12, 18)[r.nextInt(8)]
        val v = 1L + (r.nextLong() and Long.MAX_VALUE) % (if (seed % 4 == 0) Long.MAX_VALUE else 1_000_000_000_000L)
        val text = Amounts.format(v, d)
        assertEquals(v, Amounts.parse(text, d))
        assertEquals(v, Amounts.parse(text.replace('.', ','), d))
        assertEquals(0, BigDecimal(text).compareTo(Amounts.toDecimal(v, d)))
    }
}

/** QR / pasted payloads: only a bare address or a transfer URI yields an address; everything else is refused. */
@RunWith(Parameterized::class)
class QrPayloadTest(private val payload: String, private val kind: String) {
    companion object {
        private const val A = "GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv"
        @JvmStatic @Parameterized.Parameters(name = "{1}: {0}") fun data(): List<Array<Any>> = listOf(
            A to "address", "solana:$A" to "address", "SOLANA:$A" to "address", "solana:$A?amount=1000000&label=x" to "address",
            "solana:$A#frag" to "address", "  $A  " to "address", "solana:$A?memo=${"m".repeat(200)}" to "address",
            "solana:https://evil.example/tx" to "request", "solana:HTTPS://evil.example" to "request", "solana:http%3A%2F%2Fx" to "request",
            "https://evil.example" to "request", "solana:$A?${"x".repeat(300)}" to "invalid", "solana:" to "invalid", "" to "invalid",
            "solana:${A.dropLast(1)}0" to "invalid", "solana:${A}O" to "invalid", "bitcoin:$A" to "invalid", "ethereum:0xabc" to "invalid",
            "solana:$A$A" to "invalid", "x".repeat(600) to "invalid", "solana:\u202E$A" to "invalid", "solana:$A\u200B" to "invalid",
            "javascript:alert(1)" to "invalid", "intent://scan#Intent;end" to "invalid", "file:///sdcard/x" to "invalid",
            "content://com.evil/1" to "invalid", "solana:/$A" to "invalid", "solana://$A" to "invalid", "${A.lowercase()}" to "invalid",
        ).map { arrayOf<Any>(it.first, it.second) }
    }

    @Test fun classified() {
        val res = SolanaUri.parse(payload)
        when (kind) {
            "address" -> assertEquals(SolanaUri.Result.Address(SolanaPublicKey.fromBase58(A)), res)
            "request" -> assertEquals(SolanaUri.Result.TransactionRequest, res)
            else -> assertEquals(SolanaUri.Result.Invalid, res)
        }
    }
}
