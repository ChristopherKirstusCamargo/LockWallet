package com.lockwallet.data.rpc

import com.lockwallet.core.solana.SolanaPublicKey

/**
 * Solana Network Manager: Mainnet only for production transactions. Devnet/Testnet identifiers remain solely
 * to recognise and reject endpoints that report another genesis hash.
 */
/**
 * Solana clusters known by genesis hash. Since 2.0.0 the app runs on Mainnet only (ADR 0020): Devnet/Testnet stay
 * here solely so a custom endpoint that points at them is recognised and refused (and for unit-test fixtures);
 * they have no endpoint, no faucet and cannot be selected by the app.
 */
enum class Cluster(val label: String, val genesisHash: String, val defaultUrl: String?, val defaultSecondaryUrl: String?) {
    MAINNET("Solana", "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d", MAINNET_PRIMARY, MAINNET_SECONDARY),
    DEVNET("Devnet", "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG", null, null),
    TESTNET("Testnet", "4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY", null, null),
    ;
    /** Only Mainnet can be used for real transactions. */
    val usable: Boolean get() = this == MAINNET

    fun explorerTx(signature: String): String? = if (usable) "https://explorer.solana.com/tx/$signature" else null
    fun explorerAddress(address: String): String? = if (usable) "https://explorer.solana.com/address/$address" else null

    companion object {
        fun byGenesis(g: String): Cluster? = entries.firstOrNull { it.genesisHash == g }
    }
}

/** Default public endpoints (no API key, no account): Solana Foundation + PublicNode as independent fallback. */
const val MAINNET_PRIMARY = "https://api.mainnet-beta.solana.com"
const val MAINNET_SECONDARY = "https://solana-rpc.publicnode.com"

/** Selected network: primary endpoint plus an optional secondary used for fallback, broadcast and reconciliation. */
data class NetworkConfig(val cluster: Cluster, val primaryUrl: String, val secondaryUrl: String?) {
    companion object {
        fun preset(c: Cluster) = NetworkConfig(c, requireNotNull(c.defaultUrl) { "cluster has no endpoint" }, c.defaultSecondaryUrl)
        val MAINNET_DEFAULT: NetworkConfig get() = preset(Cluster.MAINNET)
    }
}

/** Outcome of probing a custom endpoint before it is saved. */
sealed interface EndpointCheck {
    data object Ok : EndpointCheck
    data object BadUrl : EndpointCheck
    data object Unreachable : EndpointCheck
    data class OtherNetwork(val genesis: String, val known: Cluster?) : EndpointCheck
}

class NetworkManager(config: NetworkConfig, private val transport: RpcTransport, private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
                     private val sleeper: (Long) -> Unit = { Thread.sleep(it) }) {
    companion object {
        const val RATE_LIMIT_BACKOFF_MS = 900L
        const val RATE_LIMIT_RETRIES = 3
        const val GENESIS_REVERIFY_MS = 60_000L
    }
    @Volatile var config: NetworkConfig = config
        private set
    private val verified = HashMap<String, Long>()

    fun select(c: NetworkConfig) { config = c; synchronized(verified) { verified.clear() } }

    /** Probe a URL for [cluster]: HTTPS syntax, reachable, and genesis hash equal to the cluster's. */
    fun check(url: String, cluster: Cluster): EndpointCheck {
        val u = RpcClient.validateUrl(url) ?: return EndpointCheck.BadUrl
        val g = try { RpcClient(u, transport).getGenesisHash() } catch (_: RpcException) { return EndpointCheck.Unreachable }
        return if (g == cluster.genesisHash) EndpointCheck.Ok else EndpointCheck.OtherNetwork(g.take(64), Cluster.byGenesis(g))
    }

    /** Client for the primary endpoint, genesis-verified (re-checked every minute). */
    fun primary(): RpcClient = verifiedClient(config.primaryUrl)
    /** Secondary (genesis-verified) or null if not configured / not answering. */
    /**
     * Network clock in epoch milliseconds: the finalized block time, read from every verified endpoint; the
     * earliest answer wins so one endpoint cannot move a cooldown forward. Throws when no endpoint answers.
     */
    fun networkTimeMs(): Long {
        val answers = listOfNotNull(
            runCatching { primary().let { c -> c.getBlockTime(c.getSlot()) } }.getOrNull(),
            runCatching { secondaryOrNull()?.let { c -> c.getBlockTime(c.getSlot()) } }.getOrNull(),
        ).filter { it > 0L }
        if (answers.isEmpty()) throw RpcException(RpcException.Kind.TRANSPORT, "horário da rede indisponível")
        return answers.min() * 1000L
    }

    fun secondaryOrNull(): RpcClient? = config.secondaryUrl?.let { u -> try { verifiedClient(u) } catch (_: RpcException) { null } }

    /**
     * Security-sensitive account read: both independently configured, genesis-verified RPCs must return
     * byte-for-byte identical account snapshots. Never use the normal fallback path for policy decisions:
     * a successful but dishonest primary response must not be treated as authoritative.
     */
    fun getMultipleAccountsVerified(keys: List<SolanaPublicKey>): List<AccountData?> {
        if (keys.isEmpty()) return emptyList()
        val first = primary().getMultipleAccounts(keys)
        val second = secondaryOrNull()
            ?: throw RpcException(RpcException.Kind.TRANSPORT, "independent RPC unavailable")
        val comparison = second.getMultipleAccounts(keys)
        if (first != comparison) {
            throw RpcException(RpcException.Kind.MALFORMED, "independent RPC account snapshots disagree")
        }
        return first
    }

    /**
     * Read path: runs [block] on the primary; on any failure except a wrong-network endpoint it falls back to the
     * (genesis-verified) secondary — public endpoints rate-limit and block some methods, and a garbled answer from
     * one node must not stop the app. When the primary is rate-limited and the fallback cannot answer, it retries the
     * primary with a short bounded backoff.
     * Never used for sendTransaction (see [broadcastClients]).
     */
    fun <T> withFallback(block: (RpcClient) -> T): T {
        val first = try { return block(primary()) } catch (e: RpcException) { if (e.kind == RpcException.Kind.WRONG_NETWORK) throw e; e }
        val s = secondaryOrNull() ?: return retryAfterRateLimit(first, block)
        return try { block(s) } catch (e2: RpcException) {
            if (e2.kind == RpcException.Kind.WRONG_NETWORK) throw e2
            if (e2.kind == RpcException.Kind.RATE_LIMITED) retryAfterRateLimit(e2, block) else retryAfterRateLimit(first, block)
        }
    }

    /**
     * Bounded exponential backoff on the primary (0.9 s, 1.8 s, 3.6 s): the public fallback refuses some methods
     * outright (e.g. token accounts by owner), so a briefly rate-limited primary is the only source for them.
     */
    private fun <T> retryAfterRateLimit(e: RpcException, block: (RpcClient) -> T): T {
        if (e.kind != RpcException.Kind.RATE_LIMITED) throw e
        var last = e
        for (attempt in 0 until RATE_LIMIT_RETRIES) {
            sleeper(RATE_LIMIT_BACKOFF_MS shl attempt)
            try { return block(primary()) } catch (r: RpcException) { if (r.kind != RpcException.Kind.RATE_LIMITED) throw r; last = r }
        }
        throw last
    }

    /** Every configured, genesis-verified endpoint (primary first) for broadcasting the same signed bytes. */
    fun broadcastClients(): List<RpcClient> = listOfNotNull(try { primary() } catch (_: RpcException) { null }, secondaryOrNull())

    private fun verifiedClient(url: String): RpcClient {
        val c = RpcClient(url, transport)
        val last = synchronized(verified) { verified[url] }
        val now = nowMs()
        if (last == null || now < last || now - last > GENESIS_REVERIFY_MS) {
            if (c.getGenesisHash() == config.cluster.genesisHash) synchronized(verified) { verified[url] = nowMs() }
            else throw RpcException(RpcException.Kind.WRONG_NETWORK, "unexpected genesis hash")
        }
        return c
    }
}
