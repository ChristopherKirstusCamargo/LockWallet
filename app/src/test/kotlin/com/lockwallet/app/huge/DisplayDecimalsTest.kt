package com.lockwallet.app.huge

import com.lockwallet.app.ui.Compact
import com.lockwallet.app.ui.Fmt
import com.lockwallet.data.price.Fiat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.math.BigDecimal
import kotlin.random.Random

private const val NBSP = '\u00A0'

/**
 * Display amounts (home, token rows, Privado, stake, history) show at most two decimals, truncated, so they never
 * overstate a balance; a nonzero amount under one cent reads "< 0,01", never "0,00"; the exact value and the send
 * review keep every decimal. Random SOL and token amounts with fixed seeds, below the compact threshold.
 */
@RunWith(Parameterized::class)
class DisplayDecimalsTest(private val seed: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "seed {0}") fun data(): List<Array<Any>> = (1..200).map { arrayOf<Any>(it) }
    }

    @Test fun twoDecimalsNeverOverstateAndTinyIsNotZero() {
        val r = Random(seed)
        val decimals = listOf(0, 2, 5, 6, 8, 9)[r.nextInt(6)]
        // magnitudes from a single base unit up to just under one million whole units
        val digits = r.nextInt(1, decimals + 7)
        val units = (1..digits).fold(0L) { acc, _ -> acc * 10 + r.nextInt(10) }.coerceAtLeast(1L)
        val v = BigDecimal(java.math.BigInteger.valueOf(units), decimals)
        val shown = Compact.units(units, decimals)
        if (v < BigDecimal("0.01")) assertEquals("<${NBSP}0,01", shown)
        else {
            assertTrue(shown, Regex("""^\d{1,3}(\.\d{3})*,\d{2}$""").matches(shown))
            val back = BigDecimal(shown.replace(".", "").replace(',', '.'))
            assertTrue("$shown > $v", back <= v); assertTrue("$shown far from $v", v - back < BigDecimal("0.01"))
        }
        assertEquals(Fmt.units(units, decimals), Compact.reviewUnits(units, decimals))
        assertEquals(0, BigDecimal(Compact.exactUnits(units, decimals).replace(".", "").replace(',', '.')).compareTo(v))
        if (decimals == 9) assertEquals("$shown SOL", Compact.sol(units))
    }
}

class DisplayDecimalsEdgeTest {
    @Test fun examplesFromTheOwner() {
        assertEquals("10,40", Compact.display(BigDecimal("10.4")))
        assertEquals("10,41", Compact.display(BigDecimal("10.419999")))
        assertEquals("0,10 SOL", Compact.sol(100_000_000))
        assertEquals("2,00 SOL", Compact.sol(2_000_000_000))
    }
    @Test fun zeroIsZero() { assertEquals("0,00 SOL", Compact.sol(0)); assertEquals("R$${NBSP}0,00", Compact.fiat(BigDecimal.ZERO, Fiat.BRL)) }
    @Test fun oneLamportIsNotZero() = assertEquals("<${NBSP}0,01 SOL", Compact.sol(1))
    @Test fun tinyFiatIsNotZero() = assertEquals("<${NBSP}R$${NBSP}0,01", Compact.fiat(BigDecimal("0.004"), Fiat.BRL))
    @Test fun oneCentIsShown() = assertEquals("R$${NBSP}0,01", Compact.fiat(BigDecimal("0.01"), Fiat.BRL))
    @Test fun solEquivalentHasTwoDecimals() = assertEquals("5,12 SOL", Compact.solDecimal(BigDecimal("5.123456789")))
    @Test fun exactValuesKeepEveryDecimal() {
        assertEquals("0,000000001 SOL", Compact.exactSol(1))
        assertEquals("0,000000001", Compact.reviewUnits(1, 9))
    }
}
