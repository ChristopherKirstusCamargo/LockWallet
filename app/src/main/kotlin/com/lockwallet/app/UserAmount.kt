package com.lockwallet.app

import com.lockwallet.app.ui.S
import com.lockwallet.core.solana.Amounts

/**
 * Amounts typed by the user (pt-BR). On top of the strict core parser:
 *  - "1.000" / "12.500" (dot + exactly 3 digits, non-zero integer part, no comma) is refused as ambiguous: in pt-BR
 *    it reads as one thousand, while the parser would read one. Comma is the decimal separator.
 *  - SOL is capped far below Long.MAX_VALUE so every later sum (amount + rent + fee) is overflow-free.
 */
object UserAmount {
    /** 10^9 SOL in lamports — above the whole supply, ~9 000× below Long.MAX_VALUE. */
    const val MAX_SOL_LAMPORTS = 1_000_000_000L * 1_000_000_000L
    private val AMBIGUOUS = Regex("^0*[1-9][0-9]{0,2}\\.[0-9]{3}$")

    fun ambiguous(text: String): Boolean = text.trim().let { !it.contains(',') && AMBIGUOUS.matches(it) }

    fun units(text: String, decimals: Int): Long {
        if (ambiguous(text)) throw ChainModel.UserError(S.AMBIGUOUS_AMOUNT)
        return Amounts.parse(text, decimals) ?: throw ChainModel.UserError(S.BAD_AMOUNT)
    }

    fun sol(text: String): Long = units(text, 9).also { if (it > MAX_SOL_LAMPORTS) throw ChainModel.UserError(S.BAD_AMOUNT) }

    /** Same rules, no exception (for live hints). */
    fun solOrNull(text: String): Long? = runCatching { sol(text) }.getOrNull()
}
