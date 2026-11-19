package com.lockwallet.custody.local.api

import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.keystore.KeySecurityLevel
import com.lockwallet.custody.local.material.AccountProfile
import com.lockwallet.custody.local.storage.WalletRole
import com.lockwallet.custody.local.storage.WalletState
import com.lockwallet.core.solana.SolanaPublicKey

/** Custody provider ids, persisted per wallet; no automatic migration between providers (ADR 0016). */
enum class CustodyProviderId(val code: String) { LOCAL_SECURE("local"), SEED_VAULT("seedvault") }

data class ProviderCapabilities(
    val id: CustodyProviderId,
    val available: Boolean,
    /** Short pt-BR reason when unavailable. */
    val unavailableReason: String?,
    val supportsBiometricGate: Boolean,
    val keySecurityLevel: KeySecurityLevel?,
)

/** Public view of a wallet (no secrets). */
data class WalletInfo(
    val id: String,
    val name: String,
    val role: WalletRole,
    val provider: CustodyProviderId,
    val isMnemonic: Boolean,
    val profile: AccountProfile,
    val accountIndex: Int,
    val address: SolanaPublicKey,
    val seedGroup: String,
    val createdAtMs: Long,
    val state: WalletState,
) {
    val path: String get() = profile.path(accountIndex)
}

/**
 * Common custody contract (ADR 0016). Signing (`sign(validatedMessage)`) is added together with the
 * Transaction Security Analyzer in Phase D: no signing API exists before a parser can produce the summary.
 */
interface KeyCustodyProvider {
    val id: CustodyProviderId
    fun capabilities(): ProviderCapabilities
    fun wallets(): List<WalletInfo>
}

/** Re-export so the UI can configure auth without seeing keystore internals. */
typealias VaultAuthMode = AuthMode
