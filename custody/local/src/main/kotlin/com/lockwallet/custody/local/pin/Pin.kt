package com.lockwallet.custody.local.pin

import com.lockwallet.core.crypto.SecretBytes

/** PIN encoding (ADR 0002): only ASCII '0'..'9'; other Unicode digits are rejected, never converted. */
object Pin {
    const val LENGTH = 6
    const val MIN = 6
    const val MAX = 12

    fun isValid(pin: CharArray): Boolean = pin.size in MIN..MAX && pin.all { it in '0'..'9' }

    /** ASCII bytes of a valid PIN as an owned secret. Throws IllegalArgumentException otherwise. */
    fun encode(pin: CharArray): SecretBytes {
        require(isValid(pin)) { "invalid PIN" }
        return SecretBytes.adopt(ByteArray(pin.size) { pin[it].code.toByte() })
    }

    /** Rejects trivial runs, repeated blocks, paired sequences and six-digit calendar dates. */
    fun isTrivial(pin: CharArray): Boolean {
        if (pin.isEmpty()) return true
        if (pin.all { it == pin[0] }) return true
        val asc = (1 until pin.size).all {
            val d = pin[it].code - pin[it - 1].code
            d == 1 || d == -9
        }
        val desc = (1 until pin.size).all {
            val d = pin[it - 1].code - pin[it].code
            d == 1 || d == -9
        }
        if (asc || desc) return true

        val text = String(pin)
        if (pin.size % 2 == 0 && text.take(pin.size / 2) == text.drop(pin.size / 2)) return true
        if (pin.size == 6) {
            val pairs = text.chunked(2)
            val pairedRun = pairs.all { it.length == 2 && it[0] == it[1] } &&
                (1 until pairs.size).all { i ->
                    val d = pairs[i][0].code - pairs[i - 1][0].code
                    d == 1 || d == -1 || d == 9 || d == -9
                }
            if (pairedRun) return true

            fun validDayMonth(dayAt: Int, monthAt: Int): Boolean {
                val day = text.substring(dayAt, dayAt + 2).toInt()
                val month = text.substring(monthAt, monthAt + 2).toInt()
                return day in 1..31 && month in 1..12
            }
            if (validDayMonth(0, 2) || validDayMonth(2, 0) || validDayMonth(4, 2)) return true
        }
        return false
    }
}
