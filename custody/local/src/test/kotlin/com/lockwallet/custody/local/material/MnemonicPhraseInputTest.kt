package com.lockwallet.custody.local.material

import com.lockwallet.core.crypto.Bip39Wordlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MnemonicPhraseInputTest {
    @Test fun normalizesCaseAndUnicodeWhitespaceButPreservesInvalidCharacters() {
        assertEquals("neither lonely fla", MnemonicPhraseInput.normalize(" Neither\u2009LONELY\tFLA"))
        assertEquals("neither lonely ", MnemonicPhraseInput.normalize("neither   LONELY "))
        assertEquals("viewé", MnemonicPhraseInput.normalize("VIEWé"))
        assertTrue(MnemonicPhraseInput.suggestions("viewé").isEmpty())
    }

    @Test fun suggestionsComeFromThePinnedEnglishListAndNeedThreeLetters() {
        assertTrue(MnemonicPhraseInput.suggestions("neither lonely fl").isEmpty())
        val suggestions = MnemonicPhraseInput.suggestions("neither lonely fla")
        assertTrue(suggestions.contains("flavor"))
        assertTrue(suggestions.all { it in Bip39Wordlist.words && it.startsWith("fla") })
        assertTrue(MnemonicPhraseInput.suggestions("neither lonely flav").contains("flavor"))
        assertTrue(MnemonicPhraseInput.suggestions("neither lonely flavor ").isEmpty())
    }

    @Test fun fourLetterPrefixesUniquelyIdentifyEveryEnglishWord() {
        Bip39Wordlist.words.filter { it.length >= 4 }.forEach { word ->
            assertEquals("four-letter prefix for $word", listOf(word), MnemonicPhraseInput.suggestions(word.take(4)))
        }
    }

    @Test fun selectionReplacesOnlyTheCurrentPartialWordAndAdvances() {
        assertEquals("neither lonely flavor ", MnemonicPhraseInput.complete("neither LONELY fla", "flavor"))
    }

    @Test fun selectionRejectsWordsOutsideTheEnglishListOrPrefix() {
        assertThrows(IllegalArgumentException::class.java) { MnemonicPhraseInput.complete("neither lonely fla", "bonjour") }
        assertThrows(IllegalArgumentException::class.java) { MnemonicPhraseInput.complete("neither lonely fla", "abandon") }
    }

    @Test fun parserAcceptsCaseAndUnicodeWhitespaceWithoutChangingThePhrase() {
        val spaced = " Neither\u2009LONELY\tflavor\nargue grass remind eye tag avocado spot unusual intact "
        PendingMaterial.fromPhrase(spaced.toCharArray()).use { material ->
            assertTrue(material.isMnemonic)
            assertEquals(12, material.wordCount)
        }
    }

    @Test fun defaultSuggestionsIncludeEveryMatchForEveryThreeLetterPrefix() {
        val prefixes = Bip39Wordlist.words.map { it.take(3) }.toSet()
        prefixes.forEach { prefix ->
            val expected = Bip39Wordlist.words.filter { it.startsWith(prefix) }
            val actual = MnemonicPhraseInput.suggestions(prefix)
            assertEquals("all candidates must be shown for prefix $prefix", expected, actual)
            assertTrue("prefix $prefix exceeds safe UI capacity", actual.size <= MnemonicPhraseInput.MAX_SUGGESTIONS)
        }
        assertEquals(13, MnemonicPhraseInput.suggestions("pro").size)
    }

    @Test fun suggestionsCanBeExplicitlyBoundedAndNeverIncludeNonWordlistEntries() {
        assertTrue(MnemonicPhraseInput.suggestions("a").isEmpty())
        val matches = MnemonicPhraseInput.suggestions("pro", limit = 8)
        assertTrue(matches.size <= 8)
        assertFalse(matches.any { it !in Bip39Wordlist.words })
    }
}
