package com.lockwallet.core.solana

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramIdsTest {
    @Test fun matchIndependentOracle() {
        val p = SolanaFixtures.json("solana-program-ids.json").getAsJsonObject("programs")
        assertEquals(p["system"].asString, ProgramIds.SYSTEM.toBase58())
        assertEquals(p["spl_token_legacy"].asString, ProgramIds.SPL_TOKEN_LEGACY.toBase58())
        assertEquals(p["associated_token"].asString, ProgramIds.ASSOCIATED_TOKEN.toBase58())
        assertEquals(p["compute_budget"].asString, ProgramIds.COMPUTE_BUDGET.toBase58())
        assertEquals(6, ProgramIds.MVP_ALLOWLIST.size) // + Token-2022 (default deny, E-26) and Stake (Phase F)
    }
}
