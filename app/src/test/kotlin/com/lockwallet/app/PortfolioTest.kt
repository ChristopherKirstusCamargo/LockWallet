package com.lockwallet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/** v3 fix: the home variation is what the user's holdings did, never the bare SOL price move. */
class PortfolioTest {
    private fun d(s: String) = BigDecimal(s)

    @Test fun zeroBalanceHasNoVariationEvenWhenSolMoved() {
        assertNull(Portfolio.change(listOf(Holding(d("0"), d("0.05")))))
        assertNull(Portfolio.change(emptyList()))
    }

    @Test fun unknownChangeIsNotGuessed() {
        assertNull(Portfolio.change(listOf(Holding(d("100"), null))))
        assertNull(Portfolio.change(listOf(Holding(d("100"), d("-1")))))   // impossible −100 % move is ignored
    }

    @Test fun singleHoldingMatchesItsPriceMove() {
        // 105 now, +5 % ⇒ was 100 ⇒ +5 fiat, +5 %.
        val c = Portfolio.change(listOf(Holding(d("105"), d("0.05"))))!!
        assertEquals(0, c.changeValue.compareTo(d("5")))
        assertEquals(0, c.ratio.compareTo(d("0.05")))
    }

    @Test fun mixedHoldingsAreValueWeighted() {
        // SOL 110 now (+10 % ⇒ +10), stable 100 with unknown change (counts in total, not in the move) ⇒ +10 on 200.
        val c = Portfolio.change(listOf(Holding(d("110"), d("0.10")), Holding(d("100"), null)))!!
        assertEquals(0, c.total.compareTo(d("210")))
        assertEquals(0, c.changeValue.setScale(8, java.math.RoundingMode.HALF_EVEN).compareTo(d("10")))
        assertEquals(0, c.ratio.setScale(10, java.math.RoundingMode.HALF_EVEN).compareTo(d("0.05")))
    }

    @Test fun lossIsNegative() {
        val c = Portfolio.change(listOf(Holding(d("90"), d("-0.10"))))!!
        assertEquals(-1, c.changeValue.signum())
        assertEquals(0, c.changeValue.compareTo(d("-10")))
    }
}
