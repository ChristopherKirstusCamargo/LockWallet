package com.lockwallet.custody.local

import com.lockwallet.core.crypto.SecretBytes
import com.lockwallet.custody.local.envelope.EnvelopeAuthException
import com.lockwallet.custody.local.envelope.EnvelopeCrypto
import com.lockwallet.custody.local.envelope.EnvelopeError
import com.lockwallet.custody.local.envelope.EnvelopeFormatException
import com.lockwallet.custody.local.envelope.LocalEnvelope
import com.lockwallet.custody.local.envelope.SealRandomness
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.keystore.KekException
import com.lockwallet.custody.local.pin.Pin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEnvelopeTest {
    private val fx = Fixtures.json("lwle-v1.json")
    private val pos = fx.getAsJsonArray("positive").map { it.asJsonObject }

    private fun kekFor(o: com.google.gson.JsonObject) = FakeKekStore().apply {
        put(o["alias_id"].asString.hex(), o["kek"].asString.hex(), AuthMode.ofId(o["auth"].asInt)!!)
        biometricsPresented = 1000
    }

    private fun open(o: com.google.gson.JsonObject, bytes: ByteArray, pin: String = o["pin"].asString): SecretBytes {
        val env = LocalEnvelope.parse(bytes)
        val kek = kekFor(o)
        val dekPin = EnvelopeCrypto.unwrapHardware(env, kek.decryptor(env.aliasId(), env.hwIv()))
        val pinKey = Pin.encode(pin.toCharArray()).use { EnvelopeCrypto.derivePinKey(it, env.pinSalt(), env.kdfProfile) }
        return pinKey.use { EnvelopeCrypto.openWithPinKey(env, dekPin, it) }
    }

    @Test fun oracleEnvelopesParseReencodeAndOpen() {
        assertEquals(3, pos.size)
        for (o in pos) {
            val bytes = o["envelope_hex"].asString.hex()
            val env = LocalEnvelope.parse(bytes)
            assertArrayEquals(bytes, env.encode())
            assertEquals(o["material"].asString.length / 2 + 236, bytes.size)
            open(o, bytes).use { m -> assertArrayEquals(o["material"].asString.hex(), m.withBytes { it.copyOf() }) }
        }
    }

    @Test fun sealIsByteIdenticalToIndependentImplementation() {
        for (o in pos) {
            val kek = kekFor(o).apply { fixedIv = o["hw_iv"].asString.hex() }
            val pinKey = Pin.encode(o["pin"].asString.toCharArray()).use { EnvelopeCrypto.derivePinKey(it, o["pin_salt"].asString.hex(), LocalEnvelope.KDF_OWASP_V1) }
            assertEquals(o["pin_key"].asString, pinKey.withBytes { it.hex() })
            val env = EnvelopeCrypto.seal(o["vault_id"].asString.hex(), o["material_id"].asString.hex(), o["kind"].asInt, o["profile"].asInt,
                o["account"].asInt, o["pub"].asString.hex(), o["pin_salt"].asString.hex(), o["alias_id"].asString.hex(), o["auth"].asInt,
                SecretBytes.copyOf(o["material"].asString.hex()), pinKey,
                SealRandomness(o["dek"].asString.hex(), o["seed_nonce"].asString.hex(), o["dekpin_nonce"].asString.hex()),
                kek.encryptor(o["alias_id"].asString.hex()), LocalEnvelope.KDF_OWASP_V1)
            assertEquals(o["id"].asString, o["envelope_hex"].asString, env.encode().hex())
        }
    }

    @Test fun structuralNegativesFromOracle() {
        val neg = fx.getAsJsonArray("negative").map { it.asJsonObject }
        assertTrue(neg.size >= 20)
        for (n in neg) {
            val err = (runCatching { LocalEnvelope.parse(n["envelope_hex"].asString.hex()) }.exceptionOrNull() as? EnvelopeFormatException)?.error
            assertEquals(n["id"].asString, EnvelopeError.valueOf(n["expected_error"].asString), err)
        }
    }

    @Test fun newEnvelopeUsesStrongKdfAndLegacyFilesStillParse() {
        val o = pos[0]
        val kek = kekFor(o)
        val salt = o["pin_salt"].asString.hex()
        val pinKey = Pin.encode(o["pin"].asString.toCharArray()).use {
            EnvelopeCrypto.derivePinKey(it, salt, LocalEnvelope.KDF_STRONG_V2)
        }
        val env = SecretBytes.copyOf(o["material"].asString.hex()).use { material ->
            EnvelopeCrypto.seal(
                o["vault_id"].asString.hex(), "ab".repeat(16).hex(), o["kind"].asInt, o["profile"].asInt,
                o["account"].asInt, o["pub"].asString.hex(), salt, o["alias_id"].asString.hex(),
                o["auth"].asInt, material, pinKey,
                SealRandomness(ByteArray(32) { 7 }, ByteArray(12) { 8 }, ByteArray(12) { 9 }),
                kek.encryptor(o["alias_id"].asString.hex()),
            )
        }
        assertEquals(LocalEnvelope.KDF_STRONG_V2, env.kdfProfile)
        val encoded = env.encode()
        assertEquals(LocalEnvelope.KDF_STRONG_V2, LocalEnvelope.parse(encoded).kdfProfile)
        val parsed = LocalEnvelope.parse(encoded)
        val dekPin = EnvelopeCrypto.unwrapHardware(parsed, kek.decryptor(parsed.aliasId(), parsed.hwIv()))
        EnvelopeCrypto.openWithPinKey(parsed, dekPin, pinKey).use { opened ->
            assertArrayEquals(o["material"].asString.hex(), opened.withBytes { it.copyOf() })
        }
        for (legacy in pos) assertEquals(LocalEnvelope.KDF_OWASP_V1, LocalEnvelope.parse(legacy["envelope_hex"].asString.hex()).kdfProfile)
    }

    @Test fun legacyEnvelopeMigrationReencryptsBothInnerLayersWithStrongKdf() {
        val o = pos[0]
        val old = LocalEnvelope.parse(o["envelope_hex"].asString.hex())
        val kek = kekFor(o)
        val oldDekPin = EnvelopeCrypto.unwrapHardware(old, kek.decryptor(old.aliasId(), old.hwIv()))
        val oldPinKey = Pin.encode(o["pin"].asString.toCharArray()).use {
            EnvelopeCrypto.derivePinKey(it, old.pinSalt(), old.kdfProfile)
        }
        val oldDek = EnvelopeCrypto.unwrapDek(old, oldDekPin, oldPinKey)
        val material = EnvelopeCrypto.openWithPinKey(old, oldDekPin, oldPinKey)
        val strongPinKey = Pin.encode(o["pin"].asString.toCharArray()).use {
            EnvelopeCrypto.derivePinKey(it, old.pinSalt(), LocalEnvelope.KDF_STRONG_V2)
        }

        try {
            val migrated = EnvelopeCrypto.migrateToStrong(
                old, oldDek, material, strongPinKey, kek.encryptor(old.aliasId()), java.security.SecureRandom(),
            )
            val parsed = LocalEnvelope.parse(migrated.encode())
            assertEquals(LocalEnvelope.KDF_STRONG_V2, parsed.kdfProfile)
            assertEquals(old.vaultId().toList(), parsed.vaultId().toList())
            assertEquals(old.materialId().toList(), parsed.materialId().toList())
            assertEquals(old.expectedPubkey().toList(), parsed.expectedPubkey().toList())
            assertEquals(old.authMode, parsed.authMode)
            val newDekPin = EnvelopeCrypto.unwrapHardware(parsed, kek.decryptor(parsed.aliasId(), parsed.hwIv()))
            try {
                EnvelopeCrypto.openWithPinKey(parsed, newDekPin, strongPinKey).use { opened ->
                    assertArrayEquals(o["material"].asString.hex(), opened.withBytes { it.copyOf() })
                    assertTrue(EnvelopeCrypto.pubkeyMatches(parsed, opened))
                }
            } finally { newDekPin.fill(0) }
        } finally {
            oldDekPin.fill(0)
            oldDek.close()
            oldPinKey.close()
            strongPinKey.close()
            material.close()
        }
    }

    @Test fun wrongPinFails() {
        val o = pos[0]
        assertTrue(runCatching { open(o, o["envelope_hex"].asString.hex(), "480918") }.exceptionOrNull() is EnvelopeAuthException)
    }

    /** Every single-bit flip in any byte is rejected: structurally, by the KEK tag, or by the PIN/seed layers. */
    @Test fun everyByteFlipIsRejected() {
        val o = pos[1] // PIN-only, 24 words
        val good = o["envelope_hex"].asString.hex()
        val pinKey = Pin.encode(o["pin"].asString.toCharArray()).use { EnvelopeCrypto.derivePinKey(it, o["pin_salt"].asString.hex()) }
        val kek = kekFor(o)
        for (i in good.indices) {
            val bad = good.copyOf(); bad[i] = (bad[i].toInt() xor 0x01).toByte()
            val ok = runCatching {
                val env = LocalEnvelope.parse(bad)
                val dp = EnvelopeCrypto.unwrapHardware(env, kek.decryptor(env.aliasId(), env.hwIv()))
                // pin_salt is in the header (bound by every AAD); reuse the good pin_key to keep the test fast.
                EnvelopeCrypto.openWithPinKey(env, dp, pinKey).close()
            }
            val e = ok.exceptionOrNull()
            assertTrue("byte $i accepted", e is EnvelopeFormatException || e is KekException || e is EnvelopeAuthException)
        }
        pinKey.close()
    }

    @Test fun envelopesOfTwoVaultsCannotBeMixed() {
        val a = LocalEnvelope.parse(pos[0]["envelope_hex"].asString.hex())
        val b = LocalEnvelope.parse(pos[2]["envelope_hex"].asString.hex())
        // Graft b's hardware blob onto a's header (same KEK on purpose): the AAD binding must reject it.
        val kek = kekFor(pos[0])
        val mixed = LocalEnvelope(a.vaultId(), a.materialId(), a.materialKind, a.derivationProfile, a.accountIndex, a.expectedPubkey(),
            a.kdfProfile, a.pinSalt(), a.aliasId(), a.authMode, a.materialLength, a.seedNonce(), a.seedCt(), a.dekPinNonce(), b.hwIv(), b.hwCt())
        assertFalse(runCatching { EnvelopeCrypto.unwrapHardware(mixed, kek.decryptor(mixed.aliasId(), mixed.hwIv())) }.isSuccess)
    }
}
