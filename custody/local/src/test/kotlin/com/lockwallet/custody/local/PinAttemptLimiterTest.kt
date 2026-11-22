package com.lockwallet.custody.local

import com.lockwallet.custody.local.pin.AttemptGate
import com.lockwallet.custody.local.pin.Pin
import com.lockwallet.custody.local.pin.PinAttemptLimiter
import com.lockwallet.custody.local.pin.PinAttemptLimiter.Companion.HOUR
import com.lockwallet.custody.local.storage.AtomicFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PinAttemptLimiterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = FakeClock()
    private var vault = true
    private val macKey = ByteArray(32) { (it + 1).toByte() }
    private fun mac(data: ByteArray): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").apply {
        init(javax.crypto.spec.SecretKeySpec(macKey, "HmacSHA256"))
    }.doFinal(data)
    private fun limiter(dir: File = tmp.root) =
        PinAttemptLimiter(File(dir, "a.bin"), File(dir, "a.mirror"), clock, AtomicFiles(), { vault }, ::mac)

    @Test fun scheduleIs5Then1hDoublingTo24h() {
        assertEquals(listOf(0L, 0, 0, 0, HOUR, 2 * HOUR, 4 * HOUR, 8 * HOUR, 16 * HOUR, 24 * HOUR, 24 * HOUR),
            (1..11).map { PinAttemptLimiter.lockoutFor(it) })
    }

    @Test fun fiveWrongLocksOneHourThenDoubles() {
        vault = false
        val l = limiter()
        repeat(4) { assertTrue(l.gate() is AttemptGate.Allowed) }
        assertEquals(0L, l.lockoutRemainingMs())
        val fifth = l.gate() as AttemptGate.Allowed
        assertEquals(HOUR, fifth.lockoutIfWrongMs)
        assertEquals(HOUR, (l.gate() as AttemptGate.LockedOut).remainingMs)
        clock.elapsed += HOUR - 1
        assertTrue(l.gate() is AttemptGate.LockedOut)
        clock.elapsed += 1
        assertEquals(2 * HOUR, (l.gate() as AttemptGate.Allowed).lockoutIfWrongMs)
        // Never wipes: nothing else to assert than the absence of any deletion API; success resets.
        clock.elapsed += 2 * HOUR
        l.gate(); l.recordSuccess()
        assertEquals(0, l.failures())
    }

    @Test fun writeAheadCountsAnInterruptedAttempt() {
        vault = false
        limiter().gate() // process "dies" here, before Argon2id finishes
        assertEquals(1, limiter().failures())
    }

    @Test fun rebootRestartsTheLockoutNeverShortens() {
        vault = false
        val l = limiter()
        repeat(5) { l.gate() }
        clock.elapsed += HOUR - 1000
        clock.boot += 1; clock.elapsed = 5_000 // reboot: elapsedRealtime resets
        assertEquals(HOUR, l.lockoutRemainingMs())
        clock.elapsed += HOUR / 2
        assertEquals(HOUR / 2, l.lockoutRemainingMs())
    }

    @Test fun deletingBothCopiesFailsClosedWithoutAFreeGuess() {
        vault = false
        val l = limiter()
        repeat(5) { l.gate() }
        File(tmp.root, "a.bin").delete()
        File(tmp.root, "a.mirror").delete()
        vault = true
        assertEquals(24 * HOUR, limiter().lockoutRemainingMs())
        assertTrue(limiter().gate() is AttemptGate.LockedOut)
    }

    @Test fun equalFailureCountUsesTheLaterLockoutState() {
        vault = false
        val l = limiter()
        repeat(5) { l.gate() }
        val stale = File(tmp.root, "a.bin").readBytes()
        clock.elapsed += 10_000
        l.recordSuccess()
        repeat(5) { l.gate() }
        val newer = File(tmp.root, "a.mirror").readBytes()
        // Both copies have five failures; only the mirror carries the later lockout start.
        File(tmp.root, "a.bin").writeBytes(stale)
        File(tmp.root, "a.mirror").writeBytes(newer)
        assertEquals(HOUR, limiter().lockoutRemainingMs())
    }

    @Test fun olderCopyLoses() {
        vault = false
        val l = limiter()
        repeat(3) { l.gate() }
        val old = File(tmp.root, "a.bin").readBytes()
        repeat(2) { l.gate() }
        File(tmp.root, "a.bin").writeBytes(old) // rollback one copy
        assertEquals(5, limiter().failures())
    }

    @Test fun tamperedAttemptStateCannotBeRecomputedWithoutKeystoreMacKey() {
        val l = limiter()
        l.gate(); l.gate()
        File(tmp.root, "a.bin").writeBytes(ByteArray(80) { 0x41 })
        File(tmp.root, "a.mirror").writeBytes(ByteArray(80) { 0x41 })
        assertEquals(10, limiter().failures())
        assertEquals(24 * HOUR, limiter().lockoutRemainingMs())
        assertTrue(limiter().gate() is AttemptGate.LockedOut)
    }

    @Test fun pinEncodingRejectsNonAsciiDigits() {
        assertTrue(Pin.isValid(listOf(8, 3, 5 + 4, 2 - 1, 14 - 7, 2 * 2).joinToString("").toCharArray()))
        assertFalse(Pin.isValid("48091".toCharArray()))
        assertFalse(Pin.isValid("٤٨٠٩١٧".toCharArray())) // Arabic-Indic
        assertFalse(Pin.isValid("４８０９１７".toCharArray())) // full-width
        assertFalse(Pin.isValid("48091a".toCharArray()))
        assertTrue(Pin.isTrivial("111111".toCharArray()))
        assertTrue(Pin.isTrivial("123456".toCharArray()))
        assertTrue(Pin.isTrivial("654321".toCharArray()))
        assertTrue(Pin.isTrivial("121212".toCharArray()))
        assertTrue(Pin.isTrivial("112233".toCharArray()))
        assertTrue(Pin.isTrivial("010203".toCharArray()))
        assertTrue(Pin.isTrivial("987654".toCharArray()))
        assertFalse(Pin.isTrivial(listOf(8, 3, 5 + 4, 2 - 1, 14 - 7, 2 * 2).joinToString("").toCharArray()))
    }
}
