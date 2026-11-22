package com.lockwallet.core.solana

import com.lockwallet.core.solana.tx.V1MessageFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Byte-exact V1 vectors for the local fee bound of the Default Ring path (LW-03 fix). */
class V1MessageFeeTest {

    private fun u32le(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun u64le(v: Long) = ByteArray(8) { i -> (v shr (8 * i)).toByte() }
    private fun u16le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    /** Minimal exact V1 message: 1 signature, 2 addresses, 1 instruction (1 account, 1 data byte). */
    private fun v1(
        mask: Long = 0L,
        priorityFee: Long? = null,
        computeLimit: Long? = null,
        trailing: ByteArray = ByteArray(0),
        programIndex: Int = 1,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(b: ByteArray) = out.write(b)
        fun w1(b: Byte) = out.write(byteArrayOf(b))
        w1(0x81.toByte())                          // version prefix
        w1(1.toByte()); w1(0.toByte()); w1(0.toByte()) // 1 required signature, readonly 0/0
        w(u32le(mask))
        w(ByteArray(32) { it.toByte() })           // lifetime specifier (blockhash)
        w1(1.toByte())                             // num instructions
        w1(2.toByte())                             // num addresses
        w(ByteArray(64) { it.toByte() })           // 2 addresses
        if (mask and 0b11 != 0L) w(u64le(priorityFee ?: 0L))
        if (mask and 0b100 != 0L) w(u32le(computeLimit ?: 0L))
        if (mask and 0b1000 != 0L) w(u32le(32 * 1024))    // loaded accounts data size
        if (mask and 0b10000 != 0L) w(u32le(64 * 1024))   // heap size
        // instruction header: program index, 1 account, data len 1
        w1(programIndex.toByte()); w1(1.toByte()); w(u16le(1))
        w1(0.toByte())                             // account index
        w1(0xFF.toByte())                          // data
        w(trailing)
        return out.toByteArray()
    }

    @Test
    fun `prices base fee only when the header carries no config`() {
        assertEquals(5_000L, V1MessageFee.maxFeeLamports(v1()))
    }

    @Test
    fun `prices base fee plus the header priority fee`() {
        val m = v1(mask = 0b11, priorityFee = 250_000L)
        assertEquals(5_000L + 250_000L, V1MessageFee.maxFeeLamports(m))
    }

    @Test
    fun `prices every known config field present`() {
        val m = v1(mask = 0b11111, priorityFee = 7L, computeLimit = 450_000L)
        assertEquals(5_000L + 7L, V1MessageFee.maxFeeLamports(m))
    }

    @Test
    fun `saturates instead of overflowing`() {
        val m = v1(mask = 0b11, priorityFee = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, V1MessageFee.maxFeeLamports(m))
    }

    @Test
    fun `refuses a message without the V1 prefix`() {
        val m = v1().copyOf().also { it[0] = 0x80.toByte() }
        assertNull(V1MessageFee.maxFeeLamports(m))
    }

    @Test
    fun `refuses unknown config mask bits`() {
        assertNull(V1MessageFee.maxFeeLamports(v1(mask = 1L shl 5)))
    }

    @Test
    fun `refuses a partial priority fee mask`() {
        // Only one of the two priority-fee bits is set: not a valid encoding.
        assertNull(V1MessageFee.maxFeeLamports(v1(mask = 0b01)))
        assertNull(V1MessageFee.maxFeeLamports(v1(mask = 0b10)))
    }

    @Test
    fun `refuses trailing bytes`() {
        assertNull(V1MessageFee.maxFeeLamports(v1(trailing = byteArrayOf(0))))
    }

    @Test
    fun `refuses an out-of-range program index`() {
        assertNull(V1MessageFee.maxFeeLamports(v1(programIndex = 5)))
    }

    @Test
    fun `refuses zero required signatures`() {
        val m = v1().copyOf().also { it[1] = 0.toByte() }
        assertNull(V1MessageFee.maxFeeLamports(m))
    }

    @Test
    fun `refuses truncated messages`() {
        val m = v1()
        assertNull(V1MessageFee.maxFeeLamports(m.copyOf(m.size - 1)))
        assertNull(V1MessageFee.maxFeeLamports(ByteArray(10)))
        assertNull(V1MessageFee.maxFeeLamports(ByteArray(0)))
    }

    @Test
    fun `refuses readonly counts that contradict the signature count`() {
        val m = v1().copyOf().also { it[2] = 2.toByte() } // num_readonly_signed > num_required_signatures
        assertNull(V1MessageFee.maxFeeLamports(m))
    }
}
