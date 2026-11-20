package com.lockwallet.data.rpc

import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.tx.Blockhash
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

class RpcException(val kind: Kind, message: String, val rpcCode: Long? = null) : Exception(message) {
    enum class Kind { TRANSPORT, HTTP, RATE_LIMITED, RPC_ERROR, MALFORMED, WRONG_NETWORK }
}

/** JSON-RPC POST. Production: [HttpsRpcTransport]; tests inject a fake. */
fun interface RpcTransport {
    fun post(url: String, body: String, maxBytes: Int): String
}

/**
 * HTTPS only (no cleartext anywhere: network security config + this check), no redirects, no cookies, neutral
 * User-Agent, bounded body, no retries here (callers decide). Only public data (addresses, signed
 * transactions) is ever sent — never seeds or keys (spec §12.1).
 */
class HttpsRpcTransport(private val timeoutMs: Int = 12_000) : RpcTransport {
    override fun post(url: String, body: String, maxBytes: Int): String {
        require(url.startsWith("https://")) { "https only" }
        val c = try { URL(url).openConnection() as HttpsURLConnection } catch (e: Exception) { throw RpcException(RpcException.Kind.TRANSPORT, "connect") }
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false; c.useCaches = false
            c.setRequestProperty("User-Agent", "LockWallet")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code == 429) throw RpcException(RpcException.Kind.RATE_LIMITED, "rate limited")
            // Some nodes answer JSON-RPC errors with a non-200 status (e.g. 410 "faucet closed"): surface the error body.
            val stream = if (code == 200) c.inputStream else (c.errorStream ?: throw RpcException(RpcException.Kind.HTTP, "http $code"))
            stream.use { s ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (true) {
                    val n = s.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw RpcException(RpcException.Kind.MALFORMED, "response too large")
                }
                val text = out.toString("UTF-8")
                if (code != 200 && !text.contains("\"jsonrpc\"")) throw RpcException(RpcException.Kind.HTTP, "http $code")
                return text
            }
        } catch (e: RpcException) { throw e } catch (e: Exception) {
            throw RpcException(RpcException.Kind.TRANSPORT, "network error")
        } finally { c.disconnect() }
    }
}

data class AccountData(val address: SolanaPublicKey, val lamports: Long, val owner: SolanaPublicKey, val executable: Boolean, private val raw: ByteArray) {
    val data: ByteArray get() = raw.copyOf()
    override fun equals(other: Any?) = other is AccountData && other.address == address && other.lamports == lamports && other.owner == owner && other.raw.contentEquals(raw)
    override fun hashCode() = address.hashCode()
}
data class LatestBlockhash(val blockhash: Blockhash, val lastValidBlockHeight: Long)
data class SimulationResult(val err: String?, val logs: List<String>, val unitsConsumed: Long?, val accounts: List<AccountData?>)
data class SignatureStatus(val slot: Long, val err: String?, val confirmationStatus: String?)
data class SignatureInfo(val signature: String, val slot: Long, val err: String?, val blockTime: Long?, val confirmationStatus: String?)
data class TokenBalanceChange(val mint: String, val owner: String?, val preUnits: Long, val postUnits: Long, val decimals: Int)
data class TransactionDetail(val fee: Long, val err: String?, val blockTime: Long?, val accountKeys: List<String>, val preBalances: List<Long>, val postBalances: List<Long>, val tokenChanges: List<TokenBalanceChange>)
/** One `epochCredits` entry from getVoteAccounts: credits earned in [epoch] = [credits] − [previous]. */
data class EpochCredit(val epoch: Long, val credits: Long, val previous: Long) { val earned: Long get() = credits - previous }

data class ValidatorInfo(val vote: SolanaPublicKey, val node: SolanaPublicKey, val commission: Int, val activatedStake: Long, val lastVote: Long, val delinquent: Boolean,
    /** Last (≤ 10) entries of on-chain vote credits; empty if the node omitted them. */
    val epochCredits: List<EpochCredit> = emptyList(),
    /** On-chain inflation-reward commission in basis points (newer nodes); null if not reported. */
    val commissionBps: Int? = null)
data class EpochInfo(val epoch: Long, val slotIndex: Long, val slotsInEpoch: Long, val absoluteSlot: Long, val blockHeight: Long)
data class InflationReward(val epoch: Long, val amount: Long, val postBalance: Long, val commission: Int?)

/** Typed JSON-RPC client for one endpoint. Blocking: call off the main thread. */
class RpcClient(val url: String, private val transport: RpcTransport) {
    private var id = 0L
    private val b64 = Base64.getDecoder()

    fun call(method: String, params: String = "[]", maxBytes: Int = 512 * 1024): Any? {
        val reqId = synchronized(this) { ++id }
        val body = """{"jsonrpc":"2.0","id":$reqId,"method":"$method","params":$params}"""
        val text = transport.post(url, body, maxBytes)
        val root = try { J.obj(MiniJson.parse(text)) } catch (e: MiniJson.JsonException) { throw RpcException(RpcException.Kind.MALFORMED, "bad json") }
        // A reply for another request (proxy cache, confused or hostile node) is never used.
        val rid = (root["id"] as? MiniJson.Num)?.text
        if (rid != null && rid != reqId.toString()) throw RpcException(RpcException.Kind.MALFORMED, "response id mismatch")
        if (rid == null && root["error"] == null) throw RpcException(RpcException.Kind.MALFORMED, "response without id")
        root["error"]?.let { e ->
            val eo = J.obj(e)
            val code = (eo["code"] as? MiniJson.Num)?.text?.toLongOrNull()
            val msg = (eo["message"] as? String)?.take(300) ?: "rpc error"
            if (code == 429L) throw RpcException(RpcException.Kind.RATE_LIMITED, msg, code)
            throw RpcException(RpcException.Kind.RPC_ERROR, msg, code)
        }
        if ("result" !in root) J.bad("no result")
        return root["result"]
    }

    private fun value(r: Any?): Any? = J.obj(r)["value"]
    private fun q(k: SolanaPublicKey) = JsonOut.str(k.toBase58())

    fun getGenesisHash(): String = J.str(call("getGenesisHash"))
    fun getBalance(k: SolanaPublicKey): Long = J.long(value(call("getBalance", """[${q(k)},{"commitment":"confirmed"}]""")))
    fun getLatestBlockhash(): LatestBlockhash {
        val v = J.obj(value(call("getLatestBlockhash", """[{"commitment":"confirmed"}]""")))
        return LatestBlockhash(Blockhash.fromBase58(J.str(v["blockhash"])), J.long(v["lastValidBlockHeight"]))
    }
    /** Whether the node still accepts [bh] (dApp-built transactions carry their own blockhash). */
    fun isBlockhashValid(bh: com.lockwallet.core.solana.tx.Blockhash): Boolean =
        J.bool(value(call("isBlockhashValid", """[${JsonOut.str(bh.toBase58())},{"commitment":"confirmed"}]""")))
    fun getBlockHeight(): Long = J.long(call("getBlockHeight", """[{"commitment":"confirmed"}]"""))
    fun getFeeForMessage(message: ByteArray): Long? =
        J.longOrNull(value(call("getFeeForMessage", """[${JsonOut.str(Base64.getEncoder().encodeToString(message))},{"commitment":"confirmed"}]""")))
    fun getMinimumBalanceForRentExemption(size: Long): Long = J.long(call("getMinimumBalanceForRentExemption", """[$size,{"commitment":"confirmed"}]"""))
    fun getStakeMinimumDelegation(): Long = J.long(value(call("getStakeMinimumDelegation", """[{"commitment":"confirmed"}]""")))

    fun simulate(tx: ByteArray, watch: List<SolanaPublicKey>): SimulationResult {
        val accts = watch.joinToString(",") { q(it) }
        val r = call("simulateTransaction", """[${JsonOut.str(Base64.getEncoder().encodeToString(tx))},{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":false,"commitment":"confirmed","accounts":{"encoding":"base64","addresses":[$accts]}}]""")
        val v = J.obj(value(r))
        val logs = (v["logs"] as? List<*>)?.map { J.str(it).take(400) }?.take(200) ?: emptyList()
        val rawAccounts = v["accounts"] as? List<*>
        // More (or fewer) accounts than requested is a malformed answer, never an index crash.
        if (rawAccounts != null && rawAccounts.size != watch.size) J.bad("simulation account count")
        val accounts = rawAccounts?.mapIndexed { i, a -> a?.let { account(watch[i], it) } } ?: emptyList()
        return SimulationResult(v["err"]?.let { errText(it) }, logs, J.longOrNull(v["unitsConsumed"]), accounts)
    }

    /** Sends exact signed bytes. Preflight on (simulation at the node too); retries are ours (same bytes only). */
    fun sendTransaction(tx: ByteArray): String = J.str(call("sendTransaction",
        """[${JsonOut.str(Base64.getEncoder().encodeToString(tx))},{"encoding":"base64","skipPreflight":false,"preflightCommitment":"confirmed","maxRetries":0}]"""))

    fun getSignatureStatuses(sigs: List<String>, searchHistory: Boolean): List<SignatureStatus?> {
        val v = J.arr(value(call("getSignatureStatuses", """[[${sigs.joinToString(",") { JsonOut.str(it) }}],{"searchTransactionHistory":$searchHistory}]""")))
        if (v.size != sigs.size) J.bad("status count")
        return v.map { s -> s?.let { val o = J.obj(it); SignatureStatus(J.long(o["slot"]), o["err"]?.let { e -> errText(e) }, o["confirmationStatus"] as? String) } }
    }

    fun getSignaturesForAddress(k: SolanaPublicKey, limit: Int = 25): List<SignatureInfo> =
        J.arr(call("getSignaturesForAddress", """[${q(k)},{"limit":$limit,"commitment":"confirmed"}]""")).map {
            val o = J.obj(it)
            SignatureInfo(J.str(o["signature"]), J.long(o["slot"]), o["err"]?.let { e -> errText(e) }, J.longOrNull(o["blockTime"]), o["confirmationStatus"] as? String)
        }

    fun getTransaction(sig: String): TransactionDetail? {
        val r = call("getTransaction", """[${JsonOut.str(sig)},{"encoding":"json","commitment":"confirmed","maxSupportedTransactionVersion":0}]""", 1024 * 1024) ?: return null
        val o = J.obj(r); val meta = J.obj(o["meta"]); val msg = J.obj(J.obj(o["transaction"])["message"])
        val staticKeys = J.arr(msg["accountKeys"]).map { J.str(it) }
        val loaded = meta["loadedAddresses"]?.let { J.obj(it) }
        val loadedWritable = loaded?.get("writable")?.let { J.arr(it).map { key -> J.str(key) } }.orEmpty()
        val loadedReadonly = loaded?.get("readonly")?.let { J.arr(it).map { key -> J.str(key) } }.orEmpty()
        val keys = staticKeys + loadedWritable + loadedReadonly
        if (keys.isEmpty() || keys.distinct().size != keys.size) J.bad("account keys")
        fun bal(n: String): List<Long> = J.arr(meta[n]).map {
            J.long(it).also { value -> if (value < 0L) J.bad("negative balance") }
        }
        fun tok(n: String) = (meta[n] as? List<*>)?.map { t ->
            val to = J.obj(t); val ui = J.obj(to["uiTokenAmount"])
            val rawIndex = J.long(to["accountIndex"])
            if (rawIndex !in 0 until keys.size.toLong()) J.bad("token account index")
            val amount = J.str(ui["amount"]).toLongOrNull() ?: J.bad("amount")
            val decimals = J.long(ui["decimals"])
            if (amount < 0L || decimals !in 0..255) J.bad("token balance")
            Triple(rawIndex.toInt(), to, amount) to decimals.toInt()
        } ?: emptyList()
        val preBalances = bal("preBalances")
        val postBalances = bal("postBalances")
        if (preBalances.size != keys.size || postBalances.size != keys.size) J.bad("transaction balance count")
        val pre = tok("preTokenBalances"); val post = tok("postTokenBalances")
        val idx = (pre.map { it.first.first } + post.map { it.first.first }).toSet()
        val changes = idx.map { i ->
            val a = pre.firstOrNull { it.first.first == i }; val b = post.firstOrNull { it.first.first == i }
            val src = (b ?: a)!!
            TokenBalanceChange(J.str(src.first.second["mint"]), src.first.second["owner"] as? String, a?.first?.third ?: 0, b?.first?.third ?: 0, src.second)
        }
        val fee = J.long(meta["fee"])
        if (fee < 0L) J.bad("negative transaction fee")
        return TransactionDetail(fee, meta["err"]?.let { errText(it) }, J.longOrNull(o["blockTime"]), keys, preBalances, postBalances, changes)
    }

    /** Recent per-slot minimum priority fees (micro-lamports per CU) for transactions locking [writable]. */
    fun getRecentPrioritizationFees(writable: List<SolanaPublicKey>): List<Long> {
        require(writable.size <= 128)
        return J.arr(call("getRecentPrioritizationFees", "[[${writable.joinToString(",") { q(it) }}]]")).take(300).map { J.long(J.obj(it)["prioritizationFee"]) }
    }

    fun getAccountInfo(k: SolanaPublicKey): AccountData? =
        value(call("getAccountInfo", """[${q(k)},{"encoding":"base64","commitment":"confirmed"}]"""))?.let { account(k, it) }

    fun getMultipleAccounts(keys: List<SolanaPublicKey>): List<AccountData?> {
        if (keys.isEmpty()) return emptyList()
        require(keys.size <= 100)
        val v = J.arr(value(call("getMultipleAccounts", """[[${keys.joinToString(",") { q(it) }}],{"encoding":"base64","commitment":"confirmed"}]""", 4 * 1024 * 1024)))
        if (v.size != keys.size) J.bad("account count")
        return v.mapIndexed { i, a -> a?.let { account(keys[i], it) } }
    }

    fun getTokenAccountsByOwner(owner: SolanaPublicKey, program: SolanaPublicKey): List<AccountData> =
        J.arr(value(call("getTokenAccountsByOwner", """[${q(owner)},{"programId":${q(program)}},{"encoding":"base64","commitment":"confirmed"}]""", 4 * 1024 * 1024))).map {
            val o = J.obj(it); account(SolanaPublicKey.fromBase58(J.str(o["pubkey"])), o["account"])
        }

    /** Stake accounts whose withdrawer is [withdrawer] (memcmp at offset 44 of StakeStateV2). */
    fun getStakeAccountsByWithdrawer(stakeProgram: SolanaPublicKey, withdrawer: SolanaPublicKey): List<AccountData> =
        J.arr(call("getProgramAccounts", """[${q(stakeProgram)},{"encoding":"base64","commitment":"confirmed","filters":[{"dataSize":200},{"memcmp":{"offset":44,"bytes":${q(withdrawer)}}}]}]""", 4 * 1024 * 1024)).map {
            val o = J.obj(it); account(SolanaPublicKey.fromBase58(J.str(o["pubkey"])), o["account"])
        }

    fun getVoteAccounts(): List<ValidatorInfo> {
        val r = J.obj(call("getVoteAccounts", """[{"commitment":"confirmed","keepUnstakedDelinquents":false}]""", 24 * 1024 * 1024))
        fun list(n: String, delinquent: Boolean) = J.arr(r[n]).map {
            val o = J.obj(it)
            val commission = J.long(o["commission"]).toInt(); if (commission > 100) J.bad("commission")
            if (commission < 0) J.bad("commission")
            // Optional fields: absent ⇒ unknown; present but malformed ⇒ the whole answer is rejected (strict parser).
            // Real mainnet answers contain sentinel rows ([u64::MAX, u64::MAX, u64::MAX]) on some vote accounts; a row
            // must be well-formed JSON (3 unsigned integers) but rows that don't fit (saturated) are dropped, never
            // used: the whole list must not fail because one delinquent validator carries a sentinel (found in 5.0).
            val credits = (o["epochCredits"]?.let { J.arr(it) } ?: emptyList()).mapNotNull { e ->
                val t = J.arr(e); if (t.size != 3) J.bad("epochCredits")
                val r = Triple(J.longSat(t[0]), J.longSat(t[1]), J.longSat(t[2]))
                if (r.first == Long.MAX_VALUE || r.second == Long.MAX_VALUE || r.third == Long.MAX_VALUE) null
                else EpochCredit(r.first, r.second, r.third)
            }.takeLast(10)
            val bps = J.longOrNull(o["inflationRewardsCommissionBps"])?.also { if (it !in 0..10_000) J.bad("commissionBps") }?.toInt()
            ValidatorInfo(SolanaPublicKey.fromBase58(J.str(o["votePubkey"])), SolanaPublicKey.fromBase58(J.str(o["nodePubkey"])),
                commission, J.longSat(o["activatedStake"]), J.long(o["lastVote"]), delinquent, credits, bps)
        }
        return list("current", false) + list("delinquent", true)
    }

    fun getEpochInfo(): EpochInfo {
        val o = J.obj(call("getEpochInfo", """[{"commitment":"confirmed"}]"""))
        return EpochInfo(J.long(o["epoch"]), J.long(o["slotIndex"]), J.long(o["slotsInEpoch"]), J.long(o["absoluteSlot"]), J.long(o["blockHeight"]))
    }

    fun getInflationReward(keys: List<SolanaPublicKey>, epoch: Long): List<InflationReward?> {
        if (keys.isEmpty()) return emptyList()
        val v = J.arr(call("getInflationReward", """[[${keys.joinToString(",") { q(it) }}],{"epoch":$epoch,"commitment":"confirmed"}]"""))
        if (v.size != keys.size) J.bad("reward count")
        return v.map { x -> x?.let { val o = J.obj(it); InflationReward(J.long(o["epoch"]), J.long(o["amount"]), J.long(o["postBalance"]), J.longOrNull(o["commission"])?.toInt()) } }
    }

    private fun account(k: SolanaPublicKey, v: Any?): AccountData {
        val o = J.obj(v)
        val d = J.arr(o["data"]); if (d.size != 2 || d[1] != "base64") J.bad("encoding")
        val raw = try { b64.decode(J.str(d[0])) } catch (_: IllegalArgumentException) { J.bad("base64") }
        return AccountData(k, J.long(o["lamports"]), SolanaPublicKey.fromBase58(J.str(o["owner"])), J.bool(o["executable"]), raw)
    }

    /** Compact, bounded text for an RPC `err` value (shown to the user as technical detail). */
    private fun errText(e: Any?): String = when (e) {
        is String -> e
        is Map<*, *> -> e.entries.joinToString(",") { "${it.key}:${errText(it.value)}" }
        is List<*> -> e.joinToString(",") { errText(it) }
        is MiniJson.Num -> e.text
        null -> "null"
        else -> e.toString()
    }.take(200)

    companion object {
        /** Syntax-only check for user-provided endpoints. Network identity is checked separately (genesis). */
        fun validateUrl(input: String): String? {
            val t = input.trim()
            if (t.length > 512 || !t.startsWith("https://")) return null
            val u = try { URI(t) } catch (_: Exception) { return null }
            if (u.scheme != "https" || u.host.isNullOrEmpty() || u.rawUserInfo != null || u.rawFragment != null) return null
            if (t.any { it.isWhitespace() || it.code > 0x7e }) return null
            return t
        }
    }
}
