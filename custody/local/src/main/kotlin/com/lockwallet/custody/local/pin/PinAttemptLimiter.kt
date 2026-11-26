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
 * PIN attempt limiter (ADR 0009, prompt 1.2): 5 wrong PINs ⇒ 1 h, each further failure doubles up to 24 h.
 * Never wipes anything. Write-ahead: the attempt is persisted (fsync) BEFORE Argon2id runs, so killing the
 * process mid-attempt counts as a failure. Two copies are kept and the most restrictive wins (deleting one
 * does not reset); a missing/corrupt state while a vault exists is treated as "one attempt left".
 * A reboot restarts the remaining lockout from the full duration (never shortens it).
 * Both copies missing/corrupt while a vault exists ⇒ fail closed with the maximum 24 h lockout.
 * Residual: a full same-device snapshot can still roll back both valid copies; Android exposes no app-level
 * hardware monotonic counter. Per-use hardware authentication on new vaults is the primary brute-force boundary.
 */
class PinAttemptLimiter(
    private val primary: File,
    private val mirror: File,
    private val clock: AttemptClock,
    private val atomic: AtomicFiles,
    private val vaultExists: () -> Boolean,
    private val hmac: (ByteArray) -> ByteArray,
) {
    data class State(val failures: Int, val lockStartElapsed: Long, val lockDurationMs: Long, val boot: Int)

    fun gate(): AttemptGate {
        val s = current()
        val remaining = remaining(s)
        if (remaining > 0) return AttemptGate.LockedOut(remaining)
        val failures = (s.failures + 1).coerceAtMost(MAX_FAILURES)
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
            if (!vaultExists()) return State(0, 0, 0, clock.bootCount())
            // Deleting/corrupting both files must never grant a sequence of one free PIN guess per deletion.
            val locked = State(MAX_FAILURES, clock.elapsedRealtimeMs(), MAX_LOCK, clock.bootCount())
            persist(locked)
            return locked
        }
        if (a == null || b == null) {
            if (!vaultExists()) return a ?: b!!
            // Both copies are always written together (atomically, each verified), so one missing or invalid copy
            // means tampering, e.g. an older copy restored next to a deleted one. The survivor may be that older copy,
            // so it is not trusted: same answer as when both are gone (maximum count, full 24 h lockout from now).
            val locked = State(MAX_FAILURES, clock.elapsedRealtimeMs(), MAX_LOCK, clock.bootCount())
            persist(locked)
            return locked
        }
        if (a.failures != b.failures) return if (a.failures > b.failures) a else b
        val now = clock.elapsedRealtimeMs()
        val boot = clock.bootCount()
        fun remainingWithoutWrite(s: State): Long = when {
            s.lockDurationMs <= 0 -> 0
            s.boot != boot || now < s.lockStartElapsed -> s.lockDurationMs
            else -> (s.lockStartElapsed + s.lockDurationMs - now).coerceAtLeast(0)
        }
        val ar = remainingWithoutWrite(a)
        val br = remainingWithoutWrite(b)
        if (ar != br) return if (ar > br) a else b
        if (a.lockDurationMs != b.lockDurationMs) return if (a.lockDurationMs > b.lockDurationMs) a else b
        if (a.boot == b.boot && a.lockStartElapsed != b.lockStartElapsed) {
            return if (a.lockStartElapsed > b.lockStartElapsed) a else b
        }
        return a
    }

    private fun persist(s: State) {
        val bytes = encode(s)
        atomic.write(primary, bytes) { check(decode(it) == s) }
        atomic.write(mirror, bytes) { check(decode(it) == s) }
    }

    private fun read(f: File): State? = if (!f.exists()) null else try { decode(f.readBytes()) } catch (_: Exception) { null }

    private fun encode(s: State): ByteArray {
        val body = ByteBuffer.allocate(BODY_LEN)
            .putInt(MAGIC).put(VERSION).putInt(s.failures).putLong(s.lockStartElapsed)
            .putLong(s.lockDurationMs).putInt(s.boot).array()
        val tag = hmac(MAC_DOMAIN + body)
        require(tag.size == MAC_LEN) { "invalid Keystore MAC length" }
        return body + tag
    }

    private fun decode(bytes: ByteArray): State? {
        if (bytes.size != BODY_LEN + MAC_LEN) return null
        val body = bytes.copyOfRange(0, BODY_LEN)
        val tag = bytes.copyOfRange(BODY_LEN, bytes.size)
        val expected = runCatching { hmac(MAC_DOMAIN + body) }.getOrNull() ?: return null
        if (expected.size != MAC_LEN || !MessageDigest.isEqual(expected, tag)) return null
        val bb = ByteBuffer.wrap(body)
        if (bb.int != MAGIC || bb.get() != VERSION) return null
        val state = State(bb.int, bb.long, bb.long, bb.int)
        if (state.failures !in 0..MAX_FAILURES || state.lockDurationMs < 0 || state.lockDurationMs > MAX_LOCK ||
            state.lockStartElapsed < 0L
        ) return null
        return state
    }

    companion object {
        const val FREE_ATTEMPTS = 5
        const val HOUR = 3_600_000L
        const val MAX_LOCK = 24 * HOUR
        private const val MAGIC = 0x4C57504E // "LWPN"
        private const val VERSION: Byte = 2
        private const val BODY_LEN = 4 + 1 + 4 + 8 + 8 + 4
        private const val MAC_LEN = 32
        private const val MAX_FAILURES = 10
        private val MAC_DOMAIN = "LockWallet/PIN-attempt-state/v2\u0000".toByteArray(Charsets.US_ASCII)

        /** 1..4 ⇒ 0; 5 ⇒ 1 h; 6 ⇒ 2 h; 7 ⇒ 4 h; 8 ⇒ 8 h; 9 ⇒ 16 h; ≥10 ⇒ 24 h. */
        fun lockoutFor(failures: Int): Long {
            if (failures < FREE_ATTEMPTS) return 0
            val exp = (failures - FREE_ATTEMPTS).coerceAtMost(5)
            return (HOUR shl exp).coerceAtMost(MAX_LOCK)
        }

    }
}
