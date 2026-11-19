package com.lockwallet.core.solana

/**
 * Program and sysvar IDs of the closed allowlist (ADR 0007 + Phase D/E/F extensions, ADR 0019).
 * Verified against solders 0.29.0 / LiteSVM builtins (fixture `solana-program-ids.json`, `tx-oracle.json`).
 * Recognising an ID authorises nothing by itself: the analyzer validates layout, accounts and privileges.
 */
object ProgramIds {
    val SYSTEM: SolanaPublicKey = SolanaPublicKey.fromBase58("11111111111111111111111111111111")
    val SPL_TOKEN_LEGACY: SolanaPublicKey = SolanaPublicKey.fromBase58("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val TOKEN_2022: SolanaPublicKey = SolanaPublicKey.fromBase58("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    val ASSOCIATED_TOKEN: SolanaPublicKey = SolanaPublicKey.fromBase58("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val COMPUTE_BUDGET: SolanaPublicKey = SolanaPublicKey.fromBase58("ComputeBudget111111111111111111111111111111")
    val STAKE: SolanaPublicKey = SolanaPublicKey.fromBase58("Stake11111111111111111111111111111111111111")
    val STAKE_CONFIG: SolanaPublicKey = SolanaPublicKey.fromBase58("StakeConfig11111111111111111111111111111111")
    val VOTE: SolanaPublicKey = SolanaPublicKey.fromBase58("Vote111111111111111111111111111111111111111")
    val METAPLEX_METADATA: SolanaPublicKey = SolanaPublicKey.fromBase58("metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s")
    val SYSVAR_RENT: SolanaPublicKey = SolanaPublicKey.fromBase58("SysvarRent111111111111111111111111111111111")
    val SYSVAR_CLOCK: SolanaPublicKey = SolanaPublicKey.fromBase58("SysvarC1ock11111111111111111111111111111111")
    val SYSVAR_STAKE_HISTORY: SolanaPublicKey = SolanaPublicKey.fromBase58("SysvarStakeHistory1111111111111111111111111")
    /** Wrapped SOL mint (legacy token program). */
    val NATIVE_MINT: SolanaPublicKey = SolanaPublicKey.fromBase58("So11111111111111111111111111111111111111112")

    val TOKEN_PROGRAMS: Set<SolanaPublicKey> = setOf(SPL_TOKEN_LEGACY, TOKEN_2022)
    val MVP_ALLOWLIST: Set<SolanaPublicKey> = setOf(SYSTEM, SPL_TOKEN_LEGACY, TOKEN_2022, ASSOCIATED_TOKEN, COMPUTE_BUDGET, STAKE)
}
