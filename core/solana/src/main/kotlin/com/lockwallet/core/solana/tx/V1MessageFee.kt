package com.lockwallet.core.solana.tx

/**
 * Deterministic fee bound of a Solana V1 message, computed from the bytes alone — never from an RPC answer.
 *
 * V1 carries its compute budget in the message header instead of Compute Budget instructions, so
 * [FeeMath] (which prices legacy messages by their instructions) cannot price it. Layout after the
 * 0x81 version byte (solana-message 4.6, `v1::Message` wire format, all integers little-endian):
 *
 *  1  version prefix (0x81)
 *  3  legacy header: num_required_signatures, num_readonly_signed_accounts, num_readonly_unsigned_accounts
 *  4  TransactionConfigMask (u32): bit0..1 = priority fee, bit2 = compute unit limit,
 *     bit3 = loaded accounts data size, bit4 = heap size; any other bit is unknown
 * 32  lifetime specifier (recent blockhash)
 *  1  num instructions
 *  1  num addresses
 *  n*32  account addresses
 *  optional config fields in mask order: priority fee (u64), compute unit limit (u32),
 *     loaded accounts data size (u32), heap size (u32)
 *  per instruction: 4-byte header (program index u8, num accounts u8, data len u16)
 *  per instruction: account indexes (u8 each) + data
 *
 * Anything that does not match this exact layout — trailing bytes, unknown/partial mask bits,
 * out-of-range indexes — yields null and the caller must refuse the message (fail closed), exactly
 * like a legacy message FeeMath cannot price.
 */
object V1MessageFee {
    /** solana-message v1: only these mask bits are defined; the rest is unknown future config. */
    const val KNOWN_MASK_BITS: Long = 0b11111L
    private const val PRIORITY_FEE_BITS: Long = 0b11L
    private const val COMPUTE_LIMIT_BIT: Long = 0b100L
    private const val LOADED_DATA_BIT: Long = 0b1000L
    private const val HEAP_BIT: Long = 0b10000L
    /** Minimum size: prefix + header + mask + hash + num ix + num addresses. */
    private const val FIXED_PREFIX = 1 + 3 + 4 + 32 + 1 + 1

    /**
     * Upper bound of what the message can cost its fee payer: base fee per required signature plus
     * the priority fee stated in the V1 header. Saturates at [Long.MAX_VALUE]. Null when the bytes
     * are not an exact, fully-consumed V1 message of the layout above.
     */
    fun maxFeeLamports(message: ByteArray): Long? {
        if (message.size < FIXED_PREFIX || (message[0].toInt() and 0xff) != 0x81) return null
        val numSigs = message[1].toLong() and 0xff
        val numReadonlySigned = message[2].toLong() and 0xff
        val numReadonlyUnsigned = message[3].toLong() and 0xff
        if (numSigs < 1 || numSigs > 255 || numReadonlySigned > numSigs) return null
        val mask = u32le(message, 4)
        if (mask and KNOWN_MASK_BITS.inv() != 0L) return null
        if (mask and PRIORITY_FEE_BITS != 0L && mask and PRIORITY_FEE_BITS != PRIORITY_FEE_BITS) return null
        val numIx = message[40].toLong() and 0xff
        val numAddr = message[41].toLong() and 0xff
        if (numAddr < numSigs || numReadonlyUnsigned > numAddr - numSigs) return null
        var o = FIXED_PREFIX.toLong()
        o += numAddr * 32
        var priorityFee = 0L
        if (mask and PRIORITY_FEE_BITS != 0L) {
            if (o + 8 > message.size) return null
            priorityFee = u64le(message, o.toInt())
            o += 8
        }
        if (mask and COMPUTE_LIMIT_BIT != 0L) o += 4
        if (mask and LOADED_DATA_BIT != 0L) o += 4
        if (mask and HEAP_BIT != 0L) o += 4
        val configEnd = o
        // Instruction headers: program index, account count, data length (u16 LE).
        o += numIx * 4
        if (o > message.size) return null
        for (i in 0 until numIx) {
            val hdr = (configEnd + i * 4).toInt()
            if (hdr + 4 > message.size) return null
            val programIndex = message[hdr].toLong() and 0xff
            val numAccounts = message[hdr + 1].toLong() and 0xff
            val dataLen = u16le(message, hdr + 2)
            if (programIndex >= numAddr) return null
            o += numAccounts + dataLen
            if (o > message.size) return null
        }
        if (o != message.size.toLong()) return null
        val base = FeeMath.LAMPORTS_PER_SIGNATURE * numSigs
        return if (priorityFee > Long.MAX_VALUE - base) Long.MAX_VALUE else base + priorityFee
    }

    private fun u32le(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)

    private fun u64le(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff)
        return v
    }

    private fun u16le(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8)
}
