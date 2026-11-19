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

    /** Rejects trivially guessable PINs (all equal, ascending/descending runs). */
    fun isTrivial(pin: CharArray): Boolean {
        if (pin.isEmpty()) return true
        if (pin.all { it == pin[0] }) return true
        val asc = (1 until pin.size).all { pin[it] - pin[it - 1] == 1 }
        val desc = (1 until pin.size).all { pin[it - 1] - pin[it] == 1 }
        return asc || desc
    }
}
