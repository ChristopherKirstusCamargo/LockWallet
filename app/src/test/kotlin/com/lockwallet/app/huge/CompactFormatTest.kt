package com.lockwallet.app.huge

import com.lockwallet.app.ui.Compact
import com.lockwallet.app.ui.Fmt
import com.lockwallet.data.price.Fiat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.math.BigDecimal
import java.util.Random

private const val NB = '\u00A0'

/** Reads a compact string back ("1,25 bi", "−3,10 mi", "4,56 × 10^24") as an exact decimal. */
internal fun parseCompact(s: String): BigDecimal {
    val neg = s.contains('−')
    val body = s.substring(s.indexOfFirst { it.isDigit() })
    val parts = body.split(NB)
    val mant = BigDecimal(parts[0].replace(".", "").replace(',', '.'))
    val mul = when {
        parts.size >= 3 && parts[1] == "×" -> BigDecimal.TEN.pow(parts[2].removePrefix("10^").toInt())
        else -> when (parts.getOrNull(1)) {
            "mi" -> BigDecimal.TEN.pow(6); "bi" -> BigDecimal.TEN.pow(9); "tri" -> BigDecimal.TEN.pow(12)
            "quatri" -> BigDecimal.TEN.pow(15); "quint" -> BigDecimal.TEN.pow(18); else -> error("no scale in $s")
        }
    }
    return mant.multiply(mul).let { if (neg) it.negate() else it }
}

private fun assertSane(s: String) {
    for (bad in listOf("NaN", "Infinity", "∞", "E+", "E-", "e+", "null")) assertFalse("$bad in $s", s.contains(bad))
    assertFalse("line break in $s", s.contains('\n'))
}

/** Exact expectations at and around every boundary. */
@RunWith(Parameterized::class)
class CompactBoundaryTest(private val input: String, private val expected: String?) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} -> {1}")
        fun data(): List<Array<Any?>> = listOf(
            "0" to null, "1" to null, "999999.99" to null, "999999.999999" to null,
            "1000000" to "1,00${NB}mi", "1000000.01" to "1,00${NB}mi", "1259000" to "1,25${NB}mi", "1259999.99" to "1,25${NB}mi",
            "9999999" to "9,99${NB}mi", "10000000" to "10,00${NB}mi", "999999999" to "999,99${NB}mi",
            "1000000000" to "1,00${NB}bi", "1250000000" to "1,25${NB}bi", "1259000000" to "1,25${NB}bi", "123456789012" to "123,45${NB}bi",
            "999999999999" to "999,99${NB}bi", "1000000000000" to "1,00${NB}tri", "7500000000000" to "7,50${NB}tri",
            "1000000000000000" to "1,00${NB}quatri", "1000000000000000000" to "1,00${NB}quint",
            "999999999999999999999" to "999,99${NB}quint", "1000000000000000000000" to "1,00${NB}×${NB}10^21",
            "9223372036854775807" to "9,22${NB}quint", "123456789012345678901234567890" to "1,23${NB}×${NB}10^29",
            "-1000000" to "−1,00${NB}mi", "-1259000000" to "−1,25${NB}bi", "-999999" to null, "-0.5" to null,
            "1E+6" to "1,00${NB}mi", "1E+9" to "1,00${NB}bi", "1E+21" to "1,00${NB}×${NB}10^21", "1E+100" to "1,00${NB}×${NB}10^100",
            "5.5E+30" to "5,50${NB}×${NB}10^30", "1234567.891" to "1,23${NB}mi", "2000000.5" to "2,00${NB}mi",
            "1999999999" to "1,99${NB}bi", "1000000000000000000000000" to "1,00${NB}×${NB}10^24", "0.000001" to null,
            "1000000.000000000000000001" to "1,00${NB}mi", "12345678901234567" to "12,34${NB}quatri",
        ).map { arrayOf<Any?>(it.first, it.second) }
    }

    @Test fun short() {
        val got = Compact.short(BigDecimal(input))
        if (expected == null) assertNull(got) else { assertEquals(expected, got); assertSane(got!!) }
    }
}

/** Random magnitudes 10^0..10^40: never overstates, never loses more than 1 % (two significant decimals), sane text. */
@RunWith(Parameterized::class)
class CompactPropertyTest(private val seed: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "seed {0}")
        fun data(): List<Array<Any>> = (1..400).map { arrayOf<Any>(it) }
    }

    @Test fun truncatesNeverOverstatesAndStaysShort() {
        val r = Random(seed.toLong() * 7919)
        val digits = 1 + r.nextInt(40)
        val sb = StringBuilder().append(1 + r.nextInt(9)); repeat(digits - 1) { sb.append(r.nextInt(10)) }
        val frac = if (r.nextBoolean()) "." + (1..(1 + r.nextInt(12))).joinToString("") { r.nextInt(10).toString() } else ""
        val neg = r.nextInt(5) == 0
        val v = BigDecimal((if (neg) "-" else "") + sb + frac)
        val s = Compact.short(v)
        if (v.abs() < Compact.THRESHOLD) { assertNull(s); return }
        assertTrue(s != null); assertSane(s!!)
        assertTrue("too long: $s", s.length <= 16)
        val back = parseCompact(s)
        assertTrue("overstated $v as $s", back.abs() <= v.abs())
        assertTrue("lost too much: $v as $s", back.abs().multiply(BigDecimal("1.01")) >= v.abs().multiply(BigDecimal("0.999")))
        assertEquals(v.signum(), back.signum())
        // pt-BR: decimal comma, exactly two fraction digits in the mantissa
        val mant = s.substring(s.indexOfFirst { it.isDigit() }).substringBefore(NB)
        assertTrue(s, Regex("^[0-9]{1,3}(\\.[0-9]{3})*,[0-9]{2}$").matches(mant))
    }
}

/** Every display currency × representative magnitudes: compact keeps the currency prefix of the full format. */
@RunWith(Parameterized::class)
class CompactFiatTest(private val fiat: Fiat, private val value: String) {
    companion object {
        private val VALUES = listOf("0", "0.01", "12.34", "999999.99", "1000000", "1250000000", "1259000000.99", "98765432109876",
            "1000000000000000000000", "-1500000", "-12.5", "123456789012345678901234567890.12")
        @JvmStatic @Parameterized.Parameters(name = "{0} {1}")
        fun data(): List<Array<Any>> = Fiat.entries.flatMap { f -> VALUES.map { arrayOf<Any>(f, it) } }
    }

    @Test fun compactAndExact() {
        val v = BigDecimal(value)
        val exact = Compact.exactFiat(v, fiat)
        val shown = Compact.fiat(v, fiat, compact = true)
        val full = Compact.fiat(v, fiat, compact = false)
        assertSane(exact); assertSane(shown); assertSane(full)
        assertEquals(exact, full)
        val prefix = Compact.fiatPrefix(fiat)
        assertTrue("prefix '$prefix' for $fiat", prefix.isNotBlank() && prefix.none { it.isDigit() })
        if (v.abs() < Compact.THRESHOLD) assertEquals(exact, shown)
        else {
            assertTrue("$shown keeps $prefix", shown.removePrefix("−").startsWith(prefix))
            assertTrue("compact $shown not shorter than $exact", shown.length < exact.length || v.abs() < BigDecimal(1e7))
            assertTrue(shown.length <= 22)
            assertTrue(parseCompact(shown).abs() <= v.abs())
        }
        // exact always has two decimals with a comma
        assertTrue(exact, Regex(",[0-9]{2}$").containsMatchIn(exact.trimEnd()))
    }
}

/** Lamports and token base units, from dust to Long.MAX_VALUE, at every decimals value the chain allows. */
@RunWith(Parameterized::class)
class CompactUnitsTest(private val units: Long, private val decimals: Int) {
    companion object {
        private val UNITS = listOf(0L, 1L, 9L, 999_999L, 1_000_000_000L, 999_999_999_999_999L, 1_000_000_000_000_000L,
            1_259_000_000_000_000_000L, 1_000_000_000_000_000_000L, Long.MAX_VALUE, Long.MAX_VALUE - 1, 4_611_686_018_427_387_904L,
            123_456_789_012_345_678L, 10_000_000_000_000_000L)
        private val DECIMALS = listOf(0, 1, 2, 6, 8, 9, 12, 18)
        @JvmStatic @Parameterized.Parameters(name = "{0} units, {1} dec")
        fun data(): List<Array<Any>> = UNITS.flatMap { u -> DECIMALS.map { d -> arrayOf<Any>(u, d) } }
    }

    @Test fun displayAndExact() {
        val shown = Compact.units(units, decimals, compact = true)
        val full = Compact.units(units, decimals, compact = false)
        val exact = Compact.exactUnits(units, decimals)
        assertSane(shown); assertSane(full); assertSane(exact)
        val v = BigDecimal(java.math.BigInteger.valueOf(units), decimals)
        // Display: at most two decimals, truncated; tiny nonzero amounts read "< 0,01", never "0,00".
        when {
            units == 0L -> assertEquals("0,00", full)
            v < BigDecimal("0.01") -> assertEquals("<${NB}0,01", full)
            else -> {
                val back = BigDecimal(full.replace(".", "").replace(',', '.'))
                assertEquals(2, full.substringAfter(',').length)
                assertTrue("$full overstates $v", back <= v); assertTrue("$full too far from $v", v - back < BigDecimal("0.01"))
            }
        }
        // The send review keeps every decimal below one million.
        assertEquals(Fmt.units(units, decimals), Compact.reviewUnits(units, decimals, compact = false))
        // exact text reads back to the same on-chain value
        assertEquals(0, BigDecimal(exact.replace(".", "").replace(',', '.')).compareTo(v))
        if (v < Compact.THRESHOLD) { assertEquals(full, shown); assertEquals(Fmt.units(units, decimals), Compact.reviewUnits(units, decimals)) } else {
            assertTrue(shown.length <= 16); assertTrue(parseCompact(shown) <= v)
        }
        if (decimals == 9) {
            assertTrue(Compact.sol(units).endsWith(" SOL")); assertTrue(Compact.exactSol(units).endsWith(" SOL"))
        }
    }
}

class CompactEdgeTest {
    @Test fun compactPreferenceOffShowsFullValues() {
        val v = BigDecimal("1259000000")
        assertEquals(Compact.exactFiat(v, Fiat.BRL), Compact.fiat(v, Fiat.BRL, compact = false))
        assertEquals("1,25${NB}bi SOL", Compact.solDecimal(v, true))
        assertEquals("1.259.000.000,00 SOL", Compact.solDecimal(v, false))
    }
    @Test fun brlPrefixIsTheRealSign() = assertTrue(Compact.fiat(BigDecimal("1259000000"), Fiat.BRL).startsWith("R$"))
    @Test fun negativeSignIsTypographicMinus() = assertTrue(Compact.fiat(BigDecimal("-2000000"), Fiat.USD).startsWith("−"))
    @Test fun exactSolGroupsThousandsAndKeepsAllDecimals() = assertEquals("9.223.372.036,854775807 SOL", Compact.exactSol(Long.MAX_VALUE))
}
