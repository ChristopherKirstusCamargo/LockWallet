package com.lockwallet.core.solana

/**
 * A 32-byte Solana public key / address. The address string is its Base58 encoding; the bytes are the
 * identity (spec §13.6, §14.3: pubkey/mint is the primary identifier, never a name or symbol).
 */
class SolanaPublicKey private constructor(private val bytes: ByteArray) {
    fun toByteArray(): ByteArray = bytes.copyOf()
    fun toBase58(): String = Base58.encode(bytes)

    override fun equals(other: Any?): Boolean = other is SolanaPublicKey && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = toBase58()

    companion object {
        const val LENGTH = 32

        fun of(bytes: ByteArray): SolanaPublicKey {
            require(bytes.size == LENGTH) { "public key must be 32 bytes" }
            return SolanaPublicKey(bytes.copyOf())
        }

        /** Strict: must decode to exactly 32 bytes and re-encode to the identical string. */
        fun fromBase58OrNull(text: String): SolanaPublicKey? {
            val raw = Base58.decodeOrNull(text, LENGTH) ?: return null
            if (raw.size != LENGTH || Base58.encode(raw) != text) return null
            return SolanaPublicKey(raw)
        }

        fun fromBase58(text: String): SolanaPublicKey =
            requireNotNull(fromBase58OrNull(text)) { "invalid Solana address" }
    }
}
