package com.lockwallet.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Spec §8.2: scope, best-effort wipe, no String in secret APIs, no secret bytes in toString/exceptions.
 * A JVM test cannot prove absence of physical residues in memory — and does not claim to.
 */
class SecretHygieneTest {
    @Test fun closeWipesBufferAndBlocksAccess() {
        val raw = ByteArray(32) { 7 }
        val s = SecretBytes.adopt(raw)
        s.close()
        assertTrue(raw.all { it.toInt() == 0 })
        assertTrue(s.isClosed)
        assertThrows(IllegalStateException::class.java) { s.withBytes { } }
    }

    @Test fun derivedKeyCloseWipesPrivateKey() {
        val seed = ByteArray(64) { it.toByte() }
        val key = Slip10Ed25519.deriveSolanaKey(seed, DerivationProfile.PRIMARY, 0)
        var captured: ByteArray? = null
        key.privateKey.withBytes { captured = it }
        key.close()
        assertTrue(captured!!.all { it.toInt() == 0 })
    }

    @Test fun toStringIsRedacted() {
        val seed = ByteArray(64) { 0x41 }
        Slip10Ed25519.deriveSolanaKey(seed, DerivationProfile.PRIMARY, 0).use { k ->
            assertFalse(k.toString().contains("41"))
            assertEquals("SecretBytes(REDACTED)", k.privateKey.toString())
        }
        Slip10Ed25519.masterNode(seed).use { assertEquals("Slip10Node(REDACTED)", it.toString()) }
    }

    /** Public secret-handling APIs take/return CharArray/ByteArray/SecretBytes — never String. */
    @Test fun noStringInSecretApis() {
        listOf(Bip39::class.java, Slip10Ed25519::class.java, SecretBytes::class.java, Ed25519DerivedKey::class.java,
            Slip10Node::class.java).forEach { cls ->
            cls.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.name.contains('$') && it.name != "toString" }
                .forEach { m ->
                    assertFalse("${cls.simpleName}.${m.name} returns String", m.returnType == String::class.java)
                    assertFalse("${cls.simpleName}.${m.name} takes String", m.parameterTypes.contains(String::class.java))
                }
        }
    }
}
