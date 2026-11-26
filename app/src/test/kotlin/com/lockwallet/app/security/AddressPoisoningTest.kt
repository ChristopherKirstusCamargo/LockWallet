package com.lockwallet.app.security

import com.lockwallet.app.policy.Lookalike
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Random

private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private fun addr(r: Random) = com.lockwallet.core.solana.SolanaPublicKey.of(ByteArray(32).also { r.nextBytes(it) }).toBase58()
private fun randomMiddle(r: Random, len: Int) = (1..len).map { B58[r.nextInt(B58.length)] }.joinToString("")

/** Vanity "poison" addresses built the way attackers build them must always warn. */
@RunWith(Parameterized::class)
class PoisonedAddressIsFlaggedTest(private val seed: Int, private val prefix: Int, private val suffix: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "seed {0} prefix {1} suffix {2}")
        fun data(): List<Array<Any>> = (1..25).flatMap { s ->
            listOf(3 to 3, 4 to 4, 3 to 4, 4 to 3, 5 to 0, 0 to 5, 6 to 6, 5 to 1, 1 to 5, 8 to 8).map { (p, x) -> arrayOf<Any>(s, p, x) }
        }
    }

    @Test fun flagged() {
        val r = Random(seed * 1_000L + prefix * 10 + suffix)
        val real = addr(r)
        var fake: String
        do { fake = real.take(prefix) + randomMiddle(r, real.length - prefix - suffix) + real.takeLast(suffix) } while (fake == real)
        assertTrue("$fake vs $real", Lookalike.similar(fake, real))
        // also with the case of the matching ends flipped (o/O, l/L…)
        val flipped = fake.take(prefix).map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("") + fake.drop(prefix)
        if (flipped != real) assertTrue(Lookalike.similar(flipped, real))
        assertEquals(real, Lookalike.find(fake, listOf(addr(r), real, addr(r))))
    }
}

/** Unrelated addresses do not warn (false positives would train people to ignore the warning). */
@RunWith(Parameterized::class)
class UnrelatedAddressIsNotFlaggedTest(private val seed: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "seed {0}") fun data(): List<Array<Any>> = (1..150).map { arrayOf<Any>(it) }
    }

    @Test fun notFlagged() {
        val r = Random(seed * 7_777L)
        val dest = addr(r)
        val known = (1..40).map { addr(r) }.filter { k ->
            val p = Lookalike.commonPrefix(dest, k); val s = Lookalike.commonSuffix(dest, k)
            !((p >= 3 && s >= 3) || p >= 5 || s >= 5)
        }
        assertNull(Lookalike.find(dest, known))
        assertFalse(Lookalike.similar(dest, dest)) // the same address is not "similar"
        assertNull(Lookalike.find(dest, listOf(dest)))
    }
}
