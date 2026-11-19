package com.lockwallet.core.crypto

import com.lockwallet.core.crypto.lwrp.LwrpError
import com.lockwallet.core.crypto.lwrp.LwrpFormatException
import com.lockwallet.core.crypto.lwrp.LwrpHeader
import com.lockwallet.core.crypto.lwrp.LwrpKdfProfile
import com.lockwallet.core.crypto.lwrp.LwrpPayload
import com.lockwallet.core.crypto.lwrp.MaterialKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * LWRP v1 structure: Kotlin encoder/parser vs the independent Python `struct` implementation
 * (fixture lwrp-v1-structure.json) — byte-for-byte on positives, exact error code on negatives.
 */
class LwrpStructureTest {
    private val fx = Fixtures.json("lwrp-v1-structure.json")

    @Test fun exactValidFileSizes() {
        assertEquals(setOf(126, 130, 134, 138, 142, 174), LwrpHeader.VALID_FILE_SIZES)
        assertEquals(174, LwrpHeader.MAX_FILE_SIZE)
    }

    @Test fun headerEncodingMatchesOracle() {
        val h = LwrpHeader(ByteArray(16) { it.toByte() }, LwrpKdfProfile.COMPAT_V1, ByteArray(12) { (100 + it).toByte() })
        assertArrayEquals(fx["header_hex_compat_v1"].asString.hex(), h.encode())
    }

    @Test fun filesPositiveAndNegative() {
        val files = fx.getAsJsonArray("files")
        files.forEach { f ->
            val o = f.asJsonObject
            val bytes = o["file_hex"].asString.hex()
            if (o["expected_error"].isJsonNull) {
                val h = LwrpHeader.parseFile(bytes)
                assertEquals(LwrpKdfProfile.COMPAT_V1, h.kdf)
                assertArrayEquals(bytes.copyOf(46), h.encode())
            } else {
                val e = assertThrows(o["id"].asString, LwrpFormatException::class.java) { LwrpHeader.parseFile(bytes) }
                assertEquals(o["id"].asString, o["expected_error"].asString, e.error.name)
            }
        }
    }

    @Test fun payloadPositivesRoundTripAndVerify() {
        val pos = fx.getAsJsonArray("payloads_positive")
        assertEquals(11, pos.size())
        pos.forEach { p ->
            val o = p.asJsonObject
            val expected = o["payload_hex"].asString.hex()
            val material = o["material_hex"].asString.hex()
            val pub = o["pubkey_hex"].asString.hex()
            val built = when (o["kind"].asInt) {
                1 -> LwrpPayload.bip39(material, DerivationProfile.ofId(o["profile"].asInt)!!, o["account_index"].asInt, pub)
                else -> LwrpPayload.keypair(material, pub)
            }
            built.use { assertArrayEquals(o["id"].asString, expected, it.encode()) }
            LwrpPayload.parse(expected).use { parsed ->
                assertEquals(if (o["kind"].asInt == 1) MaterialKind.BIP39_ENTROPY else MaterialKind.IMPORTED_ED25519_KEYPAIR, parsed.kind)
                assertEquals(o["account_index"].asInt, parsed.accountIndex)
                parsed.verifyPublicKey()
                assertArrayEquals(expected, parsed.encode())
            }
        }
    }

    @Test fun payloadNegatives() {
        fx.getAsJsonArray("payloads_negative").forEach { n ->
            val o = n.asJsonObject
            val bytes = o["payload_hex"].asString.hex()
            val e = assertThrows(o["id"].asString, LwrpFormatException::class.java) {
                LwrpPayload.parse(bytes).use { if (o["stage"].asString == "verify") it.verifyPublicKey() else error("parsed") }
            }
            assertEquals(o["id"].asString, o["expected_error"].asString, e.error.name)
        }
    }

    @Test fun errorTextNeverCarriesBytes() {
        val e = LwrpFormatException(LwrpError.PUBKEY_MISMATCH)
        assertEquals("LWRP rejected: PUBKEY_MISMATCH", e.message)
    }
}
