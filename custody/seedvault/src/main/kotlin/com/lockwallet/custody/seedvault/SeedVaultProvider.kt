package com.lockwallet.custody.seedvault

import android.content.Intent
import android.net.Uri
import com.lockwallet.core.crypto.Ed25519Signer
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.analyzer.TransactionAnalyzer
import com.lockwallet.custody.local.CustodyException
import com.lockwallet.custody.local.CustodyException.Reason
import com.lockwallet.custody.local.api.CustodyProviderId
import com.lockwallet.custody.local.api.KeyCustodyProvider
import com.lockwallet.custody.local.api.ProviderCapabilities
import com.lockwallet.custody.local.api.WalletInfo
import com.lockwallet.custody.local.material.AccountProfile
import com.lockwallet.custody.local.storage.AtomicFiles
import com.lockwallet.custody.local.storage.WalletRole
import com.lockwallet.custody.local.storage.WalletState
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** One Seed Vault-backed wallet. Only public data: the seed and keys stay inside Seed Vault. */
data class SeedVaultEntry(
    val id: String,
    val name: String,
    val role: WalletRole,
    val authToken: Long,
    val path: String,
    val address: SolanaPublicKey,
    val createdAtMs: Long,
)

/**
 * Seed Vault custody (ADR 0021). Used only when [SeedVaultGateway.available] is true on the device.
 * Signing: the same Transaction Security Analyzer must accept the exact bytes and their hash must match the
 * reviewed one BEFORE the Seed Vault intent is created; Seed Vault then authenticates the user itself
 * (the Lock Wallet PIN never replaces it, spec §23.3). The returned signature is verified locally.
 */
class SeedVaultProvider(
    dir: File,
    val gateway: SeedVaultGateway,
    private val atomic: AtomicFiles = AtomicFiles(),
    private val now: () -> Long = System::currentTimeMillis,
) : KeyCustodyProvider {
    private val file = File(dir, "seedvault.json")
    override val id = CustodyProviderId.SEED_VAULT

    override fun capabilities(): ProviderCapabilities {
        val ok = gateway.available()
        return ProviderCapabilities(id, ok, if (ok) null else UNAVAILABLE, false, null)
    }

    @Synchronized fun entries(): List<SeedVaultEntry> = read()

    override fun wallets(): List<WalletInfo> {
        val list = read()
        if (list.isEmpty()) return emptyList()
        val granted = if (gateway.available()) gateway.authorizedTokens() else emptySet()
        return list.map { e ->
            // A token Seed Vault no longer grants (seed removed / access revoked) is shown, never silently re-created.
            val st = if (granted != null && e.authToken !in granted) WalletState.QUARANTINED else WalletState.ACTIVE
            WalletInfo(e.id, e.name, e.role, CustodyProviderId.SEED_VAULT, false, AccountProfile.PRIMARY, accountIndex(e.path), e.address,
                "seedvault:${e.authToken}", e.createdAtMs, st)
        }
    }

    /** Registers the account obtained from Seed Vault. [others] = every wallet of every provider (duplicate check). */
    @Synchronized
    fun add(name: String, role: WalletRole, authToken: Long, publicKey: ByteArray, path: Uri, others: List<WalletInfo>): WalletInfo {
        val n = name.trim()
        if (n.isEmpty() || n.length > 32) throw CustodyException(Reason.BAD_NAME)
        if (publicKey.size != 32) throw CustodyException(Reason.BAD_ACCOUNT)
        val address = SolanaPublicKey.of(publicKey)
        val list = read()
        if (others.any { it.address == address } || list.any { it.address == address }) throw CustodyException(Reason.DUPLICATE_WALLET)
        // Lock Vault and Lock Connect never share a seed (spec §23.4): one Seed Vault seed ⇒ one role.
        if (list.any { it.authToken == authToken && it.role != role }) throw CustodyException(Reason.ROLE_CONFLICT)
        val e = SeedVaultEntry("sv-" + hex(ByteArray(8).also(SecureRandom()::nextBytes)), n, role, authToken, path.toString(), address, now())
        write(list + e)
        return wallets().first { it.id == e.id }
    }

    @Synchronized fun rename(id: String, name: String) {
        val n = name.trim(); if (n.isEmpty() || n.length > 32) throw CustodyException(Reason.BAD_NAME)
        val list = read(); if (list.none { it.id == id }) throw CustodyException(Reason.UNKNOWN_WALLET)
        write(list.map { if (it.id == id) it.copy(name = n) else it })
    }

    /** Forgets the wallet here (the seed stays in Seed Vault). Deauthorizes the token if no other wallet uses it. */
    @Synchronized fun remove(id: String) {
        val list = read(); val e = list.firstOrNull { it.id == id } ?: throw CustodyException(Reason.UNKNOWN_WALLET)
        val rest = list - e
        write(rest)
        if (rest.none { it.authToken == e.authToken }) gateway.deauthorize(e.authToken)
    }

    fun entry(id: String): SeedVaultEntry = read().firstOrNull { it.id == id } ?: throw CustodyException(Reason.UNKNOWN_WALLET)

    /**
     * Intent asking Seed Vault to sign [message]. Refuses (NOT_APPROVED) unless the analyzer accepts the bytes with
     * this wallet as the only signer/fee payer and [approvedHash] is the hash shown on the review screen.
     */
    fun signTransactionRequest(id: String, message: ByteArray, approvedHash: ByteArray, extraCheck: ((ByteArray, SolanaPublicKey) -> Boolean)? = null): Intent {
        val e = entry(id)
        val a = TransactionAnalyzer.analyze(message, e.address)
        if (!a.messageHash.contentEquals(approvedHash)) throw CustodyException(Reason.NOT_APPROVED)
        // Cumulative: an extra policy can only narrow the analyzer's verdict, never replace it.
        val ok = a.signable && (extraCheck?.invoke(message, e.address) ?: true)
        if (!ok) throw CustodyException(Reason.NOT_APPROVED)
        return gateway.signTransactionIntent(e.authToken, Uri.parse(e.path), message)
    }

    /** Lock Connect: same gates as the local provider (CONNECT role, dApp firewall, reviewed hash, raw ack). */
    fun signDappTransactionRequest(id: String, message: ByteArray, approvedHash: ByteArray, rawAcknowledged: Boolean): Intent {
        val e = entry(id)
        if (e.role != WalletRole.CONNECT) throw CustodyException(Reason.NOT_APPROVED)
        if (!com.lockwallet.core.solana.dapp.DappFirewall.permits(message, e.address, approvedHash, rawAcknowledged)) throw CustodyException(Reason.NOT_APPROVED)
        return gateway.signTransactionIntent(e.authToken, Uri.parse(e.path), message)
    }

    fun signMessageRequest(id: String, message: ByteArray): Intent {
        val e = entry(id)
        if (e.role != WalletRole.CONNECT || com.lockwallet.core.solana.dapp.MessagePolicy.check(message) != null) throw CustodyException(Reason.NOT_APPROVED)
        return gateway.signMessageIntent(e.authToken, Uri.parse(e.path), message)
    }

    /** Parses Seed Vault's answer and verifies the Ed25519 signature against the registered public key. */
    fun finishSignature(id: String, message: ByteArray, resultCode: Int, data: Intent?, isMessage: Boolean = false): ByteArray {
        val e = entry(id)
        val sig = try { gateway.parseSignature(resultCode, data, isMessage) } catch (_: SeedVaultFailure) { throw CustodyException(Reason.SEED_VAULT_DECLINED) }
        if (!Ed25519Signer.verify(e.address.toByteArray(), message, sig)) throw CustodyException(Reason.BAD_SIGNATURE)
        return sig
    }

    // ------------------------------------------------------------------ storage

    private fun read(): List<SeedVaultEntry> {
        if (!file.exists()) return emptyList()
        return try { parse(file.readBytes()) } catch (_: JSONException) { throw CustodyException(Reason.CORRUPTED) }
            catch (_: IllegalArgumentException) { throw CustodyException(Reason.CORRUPTED) }
    }

    private fun write(list: List<SeedVaultEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("name", e.name).put("role", e.role.name).put("token", e.authToken)
                .put("path", e.path).put("address", e.address.toBase58()).put("created", e.createdAtMs))
        }
        val bytes = JSONObject().put("v", 1).put("wallets", arr).toString().toByteArray(Charsets.UTF_8)
        try { atomic.write(file, bytes) { parse(it) } } catch (_: java.io.IOException) { throw CustodyException(Reason.STORAGE) }
    }

    private fun parse(b: ByteArray): List<SeedVaultEntry> {
        val o = JSONObject(String(b, Charsets.UTF_8))
        require(o.getInt("v") == 1)
        val arr = o.getJSONArray("wallets")
        return (0 until arr.length()).map { i ->
            val w = arr.getJSONObject(i)
            val path = w.getString("path"); require(path.startsWith("bip44:") || path.startsWith("bip32:"))
            SeedVaultEntry(w.getString("id"), w.getString("name").take(32), WalletRole.valueOf(w.getString("role")), w.getLong("token"),
                path, SolanaPublicKey.fromBase58(w.getString("address")), w.getLong("created"))
        }
    }

    private fun accountIndex(path: String): Int = Regex("""^bip44:/(\d+)'""").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        const val UNAVAILABLE = "Este aparelho não tem Seed Vault (só aparelhos Solana Mobile, como Saga/Seeker). Nada é simulado."
        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
