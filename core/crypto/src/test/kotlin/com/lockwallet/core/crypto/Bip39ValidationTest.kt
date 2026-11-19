package com.lockwallet.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class Bip39ValidationTest {
    private val valid12 = "neither lonely flavor argue grass remind eye tag avocado spot unusual intact"

    private fun reason(m: String) =
        assertThrows(Bip39Exception::class.java) { Bip39.mnemonicToEntropy(m.toCharArray()) }.reason

    @Test fun wordlistIsPinned() {
        assertEquals(2048, Bip39Wordlist.words.size)
        assertEquals("abandon", Bip39Wordlist.words.first())
        assertEquals("zoo", Bip39Wordlist.words.last())
    }

    @Test fun rejectsNonCanonicalInputInsteadOfNormalising() {
        assertEquals(Bip39Exception.Reason.NOT_CANONICAL, reason(" $valid12"))
        assertEquals(Bip39Exception.Reason.NOT_CANONICAL, reason("$valid12 "))
        assertEquals(Bip39Exception.Reason.NOT_CANONICAL, reason(valid12.replace(" ", "  ")))
        assertEquals(Bip39Exception.Reason.NOT_CANONICAL, reason(valid12.uppercase()))
        assertEquals(Bip39Exception.Reason.NOT_CANONICAL, reason(valid12.replace(' ', '\u3000')))
        assertEquals(Bip39Exception.Reason.NOT_CANONICAL, reason(valid12.replace("eye", "eye\u0301")))
        assertEquals(Bip39Exception.Reason.NOT_CANONICAL, reason(""))
    }

    @Test fun rejectsBadWordCountsUnknownWordsAndChecksum() {
        assertEquals(Bip39Exception.Reason.INVALID_WORD_COUNT, reason(valid12.substringBeforeLast(' ')))
        assertEquals(Bip39Exception.Reason.INVALID_WORD_COUNT, reason("$valid12 zoo"))
        val unknown = assertThrows(Bip39Exception::class.java) {
            Bip39.mnemonicToEntropy(valid12.replace("grass", "grasss").toCharArray())
        }
        assertEquals(Bip39Exception.Reason.UNKNOWN_WORD, unknown.reason)
        assertEquals(4, unknown.wordPosition)
        assertEquals(Bip39Exception.Reason.INVALID_CHECKSUM, reason(valid12.replace("intact", "abandon")))
    }

    @Test fun exceptionMessagesNeverContainWords() {
        val inputs = listOf(valid12.replace("intact", "abandon"), valid12.replace("grass", "grasss"), "$valid12 ")
        inputs.forEach { m ->
            val e = assertThrows(Bip39Exception::class.java) { Bip39.mnemonicToEntropy(m.toCharArray()) }
            val msg = e.message.orEmpty() + e.toString()
            m.split(' ').filter { it.length >= 3 }.forEach { w -> assertFalse("leaked '$w' in $msg", msg.contains(w)) }
        }
    }

    @Test fun seedRequiresValidChecksum() {
        assertThrows(Bip39Exception::class.java) { Bip39.mnemonicToSeed(valid12.replace("intact", "abandon").toCharArray()) }
    }

    @Test fun generatedEntropyRoundTripsForAllSizes() {
        val rng = SecureRandom()
        MnemonicLength.entries.forEach { len ->
            Bip39.generateEntropy(len, rng).use { e ->
                assertEquals(len.entropyBytes, e.size)
                val m = e.withBytes { Bip39.entropyToMnemonic(it) }
                assertEquals(len.words, String(m).split(' ').size)
                Bip39.mnemonicToEntropy(m).use { back -> assertTrue(back.withBytes { b -> e.withBytes { it.contentEquals(b) } }) }
                m.wipe()
            }
        }
    }

    @Test fun rejectsInvalidEntropyLengths() {
        listOf(0, 15, 17, 31, 33, 64).forEach { n ->
            val e = assertThrows(Bip39Exception::class.java) { Bip39.entropyToMnemonic(ByteArray(n)) }
            assertEquals(Bip39Exception.Reason.INVALID_ENTROPY_LENGTH, e.reason)
        }
    }

    @Test fun creationOffers12Or24WordsAndRestoreAcceptsAllFive() {
        assertEquals(listOf(MnemonicLength.WORDS_12, MnemonicLength.WORDS_24), MnemonicLength.CREATION_CHOICES)
        assertEquals(MnemonicLength.WORDS_12, MnemonicLength.DEFAULT_CREATION)
        assertEquals(listOf(12, 15, 18, 21, 24), MnemonicLength.entries.map { it.words })
    }

    @Test fun defaultGenerationIs128Bits() {
        Bip39.generateEntropy().use { assertEquals(16, it.size) }
        Bip39.generateEntropy().use { a -> Bip39.generateEntropy().use { b ->
            assertNotEquals(a.withBytes { it.toList() }, b.withBytes { it.toList() })
        } }
    }

    /** v3 seed re-verification: a broken RNG (constant output) must never produce a wallet. */
    @Test fun constantRngFailsClosed() {
        val zeros = object : SecureRandom() { override fun nextBytes(bytes: ByteArray) { bytes.fill(0) } }
        val sevens = object : SecureRandom() { override fun nextBytes(bytes: ByteArray) { bytes.fill(7) } }
        for (rng in listOf(zeros, sevens)) for (len in MnemonicLength.entries)
            assertThrows(IllegalStateException::class.java) { Bip39.generateEntropy(len, rng) }
    }

    /** 12 words ⇔ 128 bits + 4-bit checksum; 24 words ⇔ 256 + 8: every generated phrase passes the strict checksum. */
    @Test fun thousandGeneratedPhrasesAreValidAndDistinct() {
        val rng = SecureRandom(); val seen = HashSet<String>()
        repeat(500) {
            for (len in MnemonicLength.CREATION_CHOICES) Bip39.generateEntropy(len, rng).use { e ->
                val m = e.withBytes { Bip39.entropyToMnemonic(it) }
                Bip39.mnemonicToEntropy(m).close()   // strict checksum validation
                assertTrue(seen.add(String(m)))
                m.wipe()
            }
        }
    }
}
