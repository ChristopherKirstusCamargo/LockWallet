package com.lockwallet.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest

/**
 * Official BIP-39 English wordlist (spec §9.1: English only in the MVP).
 * The resource is pinned by SHA-256; a modified list fails closed at load time.
 * Source: https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt
 */
object Bip39Wordlist {
    const val SHA256_HEX = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"
    private const val RESOURCE = "/com/lockwallet/core/crypto/bip39-english.txt"

    /** The 2048 public words, index = 11-bit value. Public data, not secret. */
    val words: List<String>

    init {
        val raw = checkNotNull(Bip39Wordlist::class.java.getResourceAsStream(RESOURCE)) { "wordlist missing" }
            .use { it.readBytes() }
        val d = SHA256Digest()
        d.update(raw, 0, raw.size)
        val h = ByteArray(32).also { d.doFinal(it, 0) }
        check(h.toHex() == SHA256_HEX) { "BIP-39 wordlist hash mismatch" }
        val list = String(raw, Charsets.US_ASCII).split('\n').filter { it.isNotEmpty() }
        check(list.size == 2048) { "BIP-39 wordlist must have 2048 words" }
        check(list.all { w -> w.length in 3..8 && w.all { it in 'a'..'z' } })
        words = list
        check(list.toSet().size == 2048) { "duplicate words" }
        check(list.zipWithNext().all { (a, b) -> a < b }) { "wordlist must be sorted (binary search)" }
    }

    /** 11-bit index of a word given as chars [from, to) of [chars], or -1. Avoids creating String from secrets. */
    internal fun indexOf(chars: CharArray, from: Int, to: Int): Int {
        // Binary search on the sorted list comparing char by char (the official list is sorted).
        var lo = 0
        var hi = words.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(words[mid], chars, from, to)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun compare(word: String, chars: CharArray, from: Int, to: Int): Int {
        val n = to - from
        val m = minOf(word.length, n)
        for (i in 0 until m) {
            val d = word[i] - chars[from + i]
            if (d != 0) return d
        }
        return word.length - n
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
