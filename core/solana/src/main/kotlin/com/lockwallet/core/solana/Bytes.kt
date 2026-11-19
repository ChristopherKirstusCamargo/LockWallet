package com.lockwallet.core.solana

/** Thrown for any malformed binary input. Messages never contain the input bytes. */
class MalformedException(why: String) : IllegalArgumentException(why)

/** Strict little-endian reader over an immutable copy. Every read is bounds-checked. */
class ByteReader(bytes: ByteArray, private var pos: Int = 0, private val end: Int = bytes.size) {
    private val b = bytes
    val position: Int get() = pos
    val remaining: Int get() = end - pos

    private fun need(n: Int) { if (n < 0 || n > end - pos) throw MalformedException("truncated") }
    fun u8(): Int { need(1); return b[pos++].toInt() and 0xff }
    fun u16(): Int { need(2); val v = (b[pos].toInt() and 0xff) or ((b[pos + 1].toInt() and 0xff) shl 8); pos += 2; return v }
    fun u32(): Long { need(4); var v = 0L; for (i in 0 until 4) v = v or ((b[pos + i].toLong() and 0xff) shl (8 * i)); pos += 4; return v }
    /** u64 as a signed Long; values >= 2^63 are rejected (lamports and token amounts in scope never reach that). */
    fun u64(): Long {
        need(8); var v = 0L; for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xff) shl (8 * i)); pos += 8
        if (v < 0) throw MalformedException("u64 above 2^63-1")
        return v
    }
    /** u64 allowing the full range, returned as raw bits (e.g. u64::MAX sentinels). */
    fun u64Raw(): Long { need(8); var v = 0L; for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xff) shl (8 * i)); pos += 8; return v }
    fun i64(): Long = u64Raw()
    fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(pos, pos + n).also { pos += n } }
    fun pubkey(): SolanaPublicKey = SolanaPublicKey.of(bytes(32))
    fun skip(n: Int) { need(n); pos += n }

    /** Solana compact-u16 ("shortvec"): 1–3 bytes, canonical encoding only. */
    fun shortVec(): Int {
        var value = 0
        for (i in 0 until 3) {
            val byte = u8()
            if (i == 2 && byte > 0x03) throw MalformedException("shortvec overflow")
            value = value or ((byte and 0x7f) shl (7 * i))
            if (byte and 0x80 == 0) {
                if (i > 0 && byte == 0) throw MalformedException("non-canonical shortvec")
                return value
            }
        }
        throw MalformedException("shortvec too long")
    }

    fun expectEnd() { if (pos != end) throw MalformedException("trailing bytes") }
}

class ByteWriter {
    private val out = java.io.ByteArrayOutputStream()
    fun u8(v: Int) = apply { require(v in 0..255); out.write(v) }
    fun u32(v: Long) = apply { require(v in 0..0xffffffffL); for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xff).toInt()) }
    fun u64(v: Long) = apply { for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xff).toInt()) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun pubkey(k: SolanaPublicKey) = bytes(k.toByteArray())
    fun shortVec(len: Int) = apply {
        require(len in 0..0xffff)
        var v = len
        while (true) {
            val byte = v and 0x7f; v = v ushr 7
            if (v == 0) { out.write(byte); break } else out.write(byte or 0x80)
        }
    }
    fun toByteArray(): ByteArray = out.toByteArray()
}

internal fun sha256(vararg parts: ByteArray): ByteArray {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    parts.forEach { md.update(it) }
    return md.digest()
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
