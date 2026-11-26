package com.lockwallet.core.solana

/**
 * Base58 with the Bitcoin alphabet, as used by Solana for public keys, signatures and blockhashes.
 * Strict decoding: any character outside the alphabet (including whitespace) is rejected; no trimming.
 */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        val buf = input.copyOf()
        val out = CharArray(input.size * 2)
        var outStart = out.size
        var start = zeros
        while (start < buf.size) {
            var rem = 0
            for (i in start until buf.size) {
                val acc = (rem shl 8) or (buf[i].toInt() and 0xff)
                buf[i] = (acc / 58).toByte()
                rem = acc % 58
            }
            out[--outStart] = ALPHABET[rem]
            if (buf[start].toInt() == 0) start++
        }
        repeat(zeros) { out[--outStart] = '1' }
        return String(out, outStart, out.size - outStart)
    }

    /** Returns null for any invalid input. [maxDecodedLength] bounds work before decoding (anti-DoS). */
    fun decodeOrNull(input: CharSequence, maxDecodedLength: Int = 128): ByteArray? {
        if (input.isEmpty()) return ByteArray(0)
        // ceil(len * log(58)/log(256)) bound: a Base58 string longer than this cannot fit.
        if (input.length > maxDecodedLength * 138 / 100 + 1) return null
        val digits = IntArray(input.length)
        for (i in input.indices) {
            val c = input[i].code
            val d = if (c < 128) INDEX[c] else -1
            if (d < 0) return null
            digits[i] = d
        }
        var zeros = 0
        while (zeros < digits.size && digits[zeros] == 0) zeros++
        val tmp = ByteArray(input.length)
        var outStart = tmp.size
        var start = zeros
        while (start < digits.size) {
            var rem = 0
            for (i in start until digits.size) {
                val acc = rem * 58 + digits[i]
                digits[i] = acc / 256
                rem = acc % 256
            }
            tmp[--outStart] = rem.toByte()
            if (digits[start] == 0) start++
        }
        while (outStart < tmp.size && tmp[outStart].toInt() == 0) outStart++
        val len = zeros + (tmp.size - outStart)
        if (len > maxDecodedLength) { tmp.fill(0); return null }
        val out = ByteArray(len)
        tmp.copyInto(out, zeros, outStart, tmp.size)
        tmp.fill(0); digits.fill(0) // may hold private-key digits (keypair import)
        return out
    }
}
