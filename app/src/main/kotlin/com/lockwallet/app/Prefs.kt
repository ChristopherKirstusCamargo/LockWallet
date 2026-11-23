package com.lockwallet.app

import android.content.Context
import com.lockwallet.data.price.Fiat
import com.lockwallet.data.rpc.Cluster
import com.lockwallet.data.rpc.NetworkConfig
import java.io.File

/** Non-secret preferences only (theme, price, network, selection, confirmed recipients). Excluded from backup. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    var dark: Boolean
        get() = sp.getBoolean("dark", true)
        set(v) { sp.edit().putBoolean("dark", v).apply() }
    var priceEnabled: Boolean
        get() = sp.getBoolean("price", true)
        set(v) { sp.edit().putBoolean("price", v).apply() }
    var fiat: Fiat
        get() = Fiat.entries.firstOrNull { it.name == sp.getString("fiat", "BRL") } ?: Fiat.entries.first()
        set(v) { sp.edit().putString("fiat", v.name).apply() }

    /** Preferred starting unit for the home balance. Tapping the balance toggles this value. */
    var balanceInSol: Boolean
        get() = sp.getBoolean("balanceInSol", false)
        set(v) { sp.edit().putBoolean("balanceInSol", v).apply() }

    /** User images are kept under noBackupFilesDir, separate from wallet custody and excluded from backup. */
    val profilePhotos = ProfilePhotoStore(File(context.noBackupFilesDir, "profile-photos"))

    /** Mainnet endpoints (2.0.0: the only network). Custom URLs are stored only after a genesis check. */
    var network: NetworkConfig
        get() {
            val d = NetworkConfig.MAINNET_DEFAULT
            return NetworkConfig(Cluster.MAINNET, sp.getString("rpc.primary", null) ?: d.primaryUrl, d.secondaryUrl)
        }
        set(v) {
            require(v.cluster == Cluster.MAINNET)
            val d = NetworkConfig.MAINNET_DEFAULT
            sp.edit().putString("rpc.primary", v.primaryUrl.takeIf { it != d.primaryUrl })
                .remove("rpc.secondary").apply()
        }
    val customRpc: Boolean get() = sp.getString("rpc.primary", null) != null

    /**
     * Dedicated Privacy Rings (Helius) endpoint — only stored after validation (HTTPS, Helius host,
     * api-key, Mainnet genesis). Kept apart from the common RPC so /v1/spr traffic (indexer and prover)
     * never goes through the app-wide primary, and the common RPC never silently becomes a privacy
     * endpoint (ADR 0026, decision 6).
     */
    var sprEndpoint: String?
        get() {
            sp.getString("rpc.spr", null)?.let { return it }
            // One-shot 5.0.7 migration: a Helius URL configured as the common primary before this
            // dedicated field existed becomes the SPR endpoint (the common primary stays untouched).
            val legacy = sp.getString("rpc.primary", null) ?: return null
            if (isHeliusSprUrl(legacy)) { sp.edit().putString("rpc.spr", legacy).apply(); return legacy }
            return null
        }
        set(v) { sp.edit().putString("rpc.spr", v).apply() }
    val customSpr: Boolean get() = sprEndpoint != null

    /** Onboarding choices that are not secrets (biometric prompt skipped, backup reminder dismissed, haptics). */
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) { sp.edit().putBoolean("haptics", v).apply() }
    var hideBalance: Boolean
        get() = sp.getBoolean("hideBalance", false)
        set(v) { sp.edit().putBoolean("hideBalance", v).apply() }

    var selectedWallet: String?
        get() = sp.getString("wallet", null)
        set(v) { sp.edit().putString("wallet", v).apply() }

    /** Destinations the user already confirmed (public addresses; address-poisoning checks, spec §13.6). */
    /** Wallet ids with a verified off-device backup (LWRP export read back and opened). Phrase wallets need none. */
    var backedUp: Set<String>
        get() = sp.getStringSet("backedUp", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("backedUp", v).apply() }

    var recipients: Set<String>
        get() = sp.getStringSet("recipients", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("recipients", v.take(500).toSet()).apply() }

    /** Token accounts the user chose to hide (never deleted; spec §13.6 "nunca descartar silenciosamente"). */
    var hiddenTokens: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("hidden", v).apply() }
}

/** Mirrors the native gate (helius_service_url): HTTPS, Helius host and a non-empty api-key query. */
internal fun isHeliusSprUrl(url: String): Boolean {
    val u = try { java.net.URI(url.trim()) } catch (_: Exception) { return false }
    if (u.scheme != "https" || u.rawUserInfo != null || u.rawFragment != null) return false
    val host = u.host?.lowercase() ?: return false
    if (host != "helius-rpc.com" && !host.endsWith(".helius-rpc.com")) return false
    return u.rawQuery?.split('&')?.any { it.startsWith("api-key=") && it.length > "api-key=".length } == true
}
