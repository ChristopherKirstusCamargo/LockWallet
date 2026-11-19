package com.lockwallet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAmountTest {
    @Test fun ambiguousThousandsAreDetected() {
        for (t in listOf("1.000", "12.500", " 999.999 ", "001.000")) assertTrue(t, UserAmount.ambiguous(t))
        for (t in listOf("0.500", "1.5", "1.25", "1.2500", "1,000", "1000", "1234.567", ".500", "0.000")) assertFalse(t, UserAmount.ambiguous(t))
    }

    @Test fun solIsCappedAndStrict() {
        assertEquals(1_500_000_000L, UserAmount.solOrNull("1,5"))
        assertEquals(UserAmount.MAX_SOL_LAMPORTS, UserAmount.solOrNull("1000000000"))
        assertNull(UserAmount.solOrNull("1000000000,000000001"))
        for (t in listOf("", "-1", "0", "1e9", "１", "1٫5", "1.000", "0x10", "1 000", "\u200b1", "NaN", "Infinity")) assertNull(t, UserAmount.solOrNull(t))
        // every capped amount plus a generous rent/fee stays far from overflow
        assertTrue(UserAmount.MAX_SOL_LAMPORTS + 10_000_000_000L > 0)
    }
}
