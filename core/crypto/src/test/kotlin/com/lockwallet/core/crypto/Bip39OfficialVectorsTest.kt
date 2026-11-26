package com.lockwallet.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Official BIP-39 vectors (Trezor python-mnemonic vectors.json, english, passphrase "TREZOR"):
 * entropy -> mnemonic -> entropy -> seed, checked against the file, and the seed additionally against
 * a second in-process implementation (JDK SunJCE PBKDF2WithHmacSHA512).
 */
class Bip39OfficialVectorsTest {
    private val vectors = Fixtures.json("bip39-trezor-vectors.json").getAsJsonArray("english")

    @Test fun allTwentyFourEnglishVectors() {
        assertEquals(24, vectors.size())
        vectors.forEach { v ->
            val a = v.asJsonArray
            val entropy = a[0].asString.hex()
            val mnemonic = a[1].asString
            val seed = a[2].asString.hex()

            val m = Bip39.entropyToMnemonic(entropy)
            assertEquals(mnemonic, String(m))
            Bip39.mnemonicToEntropy(m).use { e -> e.withBytes { assertArrayEquals(entropy, it) } }
            Bip39.mnemonicToSeedWithPassphrase(m, "TREZOR".toCharArray()).use { s ->
                s.withBytes { assertArrayEquals("seed for $mnemonic", seed, it) }
            }
            assertArrayEquals(seed, jdkSeed(mnemonic, "TREZOR"))
        }
    }

    private fun jdkSeed(mnemonic: String, passphrase: String): ByteArray {
        val spec = PBEKeySpec(mnemonic.toCharArray(), ("mnemonic$passphrase").toByteArray(), 2048, 512)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512", "SunJCE").generateSecret(spec).encoded
    }
}
