package com.lockwallet.custody.local.material

import com.lockwallet.core.crypto.Bip39
import com.lockwallet.core.crypto.Bip39Exception
import com.lockwallet.core.crypto.Bip39Wordlist
import com.lockwallet.core.crypto.DerivationProfile
import com.lockwallet.core.crypto.MnemonicLength
import com.lockwallet.core.crypto.Ed25519Signer
import com.lockwallet.core.crypto.SecretBytes
import com.lockwallet.core.crypto.Slip10Ed25519
import com.lockwallet.core.crypto.wipe
import com.lockwallet.core.solana.Base58
import com.lockwallet.core.solana.SolanaPublicKey
import java.nio.CharBuffer
import java.security.SecureRandom

/** Derivation profiles visible to the UI (ids = LWLE/LWRP derivation_profile byte). */
enum class AccountProfile(val id: Int, val pathTemplate: String) {
    PRIMARY(0x01, "m/44'/501'/%d'/0'"),
    LEGACY(0x02, "m/44'/501'/%d'"),
    /** Imported keypair: no derivation. */
    NONE(0x00, "—");

    fun path(index: Int) = if (this == NONE) pathTemplate else pathTemplate.format(index)
    internal fun core(): DerivationProfile? = DerivationProfile.ofId(id)
    companion object { fun ofId(id: Int) = entries.firstOrNull { it.id == id } }
}

data class DiscoveredAccount(val profile: AccountProfile, val index: Int, val address: SolanaPublicKey) {
    val path: String get() = profile.path(index)
}

/** Why user-provided material was refused. Messages never echo the input. */
enum class MaterialError {
    WORD_COUNT, UNKNOWN_WORD, CHECKSUM, NOT_CANONICAL,
    KEY_FORMAT, KEY_LENGTH, KEYPAIR_INCONSISTENT, JSON_FORMAT, TOO_LARGE,
}

class MaterialException(val error: MaterialError, val wordPosition: Int? = null) : IllegalArgumentException("material rejected: $error")

/**
 * Secret material that is about to be sealed (created, or restored from phrase / key / LWRP). Owned by
 * :custody:local; the UI only gets display words (CharArray, wiped on [close]) and public addresses.
 */
/** One confirmation question; [position] is 1-based. */
class QuizQuestion(val position: Int, val options: List<CharArray>) {
    fun wipe() = options.forEach { it.wipe() }
}

class PendingMaterial private constructor(
    internal val kind: Int,
    internal val secret: SecretBytes,
    /** For an LWRP import the package states which account it protects. */
    val suggested: DiscoveredAccount?,
) : AutoCloseable {

    val isMnemonic: Boolean get() = kind == KIND_BIP39
    val wordCount: Int get() = if (isMnemonic) secret.size * 3 / 4 else 0

    /** Display copy of the words (each a fresh CharArray). The caller must wipe them. */
    fun words(): List<CharArray> {
        check(isMnemonic)
        val all = secret.withBytes { Bip39.entropyToMnemonic(it) }
        try {
            val out = mutableListOf<CharArray>()
            var start = 0
            for (i in 0..all.size) {
                if (i == all.size || all[i] == ' ') { out += all.copyOfRange(start, i); start = i + 1 }
            }
            return out
        } finally {
            all.wipe()
        }
    }

    /** Offline account listing (RPC activity check comes with :data:rpc in Phase D). */
    fun discover(count: Int = 10): List<DiscoveredAccount> {
        if (!isMnemonic) return listOf(DiscoveredAccount(AccountProfile.NONE, 0, keypairPublicKey()))
        require(count in 1..50)
        return seed { s ->
            listOf(AccountProfile.PRIMARY, AccountProfile.LEGACY).flatMap { p ->
                (0 until count).map { i -> DiscoveredAccount(p, i, derive(s, p, i)) }
            }
        }
    }

    /** One specific account (e.g. "add account" = next index of an existing seed). */
    fun account(profile: AccountProfile, index: Int): DiscoveredAccount = DiscoveredAccount(profile, index, publicKeyFor(profile, index))

    /**
     * Backup confirmation (spec §9): [questions] distinct random positions, each with [choices] options
     * (the right word + distinct random BIP-39 decoys), shuffled with SecureRandom. Options must be wiped by the caller.
     */
    fun quiz(random: SecureRandom = SecureRandom(), questions: Int = 3, choices: Int = 4): List<QuizQuestion> {
        val words = words()
        try {
            val positions = words.indices.shuffled(random).take(questions).sorted()
            return positions.map { pos ->
                val right = words[pos]
                val decoys = mutableListOf<CharArray>()
                while (decoys.size < choices - 1) {
                    val w = Bip39Wordlist.words[random.nextInt(2048)]
                    if (!w.toCharArray().contentEquals(right) && decoys.none { it.contentEquals(w.toCharArray()) }) decoys += w.toCharArray()
                }
                QuizQuestion(pos + 1, (decoys + right.copyOf()).shuffled(random))
            }
        } finally {
            words.forEach { it.wipe() }
        }
    }

    /** [position] is 1-based. Constant-shape comparison of one word; nothing is returned but the boolean. */
    fun isWordAt(position: Int, candidate: CharArray): Boolean {
        val words = words()
        try {
            return position in 1..words.size && words[position - 1].contentEquals(candidate)
        } finally {
            words.forEach { it.wipe() }
        }
    }

    internal fun publicKeyFor(profile: AccountProfile, index: Int): SolanaPublicKey =
        if (!isMnemonic) { require(profile == AccountProfile.NONE && index == 0); keypairPublicKey() }
        else { require(profile != AccountProfile.NONE && index >= 0); seed { derive(it, profile, index) } }

    /** Seed-group fingerprint: first 16 bytes of the m/44'/501'/0'/0' (or keypair) public key — public data. */
    internal fun groupFingerprint(): ByteArray =
        (if (isMnemonic) publicKeyFor(AccountProfile.PRIMARY, 0) else keypairPublicKey()).toByteArray().copyOf(16)

    internal fun copySecret(): SecretBytes = secret.copy()

    /**
     * Signs [message] with the key of ([profile], [index]); verifies the derived public key equals [expected]
     * and the signature verifies, wiping the private key before returning. Only called by [UnlockedWallet.signApproved].
     */
    internal fun signWith(profile: AccountProfile, index: Int, expected: SolanaPublicKey, message: ByteArray): ByteArray {
        val sig: ByteArray
        if (!isMnemonic) {
            require(profile == AccountProfile.NONE && index == 0)
            if (keypairPublicKey() != expected) throw IllegalStateException("key mismatch")
            sig = secret.withBytes { b -> SecretBytes.copyOf(b, 0, 32).use { sk -> Ed25519Signer.sign(sk, message) } }
        } else {
            sig = seed { s ->
                Slip10Ed25519.deriveSolanaKey(s, profile.core()!!, index).use { k ->
                    if (!SolanaPublicKey.of(k.publicKey()).equals(expected)) throw IllegalStateException("key mismatch")
                    Ed25519Signer.sign(k.privateKey, message)
                }
            }
        }
        if (!Ed25519Signer.verify(expected.toByteArray(), message, sig)) throw IllegalStateException("signature self-check failed")
        return sig
    }

    private fun <R> seed(block: (ByteArray) -> R): R {
        val words = secret.withBytes { Bip39.entropyToMnemonic(it) }
        try {
            return Bip39.mnemonicToSeed(words).use { s -> s.withBytes(block) }
        } finally {
            words.wipe()
        }
    }

    private fun derive(seed: ByteArray, p: AccountProfile, i: Int): SolanaPublicKey =
        Slip10Ed25519.deriveSolanaKey(seed, p.core()!!, i).use { SolanaPublicKey.of(it.publicKey()) }

    private fun keypairPublicKey(): SolanaPublicKey = secret.withBytes { SolanaPublicKey.of(it.copyOfRange(32, 64)) }

    override fun close() = secret.close()
    override fun toString() = "PendingMaterial(REDACTED)"

    companion object {
        internal const val KIND_BIP39 = 0x01
        internal const val KIND_KEYPAIR = 0x02
        val CREATION_WORD_COUNTS = MnemonicLength.CREATION_CHOICES.map { it.words }
        val RESTORE_WORD_COUNTS = MnemonicLength.entries.map { it.words }

        /** New wallet: 12 or 24 words only (prompt 1.2). */
        fun generate(words: Int, random: SecureRandom = SecureRandom()): PendingMaterial {
            val len = MnemonicLength.CREATION_CHOICES.firstOrNull { it.words == words } ?: throw MaterialException(MaterialError.WORD_COUNT)
            return PendingMaterial(KIND_BIP39, Bip39.generateEntropy(len, random), null)
        }

        /**
         * Tolerant phrase input (ADR 0003: tolerance lives here, the core accepts canonical only): ASCII letters are
         * lower-cased and any Unicode whitespace run becomes one space. Non-ASCII letters are refused (English list only).
         * 12/15/18/21/24 words; checksum enforced. [input] is not modified; the canonical copy is wiped.
         */
        fun fromPhrase(input: CharArray): PendingMaterial {
            val canon = CharArray(input.size)
            var n = 0
            var pendingSpace = false
            for (c in input) {
                when {
                    Character.isWhitespace(c) || Character.isSpaceChar(c) -> if (n > 0) pendingSpace = true
                    c in 'a'..'z' || c in 'A'..'Z' -> {
                        if (pendingSpace) { canon[n++] = ' '; pendingSpace = false }
                        canon[n++] = c.lowercaseChar()
                    }
                    else -> { canon.wipe(); throw MaterialException(MaterialError.NOT_CANONICAL) }
                }
            }
            val phrase = canon.copyOf(n)
            canon.wipe()
            try {
                val entropy = Bip39.mnemonicToEntropy(phrase)
                return PendingMaterial(KIND_BIP39, entropy, null)
            } catch (e: Bip39Exception) {
                throw MaterialException(
                    when (e.reason) {
                        Bip39Exception.Reason.INVALID_WORD_COUNT, Bip39Exception.Reason.INVALID_ENTROPY_LENGTH -> MaterialError.WORD_COUNT
                        Bip39Exception.Reason.UNKNOWN_WORD -> MaterialError.UNKNOWN_WORD
                        Bip39Exception.Reason.INVALID_CHECKSUM -> MaterialError.CHECKSUM
                        Bip39Exception.Reason.NOT_CANONICAL -> MaterialError.NOT_CANONICAL
                    }, e.wordPosition,
                )
            } finally {
                phrase.wipe()
            }
        }

        /** Base58 private key as exported by common wallets: 64 bytes (secret‖public) or a 32-byte secret. */
        fun fromBase58PrivateKey(input: CharArray): PendingMaterial {
            val trimmed = trim(input)
            try {
                val raw = Base58.decodeOrNull(CharBuffer.wrap(trimmed), 64) ?: throw MaterialException(MaterialError.KEY_FORMAT)
                return fromRawKey(raw)
            } finally {
                trimmed.wipe()
            }
        }

        /** solana-keygen JSON: `[n0, …, n63]`, integers 0..255, nothing else. */
        fun fromKeypairJson(input: CharArray): PendingMaterial {
            if (input.size > 1024) throw MaterialException(MaterialError.TOO_LARGE)
            val bytes = ByteArray(64)
            var count = 0
            var i = 0
            fun ws() { while (i < input.size && input[i].isWhitespace()) i++ }
            try {
                ws(); if (i >= input.size || input[i] != '[') throw MaterialException(MaterialError.JSON_FORMAT); i++
                while (true) {
                    ws()
                    var v = 0; var digits = 0
                    while (i < input.size && input[i] in '0'..'9') { v = v * 10 + (input[i] - '0'); digits++; i++; if (digits > 3) break }
                    if (digits == 0 || digits > 3 || v > 255 || (digits > 1 && input[i - digits] == '0')) throw MaterialException(MaterialError.JSON_FORMAT)
                    if (count >= 64) throw MaterialException(MaterialError.KEY_LENGTH)
                    bytes[count++] = v.toByte()
                    ws()
                    if (i < input.size && input[i] == ',') { i++; continue }
                    if (i < input.size && input[i] == ']') { i++; break }
                    throw MaterialException(MaterialError.JSON_FORMAT)
                }
                ws(); if (i != input.size) throw MaterialException(MaterialError.JSON_FORMAT)
                if (count != 64) throw MaterialException(MaterialError.KEY_LENGTH)
                return fromRawKey(bytes.copyOf())
            } finally {
                bytes.wipe()
            }
        }

        internal fun fromLwrp(kind: Int, material: SecretBytes, suggested: DiscoveredAccount): PendingMaterial =
            PendingMaterial(kind, material, suggested)

        internal fun fromEnvelope(kind: Int, material: SecretBytes): PendingMaterial = PendingMaterial(kind, material, null)

        /** Takes ownership of [raw]. */
        private fun fromRawKey(raw: ByteArray): PendingMaterial {
            try {
                when (raw.size) {
                    64 -> {
                        val sk = raw.copyOfRange(0, 32)
                        val pub = try { Slip10Ed25519.publicKeyOf(sk) } finally { sk.wipe() }
                        if (!pub.contentEquals(raw.copyOfRange(32, 64))) throw MaterialException(MaterialError.KEYPAIR_INCONSISTENT)
                        return PendingMaterial(KIND_KEYPAIR, SecretBytes.copyOf(raw), null)
                    }
                    32 -> {
                        val pub = Slip10Ed25519.publicKeyOf(raw)
                        return PendingMaterial(KIND_KEYPAIR, SecretBytes.adopt(raw + pub), null)
                    }
                    else -> throw MaterialException(MaterialError.KEY_LENGTH)
                }
            } finally {
                raw.wipe()
            }
        }

        private fun trim(c: CharArray): CharArray {
            var s = 0; var e = c.size
            while (s < e && c[s].isWhitespace()) s++
            while (e > s && c[e - 1].isWhitespace()) e--
            return c.copyOfRange(s, e)
        }
    }
}
