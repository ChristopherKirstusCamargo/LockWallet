package com.lockwallet.core.solana

import org.junit.Assert.assertEquals
import org.junit.Test

class SolanaUriTest {
    private val a = "GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv"

    @Test fun addressesAndTransferRequests() {
        assertEquals(a, (SolanaUri.parse(a) as SolanaUri.Result.Address).key.toBase58())
        assertEquals(a, (SolanaUri.parse(" solana:$a?amount=1&label=x ") as SolanaUri.Result.Address).key.toBase58())
        assertEquals(a, (SolanaUri.parse("SOLANA:$a") as SolanaUri.Result.Address).key.toBase58())
        assertEquals(SolanaUri.Result.TransactionRequest, SolanaUri.parse("solana:https%3A%2F%2Fevil"))
        assertEquals(SolanaUri.Result.TransactionRequest, SolanaUri.parse("solana:https://evil.example/tx"))
        for (bad in listOf("", "solana:", "0OIl$a", a + "1", "bitcoin:$a", "x".repeat(600), "solana:" + "1".repeat(31), "solana:$a?" + "x".repeat(SolanaUri.MAX_QUERY_LEN + 1)))
            assertEquals(bad, SolanaUri.Result.Invalid, SolanaUri.parse(bad))
    }
}
