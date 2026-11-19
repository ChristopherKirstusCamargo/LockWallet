package com.lockwallet.custody.local

import com.lockwallet.custody.local.bio.BioGate
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.material.PendingMaterial
import com.lockwallet.custody.local.pin.PinAttemptLimiter.Companion.HOUR
import com.lockwallet.custody.local.storage.WalletRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ADR 0024: biometric unlock as an alternative to the PIN, with the PIN as mandatory fallback. Attack-oriented. */
class BiometricUnlockTest {
    @get:Rule val tmp = TemporaryFolder()
    private val kek = FakeKekStore()
    private val bioKeys = FakeBioKeyStore()
    private val clock = FakeClock()
    private fun provider() = LocalSecureProvider(tmp.root, kek, clock, bioKeys = bioKeys)
    private val pin = "480917"
    private val spec = Fixtures.json("lockwallet-spec-23-1.json")["mnemonic"].asString
    private fun reason(block: () -> Unit) = (runCatching(block).exceptionOrNull() as CustodyException).reason

    private fun create(p: LocalSecureProvider, mode: AuthMode = AuthMode.PIN_AND_BIOMETRIC, bio: Boolean = false) =
        PendingMaterial.fromPhrase(spec.toCharArray()).use { m ->
            val t = p.beginNewVault(mode); kek.biometricsPresented = 1
            p.seal(t, m, m.discover(1)[0], "Principal", WalletRole.VAULT, newPin = pin.toCharArray(), enableBiometric = bio)
        }

    private fun pinUnlock(p: LocalSecureProvider, id: String, pinText: String = pin): UnlockResult {
        val t = p.beginUnlock(id); kek.biometricsPresented = 1
        return p.completeUnlock(t, pinText.toCharArray())
    }

    private fun bioUnlock(p: LocalSecureProvider, id: String? = null): UnlockedWallet {
        val t = p.beginBiometricUnlock(id); bioKeys.biometrics = 1
        return p.completeBiometricUnlock(t)
    }

    private fun enable(p: LocalSecureProvider, id: String) = (pinUnlock(p, id) as UnlockResult.Success).session.use { p.enableBiometricUnlock(it) }

    @Test fun offByDefaultAndAfterUpgrade() {
        val p = provider(); val w = create(p)
        assertEquals(BioGate.OFF, p.biometricGate())
        assertEquals(CustodyException.Reason.BIO_PIN_REQUIRED, reason { p.beginBiometricUnlock(w.id) })
        assertFalse(File(tmp.root, "bio").exists())
    }

    @Test fun enableWithPinThenUnlockWithBiometricOnlyAndVerifiedMaterial() {
        val p = provider(); val w = create(p)
        enable(p, w.id)
        assertEquals(BioGate.READY, p.biometricGate())
        bioUnlock(p).use { s ->
            assertEquals(w.address, s.info.address)
            assertFalse(s.viaPin)
            // Material verified inside custody (public key re-derived); reading it back is PIN-only.
            assertEquals(spec, s.materialCopy().use { m -> PendingMaterial.fromEnvelope(PendingMaterial.KIND_BIP39, m).use { pm -> pm.words().joinToString(" ") { String(it) } } })
            // A biometric session can never vouch for the PIN: no sealing / adding accounts / re-enabling with it.
            assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { s.pinVerification() })
            assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { p.enableBiometricUnlock(s) })
        }
        // A new provider instance (process death) still works.
        bioUnlock(provider(), w.id).close()
    }

    @Test fun onboardingChoiceEnablesItDirectly() {
        val p = provider(); val w = create(p, bio = true)
        assertEquals(BioGate.READY, p.biometricGate())
        bioUnlock(p, w.id).close()
    }

    /** Attack: complete without a real biometric (forged success callback, replayed ticket). */
    @Test fun noBiometricNoUnlockAndTicketsAreSingleUse() {
        val p = provider(); val w = create(p, bio = true)
        val t = p.beginBiometricUnlock(w.id)
        bioKeys.biometrics = 0
        assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { p.completeBiometricUnlock(t) })
        bioKeys.biometrics = 1
        p.completeBiometricUnlock(t).close()
        bioKeys.biometrics = 0
        assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { p.completeBiometricUnlock(p.beginBiometricUnlock(w.id)) })
    }

    /** Lockout bypass attempt: after any wrong PIN, biometrics stop until the right PIN is entered. */
    /** Attack: use a biometric session to read the phrase, export it or derive accounts. Custody refuses (not just the UI). */
    @Test fun biometricSessionCannotRevealExportOrDerive() {
        val p = provider(); val w = create(p); enable(p, w.id)
        bioUnlock(p, w.id).use { s ->
            assertEquals(false, s.viaPin)
            assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { s.words() })
            assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { s.exportLwrp("a sufficiently long password".toCharArray()) })
            assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { s.toPending() })
            assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { s.pinVerification() })
        }
        (pinUnlock(p, w.id) as UnlockResult.Success).session.use { s -> s.words().forEach { it.fill('\u0000') } }
    }

    @Test fun pinErrorsDisableBiometricsUntilCorrectPin() {
        val p = provider(); val w = create(p, AuthMode.PIN_ONLY, bio = true)
        assertTrue(pinUnlock(p, w.id, "000001") is UnlockResult.Failed)
        assertEquals(BioGate.PIN_AFTER_ERRORS, p.biometricGate())
        assertEquals(CustodyException.Reason.BIO_PIN_REQUIRED, reason { p.beginBiometricUnlock(w.id) })
        repeat(4) { pinUnlock(p, w.id, "000001") }
        assertTrue(pinUnlock(p, w.id) is UnlockResult.LockedOut)
        assertEquals(CustodyException.Reason.BIO_PIN_REQUIRED, reason { p.beginBiometricUnlock(null) })
        clock.elapsed += HOUR
        assertEquals("lockout over, still PIN first", BioGate.PIN_AFTER_ERRORS, p.biometricGate())
        (pinUnlock(p, w.id) as UnlockResult.Success).session.close()
        assertEquals(BioGate.READY, p.biometricGate())
        assertEquals(1, p.wallets().size) // never wiped
    }

    @Test fun pinRequiredAfterRebootOrClockRollbackAndEvery24HoursByDefault() {
        val p = provider(); val w = create(p, bio = true)
        clock.boot++
        assertEquals(BioGate.PIN_AFTER_RESTART, p.biometricGate())
        (pinUnlock(p, w.id) as UnlockResult.Success).session.close()
        assertEquals(BioGate.READY, p.biometricGate())
        clock.elapsed -= 1  // elapsedRealtime went backwards ⇒ treat as a restart
        assertEquals(BioGate.PIN_AFTER_RESTART, p.biometricGate())
        (pinUnlock(p, w.id) as UnlockResult.Success).session.close()
        clock.elapsed += 24 * HOUR - 1
        assertEquals(BioGate.READY, p.biometricGate())
        clock.elapsed += 2
        assertEquals(BioGate.PIN_EXPIRED, p.biometricGate())
        assertEquals(CustodyException.Reason.BIO_PIN_REQUIRED, reason { p.beginBiometricUnlock(w.id) })
    }

    /** New fingerprint enrolled ⇒ OS invalidates the key ⇒ feature off, PIN works, wallet untouched. */
    @Test fun invalidationTurnsItOffAndPinStillWorks() {
        val p = provider(); val w = create(p, AuthMode.PIN_ONLY, bio = true)
        bioKeys.invalidated = true
        assertEquals(CustodyException.Reason.BIO_INVALIDATED, reason { p.beginBiometricUnlock(w.id) })
        assertEquals(BioGate.OFF, p.biometricGate())
        assertTrue(bioKeys.keys.isEmpty())
        assertTrue(pinUnlock(p, w.id) is UnlockResult.Success)
    }

    /** Storage tampering: flipped record, swapped records, forged policy state. Never opens; PIN keeps working. */
    @Test fun tamperedRecordsAndStateFailClosed() {
        val p = provider(); val w = create(p, AuthMode.PIN_ONLY, bio = true)
        val dir = File(tmp.root, "bio")
        val rec = File(dir, "${w.id}.lwbio")
        val good = rec.readBytes()
        for (i in listOf(10, 60, good.size / 2, good.size - 1)) {
            val b = good.copyOf(); b[i] = (b[i].toInt() xor 1).toByte(); rec.writeBytes(b)
            val r = runCatching { bioUnlock(p, w.id).close() }.exceptionOrNull() as CustodyException
            assertTrue(r.reason == CustodyException.Reason.BIO_NOT_ENROLLED)
            rec.writeBytes(good)
        }
        // Forged policy state (e.g. to stretch the 72 h window) ⇒ checksum fails ⇒ OFF, never READY.
        val st = File(dir, "state.lwbs"); val sb = st.readBytes(); sb[25] = (sb[25].toInt() xor 0x7f).toByte(); st.writeBytes(sb)
        assertEquals(BioGate.OFF, p.biometricGate())
        assertTrue(pinUnlock(p, w.id) is UnlockResult.Success)
    }

    /** Attack: rewrite the policy file with a valid-looking body and a recomputed (unkeyed) digest. The Keystore HMAC refuses it. */
    @Test fun forgedStateWithRecomputedDigestIsRejected() {
        val p = provider(); val w = create(p, AuthMode.PIN_ONLY, bio = true)
        val st = File(File(tmp.root, "bio"), "state.lwbs"); val good = st.readBytes()
        assertEquals(68, good.size)
        clock.elapsed += com.lockwallet.custody.local.bio.BioVault.MAX_WITHOUT_PIN_MS + 1
        assertEquals(BioGate.PIN_EXPIRED, p.biometricGate())
        val body = good.copyOf(36)
        java.nio.ByteBuffer.wrap(body).putLong(25, clock.elapsed)   // "PIN just entered"
        st.writeBytes(body + java.security.MessageDigest.getInstance("SHA-256").digest(body))
        assertEquals(BioGate.OFF, p.biometricGate())
        assertEquals(CustodyException.Reason.BIO_PIN_REQUIRED, reason { bioUnlock(p, w.id).close() })
        st.writeBytes(good)   // the genuine file still says "expired"
        assertEquals(BioGate.PIN_EXPIRED, p.biometricGate())
        assertTrue(pinUnlock(p, w.id) is UnlockResult.Success)
    }

    @Test fun recordIsBoundToItsWalletAndNeverHoldsPlaintext() {
        val p = provider(); val w = create(p, AuthMode.PIN_ONLY, bio = true)
        val rec = File(tmp.root, "bio/${w.id}.lwbio").readBytes()
        val entropy = PendingMaterial.fromPhrase(spec.toCharArray()).use { it.copySecret().use { s -> s.withBytes { b -> b.copyOf() } } }
        assertFalse(String(rec, Charsets.ISO_8859_1).contains(String(entropy, Charsets.ISO_8859_1)))
        // Copy wallet A's record under another wallet id ⇒ rejected.
        val other = File(tmp.root, "bio/" + "ab".repeat(16) + ".lwbio"); other.writeBytes(rec)
        assertEquals(CustodyException.Reason.BIO_NOT_ENROLLED, reason { p.beginBiometricUnlock("ab".repeat(16)) })
    }

    @Test fun disableRemovesKeyAndRecordsAndNewVaultNeverInherits() {
        val p = provider(); val w = create(p, AuthMode.PIN_ONLY, bio = true)
        p.disableBiometricUnlock()
        assertTrue(bioKeys.keys.isEmpty())
        assertTrue(File(tmp.root, "bio").listFiles().isNullOrEmpty())
        assertEquals(BioGate.OFF, p.biometricGate())
        assertTrue(pinUnlock(p, w.id) is UnlockResult.Success)
    }

    // ------------------------------------------------------------------ 5.0: coercion window + lockdown

    @Test fun lockdownRefusesBiometricsUntilNextPin() {
        val p = provider(); val w = create(p, bio = true)
        assertEquals(BioGate.READY, p.biometricGate())
        p.biometricLockdown()
        assertEquals(BioGate.PIN_LOCKDOWN, p.biometricGate())
        assertEquals(CustodyException.Reason.BIO_PIN_REQUIRED, reason { p.beginBiometricUnlock(w.id) })
        // Survives a restart of the app (persisted, HMAC-authenticated) and a reboot still needs the PIN anyway.
        assertEquals(BioGate.PIN_LOCKDOWN, provider().biometricGate())
        (pinUnlock(p, w.id) as UnlockResult.Success).session.close()
        assertEquals(BioGate.READY, p.biometricGate())
        p.disableBiometricUnlock(); p.biometricLockdown() // no-op when off, never throws
        assertEquals(BioGate.OFF, p.biometricGate())
    }

    @Test fun windowShortensFreelyButOnlyThePinCanLengthenIt() {
        val p = provider(); val w = create(p, bio = true)
        assertEquals(24, p.biometricWindowHours())
        p.setBiometricWindow(1, null)
        clock.elapsed += HOUR + 1
        assertEquals(BioGate.PIN_EXPIRED, p.biometricGate())
        (pinUnlock(p, w.id) as UnlockResult.Success).session.close()
        // A biometric session (possibly a coerced finger) cannot extend its own window.
        bioUnlock(p, w.id).use { s -> assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { p.setBiometricWindow(72, s) }) }
        assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { p.setBiometricWindow(4, null) })
        assertEquals(1, p.biometricWindowHours())
        (pinUnlock(p, w.id) as UnlockResult.Success).session.use { s -> p.setBiometricWindow(72, s) }
        assertEquals(72, p.biometricWindowHours())
        assertEquals(CustodyException.Reason.STORAGE, reason { p.setBiometricWindow(48, null) }) // not an allowed choice
    }

    /** Attack: flip the window/lockdown bytes in the state file. HMAC ⇒ OFF (never a longer window). */
    @Test fun editedWindowOrLockdownFlagFailsClosed() {
        val p = provider(); val w = create(p, bio = true)
        p.biometricLockdown()
        val st = File(File(tmp.root, "bio"), "state.lwbs"); val good = st.readBytes()
        for ((i, v) in listOf(33 to 0x48, 34 to 0x48, 35 to 0x00)) { // window → 72 h / lockdown → off
            val b = good.copyOf(); b[i] = v.toByte(); st.writeBytes(b)
            assertEquals(BioGate.OFF, p.biometricGate())
        }
        st.writeBytes(good)
        assertEquals(BioGate.PIN_LOCKDOWN, p.biometricGate())
        assertTrue(pinUnlock(p, w.id) is UnlockResult.Success)
    }

    /** Upgrade from 4.0 (state v1, fixed 72 h): read with the stricter 24 h default, and a v1 forgery still fails. */
    @Test fun v1StateFromOlderVersionGetsTheStricterDefault() {
        val p = provider(); val w = create(p, bio = true)
        val st = File(File(tmp.root, "bio"), "state.lwbs"); val v2 = st.readBytes()
        val alias = v2.copyOfRange(5, 21)
        val body = java.nio.ByteBuffer.allocate(33).putInt(0x4C574253).put(1).put(alias).putInt(clock.bootCount()).putLong(clock.elapsedRealtimeMs()).array()
        st.writeBytes(body + bioKeys.mac(alias, "LWBIO-v1/state".toByteArray() + body))
        assertEquals(BioGate.READY, p.biometricGate())
        assertEquals(24, p.biometricWindowHours())
        clock.elapsed += 24 * HOUR + 1
        assertEquals(BioGate.PIN_EXPIRED, p.biometricGate())
        st.writeBytes(body + ByteArray(32))
        assertEquals(BioGate.OFF, p.biometricGate())
        assertTrue(pinUnlock(p, w.id) is UnlockResult.Success)
    }

    /** A-52 (device edge case): the boot counter is unavailable; a reboot must still force the PIN. */
    @Test fun unknownBootCounterForcesThePin() {
        val p = provider(); val w = create(p); enable(p, w.id)
        assertEquals(BioGate.READY, p.biometricGate())
        clock.boot = -1; clock.elapsed += HOUR // "rebooted", uptime already past the last PIN
        assertEquals(BioGate.PIN_AFTER_RESTART, p.biometricGate())
        assertEquals(CustodyException.Reason.BIO_PIN_REQUIRED, reason { p.beginBiometricUnlock(w.id) })
        assertTrue(pinUnlock(p, w.id) is UnlockResult.Success) // the PIN always works
        assertEquals(BioGate.PIN_AFTER_RESTART, p.biometricGate()) // still unknown ⇒ still PIN
        clock.boot = 9; pinUnlock(p, w.id)
        assertEquals(BioGate.READY, p.biometricGate())
    }

    /** A-56 (device edge case): uptime jumps backwards without a boot-count change (clock/emulator glitch). */
    @Test fun uptimeGoingBackwardsForcesThePin() {
        val p = provider(); val w = create(p); enable(p, w.id)
        clock.elapsed -= 1
        assertEquals(BioGate.PIN_AFTER_RESTART, p.biometricGate())
    }
}
