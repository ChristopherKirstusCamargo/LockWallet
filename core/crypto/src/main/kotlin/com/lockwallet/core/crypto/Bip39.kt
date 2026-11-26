package com.lockwallet.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

/**
 * Supported BIP-39 sizes (spec §9.1, v2.1 §3). Creation offers the user 12 (default) or 24 words
 * ([CREATION_CHOICES], owner requirement 2026-11-19); restore and LWRP accept all five sizes.
 */
enum class MnemonicLength(val entropyBytes: Int, val words: Int) {
    WORDS_12(16, 12), WORDS_15(20, 15), WORDS_18(24, 18), WORDS_21(28, 21), WORDS_24(32, 24);

    companion object {
        /** What the creation ceremony offers. 15/18/21 are restore-only. */
        val CREATION_CHOICES: List<MnemonicLength> = listOf(WORDS_12, WORDS_24)
        val DEFAULT_CREATION: MnemonicLength = WORDS_12

        fun ofEntropyBytes(n: Int): MnemonicLength? = entries.firstOrNull { it.entropyBytes == n }
        fun ofWords(n: Int): MnemonicLength? = entries.firstOrNull { it.words == n }
    }
}

/** Why a mnemonic was rejected. Messages never contain words or secret material (spec §8). */
class Bip39Exception(val reason: Reason, val wordPosition: Int? = null) :
    IllegalArgumentException("BIP-39 rejected: $reason" + (wordPosition?.let { " at word #${it + 1}" } ?: "")) {
    enum class Reason { NOT_CANONICAL, INVALID_WORD_COUNT, UNKNOWN_WORD, INVALID_CHECKSUM, INVALID_ENTROPY_LENGTH }
}

/**
 * Independent BIP-39 implementation (English wordlist only).
 * https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki
 *
 * Input contract (ADR 0003): the core accepts a mnemonic only in *canonical form* — lowercase ASCII
 * words from the English list separated by exactly one ASCII space, no leading/trailing space. For such
 * input, Unicode NFKD is the identity, so the PBKDF2 password bytes equal the ASCII bytes and no String
 * copy of the secret is ever created. Tolerant parsing (case, extra spaces, autocomplete) belongs to the
 * UI layer, which must then hand over the canonical CharArray. Non-canonical input is rejected, never
 * silently normalised here.
 *
 * Seed derivation (PBKDF2-HMAC-SHA512, 2048 iterations, salt "mnemonic"+passphrase) uses the Bouncy
 * Castle lightweight API. A valid checksum is required before deriving (stricter than BIP-39, which
 * allows deriving from any sentence): the wallet never derives from a sentence it would not generate.
 */
object Bip39 {
    private const val PBKDF2_ITERATIONS = 2048
    private val SALT_PREFIX = "mnemonic".toByteArray(Charsets.US_ASCII)

    /** New entropy from [random] (default: platform SecureRandom; spec §4.1). */
    fun generateEntropy(length: MnemonicLength = MnemonicLength.WORDS_12, random: SecureRandom = SecureRandom()): SecretBytes {
        val e = ByteArray(length.entropyBytes)
        random.nextBytes(e)
        // Fail closed on a catastrophically broken RNG (constant output, e.g. all zero): never hand out such a seed.
        // ≥ 16 bytes of real entropy are all-equal with probability 2^-120; a false positive is not a concern.
        if (e.all { it == e[0] }) { e.fill(0); throw IllegalStateException("entropy source failure") }
        return SecretBytes.adopt(e)
    }

    /** Entropy (16/20/24/28/32 bytes) to canonical mnemonic chars. Caller owns and must wipe the result. */
    fun entropyToMnemonic(entropy: ByteArray): CharArray {
        val len = MnemonicLength.ofEntropyBytes(entropy.size)
            ?: throw Bip39Exception(Bip39Exception.Reason.INVALID_ENTROPY_LENGTH)
        val hash = sha256(entropy)
        val csBits = entropy.size * 8 / 32
        val totalBits = entropy.size * 8 + csBits
        val words = Bip39Wordlist.words
        val indices = IntArray(len.words)
        try {
            for (w in 0 until len.words) {
                var v = 0
                for (b in 0 until 11) {
                    val bit = w * 11 + b
                    val set = if (bit < entropy.size * 8) bitAt(entropy, bit) else bitAt(hash, bit - entropy.size * 8)
                    v = (v shl 1) or set
                }
                indices[w] = v
            }
            check(len.words * 11 == totalBits)
            val out = CharArray(indices.sumOf { words[it].length } + len.words - 1)
            var p = 0
            indices.forEachIndexed { i, idx ->
                if (i > 0) out[p++] = ' '
                for (c in words[idx]) out[p++] = c
            }
            return out
        } finally {
            indices.fill(0)
            hash.wipe()
        }
    }

    /** Canonical mnemonic to entropy, verifying word count, words and checksum. */
    fun mnemonicToEntropy(mnemonic: CharArray): SecretBytes {
        val indices = parseIndices(mnemonic)
        try {
            val len = MnemonicLength.ofWords(indices.size)!!
            val totalBits = indices.size * 11
            val entBits = len.entropyBytes * 8
            val bits = ByteArray((totalBits + 7) / 8)
            indices.forEachIndexed { w, idx ->
                for (b in 0 until 11) if ((idx shr (10 - b)) and 1 == 1) setBit(bits, w * 11 + b)
            }
            val entropy = bits.copyOf(len.entropyBytes)
            val hash = sha256(entropy)
            try {
                var ok = true
                for (i in 0 until totalBits - entBits) ok = ok and (bitAt(bits, entBits + i) == bitAt(hash, i))
                if (!ok) {
                    entropy.wipe()
                    throw Bip39Exception(Bip39Exception.Reason.INVALID_CHECKSUM)
                }
                return SecretBytes.adopt(entropy)
            } finally {
                bits.wipe()
                hash.wipe()
            }
        } finally {
            indices.fill(0)
        }
    }

    /** Validates the mnemonic (incl. checksum) and derives the 64-byte BIP-39 seed with an EMPTY passphrase. */
    fun mnemonicToSeed(mnemonic: CharArray): SecretBytes = seed(mnemonic, CharArray(0))

    /**
     * Passphrase variant. NOT part of the MVP product surface (spec §9.1, v2.1 §8): kept `internal` so only
     * official test vectors (which use passphrase "TREZOR") can exercise it. ASCII passphrases only.
     */
    internal fun mnemonicToSeedWithPassphrase(mnemonic: CharArray, passphrase: CharArray): SecretBytes =
        seed(mnemonic, passphrase)

    private fun seed(mnemonic: CharArray, passphrase: CharArray): SecretBytes {
        mnemonicToEntropy(mnemonic).close() // full validation first; fails closed
        require(passphrase.all { it.code in 0x20..0x7e }) { "only printable ASCII passphrase supported" }
        val password = ByteArray(mnemonic.size) { mnemonic[it].code.toByte() } // ASCII == NFKD here
        val salt = ByteArray(SALT_PREFIX.size + passphrase.size)
        SALT_PREFIX.copyInto(salt)
        passphrase.forEachIndexed { i, c -> salt[SALT_PREFIX.size + i] = c.code.toByte() }
        try {
            val gen = PKCS5S2ParametersGenerator(SHA512Digest())
            gen.init(password, salt, PBKDF2_ITERATIONS)
            val key = (gen.generateDerivedParameters(512) as KeyParameter).key
            try {
                return SecretBytes.copyOf(key)
            } finally {
                key.wipe()
            }
        } finally {
            password.wipe()
            salt.wipe()
        }
    }

    /** Parses canonical form into word indices; rejects anything else without echoing input. */
    private fun parseIndices(m: CharArray): IntArray {
        if (m.isEmpty() || m.first() == ' ' || m.last() == ' ') throw Bip39Exception(Bip39Exception.Reason.NOT_CANONICAL)
        var count = 1
        for (i in m.indices) {
            val c = m[i]
            if (c == ' ') {
                if (m[i - 1] == ' ') throw Bip39Exception(Bip39Exception.Reason.NOT_CANONICAL)
                count++
            } else if (c !in 'a'..'z') {
                throw Bip39Exception(Bip39Exception.Reason.NOT_CANONICAL)
            }
        }
        if (MnemonicLength.ofWords(count) == null) throw Bip39Exception(Bip39Exception.Reason.INVALID_WORD_COUNT)
        val out = IntArray(count)
        var start = 0
        var w = 0
        for (i in 0..m.size) {
            if (i == m.size || m[i] == ' ') {
                val idx = Bip39Wordlist.indexOf(m, start, i)
                if (idx < 0) {
                    out.fill(0)
                    throw Bip39Exception(Bip39Exception.Reason.UNKNOWN_WORD, w)
                }
                out[w++] = idx
                start = i + 1
            }
        }
        return out
    }

    private fun sha256(data: ByteArray): ByteArray {
        val d = SHA256Digest()
        d.update(data, 0, data.size)
        return ByteArray(32).also { d.doFinal(it, 0) }
    }

    private fun bitAt(a: ByteArray, bit: Int): Int = (a[bit / 8].toInt() shr (7 - bit % 8)) and 1
    private fun setBit(a: ByteArray, bit: Int) {
        a[bit / 8] = (a[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
    }
}
