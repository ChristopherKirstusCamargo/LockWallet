package com.lockwallet.core.solana

import com.lockwallet.core.solana.tx.LegacyMessage
import com.lockwallet.core.solana.tx.UnsupportedFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CodecTest {
    @Test fun shortVecCanonical() {
        for (n in listOf(0, 1, 127, 128, 255, 16383, 16384, 65535)) {
            val b = ByteWriter().shortVec(n).toByteArray()
            assertEquals(n, ByteReader(b).shortVec())
        }
        for (bad in listOf(byteArrayOf(0x80.toByte(), 0x00), byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x04), byteArrayOf(0x80.toByte()))) {
            try { ByteReader(bad).shortVec(); fail() } catch (_: MalformedException) {}
        }
    }

    @Test fun versionedMessageRefused() {
        try { LegacyMessage.parse(byteArrayOf(0x80.toByte(), 1, 0, 0)); fail() } catch (_: UnsupportedFormatException) {}
    }

    @Test fun amountsExactDecimal() {
        assertEquals(1_500_000_000L, Amounts.parse("1,5", 9))
        assertEquals(1L, Amounts.parse("0.000000001", 9))
        assertNull(Amounts.parse("0.0000000001", 9))
        for (bad in listOf("", "-1", "1e3", "1.2.3", "0", "٣", "1 000", "99999999999999999999")) assertNull(bad, Amounts.parse(bad, 9))
        assertEquals("1.5", Amounts.sol(1_500_000_000)); assertEquals("0", Amounts.sol(0)); assertEquals("0.000000001", Amounts.sol(1))
    }
}
