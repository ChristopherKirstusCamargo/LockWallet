package com.lockwallet.custody.local

import com.lockwallet.custody.local.envelope.EnvelopeFormatException
import com.lockwallet.custody.local.envelope.LocalEnvelope
import com.lockwallet.custody.local.pin.PinAttemptLimiter
import com.lockwallet.custody.local.storage.AtomicFiles
import com.lockwallet.custody.local.storage.RegistryFormatException
import com.lockwallet.custody.local.storage.WalletRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Phase I: LWLE envelope, registry and attempt-counter decoders under random/mutated input. */
class EnvelopeFuzzTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = FakeClock()
    private val hmacKey = ByteArray(32) { (it + 7).toByte() }
    private val rnd = Random(0x5EA1)
    private val pos = Fixtures.json("lwle-v1.json").getAsJsonArray("positive").map { it.asJsonObject["envelope_hex"].asString.hex() }

    @Test fun envelopeParserIsStrictAndCanonical() {
        for (e in pos) {
            repeat(3_000) {
                val x = mutate(e)
                try {
                    val env = LocalEnvelope.parse(x)
                    assertArrayEquals("accepted envelopes re-encode byte-identically", x, env.encode())
                } catch (_: EnvelopeFormatException) { }
            }
        }
        repeat(3_000) { try { LocalEnvelope.parse(rnd.nextBytes(rnd.nextInt(0, 600))) } catch (_: EnvelopeFormatException) { } }
    }

    @Test fun registryAndCounterDecodersNeverCrash() {
        repeat(3_000) {
            val b = rnd.nextBytes(rnd.nextInt(0, 400))
            try { WalletRegistry.decode(b) } catch (_: RegistryFormatException) { }
            try { WalletRegistry.decode(("LWREG 1\n" + String(b, Charsets.ISO_8859_1)).toByteArray()) } catch (_: RegistryFormatException) { }
            // Exercise the authenticated decoder via the public state reader; malformed or forged
            // records must be rejected conservatively while a vault exists.
            val primary = File(tmp.root, "attempts.bin")
            val mirror = File(tmp.root, "attempts.mirror")
            primary.writeBytes(b)
            mirror.writeBytes(b)
            val limiter = PinAttemptLimiter(primary, mirror, clock, AtomicFiles(), { true }) { data ->
                Mac.getInstance("HmacSHA256").apply {
                    init(SecretKeySpec(hmacKey, "HmacSHA256"))
                }.doFinal(data)
            }
            limiter.failures()
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
