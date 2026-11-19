package com.lockwallet.core.crypto

import com.lockwallet.core.crypto.lwrp.LwrpCipher
import com.lockwallet.core.crypto.lwrp.LwrpError
import com.lockwallet.core.crypto.lwrp.LwrpFormatException
import com.lockwallet.core.crypto.lwrp.LwrpOpenException
import com.lockwallet.core.crypto.lwrp.LwrpPasswordError
import com.lockwallet.core.crypto.lwrp.LwrpPasswordException
import com.lockwallet.core.crypto.lwrp.LwrpPayload
import com.lockwallet.core.crypto.lwrp.MaterialKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class LwrpCipherTest {
    private val cases = Fixtures.json("lwrp-v1-encrypted.json").getAsJsonArray("cases").map { it.asJsonObject }

    private fun payloadOf(o: com.google.gson.JsonObject): LwrpPayload {
        val mat = o["material_hex"].asString.hex(); val pub = o["pubkey_hex"].asString.hex()
        return if (o["kind"].asInt == 1) LwrpPayload.bip39(mat, DerivationProfile.ofId(o["profile"].asInt)!!, o["account_index"].asInt, pub)
        else LwrpPayload.keypair(mat, pub)
    }

    @Test fun opensOracleFilesAndSealsByteIdentical() {
        assertEquals(3, cases.size)
        for (o in cases) {
            val file = o["file_hex"].asString.hex()
            LwrpCipher.open(file, o["password"].asString.toCharArray()).use { p ->
                assertEquals(if (o["kind"].asInt == 1) MaterialKind.BIP39_ENTROPY else MaterialKind.IMPORTED_ED25519_KEYPAIR, p.kind)
                assertEquals(o["account_index"].asInt, p.accountIndex)
                assertArrayEquals(o["pubkey_hex"].asString.hex(), p.expectedPubkey())
                assertArrayEquals(o["material_hex"].asString.hex(), p.material.withBytes { it.copyOf() })
            }
            payloadOf(o).use { p ->
                val sealed = LwrpCipher.sealWith(p, o["password"].asString.toCharArray(), o["salt_hex"].asString.hex(), o["nonce_hex"].asString.hex())
                assertArrayEquals(o["id"].asString, file, sealed)
            }
        }
    }

    @Test fun nfdPasswordIsNormalisedToNfc() {
        val o = cases.first { it["id"].asString == "LWRP-ENC-24W-P2-3" }
        val nfd = o["password"].asString
        val nfc = java.text.Normalizer.normalize(nfd, java.text.Normalizer.Form.NFC)
        assertTrue(nfd != nfc)
        LwrpCipher.open(o["file_hex"].asString.hex(), nfc.toCharArray()).close()
        LwrpCipher.open(o["file_hex"].asString.hex(), nfd.toCharArray()).close()
    }

    @Test fun wrongPasswordAndTamperingAreTheSameGenericFailure() {
        val o = cases[0]
        val file = o["file_hex"].asString.hex()
        val pw = o["password"].asString.toCharArray()
        assertTrue(runCatching { LwrpCipher.open(file, "lunar orbit candle mosaic velvet harbor tiger sketcH".toCharArray()) }.exceptionOrNull() is LwrpOpenException)
        // Flip one bit in the salt, the nonce, the ciphertext and the tag: all generic open failures.
        for (i in listOf(6, 21, 34, 45, 46, 80, file.size - 1)) {
            val bad = file.copyOf(); bad[i] = (bad[i].toInt() xor 1).toByte()
            assertTrue("byte $i", runCatching { LwrpCipher.open(bad, pw) }.exceptionOrNull() is LwrpOpenException)
        }
        // Header fields outside the allowlist are refused structurally, before any KDF.
        val badKdf = file.copyOf(); badKdf[25] = (badKdf[25] + 1).toByte()
        assertEquals(LwrpError.KDF_PROFILE_NOT_ALLOWED, (runCatching { LwrpCipher.open(badKdf, pw) }.exceptionOrNull() as LwrpFormatException).error)
        assertEquals(LwrpError.BAD_SIZE, (runCatching { LwrpCipher.open(file + 0, pw) }.exceptionOrNull() as LwrpFormatException).error)
    }

    @Test fun passwordRules() {
        fun err(s: CharArray) = (runCatching { LwrpCipher.encodePassword(s) }.exceptionOrNull() as LwrpPasswordException).error
        assertEquals(LwrpPasswordError.TOO_SHORT, err("fifteen chars!!".toCharArray()))
        LwrpCipher.encodePassword("sixteen chars!!!".toCharArray()).close()
        assertEquals(LwrpPasswordError.TOO_LONG, err(CharArray(257) { 'a' }))
        assertEquals(LwrpPasswordError.INVALID_UNICODE, err(("a".repeat(20) + "\ud800").toCharArray()))
        // 16 bytes, fewer chars: limits are on UTF-8 bytes.
        assertEquals(16, LwrpCipher.encodePassword("ççççççç12".toCharArray()).use { it.size })
    }

    @Test fun generatedPasswordHas8WordsAndRoundTrips() {
        val pw = LwrpCipher.generatePassword(SecureRandom())
        val words = String(pw).split(' ')
        assertEquals(8, words.size)
        assertTrue(words.all { it in Bip39Wordlist.words })
        payloadOf(cases[2]).use { p ->
            val f = LwrpCipher.seal(p, pw)
            assertEquals(174, f.size)
            LwrpCipher.open(f, pw).use { assertArrayEquals(cases[2]["pubkey_hex"].asString.hex(), it.expectedPubkey()) }
        }
    }

    @Test fun sealRefusesInconsistentPayload() {
        val o = cases[0]
        LwrpPayload.bip39(o["material_hex"].asString.hex(), DerivationProfile.PRIMARY, 1, o["pubkey_hex"].asString.hex()).use { p ->
            assertEquals(LwrpError.PUBKEY_MISMATCH, (runCatching { LwrpCipher.seal(p, "a long enough password".toCharArray()) }.exceptionOrNull() as LwrpFormatException).error)
        }
    }
}
