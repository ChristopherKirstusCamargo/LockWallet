package com.lockwallet.custody.local

import com.lockwallet.custody.local.api.RecoveryException
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.material.AccountProfile
import com.lockwallet.custody.local.material.PendingMaterial
import com.lockwallet.custody.local.pin.PinAttemptLimiter.Companion.HOUR
import com.lockwallet.custody.local.storage.WalletRole
import com.lockwallet.custody.local.storage.WalletState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalSecureProviderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val kek = FakeKekStore()
    private val clock = FakeClock()
    private fun provider() = LocalSecureProvider(tmp.root, kek, clock)
    private val pin = "listOf(8, 3, 5 + 4, 2 - 1, 14 - 7, 2 * 2).joinToString("")"
    private val spec = Fixtures.json("lockwallet-spec-23-1.json")["mnemonic"].asString

    private fun reason(block: () -> Unit) = (runCatching(block).exceptionOrNull() as CustodyException).reason

    private fun createFirst(p: LocalSecureProvider, mode: AuthMode = AuthMode.PIN_AND_BIOMETRIC, phrase: String = spec, role: WalletRole = WalletRole.VAULT) =
        PendingMaterial.fromPhrase(phrase.toCharArray()).use { m ->
            val t = p.beginNewVault(mode)
            assertEquals(mode != AuthMode.PIN_ONLY, t.needsBiometric)
            if (t.needsBiometric) assertTrue(t.cipher != null)
            kek.biometricsPresented = 1
            p.seal(t, m, m.discover(1)[0], "Principal", role, newPin = pin.toCharArray())
        }

    private fun unlock(p: LocalSecureProvider, id: String, pinText: String = pin): UnlockResult {
        val t = p.beginUnlock(id)
        kek.biometricsPresented = 1
        return p.completeUnlock(t, pinText.toCharArray())
    }

    @Test fun createUnlockReviewPhrase() {
        val p = provider()
        val w = createFirst(p)
        assertTrue(p.hasVault())
        assertEquals("HQjz2hMLvhqBqWx7ntNUyEbJQVTSBL6Lhv2xtUV79Pfs".length, w.address.toBase58().length)
        val r = unlock(p, w.id) as UnlockResult.Success
        r.session.use { s -> assertEquals(spec, s.words().joinToString(" ") { String(it) }) }
        // Reopen from disk with a new instance.
        (unlock(provider(), w.id) as UnlockResult.Success).session.close()
    }

    @Test fun legacyKdfIsMigratedAfterPinAndWalletReopensFromDisk() {
        val p = provider()
        val wallet = createFirst(p, AuthMode.PIN_ONLY)
        val envelopeFile = File(tmp.root, "envelopes/${wallet.id}.lwle")
        val original = com.lockwallet.custody.local.envelope.LocalEnvelope.parse(envelopeFile.readBytes())

        // Simulate a real pre-migration v1 envelope with the same wallet IDs, KEK alias and material.
        PendingMaterial.fromPhrase(spec.toCharArray()).use { material ->
            val legacyPinKey = com.lockwallet.custody.local.pin.Pin.encode(pin.toCharArray()).use {
                com.lockwallet.custody.local.envelope.EnvelopeCrypto.derivePinKey(
                    it, original.pinSalt(), com.lockwallet.custody.local.envelope.LocalEnvelope.KDF_OWASP_V1,
                )
            }
            material.copySecret().use { secret ->
                val legacy = com.lockwallet.custody.local.envelope.EnvelopeCrypto.seal(
                    original.vaultId(), original.materialId(), original.materialKind, original.derivationProfile,
                    original.accountIndex, original.expectedPubkey(), original.pinSalt(), original.aliasId(),
                    original.authMode, secret, legacyPinKey,
                    com.lockwallet.custody.local.envelope.SealRandomness(
                        ByteArray(32) { 0x31 }, ByteArray(12) { 0x41 }, ByteArray(12) { 0x51 },
                    ),
                    kek.encryptor(original.aliasId()),
                    com.lockwallet.custody.local.envelope.LocalEnvelope.KDF_OWASP_V1,
                )
                envelopeFile.writeBytes(legacy.encode())
            }
            legacyPinKey.close()
        }

        val unlocked = unlock(p, wallet.id) as UnlockResult.Success
        val ticket = requireNotNull(unlocked.migration)
        assertTrue("legacy KDF migration should commit", p.completeKdfMigration(ticket, unlocked.session))
        unlocked.session.close()
        assertEquals(
            com.lockwallet.custody.local.envelope.LocalEnvelope.KDF_STRONG_V2,
            com.lockwallet.custody.local.envelope.LocalEnvelope.parse(envelopeFile.readBytes()).kdfProfile,
        )

        // A new provider instance must reopen the migrated wallet with the same PIN and material.
        val reopened = unlock(provider(), wallet.id) as UnlockResult.Success
        reopened.session.use { session ->
            assertEquals(spec, session.words().joinToString(" ") { String(it) })
        }
        reopened.migration?.close()
    }

    @Test fun biometricIsRequiredPerUse() {
        val p = provider()
        val w = createFirst(p)
        val t = p.beginUnlock(w.id)
        kek.biometricsPresented = 0
        assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { p.completeUnlock(t, pin.toCharArray()) })
    }

    @Test fun wrongPinsLockOutAndNeverWipe() {
        val p = provider()
        val w = createFirst(p, AuthMode.PIN_ONLY)
        for (i in 1..4) assertEquals(5 - i, (unlock(p, w.id, "000001") as UnlockResult.Failed).attemptsBeforeLockout)
        val fifth = unlock(p, w.id, "000001") as UnlockResult.Failed
        assertEquals(HOUR, fifth.lockoutMs)
        // Even the right PIN is refused while locked out.
        assertEquals(HOUR, (unlock(p, w.id) as UnlockResult.LockedOut).remainingMs)
        clock.elapsed += HOUR
        assertTrue(unlock(p, w.id) is UnlockResult.Success)
        assertEquals(1, p.wallets().size)
    }

    @Test fun secondWalletUsesPinVerificationAndRolesStaySeparate() {
        val p = provider()
        val first = createFirst(p)
        val verification = (unlock(p, first.id) as UnlockResult.Success).session.use { it.pinVerification() }
        val other = PendingMaterial.generate(24)
        val t = p.beginSealExisting()
        kek.biometricsPresented = 1
        val second = p.seal(t, other, other.discover(1)[0], "dApps", WalletRole.CONNECT, verification = verification)
        assertEquals(2, p.wallets().size)
        // Same PIN opens both.
        (unlock(p, second.id) as UnlockResult.Success).session.use { assertEquals(24, it.words().size) }
        // Another account of the Lock Vault seed cannot become a Lock Connect wallet.
        val v2 = (unlock(p, first.id) as UnlockResult.Success).session.use { it.pinVerification() }
        PendingMaterial.fromPhrase(spec.toCharArray()).use { m ->
            val acct = m.discover(2).first { it.profile == AccountProfile.PRIMARY && it.index == 1 }
            assertEquals(CustodyException.Reason.ROLE_CONFLICT, reason { p.seal(p.beginSealExisting(), m, acct, "x", WalletRole.CONNECT, verification = v2) })
        }
        // Duplicate account is refused.
        val v3 = (unlock(p, first.id) as UnlockResult.Success).session.use { it.pinVerification() }
        PendingMaterial.fromPhrase(spec.toCharArray()).use { m ->
            assertEquals(CustodyException.Reason.DUPLICATE_WALLET, reason { p.seal(p.beginSealExisting(), m, m.discover(1)[0], "x", WalletRole.VAULT, verification = v3) })
        }
        // Sealing into an existing vault without a verification is refused.
        PendingMaterial.generate(12).use { m ->
            assertEquals(CustodyException.Reason.AUTH_REQUIRED, reason { p.seal(p.beginSealExisting(), m, m.discover(1)[0], "x", WalletRole.VAULT) })
        }
    }

    @Test fun weakOrMalformedPinRefusedAtSetup() {
        val p = provider()
        PendingMaterial.generate(12).use { m ->
            assertEquals(CustodyException.Reason.WEAK_PIN, reason { p.seal(p.beginNewVault(AuthMode.PIN_ONLY), m, m.discover(1)[0], "a", WalletRole.VAULT, newPin = "123456".toCharArray()) })
            assertEquals(CustodyException.Reason.BAD_PIN_FORMAT, reason { p.seal(p.beginNewVault(AuthMode.PIN_ONLY), m, m.discover(1)[0], "a", WalletRole.VAULT, newPin = "４８０９１７".toCharArray()) })
        }
        assertFalse(p.hasVault())
    }

    @Test fun keyInvalidationQuarantinesAndPreservesFiles() {
        val p = provider()
        val w = createFirst(p)
        val envFile = File(tmp.root, "envelopes/${w.id}.lwle")
        val before = envFile.readBytes()
        kek.invalidateAll()
        assertEquals(CustodyException.Reason.KEY_INVALIDATED, reason { p.beginUnlock(w.id) })
        assertEquals(WalletState.QUARANTINED, p.wallets().single().state)
        assertArrayEquals(before, envFile.readBytes())
        assertFalse(p.hasVault())
        // Recovery: restore by phrase into a brand-new vault (new KEK alias), the quarantined entry stays listed.
        val restored = createFirst(p, AuthMode.PIN_ONLY)
        assertEquals(2, p.wallets().size)
        assertTrue(unlock(p, restored.id) is UnlockResult.Success)
        p.removeQuarantined(w.id)
        assertFalse(envFile.exists())
    }

    @Test fun missingKeyIsNeverRecreated() {
        val p = provider()
        val w = createFirst(p)
        kek.keys.clear()
        assertEquals(CustodyException.Reason.KEY_MISSING, reason { p.beginUnlock(w.id) })
        assertTrue(kek.keys.isEmpty())
        assertEquals(WalletState.QUARANTINED, p.wallets().single().state)
    }

    @Test fun tamperedEnvelopeCountsAsFailedAttempt() {
        val p = provider()
        val w = createFirst(p, AuthMode.PIN_ONLY)
        val f = File(tmp.root, "envelopes/${w.id}.lwle")
        val b = f.readBytes(); b[b.size - 5] = (b[b.size - 5].toInt() xor 1).toByte(); f.writeBytes(b)
        assertTrue(unlock(p, w.id) is UnlockResult.Failed)
        val c = f.readBytes(); c[140] = (c[140].toInt() xor 1).toByte(); c[c.size - 5] = (c[c.size - 5].toInt() xor 1).toByte(); f.writeBytes(c)
        assertTrue(unlock(p, w.id) is UnlockResult.Failed) // seed_ct flip: PIN layer OK, material tag fails
    }

    @Test fun downgradeNewerEnvelopeVersionRefusedUntouched() {
        val p = provider()
        val w = createFirst(p, AuthMode.PIN_ONLY)
        val f = File(tmp.root, "envelopes/${w.id}.lwle")
        val b = f.readBytes(); b[4] = 2; f.writeBytes(b)
        assertEquals(CustodyException.Reason.UNSUPPORTED_VERSION, reason { p.beginUnlock(w.id) })
        assertArrayEquals(b, f.readBytes())
    }

    @Test fun swappedEnvelopesAreDetected() {
        val p = provider()
        val a = createFirst(p, AuthMode.PIN_ONLY)
        val v = (unlock(p, a.id) as UnlockResult.Success).session.use { it.pinVerification() }
        val b = PendingMaterial.generate(12).use { m -> p.seal(p.beginSealExisting(), m, m.discover(1)[0], "b", WalletRole.VAULT, verification = v) }
        val fa = File(tmp.root, "envelopes/${a.id}.lwle"); val fb = File(tmp.root, "envelopes/${b.id}.lwle")
        fa.writeBytes(fb.readBytes())
        assertEquals(CustodyException.Reason.CORRUPTED, reason { p.beginUnlock(a.id) })
    }

    @Test fun corruptedRegistryFailsClosed() {
        val p = provider()
        createFirst(p)
        val r = File(tmp.root, "registry.lwreg")
        r.writeBytes(r.readBytes().also { it[20] = (it[20] + 1).toByte() })
        assertEquals(CustodyException.Reason.CORRUPTED, reason { p.wallets() })
        r.writeText("LWREG 9\n")
        assertEquals(CustodyException.Reason.CORRUPTED, reason { p.wallets() })
    }

    @Test fun crashBetweenEnvelopeAndRegistryLeavesNoDanglingWallet() {
        val crashing = LocalSecureProvider(tmp.root, kek, clock, atomic = com.lockwallet.custody.local.storage.AtomicFiles {
            if (it == com.lockwallet.custody.local.storage.WriteStep.READ_BACK_VALIDATED && File(tmp.root, "envelopes").listFiles()?.any { f -> f.name.endsWith(".lwle") } == true) throw IllegalStateException("crash")
        })
        PendingMaterial.generate(12).use { m ->
            val t = crashing.beginNewVault(AuthMode.PIN_ONLY)
            assertTrue(runCatching { crashing.seal(t, m, m.discover(1)[0], "a", WalletRole.VAULT, newPin = pin.toCharArray()) }.isFailure)
        }
        val p = provider()
        assertTrue(p.wallets().isEmpty())
        assertFalse(p.hasVault())
    }

    @Test fun lwrpExportVerifyImportRoundTrip() {
        val p = provider()
        val w = createFirst(p)
        val password = "lunar orbit candle mosaic velvet harbor".toCharArray()
        val file = (unlock(p, w.id) as UnlockResult.Success).session.use { it.exportLwrp(password) }
        assertEquals(126, file.size)
        assertTrue(p.verifyRecoveryPackage(file, password, w.address))
        assertFalse(p.verifyRecoveryPackage(file, "lunar orbit candle mosaic velvet harbour".toCharArray(), w.address))
        p.openRecoveryPackage(file, password).use { m ->
            assertEquals(w.address, m.suggested!!.address)
            assertEquals(spec, m.words().joinToString(" ") { String(it) })
        }
        assertEquals(RecoveryException.Reason.OPEN_FAILED,
            (runCatching { p.openRecoveryPackage(file.copyOf().also { it[60] = (it[60] + 1).toByte() }, password) }.exceptionOrNull() as RecoveryException).reason)
        assertEquals(RecoveryException.Reason.NOT_A_PACKAGE,
            (runCatching { p.openRecoveryPackage(file + 1, password) }.exceptionOrNull() as RecoveryException).reason)
        assertEquals(RecoveryException.Reason.PASSWORD_TOO_SHORT,
            (runCatching { p.openRecoveryPackage(file, "short".toCharArray()) }.exceptionOrNull() as RecoveryException).reason)
    }

    @Test fun keypairWalletAndRename() {
        val p = provider()
        val enc = Fixtures.json("lwrp-v1-encrypted.json").getAsJsonArray("cases").map { it.asJsonObject }.first { it["kind"].asInt == 2 }
        val json = enc["material_hex"].asString.hex().joinToString(",", "[", "]") { (it.toInt() and 0xff).toString() }
        val w = PendingMaterial.fromKeypairJson(json.toCharArray()).use { m ->
            p.seal(p.beginNewVault(AuthMode.PIN_ONLY), m, m.discover()[0], "Importada", WalletRole.VAULT, newPin = pin.toCharArray())
        }
        assertFalse(w.isMnemonic)
        assertEquals(AccountProfile.NONE, w.profile)
        p.rename(w.id, "  Nova  ")
        assertEquals("Nova", p.wallets().single().name)
        assertEquals(CustodyException.Reason.BAD_NAME, reason { p.rename(w.id, "") })
        (unlock(p, w.id) as UnlockResult.Success).session.use { s -> assertEquals(174, s.exportLwrp("another long password".toCharArray()).size) }
    }

    /** A-57 (device edge case): storage refuses writes (full / read-only). The attempt can't be counted ⇒ no PIN check. */
    @Test fun unwritableAttemptCounterRefusesUnlockAndKeepsTheVault() {
        val p = provider(); val w = createFirst(p, AuthMode.PIN_ONLY)
        val blocker = File(tmp.root, "attempts.bin" + com.lockwallet.custody.local.storage.AtomicFiles.TMP_SUFFIX)
        assertTrue(blocker.mkdir()) // the temp file can't be created: every write fails like ENOSPC
        for (guess in listOf(pin, "111111", "222222", "333333", "444444", "555555", "666666")) {
            assertTrue(guess, runCatching { unlock(p, w.id, guess) }.isFailure)
        }
        assertTrue(blocker.delete())
        assertTrue(unlock(p, w.id) is UnlockResult.Success) // nothing was wiped, and no attempts were burnt for free
    }
}
