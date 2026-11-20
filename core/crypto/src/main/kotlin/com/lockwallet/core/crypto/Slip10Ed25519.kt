package com.lockwallet.core.crypto

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc8032.Ed25519

/**
 * Solana derivation profiles (spec §9.2, v2.1 §3 `derivation_profile`). Identified by number, never by text.
 * Every level is hardened; SLIP-0010 ed25519 has no non-hardened derivation.
 */
enum class DerivationProfile(val id: Int) {
    /** Lock Wallet default: m/44'/501'/i'/0'. */
    PRIMARY(0x01),

    /** Compatibility path: m/44'/501'/i'. Never mixed with PRIMARY. */
    COMPATIBILITY(0x02);

    fun path(accountIndex: Int): HardenedPath {
        require(accountIndex >= 0) { "account index must be in 0..2^31-1" }
        return when (this) {
            PRIMARY -> HardenedPath(intArrayOf(44, 501, accountIndex, 0))
            COMPATIBILITY -> HardenedPath(intArrayOf(44, 501, accountIndex))
        }
    }

    companion object {
        fun ofId(id: Int): DerivationProfile? = entries.firstOrNull { it.id == id }
    }
}

/** A path where every index is hardened. Indices are stored without the 2^31 bit (0..2^31-1). */
class HardenedPath(indices: IntArray) {
    private val idx = indices.copyOf()

    init {
        require(idx.all { it >= 0 }) { "hardened index must be in 0..2^31-1" }
    }

    val depth: Int get() = idx.size
    fun indices(): IntArray = idx.copyOf()

    override fun toString(): String = "m" + idx.joinToString("") { "/$it'" }
    override fun equals(other: Any?): Boolean = other is HardenedPath && other.idx.contentEquals(idx)
    override fun hashCode(): Int = idx.contentHashCode()

    companion object {
        /**
         * Parses "m/44'/501'/0'/0'". Every segment MUST carry the apostrophe (spec §9.2: never drop the
         * hardened marks). Used for test vectors and diagnostics; product code uses [DerivationProfile].
         */
        fun parse(text: String): HardenedPath {
            val parts = text.split('/')
            require(parts.isNotEmpty() && parts[0] == "m") { "path must start with m" }
            val out = IntArray(parts.size - 1)
            for (i in 1 until parts.size) {
                val p = parts[i]
                require(p.length >= 2 && p.endsWith("'")) { "every SLIP-0010 ed25519 level must be hardened" }
                val digits = p.dropLast(1)
                require(digits.all { it in '0'..'9' } && (digits == "0" || !digits.startsWith("0"))) { "bad index" }
                val v = digits.toLongOrNull()
                require(v != null && v in 0..0x7fffffffL) { "index out of range" }
                out[i - 1] = v.toInt()
            }
            return HardenedPath(out)
        }
    }
}

/** Derived Ed25519 key: 32-byte private seed (secret) + 32-byte public key. Close to wipe. */
class Ed25519DerivedKey internal constructor(
    val privateKey: SecretBytes,
    publicKey: ByteArray,
) : AutoCloseable {
    private val pub = publicKey.copyOf()
    fun publicKey(): ByteArray = pub.copyOf()
    override fun close() = privateKey.close()
    override fun toString(): String = "Ed25519DerivedKey(private=REDACTED)"
}

/** Extended private key (k, c) at some depth — exposed for official SLIP-0010 vector tests. */
class Slip10Node internal constructor(val key: SecretBytes, val chainCode: SecretBytes) : AutoCloseable {
    override fun close() {
        key.close(); chainCode.close()
    }
    override fun toString(): String = "Slip10Node(REDACTED)"
}

/**
 * Independent SLIP-0010 implementation for curve ed25519.
 * https://github.com/satoshilabs/slips/blob/master/slip-0010.md
 *
 *  master:  I = HMAC-SHA512(key = "ed25519 seed", data = seed);  k = I[0..32), c = I[32..64)
 *  child i: I = HMAC-SHA512(key = c_par, data = 0x00 || k_par || ser32(i + 2^31)); k = I_L, c = I_R
 *  public:  RFC 8032 Ed25519 public key of k (Bouncy Castle rfc8032.Ed25519).
 * Intermediate buffers are wiped best-effort (ADR 0013).
 */
object Slip10Ed25519 {
    private val CURVE_KEY = "ed25519 seed".toByteArray(Charsets.US_ASCII)
    private const val HARDENED = 0x80000000L

    fun masterNode(seed: ByteArray): Slip10Node {
        require(seed.size in 16..64) { "seed must be 16..64 bytes" }
        return split(hmacSha512(CURVE_KEY, seed))
    }

    fun deriveNode(seed: ByteArray, path: HardenedPath): Slip10Node {
        var node = masterNode(seed)
        for (i in path.indices()) {
            val next = child(node, i)
            node.close()
            node = next
        }
        return node
    }

    /** Derives the Solana signing key for [profile] / [accountIndex] from a 64-byte BIP-39 seed. */
    fun deriveSolanaKey(seed: ByteArray, profile: DerivationProfile, accountIndex: Int): Ed25519DerivedKey =
        deriveKey(seed, profile.path(accountIndex))

    fun deriveKey(seed: ByteArray, path: HardenedPath): Ed25519DerivedKey {
        val node = deriveNode(seed, path)
        try {
            val pub = node.key.withBytes { publicKeyOf(it) }
            return Ed25519DerivedKey(node.key.copy(), pub)
        } finally {
            node.close()
        }
    }

    /** RFC 8032 public key for a 32-byte Ed25519 private seed. */
    fun publicKeyOf(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "ed25519 private key must be 32 bytes" }
        val pk = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
        Ed25519.generatePublicKey(privateKey, 0, pk, 0)
        return pk
    }

    private fun child(parent: Slip10Node, index: Int): Slip10Node {
        val i = index.toLong() or HARDENED
        val data = ByteArray(1 + 32 + 4)
        try {
            parent.key.withBytes { it.copyInto(data, 1) }
            data[33] = (i ushr 24).toByte(); data[34] = (i ushr 16).toByte()
            data[35] = (i ushr 8).toByte(); data[36] = i.toByte()
            return split(parent.chainCode.withBytes { hmacSha512(it, data) })
        } finally {
            data.wipe()
        }
    }

    private fun split(i: ByteArray): Slip10Node = try {
        Slip10Node(SecretBytes.copyOf(i, 0, 32), SecretBytes.copyOf(i, 32, 64))
    } finally {
        i.wipe()
    }

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA512Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(64)
        mac.doFinal(out, 0)
        mac.reset()
        return out
    }
}
