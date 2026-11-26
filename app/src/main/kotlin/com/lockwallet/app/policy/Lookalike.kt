package com.lockwallet.app.policy

/**
 * Address-poisoning detector (spec §13.6). A destination is "similar" to a known address (own wallets, past
 * recipients, contacts) when it is not identical but shares what people actually compare by eye:
 *  - at least 3 leading and 3 trailing characters, or
 *  - at least 5 leading or 5 trailing characters.
 * Comparison ignores case, because o/O, l/L, x/X and similar pairs read alike on small screens. A random address
 * meets these rules by chance with probability around 10⁻⁹ per known address, so a warning is meaningful.
 */
object Lookalike {
    const val BOTH_ENDS = 3
    const val ONE_END = 5

    fun commonPrefix(a: String, b: String): Int { var i = 0; while (i < a.length && i < b.length && a[i].lowercaseChar() == b[i].lowercaseChar()) i++; return i }
    fun commonSuffix(a: String, b: String): Int { var i = 0; while (i < a.length && i < b.length && a[a.length - 1 - i].lowercaseChar() == b[b.length - 1 - i].lowercaseChar()) i++; return i }

    fun similar(destination: String, known: String): Boolean {
        if (destination == known) return false
        val p = commonPrefix(destination, known); val s = commonSuffix(destination, known)
        return (p >= BOTH_ENDS && s >= BOTH_ENDS) || p >= ONE_END || s >= ONE_END
    }

    /** First known address that [destination] imitates, or null. */
    fun find(destination: String, known: Iterable<String>): String? = known.firstOrNull { similar(destination, it) }
}
