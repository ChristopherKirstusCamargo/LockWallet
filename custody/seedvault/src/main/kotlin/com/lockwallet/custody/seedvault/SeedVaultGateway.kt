package com.lockwallet.custody.seedvault

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.solanamobile.seedvault.Bip44DerivationPath
import com.solanamobile.seedvault.BipLevel
import com.solanamobile.seedvault.PublicKeyResponse
import com.solanamobile.seedvault.SeedVault
import com.solanamobile.seedvault.Wallet
import com.solanamobile.seedvault.WalletContractV1

/** How the user wants to obtain a Seed Vault seed for Lock Wallet. */
enum class SeedSource { AUTHORIZE_EXISTING, CREATE_NEW, IMPORT }

/** Everything Lock Wallet needs from Seed Vault, behind an interface so the provider is testable. */
interface SeedVaultGateway {
    /** True only when the device really ships a Seed Vault implementation (never simulated). */
    fun available(): Boolean
    fun accessGranted(): Boolean
    /** Runtime permission to request at use time (null when privileged/not needed). */
    fun permission(): String?
    fun seedIntent(source: SeedSource): Intent
    fun parseSeedResult(source: SeedSource, resultCode: Int, data: Intent?): Long
    fun publicKeyIntent(authToken: Long, path: Uri): Intent
    fun parsePublicKey(resultCode: Int, data: Intent?): Pair<ByteArray, Uri>
    fun signTransactionIntent(authToken: Long, path: Uri, payload: ByteArray): Intent
    fun signMessageIntent(authToken: Long, path: Uri, payload: ByteArray): Intent
    fun parseSignature(resultCode: Int, data: Intent?, message: Boolean): ByteArray
    /** Auth tokens Seed Vault currently grants this app (null if it cannot be read now). */
    fun authorizedTokens(): Set<Long>?
    fun deauthorize(authToken: Long)
    /** Solana's standard first account: bip44 m/44'/501'/[account]'/0'. */
    fun accountPath(account: Int): Uri
}

class SeedVaultFailure(message: String) : Exception(message)

/** Real gateway over the official SDK (com.solanamobile:seedvault-wallet-sdk). */
class SdkSeedVaultGateway(context: Context) : SeedVaultGateway {
    private val ctx = context.applicationContext

    override fun available(): Boolean = try { SeedVault.isAvailable(ctx, false) } catch (_: RuntimeException) { false }
    override fun accessGranted(): Boolean = try { SeedVault.getAccessType(ctx).isGranted } catch (_: RuntimeException) { false }
    override fun permission(): String? = if (accessGranted()) null else WalletContractV1.PERMISSION_ACCESS_SEED_VAULT

    override fun seedIntent(source: SeedSource): Intent = when (source) {
        SeedSource.AUTHORIZE_EXISTING -> Wallet.authorizeSeed(ctx, WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION)
        SeedSource.CREATE_NEW -> Wallet.createSeed(ctx, WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION)
        SeedSource.IMPORT -> Wallet.importSeed(ctx, WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION)
    }

    override fun parseSeedResult(source: SeedSource, resultCode: Int, data: Intent?): Long = wrap {
        when (source) {
            SeedSource.AUTHORIZE_EXISTING -> Wallet.onAuthorizeSeedResult(resultCode, data)
            SeedSource.CREATE_NEW -> Wallet.onCreateSeedResult(resultCode, data)
            SeedSource.IMPORT -> Wallet.onImportSeedResult(resultCode, data)
        }
    }

    override fun publicKeyIntent(authToken: Long, path: Uri): Intent = Wallet.requestPublicKey(ctx, authToken, path)

    override fun parsePublicKey(resultCode: Int, data: Intent?): Pair<ByteArray, Uri> = wrap {
        val r: PublicKeyResponse = Wallet.onRequestPublicKeysResult(resultCode, data).singleOrNull() ?: throw SeedVaultFailure("public key count")
        try { r.publicKey to r.resolvedDerivationPath } catch (_: PublicKeyResponse.KeyNotValidException) { throw SeedVaultFailure("key not valid") }
    }

    override fun signTransactionIntent(authToken: Long, path: Uri, payload: ByteArray): Intent = Wallet.signTransaction(ctx, authToken, path, payload)
    override fun signMessageIntent(authToken: Long, path: Uri, payload: ByteArray): Intent = Wallet.signMessage(ctx, authToken, path, payload)

    override fun parseSignature(resultCode: Int, data: Intent?, message: Boolean): ByteArray = wrap {
        val list = if (message) Wallet.onSignMessagesResult(resultCode, data) else Wallet.onSignTransactionsResult(resultCode, data)
        list.singleOrNull()?.signatures?.singleOrNull() ?: throw SeedVaultFailure("signature count")
    }

    override fun authorizedTokens(): Set<Long>? = try {
        Wallet.getAuthorizedSeeds(ctx, arrayOf(WalletContractV1.AUTHORIZED_SEEDS_AUTH_TOKEN))?.use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }
    } catch (_: RuntimeException) { null }

    override fun deauthorize(authToken: Long) {
        try { Wallet.deauthorizeSeed(ctx, authToken) } catch (_: Wallet.NotModifiedException) { } catch (_: RuntimeException) { }
    }

    override fun accountPath(account: Int): Uri = Bip44DerivationPath.newBuilder()
        .setAccount(BipLevel(account, true)).setChange(BipLevel(0, true)).build().toUri()

    private inline fun <T> wrap(block: () -> T): T = try { block() }
        catch (e: Wallet.ActionFailedException) { throw SeedVaultFailure(e.message ?: "action failed") }
        catch (e: IllegalArgumentException) { throw SeedVaultFailure("bad result") }
}
