package com.lockwallet.custody.local.pin

import com.lockwallet.custody.local.storage.AtomicFiles
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Monotonic time source. Android: SystemClock.elapsedRealtime + Settings.Global.BOOT_COUNT. */
interface AttemptClock {
    fun elapsedRealtimeMs(): Long
    fun bootCount(): Int
}

sealed class AttemptGate {
    /** Attempt may proceed; it has ALREADY been counted (write-ahead). Call [PinAttemptLimiter.recordSuccess] on success. */
    data class Allowed(val failuresIfWrong: Int, val lockoutIfWrongMs: Long) : AttemptGate()
    data class LockedOut(val remainingMs: Long) : AttemptGate()
}

/**
 * PIN attempt limiter (ADR 0010, prompt 1.2): 5 wrong PINs ⇒ 1 h, each further failure doubles up to 24 h.
 * Never wipes anything. Write-ahead: the attempt is persisted (fsync) BEFORE Argon2id runs, so killing the
 * process mid-attempt counts as a failure. Two copies are kept and the most restrictive wins (deleting one
 * does not reset); a missing/corrupt state while a vault exists is treated as "one attempt left".
 * A reboot restarts the remaining lockout from the full duration (never shortens it).
 * Residual: root/snapshot can restore old state — this is UX defense, not a crypto boundary.
 */
class PinAttemptLimiter(
    private val primary: File,
    private val mirror: File,
    private val clock: AttemptClock,
    private val atomic: AtomicFiles,
    private val vaultExists: () -> Boolean,
) {
    data class State(val failures: Int, val lockStartElapsed: Long, val lockDurationMs: Long, val boot: Int)

    fun gate(): AttemptGate {
        val s = current()
        val remaining = remaining(s)
        if (remaining > 0) return AttemptGate.LockedOut(remaining)
        val failures = s.failures + 1
        val dur = lockoutFor(failures)
        val now = clock.elapsedRealtimeMs()
        persist(State(failures, if (dur > 0) now else 0, dur, clock.bootCount()))
        return AttemptGate.Allowed(failures, dur)
    }

    /** Remaining lockout right now (0 if none), without consuming an attempt. */
    fun lockoutRemainingMs(): Long = remaining(current())

    fun failures(): Int = current().failures

    fun recordSuccess() = persist(State(0, 0, 0, clock.bootCount()))

    private fun remaining(s: State): Long {
        if (s.lockDurationMs <= 0) return 0
        val now = clock.elapsedRealtimeMs()
        if (s.boot != clock.bootCount() || now < s.lockStartElapsed) {
            // Different boot (or clock went backwards): restart the full lockout from now, persist it.
            val restarted = State(s.failures, now, s.lockDurationMs, clock.bootCount())
            persist(restarted)
            return s.lockDurationMs
        }
        return (s.lockStartElapsed + s.lockDurationMs - now).coerceAtLeast(0)
    }

    private fun current(): State {
        val a = read(primary)
        val b = read(mirror)
        if (a == null && b == null) {
            return if (vaultExists()) State(FREE_ATTEMPTS - 1, 0, 0, clock.bootCount()) else State(0, 0, 0, clock.bootCount())
        }
        if (a == null) return b!!
        if (b == null) return a
        return if (b.failures > a.failures) b else a
    }

    private fun persist(s: State) {
        val bytes = encode(s)
        atomic.write(primary, bytes) { requireNotNull(decode(it)) }
        atomic.write(mirror, bytes) { requireNotNull(decode(it)) }
    }

    private fun read(f: File): State? = if (!f.exists()) null else try { decode(f.readBytes()) } catch (_: Exception) { null }

    companion object {
        const val FREE_ATTEMPTS = 5
        const val HOUR = 3_600_000L
        const val MAX_LOCK = 24 * HOUR
        private const val MAGIC = 0x4C57504E // "LWPN"
        private const val LEN = 4 + 1 + 4 + 8 + 8 + 4 + 16

        /** 1..4 ⇒ 0; 5 ⇒ 1 h; 6 ⇒ 2 h; 7 ⇒ 4 h; 8 ⇒ 8 h; 9 ⇒ 16 h; ≥10 ⇒ 24 h. */
        fun lockoutFor(failures: Int): Long {
            if (failures < FREE_ATTEMPTS) return 0
            val exp = (failures - FREE_ATTEMPTS).coerceAtMost(5)
            return (HOUR shl exp).coerceAtMost(MAX_LOCK)
        }

        fun encode(s: State): ByteArray {
            val bb = ByteBuffer.allocate(LEN)
            bb.putInt(MAGIC).put(1).putInt(s.failures).putLong(s.lockStartElapsed).putLong(s.lockDurationMs).putInt(s.boot)
            val body = bb.array().copyOf(LEN - 16)
            bb.put(MessageDigest.getInstance("SHA-256").digest(body), 0, 16)
            return bb.array()
        }

        fun decode(b: ByteArray): State? {
            if (b.size != LEN) return null
            val body = b.copyOf(LEN - 16)
            if (!MessageDigest.getInstance("SHA-256").digest(body).copyOf(16).contentEquals(b.copyOfRange(LEN - 16, LEN))) return null
            val bb = ByteBuffer.wrap(b)
            if (bb.int != MAGIC || bb.get().toInt() != 1) return null
            val s = State(bb.int, bb.long, bb.long, bb.int)
            if (s.failures < 0 || s.lockDurationMs < 0 || s.lockDurationMs > MAX_LOCK) return null
            return s
        }
    }
}
