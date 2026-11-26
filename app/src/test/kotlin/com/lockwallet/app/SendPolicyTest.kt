package com.lockwallet.app

import com.lockwallet.app.policy.SendPolicy
import com.lockwallet.app.policy.SendPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pure rules of the optional send protections (Ajustes > Segurança). */
@RunWith(RobolectricTestRunner::class)
class SendPolicyTest {
    private class Mem : SendPolicy.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }
    private val day = SendPolicy.COOLDOWN_MS
    private val t0 = 1_800_000_000_000L
    private val a = "GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv"
    private val b = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"

    @Test fun defaultsAreOpenWithNewAddressConfirmationOn() {
        val p = SendPolicy(Mem())
        assertFalse(p.allowlistOnly); assertEquals(0L, p.dailyLimitLamports); assertTrue(p.confirmNewAddress)
        assertEquals(Verdict.Allowed, p.check(a, 10_000_000_000L, t0, emptySet()))
        assertNull(p.remaining(t0))
    }

    @Test fun allowlistAllowsOwnWalletsAndMatureContactsOnly() {
        val p = SendPolicy(Mem())
        p.enableAllowlist()
        assertTrue(p.check(a, 1, t0, emptySet()) is Verdict.Blocked)
        assertEquals(Verdict.Allowed, p.check(a, 1, t0, setOf(a)))           // own account
        p.addContact(a, "Corretora", t0)
        assertTrue(p.check(a, 1, t0 + day - 1, emptySet()) is Verdict.Blocked)
        assertEquals(Verdict.Allowed, p.check(a, 1, t0 + day, emptySet()))
        p.removeContact(a)
        assertTrue(p.check(a, 1, t0 + day, emptySet()) is Verdict.Blocked)
    }

    @Test fun turningAllowlistOffIsDeferredAndCancellable() {
        val p = SendPolicy(Mem())
        p.enableAllowlist()
        assertEquals(t0 + day, p.requestAllowlistOff(t0))
        p.settle(t0 + day - 1); assertTrue(p.allowlistOnly)
        p.cancelPending(); p.settle(t0 + 2 * day); assertTrue(p.allowlistOnly)
        p.requestAllowlistOff(t0); p.settle(t0 + day); assertFalse(p.allowlistOnly)
    }

    @Test fun reEnablingAllowlistCancelsAPendingDisable() {
        val p = SendPolicy(Mem())
        p.enableAllowlist(); p.requestAllowlistOff(t0); p.enableAllowlist()
        assertNull(p.pending())
        p.settle(t0 + 2 * day); assertTrue(p.allowlistOnly)
    }

    @Test fun limitLowersNowRaisesLaterAndCountsARollingWindow() {
        val p = SendPolicy(Mem())
        assertEquals(t0, p.requestLimit(5_000_000_000L, t0))               // enabling: immediate
        assertFalse(p.limitChangeLoosens(1_000_000_000L))
        assertTrue(p.limitChangeLoosens(9_000_000_000L))
        assertTrue(p.limitChangeLoosens(0L))
        p.recordSpend(3_000_000_000L, t0)
        assertEquals(2_000_000_000L, p.remaining(t0 + 1))
        val v = p.check(b, 2_500_000_000L, t0 + 1, emptySet())
        assertEquals(Verdict.Blocked(SendPolicy.LIMIT_PREFIX + 2_000_000_000L), v)
        assertEquals(Verdict.Allowed, p.check(b, 2_000_000_000L, t0 + 1, emptySet()))
        // 24 h later the earlier send leaves the window.
        assertEquals(Verdict.Allowed, p.check(b, 5_000_000_000L, t0 + day, emptySet()))
        // Raising waits; lowering is immediate and drops a pending raise.
        assertEquals(t0 + day, p.requestLimit(9_000_000_000L, t0))
        assertEquals(5_000_000_000L, p.dailyLimitLamports)
        p.requestLimit(1_000_000_000L, t0)
        assertEquals(1_000_000_000L, p.dailyLimitLamports); assertNull(p.pending())
        p.requestLimit(0L, t0); p.settle(t0 + day)
        assertEquals(0L, p.dailyLimitLamports)
    }

    @Test fun tokensAreNotCountedButStillFollowTheAllowlist() {
        val p = SendPolicy(Mem())
        p.requestLimit(1L, t0)
        assertEquals(Verdict.Allowed, p.check(a, 0L, t0, emptySet()))
        p.enableAllowlist()
        assertTrue(p.check(a, 0L, t0, emptySet()) is Verdict.Blocked)
    }

    @Test fun futureRecordsCannotPinTheWindowShut() {
        val p = SendPolicy(Mem())
        p.requestLimit(1_000_000_000L, t0)
        p.recordSpend(1_000_000_000L, t0 + 30 * day)                        // garbage clock entry
        assertEquals(1_000_000_000L, p.remaining(t0))
    }

    @Test fun contactBookValidatesAndIsBounded() {
        val p = SendPolicy(Mem())
        p.addContact(a, "  Corretora  ", t0)
        assertEquals("Corretora", p.contact(a)!!.name)
        assertTrue(runCatching { p.addContact(a, "Outra", t0) }.isFailure)  // duplicate address
        assertTrue(runCatching { p.addContact(b, "   ", t0) }.isFailure)    // blank name
        p.renameContact(a, "Cofre")
        assertEquals("Cofre", p.contact(a)!!.name)
        assertEquals(t0, p.contact(a)!!.addedAtMs)                           // renaming never resets the 24 h clock
    }

    @Test fun corruptedStoreFailsToSafeDefaults() {
        val m = Mem(); m.m["sp.book"] = "{not json"; m.m["sp.pending"] = "[]"; m.m["sp.spent"] = "x"
        val p = SendPolicy(m)
        assertTrue(p.contacts().isEmpty()); assertNull(p.pending())
        // A damaged spend log fails closed: the window counts as full for 24 h, then the limit is available again.
        assertEquals(SendPolicy.CORRUPT_SPEND, p.spentInWindow(t0))
        p.requestLimit(5_000_000_000L, t0)
        assertTrue(p.check(a, 1, t0 + 1, emptySet()) is Verdict.Blocked)
        assertEquals(Verdict.Allowed, p.check(a, 1, t0 + SendPolicy.WINDOW_MS + 1, emptySet()))
    }
}
