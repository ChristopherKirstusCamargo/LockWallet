package com.lockwallet.core.solana

import java.math.BigDecimal
import java.math.BigInteger

/** Integer base units ↔ decimal text. Decimals are presentation only (spec §14.1); no binary floating point. */
object Amounts {
    const val LAMPORTS_PER_SOL = 1_000_000_000L

    fun format(units: Long, decimals: Int): String = BigDecimal(BigInteger.valueOf(units), decimals).stripTrailingZeros().let {
        if (it.signum() == 0) "0" else it.toPlainString()
    }
    fun sol(lamports: Long): String = format(lamports, 9)
    fun toDecimal(units: Long, decimals: Int): BigDecimal = BigDecimal(BigInteger.valueOf(units), decimals)

    /** Parses "1", "1.5", "1,5", "0.000000001". Rejects signs, exponents, grouping, too many decimals, overflow, zero. */
    fun parse(text: String, decimals: Int): Long? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty() || t.length > 40 || !t.all { it.isDigit() && it < '\u0080' || it == '.' } || t.count { it == '.' } > 1) return null
        if (t == "." || t.startsWith(".") && t.length == 1) return null
        val parts = t.split('.')
        val frac = parts.getOrElse(1) { "" }
        if (frac.length > decimals) return null
        val v = try { BigDecimal(if (parts[0].isEmpty()) "0$t" else t).movePointRight(decimals).toBigIntegerExact() } catch (_: ArithmeticException) { return null } catch (_: NumberFormatException) { return null }
        if (v.signum() <= 0 || v.bitLength() > 63) return null
        return v.toLong()
    }
}
