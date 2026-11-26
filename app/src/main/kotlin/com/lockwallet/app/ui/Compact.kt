package com.lockwallet.app.ui

import com.lockwallet.core.solana.Amounts
import com.lockwallet.data.price.Fiat
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Display formatting for very large amounts (pt-BR). Below [THRESHOLD] values are shown in full; from one million
 * up they use short scale words truncated to two decimals, so a compact value never overstates what is held:
 * 1 259 000 000 → "1,25 bi". Beyond the named scales a power of ten is used. Pure BigDecimal: no binary floating
 * point, no overflow, no NaN. The exact value is always available through [exactFiat] / [exactSol] (accessibility
 * label and long press).
 */
object Compact {
    /** Mirrors the "Valores grandes" preference (set by the controller, like Motion.enabled). */
    @Volatile var enabled: Boolean = true
    val THRESHOLD: BigDecimal = BigDecimal(1_000_000)
    private val SCALES = listOf(
        BigDecimal.TEN.pow(18) to "quint", BigDecimal.TEN.pow(15) to "quatri", BigDecimal.TEN.pow(12) to "tri",
        BigDecimal.TEN.pow(9) to "bi", BigDecimal.TEN.pow(6) to "mi",
    )
    private val PT: Locale = Locale.forLanguageTag("pt-BR")
    private const val NBSP = '\u00A0'
    private val CENT = BigDecimal("0.01")

    /** "1,25 bi" for |v| ≥ 1 million; otherwise null (caller shows the full value). */
    fun short(v: BigDecimal): String? {
        val abs = v.abs()
        if (abs < THRESHOLD) return null
        val sign = if (v.signum() < 0) "−" else ""
        val top = SCALES.first().first
        if (abs >= top.multiply(BigDecimal(1000))) {
            // 10^21 and up: mantissa × 10^n, mantissa in [1, 10).
            val exp = abs.precision() - abs.scale() - 1
            val mant = abs.movePointLeft(exp).setScale(2, RoundingMode.DOWN)
            return sign + decimal(mant) + "${NBSP}×${NBSP}10^" + exp
        }
        val (div, word) = SCALES.first { abs >= it.first }
        return sign + decimal(abs.divide(div, 2, RoundingMode.DOWN)) + NBSP + word
    }

    /** Two-decimal pt-BR number: 1234.5 → "1.234,50". */
    fun decimal(v: BigDecimal): String = NumberFormat.getNumberInstance(PT).apply {
        minimumFractionDigits = 2; maximumFractionDigits = 2; roundingMode = RoundingMode.DOWN
    }.format(v)

    /** Currency prefix exactly as the full formatter writes it ("R$", "US$", "€"…). */
    fun fiatPrefix(f: Fiat): String {
        val zero = exactFiat(BigDecimal.ZERO, f)
        val i = zero.indexOfFirst { it.isDigit() }
        return if (i <= 0) f.name + NBSP else zero.substring(0, i)
    }

    fun exactFiat(v: BigDecimal, f: Fiat): String {
        val nf = NumberFormat.getCurrencyInstance(PT)
        nf.currency = Currency.getInstance(f.name)
        nf.minimumFractionDigits = 2; nf.maximumFractionDigits = 2
        nf.roundingMode = RoundingMode.HALF_EVEN
        return nf.format(v.setScale(2, RoundingMode.HALF_EVEN))
    }

    /** Fiat for display: full below one million (or when [compact] is off), compact above. */
    fun fiat(v: BigDecimal, f: Fiat, compact: Boolean = true): String {
        if (v.signum() != 0 && v.abs() < CENT) return (if (v.signum() < 0) "−" else "") + "<" + NBSP + fiatPrefix(f) + decimal(CENT)
        val s = if (compact) short(v) else null
        if (s == null) return exactFiat(v, f)
        return if (s.startsWith("−")) "−" + fiatPrefix(f) + s.drop(1) else fiatPrefix(f) + s
    }

    /** Exact SOL text from lamports: "1.234.567,123456789 SOL" (thousands grouped, all significant decimals). */
    fun exactSol(lamports: Long): String = exactUnits(lamports, 9) + " SOL"

    fun exactUnits(units: Long, decimals: Int): String {
        val d = Amounts.toDecimal(units, decimals).stripTrailingZeros()
        val nf = NumberFormat.getNumberInstance(PT).apply {
            minimumFractionDigits = 0; maximumFractionDigits = decimals.coerceIn(0, 18); roundingMode = RoundingMode.DOWN
        }
        return nf.format(d)
    }

    /** SOL for display: Fmt.sol below one million SOL (or when [compact] is off), compact above. */
    fun sol(lamports: Long, compact: Boolean = true): String = units(lamports, 9, compact) + " SOL"

    /** Display quantity: compact from one million (when [compact]), otherwise two decimals ([display]). */
    fun units(units: Long, decimals: Int, compact: Boolean = true): String {
        val d = Amounts.toDecimal(units, decimals)
        return (if (compact) short(d) else null) ?: display(d)
    }

    /**
     * Send review only: compact from one million, otherwise every significant decimal, so the hero shows exactly
     * what is signed. (Display screens use [units].)
     */
    fun reviewUnits(units: Long, decimals: Int, compact: Boolean = true): String {
        val d = Amounts.toDecimal(units, decimals)
        return (if (compact) short(d) else null) ?: Fmt.units(units, decimals)
    }

    /** A SOL quantity already in decimal form (e.g. a fiat value divided by the price). */
    fun solDecimal(v: BigDecimal, compact: Boolean = true): String =
        ((if (compact) short(v) else null) ?: display(v)) + " SOL"

    /**
     * Two decimals, truncated (never shows more than is held): 10,419 → "10,41". A nonzero amount below one cent of a
     * unit is shown as "< 0,01", never as a misleading "0,00". Exact values stay on [exactUnits] / [exactSol].
     */
    fun display(v: BigDecimal): String {
        val abs = v.abs()
        if (abs.signum() != 0 && abs < CENT) return (if (v.signum() < 0) "−" else "") + "<" + NBSP + decimal(CENT)
        return decimal(v)
    }
}
