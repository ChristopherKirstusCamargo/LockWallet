package com.lockwallet.core.crypto

import com.lockwallet.core.crypto.aead.AeadAuthenticationException
import com.lockwallet.core.crypto.aead.AesGcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Argon2idAesGcmTest {
    private fun ByteArray.h() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** RFC 9106 §5.3 Argon2id test vector (with secret and associated data). */
    @Test fun argon2idRfc9106Vector() {
        val tag = Argon2id.deriveRaw(ByteArray(32) { 1 }, ByteArray(16) { 2 }, 3, 32, 4, 32, ByteArray(8) { 3 }, ByteArray(12) { 4 })
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", tag.withBytes { it.h() })
    }

    @Test fun argon2idMatchesLibsodiumOracle() {
        val cases = Fixtures.json("argon2id-v13.json").getAsJsonArray("cases")
        assertEquals(3, cases.size())
        for (c in cases) {
            val o = c.asJsonObject
            assertEquals(KdfProfile.OWASP_V1.memoryKiB, o["m_kib"].asInt)
            assertEquals(KdfProfile.OWASP_V1.iterations, o["t"].asInt)
            val out = Argon2id.derive(o["password_hex"].asString.hex(), o["salt_hex"].asString.hex(), KdfProfile.OWASP_V1)
            assertEquals(o["out_hex"].asString, out.withBytes { it.h() })
        }
    }

    @Test fun aesGcmMatchesPycryptodomeAndJdk() {
        for (c in Fixtures.json("aes-256-gcm.json").getAsJsonArray("cases")) {
            val o = c.asJsonObject
            val key = o["key_hex"].asString.hex(); val nonce = o["nonce_hex"].asString.hex()
            val aad = o["aad_hex"].asString.hex(); val pt = o["pt_hex"].asString.hex(); val ct = o["ct_hex"].asString.hex()
            SecretBytes.copyOf(key).use { k ->
                assertArrayEquals(ct, AesGcm.encrypt(k, nonce, aad, pt))
                assertArrayEquals(pt, AesGcm.decrypt(k, nonce, aad, ct).withBytes { it.copyOf() })
            }
            // Third implementation: the JDK provider.
            val jdk = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad)
            }.doFinal(pt)
            assertArrayEquals(ct, jdk)
        }
    }

    @Test fun aesGcmRejectsEveryBitFlipAndWrongAad() {
        val key = SecretBytes.copyOf(ByteArray(32) { it.toByte() })
        val nonce = ByteArray(12) { 7 }
        val ct = AesGcm.encrypt(key, nonce, byteArrayOf(1, 2), ByteArray(20) { 9 })
        for (i in ct.indices) for (bit in 0 until 8) {
            val bad = ct.copyOf(); bad[i] = (bad[i].toInt() xor (1 shl bit)).toByte()
            assertTrue(runCatching { AesGcm.decrypt(key, nonce, byteArrayOf(1, 2), bad) }.exceptionOrNull() is AeadAuthenticationException)
        }
        assertTrue(runCatching { AesGcm.decrypt(key, nonce, byteArrayOf(1, 3), ct) }.exceptionOrNull() is AeadAuthenticationException)
        assertTrue(runCatching { AesGcm.decrypt(key, nonce, byteArrayOf(1, 2), ct.copyOf(15)) }.exceptionOrNull() is AeadAuthenticationException)
    }

    /** RFC 8032 §7.1 TEST 1, cross-checked with libsodium (PyNaCl). */
    @Test fun ed25519Rfc8032Test1() {
        val sk = SecretBytes.copyOf("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hex())
        val pk = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hex()
        val sig = Ed25519Signer.sign(sk, ByteArray(0))
        assertEquals("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b", sig.h())
        assertTrue(Ed25519Signer.verify(pk, ByteArray(0), sig))
        sig[0] = (sig[0] + 1).toByte()
        assertFalse(Ed25519Signer.verify(pk, ByteArray(0), sig))
        assertFalse(Ed25519Signer.verify(pk, byteArrayOf(1), Ed25519Signer.sign(sk, ByteArray(0))))
    }
}
