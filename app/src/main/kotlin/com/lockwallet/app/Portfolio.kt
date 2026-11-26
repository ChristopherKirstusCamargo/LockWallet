package com.lockwallet.app

import java.math.BigDecimal
import java.math.MathContext

/** One priced holding: its current fiat value and the 24 h price change ratio of its asset (null = unknown). */
data class Holding(val value: BigDecimal, val change24h: BigDecimal?)

/** Change of what the user actually holds: [changeValue] in fiat and [ratio] relative to the value 24 h ago. */
data class PortfolioChange(val total: BigDecimal, val changeValue: BigDecimal, val ratio: BigDecimal)

/**
 * Portfolio variation (v3 fix): the market move of an asset only counts in proportion to how much of it is held.
 * Holding nothing ⇒ no variation at all (a SOL price move is not a profit for someone without SOL). Pure, exact
 * decimals; unpriced holdings are excluded rather than guessed.
 */
object Portfolio {
    private val MC = MathContext.DECIMAL64

    fun change(holdings: List<Holding>): PortfolioChange? {
        val held = holdings.filter { it.value.signum() > 0 }
        val total = held.fold(BigDecimal.ZERO) { a, h -> a + h.value }
        if (total.signum() <= 0) return null
        val moving = held.filter { it.change24h != null && it.change24h > BigDecimal.ONE.negate() }
        if (moving.isEmpty()) return null
        // value now = value24h × (1 + r) ⇒ change = value × r / (1 + r)
        val cv = moving.fold(BigDecimal.ZERO) { a, h -> a + h.value.multiply(h.change24h!!, MC).divide(BigDecimal.ONE + h.change24h, MC) }
        val before = total - cv
        if (before.signum() <= 0) return null
        return PortfolioChange(total, cv, cv.divide(before, MC))
    }
}
