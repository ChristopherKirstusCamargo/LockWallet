package com.lockwallet.core.crypto

import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Official SLIP-0010 ed25519 test vectors 1 and 2 (every chain: key, chain code, public, fingerprint). */
class Slip10OfficialVectorsTest {
    @Test fun officialEd25519Vectors() {
        val vectors = Fixtures.json("slip10-ed25519-official.json").getAsJsonArray("vectors")
        assertEquals(2, vectors.size())
        vectors.forEach { v ->
            val o = v.asJsonObject
            val seed = o["seed"].asString.hex()
            var parentPub: ByteArray? = null
            o.getAsJsonArray("chains").forEach { c ->
                val ch = c.asJsonObject
                val path = HardenedPath.parse(ch["path"].asString)
                Slip10Ed25519.deriveNode(seed, path).use { node ->
                    node.key.withBytes { assertArrayEquals(ch["path"].asString, ch["private"].asString.hex(), it) }
                    node.chainCode.withBytes { assertArrayEquals(ch["chain_code"].asString.hex(), it) }
                    val pub = node.key.withBytes { Slip10Ed25519.publicKeyOf(it) }
                    assertArrayEquals(ch["public"].asString.hex(), byteArrayOf(0) + pub) // ser_P = 0x00 || A
                    val expectedFp = ch["fingerprint"].asString.hex()
                    val fp = parentPub?.let { fingerprint(byteArrayOf(0) + it) } ?: ByteArray(4)
                    assertArrayEquals(expectedFp, fp)
                    parentPub = pub
                }
            }
        }
    }

    @Test fun pathParserRefusesNonHardenedAndMalformed() {
        listOf("m/44/501'/0'/0'", "m/44'/501'/0'/0", "44'/501'", "m/-1'", "m/2147483648'", "m/01'", "m/'", "m//0'")
            .forEach { p -> assertThrows(p, IllegalArgumentException::class.java) { HardenedPath.parse(p) } }
        assertEquals("m/44'/501'/0'/0'", HardenedPath.parse("m/44'/501'/0'/0'").toString())
        assertEquals(HardenedPath.parse("m/2147483647'"), HardenedPath(intArrayOf(Int.MAX_VALUE)))
    }

    @Test fun profilesMapToExactPaths() {
        assertEquals("m/44'/501'/0'/0'", DerivationProfile.PRIMARY.path(0).toString())
        assertEquals("m/44'/501'/7'/0'", DerivationProfile.PRIMARY.path(7).toString())
        assertEquals("m/44'/501'/0'", DerivationProfile.LEGACY_LEDGER.path(0).toString())
        assertThrows(IllegalArgumentException::class.java) { DerivationProfile.PRIMARY.path(-1) }
        assertEquals(DerivationProfile.PRIMARY, DerivationProfile.ofId(1))
        assertEquals(DerivationProfile.LEGACY_LEDGER, DerivationProfile.ofId(2))
        assertEquals(null, DerivationProfile.ofId(0))
    }

    private fun fingerprint(serPub: ByteArray): ByteArray {
        val s = SHA256Digest(); s.update(serPub, 0, serPub.size); val h = ByteArray(32); s.doFinal(h, 0)
        val r = RIPEMD160Digest(); r.update(h, 0, 32); val out = ByteArray(20); r.doFinal(out, 0)
        return out.copyOf(4)
    }
}
