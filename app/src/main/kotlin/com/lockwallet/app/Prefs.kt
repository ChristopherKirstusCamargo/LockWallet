package com.lockwallet.app

import android.content.Context
import com.lockwallet.data.price.Fiat
import com.lockwallet.data.rpc.Cluster
import com.lockwallet.data.rpc.NetworkConfig

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
