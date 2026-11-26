package com.lockwallet.core.solana

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Base58Test {
    @Test fun oracleVectors() {
        val v = SolanaFixtures.json("base58-oracle.json").getAsJsonArray("vectors")
        v.forEach {
            val o = it.asJsonObject
            val raw = o["hex"].asString.hex()
            assertEquals(o["base58"].asString, Base58.encode(raw))
            assertArrayEquals(raw, Base58.decodeOrNull(o["base58"].asString))
        }
    }

    @Test fun strictDecoding() {
        listOf("0", "O", "I", "l", " 1", "1 ", "abc\n", "é", "+").forEach { assertNull(it, Base58.decodeOrNull(it)) }
        assertNull(Base58.decodeOrNull("z".repeat(500), 32)) // bounded before work
    }

    @Test fun publicKeyStrictness() {
        val sys = "11111111111111111111111111111111"
        assertEquals(sys, SolanaPublicKey.fromBase58(sys).toBase58())
        assertNull(SolanaPublicKey.fromBase58OrNull("1111111111111111111111111111111")) // 31 bytes
        assertNull(SolanaPublicKey.fromBase58OrNull("111111111111111111111111111111111")) // 33 bytes
        assertNull(SolanaPublicKey.fromBase58OrNull("5vftMkHL72JaJG6ExQfGAsT2uGVHpRR7oTNUPMs68Y2N "))
        assertNull(SolanaPublicKey.fromBase58OrNull(""))
    }
}
