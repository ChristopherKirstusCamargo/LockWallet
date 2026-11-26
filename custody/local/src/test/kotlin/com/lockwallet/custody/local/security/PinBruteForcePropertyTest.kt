package com.lockwallet.custody.local.security

import com.lockwallet.custody.local.pin.AttemptClock
import com.lockwallet.custody.local.pin.AttemptGate
import com.lockwallet.custody.local.pin.PinAttemptLimiter
import com.lockwallet.custody.local.pin.PinAttemptLimiter.Companion.HOUR
import com.lockwallet.custody.local.storage.AtomicFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.Random

private class Clock(var elapsed: Long = 1_000_000L, var boot: Int = 3) : AttemptClock {
    override fun elapsedRealtimeMs() = elapsed
    override fun bootCount() = boot
}

private val KEY = ByteArray(32) { (it * 7 + 1).toByte() }
private fun mac(d: ByteArray): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(KEY, "HmacSHA256")) }.doFinal(d)

/**
 * An attacker without the Keystore MAC key guesses PINs while rebooting, moving the clock, deleting, corrupting,
 * truncating and rolling back one of the two state copies. Whatever the interleaving, no guess is ever granted
 * before the scheduled lockout has elapsed in real time (5 free guesses, then 1 h doubling to 24 h).
 */
@RunWith(Parameterized::class)
class PinBruteForcePropertyTest(private val seed: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "seed {0}") fun data(): List<Array<Any>> = (1..200).map { arrayOf<Any>(it) }
    }
    @get:Rule val tmp = TemporaryFolder()

    @Test fun noGuessBeforeTheLockoutExpires() {
        val r = Random(seed * 131L)
        val clock = Clock()
        val a = File(tmp.root, "a.bin"); val b = File(tmp.root, "a.mirror")
        fun limiter() = PinAttemptLimiter(a, b, clock, AtomicFiles(), { true }, ::mac)
        PinAttemptLimiter(a, b, clock, AtomicFiles(), { false }, ::mac).recordSuccess() // vault just created
        var real = 0L
        var granted = 0
        var lastGrantReal = 0L
        val snapshots = ArrayList<Pair<ByteArray?, ByteArray?>>()
        val log = StringBuilder()
        repeat(400) {
            val op = r.nextInt(12)
            log.append(op).append(':').append(granted).append(':').append(runCatching { limiter().failures() }.getOrNull()).append(' ')
            when (op) {
                0, 1, 2, 3 -> {
                    val g = limiter().gate()
                    if (g is AttemptGate.Allowed) {
                        if (granted >= PinAttemptLimiter.FREE_ATTEMPTS) {
                            val need = PinAttemptLimiter.lockoutFor(granted)
                            assertTrue("seed $seed: guess ${granted + 1} after ${real - lastGrantReal} ms, needed $need; ${log.takeLast(600)}", real - lastGrantReal >= need)
                        }
                        granted++; lastGrantReal = real
                        assertTrue("fw ${g.failuresIfWrong} granted $granted; ${log.takeLast(400)}", g.failuresIfWrong >= minOf(granted, 10))
                    } else assertTrue((g as AttemptGate.LockedOut).remainingMs in 1..PinAttemptLimiter.MAX_LOCK)
                }
                4 -> { val d = (r.nextInt(30) * HOUR / 4); clock.elapsed += d; real += d }
                5 -> { clock.boot++; clock.elapsed = 1_000L + r.nextInt(100_000); real += 30_000 } // reboot
                6 -> { clock.elapsed = (clock.elapsed - r.nextInt(3_600_000)).coerceAtLeast(0) } // clock goes backwards
                7 -> if (r.nextBoolean()) a.delete() else b.delete() // delete one copy
                8 -> { val f = if (r.nextBoolean()) a else b; if (f.exists() && f.length() > 0) f.writeBytes(f.readBytes().also { it[r.nextInt(it.size)] = (it[r.nextInt(it.size)].toInt() xor (1 + r.nextInt(255))).toByte() }) }
                9 -> { val f = if (r.nextBoolean()) a else b; if (f.exists() && f.length() > 0) f.writeBytes(f.readBytes().copyOf(r.nextInt(f.length().toInt()))) }
                10 -> snapshots += (if (a.exists()) a.readBytes() else null) to (if (b.exists()) b.readBytes() else null)
                // Roll ONE copy (always the primary) back to an older valid state. Restoring both copies together is
                // a full-filesystem replay that no app-level counter can detect (documented limit, root only).
                11 -> if (snapshots.isNotEmpty()) snapshots[r.nextInt(snapshots.size)].first?.let { a.writeBytes(it) }
            }
        }
        if (r.nextBoolean()) { a.delete(); b.delete(); assertTrue(limiter().lockoutRemainingMs() >= HOUR || granted < 5 && limiter().lockoutRemainingMs() == PinAttemptLimiter.MAX_LOCK) }
    }
}

/** Every single-byte change to either or both copies: the state never becomes more permissive. */
@RunWith(Parameterized::class)
class PinStateTamperTest(private val byteIndex: Int, private val target: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "byte {0} of {1}")
        fun data(): List<Array<Any>> = (0 until 61).flatMap { i -> listOf("primary", "mirror", "both").map { arrayOf<Any>(i, it) } }
    }
    @get:Rule val tmp = TemporaryFolder()

    @Test fun neverMorePermissive() {
        val clock = Clock()
        val a = File(tmp.root, "a.bin"); val b = File(tmp.root, "a.mirror")
        fun limiter() = PinAttemptLimiter(a, b, clock, AtomicFiles(), { true }, ::mac)
        PinAttemptLimiter(a, b, clock, AtomicFiles(), { false }, ::mac).recordSuccess() // vault just created
        repeat(5) { limiter().gate() } // 5 failures: 1 h lockout
        clock.elapsed += HOUR / 2
        val before = limiter().lockoutRemainingMs(); val failures = limiter().failures()
        assertEquals(HOUR / 2, before)
        for (f in when (target) { "primary" -> listOf(a); "mirror" -> listOf(b); else -> listOf(a, b) }) {
            val bytes = f.readBytes(); assertEquals(61, bytes.size)
            bytes[byteIndex] = (bytes[byteIndex].toInt() xor 0x5A).toByte(); f.writeBytes(bytes)
        }
        assertTrue(limiter().failures() >= failures)
        assertTrue(limiter().lockoutRemainingMs() >= before)
        assertTrue(limiter().gate() is AttemptGate.LockedOut)
    }
}
