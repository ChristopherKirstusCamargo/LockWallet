package com.lockwallet.app.security

import com.lockwallet.app.policy.SendPolicy
import com.lockwallet.app.policy.SendPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import java.util.Random

internal class MemStore : SendPolicy.Store {
    val m = HashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
}

internal fun randomAddress(r: Random): String = com.lockwallet.core.solana.SolanaPublicKey.of(ByteArray(32).also { r.nextBytes(it) }).toBase58()

private const val DAY = SendPolicy.COOLDOWN_MS
private const val T0 = 1_800_000_000_000L
private const val SOL = 1_000_000_000L

/**
 * Daily-limit invariant against an exact oracle: whatever the order, size and timing of sends (including floods of
 * dust meant to push big sends out of the bounded log), an allowed send never takes the 24 h total above the limit.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class SendLimitPropertyTest(private val seed: Int) {
    companion object {
        @JvmStatic @Parameters(name = "seed {0}") fun data(): List<Array<Any>> = (1..250).map { arrayOf<Any>(it) }
    }

    @Test fun allowedSendsNeverExceedTheLimitInAnyWindow() {
        val r = Random(seed * 31L)
        val p = SendPolicy(MemStore())
        val limit = (1 + r.nextInt(50)) * SOL / (1 + r.nextInt(4))
        p.requestLimit(limit, T0)
        assertEquals(limit, p.dailyLimitLamports)
        val sent = ArrayList<Pair<Long, Long>>() // exact oracle
        var now = T0
        val dest = randomAddress(r)
        val steps = 60 + r.nextInt(300)
        val flood = seed % 3 == 0
        repeat(steps) { i ->
            now += when { flood && i % 50 != 0 -> 1L + r.nextInt(1000); r.nextInt(10) == 0 -> r.nextInt(30) * 3_600_000L; else -> r.nextInt(600_000).toLong() }
            val amt = when { flood && i % 50 != 0 -> 1L + r.nextInt(10); r.nextBoolean() -> 1L + (r.nextLong() and Long.MAX_VALUE) % limit; else -> 1L + (r.nextLong() and Long.MAX_VALUE) % (limit * 2) }
            val oracle = sent.filter { it.first > now - SendPolicy.WINDOW_MS }.sumOf { it.second }
            val v = p.check(dest, amt, now, emptySet())
            if (v == Verdict.Allowed) {
                assertTrue("seed $seed step $i: allowed $amt with $oracle of $limit already spent", oracle + amt <= limit)
                p.recordSpend(amt, now); sent += now to amt
            } else {
                assertTrue((v as Verdict.Blocked).reason.startsWith(SendPolicy.LIMIT_PREFIX))
            }
        }
        // The full limit is available again one window after the last send.
        assertEquals(Verdict.Allowed, p.check(dest, limit, now + SendPolicy.WINDOW_MS + 1, emptySet()))
    }
}

/** Every way of reaching a looser policy early: each loosening waits the full cooldown, measured by the given clock. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class SendCooldownBypassTest(private val offsetMs: Long, private val attempt: String) {
    companion object {
        private val OFFSETS = listOf(0L, 1L, 1_000L, 60_000L, 3_600_000L, 12 * 3_600_000L, DAY - 60_000L, DAY - 1L, -1L, -DAY, -365 * DAY, Long.MIN_VALUE / 4)
        private val ATTEMPTS = listOf("raise", "remove", "allowlist-off", "raise-twice", "re-request", "cancel-and-raise")
        @JvmStatic @Parameters(name = "{1} at +{0}ms") fun data(): List<Array<Any>> = ATTEMPTS.flatMap { a -> OFFSETS.map { arrayOf<Any>(it, a) } }
    }

    @Test fun looseningNeverAppliesBeforeTheCooldown() {
        val p = SendPolicy(MemStore())
        p.requestLimit(SOL, T0); p.enableAllowlist()
        when (attempt) {
            "raise" -> p.requestLimit(10 * SOL, T0)
            "remove" -> p.requestLimit(0, T0)
            "allowlist-off" -> p.requestAllowlistOff(T0)
            "raise-twice" -> { p.requestLimit(5 * SOL, T0); p.requestLimit(10 * SOL, T0 + DAY / 2) } // restarts the clock
            "re-request" -> { p.requestLimit(10 * SOL, T0 - DAY); p.requestLimit(10 * SOL, T0) }   // the newest request counts
            "cancel-and-raise" -> { p.requestLimit(10 * SOL, T0 - 2 * DAY); p.cancelPending(); p.requestLimit(10 * SOL, T0) }
        }
        val start = if (attempt == "raise-twice") T0 + DAY / 2 else T0
        val now = start + offsetMs
        p.settle(now)
        val dest = randomAddress(Random(offsetMs))
        if (offsetMs < DAY) {
            assertEquals(SOL, p.dailyLimitLamports)
            assertTrue(p.allowlistOnly)
            assertTrue(p.check(dest, 2 * SOL, now, setOf(dest)) is Verdict.Blocked)
            assertTrue(p.check(dest, 1, now, emptySet()) is Verdict.Blocked)
        }
        // At the cooldown boundary it applies.
        p.settle(start + DAY)
        when (attempt) {
            "allowlist-off" -> assertFalse(p.allowlistOnly)
            "remove" -> assertEquals(0L, p.dailyLimitLamports)
            else -> assertEquals(10 * SOL, p.dailyLimitLamports)
        }
    }
}

/** A damaged or tampered settings file must never relax a protection. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class SendPolicyTamperTest(private val key: String, private val garbage: String) {
    companion object {
        private val GARBAGE = listOf("", " ", "0", "-1", "-9223372036854775808", "9223372036854775808", "1e9", "NaN", "null", "[", "{}", "[[1]]",
            "[[\"a\",\"b\"]]", "\u0000damaged", "[[1,2],[3]]", "true")
        @JvmStatic @Parameters(name = "{0} = ''{1}''") fun data(): List<Array<Any>> =
            listOf("sp.allow", "sp.limit", "sp.spent", "sp.book", "sp.pending", "sp.confirmNew").flatMap { k -> GARBAGE.map { arrayOf<Any>(k, it) } }
    }

    @Test fun failsClosed() {
        val st = MemStore(); val p = SendPolicy(st)
        p.requestLimit(SOL, T0); p.enableAllowlist()
        val c = randomAddress(Random(1)); p.addContact(c, "Corretora", T0 - 2 * DAY)
        p.recordSpend(SOL / 2, T0)
        st.m[key] = garbage
        val now = T0 + 1
        when (key) {
            "sp.allow" -> assertTrue(p.allowlistOnly)
            "sp.limit" -> {
                val l = p.dailyLimitLamports
                assertTrue("limit $l from '$garbage'", l in 1L..SOL || garbage.toLongOrNull()?.let { it in 1L..SOL } == true)
                assertTrue(p.check(c, 2 * SOL, now, emptySet()) is Verdict.Blocked)
            }
            "sp.spent" -> {
                val parsedOk = runCatching { org.json.JSONArray(garbage).let { a -> (0 until a.length()).forEach { i -> a.getJSONArray(i).let { it.getLong(0); it.getLong(1) } } } }.isSuccess
                if (!parsedOk) assertTrue(p.check(c, 1, now, emptySet()) is Verdict.Blocked)
                // a well-formed log is honoured as written; nothing above the limit is ever allowed
                assertTrue(p.check(c, SOL + 1, now, emptySet()) is Verdict.Blocked)
            }
            "sp.book" -> assertTrue(p.check(c, 1, now, emptySet()).let { it is Verdict.Blocked || p.contact(c) != null })
            "sp.pending" -> { p.settle(now + 10 * DAY); assertEquals(SOL, p.dailyLimitLamports); assertTrue(p.allowlistOnly) }
            "sp.confirmNew" -> assertEquals(garbage != "0", p.confirmNewAddress)
        }
        // A send far above the limit is never allowed, whatever was damaged.
        assertTrue(p.check(c, 100 * SOL, now, emptySet()) is Verdict.Blocked)
    }
}

/** Contacts-only mode matches addresses exactly: a one-character change, a case flip or whitespace is a stranger. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class AllowlistLookalikeTest(private val seed: Int) {
    companion object {
        @JvmStatic @Parameters(name = "seed {0}") fun data(): List<Array<Any>> = (1..120).map { arrayOf<Any>(it) }
        private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    }

    @Test fun nearMissesAreBlocked() {
        val r = Random(seed * 97L)
        val p = SendPolicy(MemStore()); p.enableAllowlist()
        val real = randomAddress(r); p.addContact(real, "Exchange", T0 - 2 * DAY)
        assertEquals(Verdict.Allowed, p.check(real, 1, T0, emptySet()))
        val i = r.nextInt(real.length)
        val ch = real[i]
        val mutations = listOf(
            real.substring(0, i) + ALPHABET.filter { it != ch }[r.nextInt(ALPHABET.length - 1)] + real.substring(i + 1),
            real.substring(0, i) + (if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()) + real.substring(i + 1),
            " $real", "$real ", real + "1", real.dropLast(1), real.reversed(), real.uppercase(), real.lowercase(),
            real.replace('l', 'I'), real.replace('o', '0'),
        ).filter { it != real }
        for (m in mutations) assertTrue("'$m' passed as '$real'", p.check(m, 1, T0, emptySet()) is Verdict.Blocked)
    }
}
