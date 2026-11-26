package com.lockwallet.core.crypto

/**
 * Owner of a secret byte buffer (seed, entropy, private key, chain code).
 *
 * Policy (spec §8, ADR 0012): the app *reduces the time secrets stay in memory and tries to wipe buffers
 * after use*. Wiping a JVM array is best-effort: ART/JIT/GC may have copied it. Never claim otherwise.
 *
 * - [toString] never reveals content; equality is identity (no accidental content comparison/logging).
 * - [close] overwrites the buffer with zeros and makes further access fail.
 */
class SecretBytes private constructor(private var buffer: ByteArray?) : AutoCloseable {

    /** Length of the secret; fails after [close]. */
    val size: Int get() = live().size

    /** Gives temporary access to the bytes. The block must not retain or copy the array beyond its scope. */
    fun <R> withBytes(block: (ByteArray) -> R): R = block(live())

    /** Returns a new, independently owned copy. */
    fun copy(): SecretBytes = SecretBytes(live().copyOf())

    val isClosed: Boolean get() = buffer == null

    override fun close() {
        buffer?.wipe()
        buffer = null
    }

    override fun toString(): String = "SecretBytes(REDACTED)"

    private fun live(): ByteArray = checkNotNull(buffer) { "secret already wiped" }

    companion object {
        /** Takes ownership of [bytes]; the caller must not use the array afterwards. */
        fun adopt(bytes: ByteArray): SecretBytes = SecretBytes(bytes)

        /** Copies [bytes] from [from] (inclusive) to [to] (exclusive) into a new owned buffer. */
        fun copyOf(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): SecretBytes =
            SecretBytes(bytes.copyOfRange(from, to))
    }
}

/** Best-effort overwrite with zeros (see [SecretBytes]). */
fun ByteArray.wipe() = fill(0)

/** Best-effort overwrite with NUL characters (see [SecretBytes]). */
fun CharArray.wipe() = fill('\u0000')
