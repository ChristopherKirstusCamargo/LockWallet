package com.lockwallet.core.crypto

import com.lockwallet.core.crypto.lwrp.LwrpCipher
import com.lockwallet.core.crypto.lwrp.LwrpFormatException
import com.lockwallet.core.crypto.lwrp.LwrpHeader
import com.lockwallet.core.crypto.lwrp.LwrpOpenException
import com.lockwallet.core.crypto.lwrp.LwrpPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Phase I: LWRP parsers under random and mutated input (only documented exceptions; tampering never opens). */
class LwrpFuzzTest {
    private val rnd = Random(0x1A2B)
    private val cases = Fixtures.json("lwrp-v1-encrypted.json").getAsJsonArray("cases").map { it.asJsonObject }

    @Test fun headerParserOnlyThrowsFormatErrors() {
        repeat(5_000) {
            val size = if (rnd.nextBoolean()) LwrpHeader.MAX_FILE_SIZE - rnd.nextInt(0, 40) else rnd.nextInt(0, 400)
            try { LwrpHeader.parseFile(rnd.nextBytes(size)) } catch (_: LwrpFormatException) { }
        }
        for (o in cases) {
            val f = o["file_hex"].asString.hex()
            repeat(2_000) { try { LwrpHeader.parseFile(mutate(f)) } catch (_: LwrpFormatException) { } }
        }
    }

    @Test fun payloadParserOnlyThrowsFormatErrors() {
        for (o in cases) {
            LwrpCipher.open(o["file_hex"].asString.hex(), o["password"].asString.toCharArray()).use { p ->
                val plain = p.encode()
                LwrpPayload.parse(plain).use { q -> assertArrayEquals(plain, q.encode()) }
                repeat(3_000) {
                    try { LwrpPayload.parse(mutate(plain)).close() } catch (_: LwrpFormatException) { }
                }
            }
        }
        repeat(3_000) { try { LwrpPayload.parse(rnd.nextBytes(rnd.nextInt(0, 200))).close() } catch (_: LwrpFormatException) { } }
    }

    @Test fun tamperedFilesNeverOpen() {
        val o = cases[0]
        val f = o["file_hex"].asString.hex()
        repeat(6) { i ->
            val x = f.copyOf().also { val p = 46 + rnd.nextInt(it.size - 46); it[p] = (it[p].toInt() xor (1 shl (i % 8))).toByte() }
            val ok = try { LwrpCipher.open(x, o["password"].asString.toCharArray()).close(); true } catch (_: LwrpOpenException) { false } catch (_: LwrpFormatException) { false }
            assertTrue("tampered ciphertext must not open", !ok)
        }
    }

    private fun mutate(src: ByteArray): ByteArray {
        val b = src.copyOf()
        return when (rnd.nextInt(4)) {
            0 -> b.also { val i = rnd.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
            1 -> b.also { repeat(rnd.nextInt(1, 5)) { _ -> val i = rnd.nextInt(it.size); it[i] = rnd.nextInt(256).toByte() } }
            2 -> b.copyOf(rnd.nextInt(0, b.size))
            else -> b + rnd.nextBytes(rnd.nextInt(1, 8))
        }
    }
}
