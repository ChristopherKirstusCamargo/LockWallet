package com.lockwallet.core.solana

import com.lockwallet.core.solana.state.TokenPolicy
import com.lockwallet.core.solana.state.TokenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TokenPolicyTest {
    private fun tlv(vararg e: Pair<Int, ByteArray>): ByteArray =
        e.fold(ByteArray(0)) { acc, (t, v) -> acc + byteArrayOf(t.toByte(), (t shr 8).toByte(), v.size.toByte(), (v.size shr 8).toByte()) + v }

    /** Token-2022 mint base (82) + zero padding to 165 + account type 1 + TLV. */
    private fun mint22(vararg e: Pair<Int, ByteArray>): ByteArray {
        val base = ByteArray(82).also { it[44] = 6; it[45] = 1 } // decimals 6, initialized
        return base + ByteArray(165 - 82) + byteArrayOf(1) + tlv(*e)
    }

    @Test fun spec142RiskyExtensionsAreRefused() {
        val risky = listOf(1, 3, 4, 6, 8, 9, 10, 11, 12, 14, 16, 20, 21, 24, 25, 26, 28)
        for (t in risky) {
            val m = TokenState.parseMint(ProgramIds.TOKEN_2022, mint22(t to ByteArray(8)))
            val v = TokenPolicy.judge(m.extensions, emptyList())
            assertFalse("ext $t", v.allowed); assertTrue(v.reasons.isNotEmpty())
        }
        assertFalse(TokenPolicy.judge(emptyList(), listOf(15)).allowed) // TransferHookAccount
        assertFalse(TokenPolicy.judge(emptyList(), listOf(11)).allowed) // CpiGuard on the account
    }

    @Test fun unknownFutureExtensionIsRefusedByDefault() {
        val m = TokenState.parseMint(ProgramIds.TOKEN_2022, mint22(99 to ByteArray(3)))
        val v = TokenPolicy.judge(m.extensions, emptyList())
        assertFalse(v.allowed); assertTrue(v.reasons.single().contains("desconhecida"))
    }

    @Test fun metadataOnlyMintIsAllowedAndParsed() {
        fun s(x: String) = x.toByteArray().let { byteArrayOf(it.size.toByte(), 0, 0, 0) + it }
        val meta = ByteArray(64) + s("Lock\u202ETest\u0000") + s("LCK") + s("https://x") + byteArrayOf(0, 0, 0, 0)
        val m = TokenState.parseMint(ProgramIds.TOKEN_2022, mint22(18 to ByteArray(64), 19 to meta))
        assertTrue(TokenPolicy.judge(m.extensions, listOf(7)).allowed)
        assertEquals("LockTest", m.metadata!!.name) // bidi override and NUL stripped
        assertEquals("LCK", m.metadata!!.symbol)
    }

    @Test fun malformedTlvIsRejected() {
        val cases = listOf(
            mint22(12 to ByteArray(32), 12 to ByteArray(32)),            // duplicate extension
            mint22(18 to ByteArray(64)).copyOf(166 + 4 + 10),            // truncated value
            mint22().also { it[100] = 1 },                               // non-zero padding
            ByteArray(82).also { it[45] = 1 } + ByteArray(5),            // wrong size, no account type
        )
        for (c in cases) try { TokenState.parseMint(ProgramIds.TOKEN_2022, c); fail("accepted") } catch (_: MalformedException) {}
        try { TokenState.parseMint(ProgramIds.SPL_TOKEN_LEGACY, mint22(18 to ByteArray(64))); fail("legacy with TLV") } catch (_: MalformedException) {}
    }
}
