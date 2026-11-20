package com.lockwallet.custody.local.pin

import com.lockwallet.core.crypto.SecretBytes
import com.lockwallet.core.crypto.KdfProfile

/** PIN encoding (ADR 0002): only ASCII '0'..'9'; other Unicode digits are rejected, never converted. */
object Pin {
    const val LEGACY_LENGTH = 6
    const val LENGTH = 12
    const val MIN = LEGACY_LENGTH
    const val MAX = LENGTH

    /** Generic format check used by parsers; profile-aware validation is mandatory at vault boundaries. */
    fun isValid(pin: CharArray): Boolean = pin.size in MIN..MAX && pin.all { it in '0'..'9' }

    /** Exact length is bound to the envelope KDF profile so legacy six-digit vaults remain readable. */
    fun isValid(pin: CharArray, profile: KdfProfile): Boolean =
        isValid(pin) && pin.size == when (profile) {
            KdfProfile.OWASP_V1 -> LEGACY_LENGTH
            KdfProfile.HARDENED_V2 -> LENGTH
        }

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

        if (pin.size % 2 == 0) {
            val half = pin.size / 2
            if ((0 until half).all { pin[it] == pin[it + half] }) return true
        }
        if (pin.size == 6) {
            fun samePair(at: Int) = pin[at] == pin[at + 1]
            val pairedRun = (0..2).all { samePair(it * 2) } &&
                (1..2).all { i ->
                    val d = pin[i * 2].code - pin[(i - 1) * 2].code
                    d == 1 || d == -1 || d == 9 || d == -9
                }
            if (pairedRun) return true

            fun twoDigits(at: Int) = (pin[at].code - '0'.code) * 10 + (pin[at + 1].code - '0'.code)
            fun validDayMonth(dayAt: Int, monthAt: Int): Boolean {
                val day = twoDigits(dayAt)
                val month = twoDigits(monthAt)
                return day in 1..31 && month in 1..12
            }
            if (validDayMonth(0, 2) || validDayMonth(2, 0) || validDayMonth(4, 2)) return true
        }
        return false
    }
}
