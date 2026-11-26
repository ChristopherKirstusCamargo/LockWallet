package com.lockwallet.app.ui

import com.lockwallet.core.solana.Amounts

/** pt-BR display of on-chain amounts (decimal comma), same text the amount fields accept back. Presentation only. */
object Fmt {
    fun sol(lamports: Long): String = Amounts.sol(lamports).replace('.', ',')
    /** Whole SOL with pt-BR thousands dots (validator stake): 1381585123456789 → "1.381.585". */
    fun solWhole(lamports: Long): String = (lamports / 1_000_000_000L).toString().reversed().chunked(3).joinToString(".").reversed()
    /** Ratio 0..1 as a pt-BR percentage with one decimal: 0.9984 → "99,8%". */
    fun pct1(r: Double): String = java.math.BigDecimal(r * 100).setScale(1, java.math.RoundingMode.DOWN).toPlainString().replace('.', ',') + "%"
    /** Basis points as a percentage: 500 → "5%", 250 → "2,5%". */
    fun bps(b: Int): String = java.math.BigDecimal(b).movePointLeft(2).stripTrailingZeros().toPlainString().replace('.', ',') + "%"
    fun units(units: Long, decimals: Int): String = Amounts.format(units, decimals).replace('.', ',')
}
