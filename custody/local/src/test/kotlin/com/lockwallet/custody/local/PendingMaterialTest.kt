package com.lockwallet.custody.local

import com.lockwallet.core.solana.Base58
import com.lockwallet.custody.local.material.AccountProfile
import com.lockwallet.custody.local.material.MaterialError
import com.lockwallet.custody.local.material.MaterialException
import com.lockwallet.custody.local.material.PendingMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMaterialTest {
    private val spec = Fixtures.json("lockwallet-spec-23-1.json")
    private val oracle = Fixtures.json("oracle-derivations.json").getAsJsonArray("vectors").map { it.asJsonObject }

    private fun err(block: () -> Unit) = (runCatching(block).exceptionOrNull() as MaterialException).error

    @Test fun tolerantPhraseInputReachesOracleAddresses() {
        for (v in oracle) {
            val messy = "  " + v["mnemonic"].asString.uppercase().replace(" ", " \n\t ") + "  "
            PendingMaterial.fromPhrase(messy.toCharArray()).use { m ->
                val found = m.discover(10)
                for (d in v.getAsJsonArray("derivations").map { it.asJsonObject }) {
                    val profile = if (d["profile"].asString == "PRIMARY") AccountProfile.PRIMARY else AccountProfile.LEGACY
                    val idx = d["account_index"].asInt
                    if (idx >= 10) continue
                    assertEquals(d["address"].asString, found.first { it.profile == profile && it.index == idx }.address.toBase58())
                    assertEquals(d["path"].asString, profile.path(idx))
                }
            }
        }
    }

    @Test fun specVectorWordsRoundTrip() {
        val phrase = spec["mnemonic"].asString
        PendingMaterial.fromPhrase(phrase.toCharArray()).use { m ->
            assertEquals(12, m.wordCount)
            assertEquals(phrase, m.words().joinToString(" ") { String(it) })
        }
    }

    @Test fun generateOnly12Or24() {
        for (n in listOf(12, 24)) PendingMaterial.generate(n).use { m ->
            assertEquals(n, m.words().size)
            assertEquals(20, m.discover(10).size)
            // a generated phrase must restore to the same first address
            val again = PendingMaterial.fromPhrase(m.words().joinToString(" ") { String(it) }.toCharArray())
            assertEquals(m.discover(1)[0].address, again.discover(1)[0].address)
        }
        for (n in listOf(15, 18, 21, 13)) assertEquals(MaterialError.WORD_COUNT, err { PendingMaterial.generate(n) })
    }

    @Test fun phraseErrors() {
        val words = spec["mnemonic"].asString.split(" ")
        assertEquals(MaterialError.WORD_COUNT, err { PendingMaterial.fromPhrase(words.dropLast(1).joinToString(" ").toCharArray()) })
        assertEquals(MaterialError.UNKNOWN_WORD, err { PendingMaterial.fromPhrase((words.dropLast(1) + "bitcoinx").joinToString(" ").toCharArray()) })
        val swapped = listOf(words[1], words[0]) + words.drop(2)
        assertEquals(MaterialError.CHECKSUM, err { PendingMaterial.fromPhrase(swapped.joinToString(" ").toCharArray()) })
        assertEquals(MaterialError.NOT_CANONICAL, err { PendingMaterial.fromPhrase("neither lonely flavor, argue".toCharArray()) })
        assertEquals(MaterialError.NOT_CANONICAL, err { PendingMaterial.fromPhrase("néither".toCharArray()) })
    }

    @Test fun keypairImports() {
        val enc = Fixtures.json("lwrp-v1-encrypted.json").getAsJsonArray("cases").map { it.asJsonObject }.first { it["kind"].asInt == 2 }
        val kp = enc["material_hex"].asString.hex()
        val pub = enc["pubkey_hex"].asString.hex()
        val b58 = Base58.encode(kp)
        PendingMaterial.fromBase58PrivateKey(" $b58\n".toCharArray()).use { assertEquals(Base58.encode(pub), it.discover()[0].address.toBase58()) }
        PendingMaterial.fromBase58PrivateKey(Base58.encode(kp.copyOf(32)).toCharArray()).use { assertEquals(Base58.encode(pub), it.discover()[0].address.toBase58()) }
        val json = kp.joinToString(",", "[", "]") { (it.toInt() and 0xff).toString() }
        PendingMaterial.fromKeypairJson(json.toCharArray()).use { assertEquals(Base58.encode(pub), it.discover()[0].address.toBase58()) }
        PendingMaterial.fromKeypairJson(("\n [ " + json.drop(1).replace(",", " , ")).toCharArray()).close()

        val inconsistent = kp.copyOf().also { it[63] = (it[63] + 1).toByte() }
        assertEquals(MaterialError.KEYPAIR_INCONSISTENT, err { PendingMaterial.fromBase58PrivateKey(Base58.encode(inconsistent).toCharArray()) })
        assertEquals(MaterialError.KEY_FORMAT, err { PendingMaterial.fromBase58PrivateKey("0OIl".toCharArray()) })
        assertEquals(MaterialError.KEY_LENGTH, err { PendingMaterial.fromBase58PrivateKey(Base58.encode(kp.copyOf(40)).toCharArray()) })
        assertEquals(MaterialError.KEY_LENGTH, err { PendingMaterial.fromKeypairJson("[1,2,3]".toCharArray()) })
        assertEquals(MaterialError.JSON_FORMAT, err { PendingMaterial.fromKeypairJson(json.replace("[", "{").toCharArray()) })
        assertEquals(MaterialError.JSON_FORMAT, err { PendingMaterial.fromKeypairJson((json.dropLast(1) + ",256]").toCharArray()) })
        assertEquals(MaterialError.JSON_FORMAT, err { PendingMaterial.fromKeypairJson(json.replaceFirst("[", "[01,").toCharArray()) })
        assertEquals(MaterialError.JSON_FORMAT, err { PendingMaterial.fromKeypairJson((json + "x").toCharArray()) })
        assertEquals(MaterialError.TOO_LARGE, err { PendingMaterial.fromKeypairJson(CharArray(2000) { ' ' }) })
    }

    @Test fun closeWipes() {
        val m = PendingMaterial.generate(12)
        m.close()
        assertTrue(runCatching { m.words() }.isFailure)
        assertEquals("PendingMaterial(REDACTED)", m.toString())
    }
}

class QuizTest {
    @Test fun quizHasRightAnswerAmongDistinctOptions() {
        PendingMaterial.generate(24).use { m ->
            val q = m.quiz()
            org.junit.Assert.assertEquals(3, q.size)
            org.junit.Assert.assertEquals(3, q.map { it.position }.toSet().size)
            for (question in q) {
                org.junit.Assert.assertEquals(4, question.options.size)
                org.junit.Assert.assertEquals(4, question.options.map { String(it) }.toSet().size)
                org.junit.Assert.assertEquals(1, question.options.count { m.isWordAt(question.position, it) })
                question.wipe()
            }
            org.junit.Assert.assertFalse(m.isWordAt(0, "abandon".toCharArray()))
            org.junit.Assert.assertFalse(m.isWordAt(25, "abandon".toCharArray()))
        }
    }

    @Test fun accountAtIndex() {
        val spec = Fixtures.json("lockwallet-spec-23-1.json")["mnemonic"].asString
        PendingMaterial.fromPhrase(spec.toCharArray()).use { m ->
            org.junit.Assert.assertEquals(m.discover(5).first { it.profile == AccountProfile.PRIMARY && it.index == 4 }, m.account(AccountProfile.PRIMARY, 4))
        }
    }
}
