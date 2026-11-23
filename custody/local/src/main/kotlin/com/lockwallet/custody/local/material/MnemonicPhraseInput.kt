package com.lockwallet.custody.local.material

import com.lockwallet.core.crypto.Bip39Wordlist

/**
 * UI-safe helpers for the English BIP-39 recovery field.
 *
 * Suggestions come only from the pinned 2,048-word list. They are rendered by
 * Lock Wallet; the secret-entry keyboard remains in password mode and receives
 * no external autocomplete data.
 */
object MnemonicPhraseInput {
    const val MIN_PREFIX_LENGTH = 3
    const val MAX_SUGGESTIONS = 16

    /**
     * Lowercases ASCII, collapses Unicode whitespace and preserves one trailing
     * space to indicate that the next word has not started. Invalid letters are
     * deliberately preserved for the validator to reject; this is not a spell-corrector.
     */
    fun normalize(input: String): String {
        val out = StringBuilder(input.length)
        var hasContent = false
        var pendingSpace = false
        for (c in input) {
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (hasContent) pendingSpace = true
            } else {
                if (pendingSpace) {
                    out.append(' ')
                    pendingSpace = false
                }
                out.append(if (c in 'A'..'Z') c.lowercaseChar() else c)
                hasContent = true
            }
        }
        if (pendingSpace && hasContent) out.append(' ')
        return out.toString()
    }

    /** Suggestions for the unfinished last word; fewer than three letters never trigger a lookup. */
    fun suggestions(input: String, limit: Int = MAX_SUGGESTIONS): List<String> {
        require(limit in 1..MAX_SUGGESTIONS) { "invalid suggestion limit" }
        val normalized = normalize(input)
        if (normalized.isEmpty() || normalized.last() == ' ') return emptyList()

        val prefix = normalized.substring(normalized.lastIndexOf(' ') + 1)
        if (prefix.length < MIN_PREFIX_LENGTH || prefix.any { it !in 'a'..'z' }) return emptyList()

        return Bip39Wordlist.words.asSequence()
            .filter { it.startsWith(prefix) }
            .take(limit)
            .toList()
    }

    /** Completes only the current partial word and verifies the selected word and prefix. */
    fun complete(input: String, word: String): String {
        require(Bip39Wordlist.words.binarySearch(word) >= 0) { "word is not in the English BIP-39 list" }
        val normalized = normalize(input)
        require(normalized.isNotEmpty() && normalized.last() != ' ') { "no unfinished word" }
        val boundary = normalized.lastIndexOf(' ') + 1
        val prefix = normalized.substring(boundary)
        require(word.startsWith(prefix)) { "suggestion does not match the current prefix" }
        return normalized.substring(0, boundary) + word + " "
    }
}
