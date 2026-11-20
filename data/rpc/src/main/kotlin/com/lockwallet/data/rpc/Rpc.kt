package com.lockwallet.data.rpc

import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.tx.Blockhash
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
class HttpsRpcTransport(
    private val timeoutMs: Int = 12_000,
    private val resolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
) : RpcTransport {
    override fun post(url: String, body: String, maxBytes: Int): String {
        val accepted = RpcClient.validateUrl(url) ?: throw RpcException(RpcException.Kind.MALFORMED, "invalid HTTPS endpoint")
        val uri = URI(accepted)
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
            ?: throw RpcException(RpcException.Kind.MALFORMED, "endpoint host missing")
        val port = if (uri.port == -1) 443 else uri.port
        if (port !in 1..65535 || maxBytes < 1) throw RpcException(RpcException.Kind.MALFORMED, "invalid endpoint limits")

        // Resolve exactly once per request, validate every A/AAAA answer, then connect to those exact address objects.
        // The TCP socket is never allowed to resolve the hostname a second time (DNS rebinding/TOCTOU).
        val addresses = try { resolver(host) } catch (_: Exception) {
            throw RpcException(RpcException.Kind.TRANSPORT, "DNS resolution failed")
        }
        val safeAddresses = RpcClient.validateResolvedAddresses(host, addresses)
        val target = (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "")
        val hostHeader = (if (host.contains(':')) "[$host]" else host) +
            if (port == 443) "" else ":$port"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        if (bodyBytes.size > 4 * 1024 * 1024) throw RpcException(RpcException.Kind.MALFORMED, "request too large")

        var lastFailure: Exception? = null
        for (address in safeAddresses) {
            var plain: Socket? = null
            var tls: SSLSocket? = null
            try {
                plain = Socket()
                plain.connect(InetSocketAddress(address, port), timeoutMs)
                plain.soTimeout = timeoutMs
                tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plain, host, port, true) as SSLSocket
                tls.soTimeout = timeoutMs

                // Preserve the original DNS hostname for both SNI and certificate hostname verification,
                // while the underlying TCP socket remains pinned to the already-validated IP address.
                val parameters = tls.sslParameters
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                if (!isIpLiteral(host)) parameters.serverNames = listOf(SNIHostName(host))
                tls.sslParameters = parameters
                tls.startHandshake()
                return exchange(tls, hostHeader, target, bodyBytes, maxBytes)
            } catch (e: RpcException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
            } finally {
                runCatching { tls?.close() }
                if (tls == null) runCatching { plain?.close() }
            }
        }
        throw RpcException(RpcException.Kind.TRANSPORT, "pinned HTTPS connection failed", null).also {
            lastFailure?.let { cause -> it.initCause(cause) }
        }
    }

    private fun exchange(socket: SSLSocket, hostHeader: String, target: String, body: ByteArray, maxBytes: Int): String {
        val output = BufferedOutputStream(socket.outputStream)
        val requestHeaders = buildString {
            append("POST ").append(target).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("User-Agent: LockWallet\r\n")
            append("Content-Type: application/json\r\n")
            append("Accept: application/json\r\n")
            append("Accept-Encoding: identity\r\n")
            append("Connection: close\r\n")
            append("Content-Length: ").append(body.size).append("\r\n\r\n")
        }
        output.write(requestHeaders.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()

        val input = BufferedInputStream(socket.inputStream)
        val statusLine = readLine(input, 8 * 1024) ?: throw RpcException(RpcException.Kind.TRANSPORT, "empty HTTPS response")
        val statusMatch = Regex("^HTTP/1\\.[01] ([0-9]{3})(?: .*)?$").matchEntire(statusLine)
            ?: throw RpcException(RpcException.Kind.MALFORMED, "invalid HTTP status line")
        val status = statusMatch.groupValues[1].toInt()
        val headers = linkedMapOf<String, MutableList<String>>()
        var headerBytes = statusLine.length
        while (true) {
            val line = readLine(input, 8 * 1024) ?: throw RpcException(RpcException.Kind.MALFORMED, "truncated HTTP headers")
            headerBytes += line.length + 2
            if (headerBytes > 32 * 1024) throw RpcException(RpcException.Kind.MALFORMED, "HTTP headers too large")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) throw RpcException(RpcException.Kind.MALFORMED, "invalid HTTP header")
            val name = line.substring(0, colon).trim().lowercase()
            if (!name.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]+"))) throw RpcException(RpcException.Kind.MALFORMED, "invalid HTTP header name")
            val value = line.substring(colon + 1).trim()
            headers.getOrPut(name) { mutableListOf() }.add(value)
        }

        if (headers["content-encoding"]?.any { !it.equals("identity", ignoreCase = true) } == true) {
            throw RpcException(RpcException.Kind.MALFORMED, "unsupported HTTP content encoding")
        }
        val transfer = headers["transfer-encoding"]
        val lengths = headers["content-length"]
        if (transfer != null && lengths != null) throw RpcException(RpcException.Kind.MALFORMED, "ambiguous HTTP body framing")
        val response = when {
            transfer != null -> {
                if (transfer.size != 1 || !transfer.single().equals("chunked", ignoreCase = true)) {
                    throw RpcException(RpcException.Kind.MALFORMED, "unsupported HTTP transfer encoding")
                }
                readChunked(input, maxBytes)
            }
            lengths != null -> {
                if (lengths.distinct().size != 1) throw RpcException(RpcException.Kind.MALFORMED, "conflicting HTTP content lengths")
                val length = lengths.single().toLongOrNull() ?: throw RpcException(RpcException.Kind.MALFORMED, "invalid HTTP content length")
                if (length < 0 || length > maxBytes) throw RpcException(RpcException.Kind.MALFORMED, "HTTP response too large")
                readExact(input, length.toInt())
            }
            else -> readToEof(input, maxBytes)
        }

        if (status == 429) throw RpcException(RpcException.Kind.RATE_LIMITED, "rate limited")
        val text = response.toString(Charsets.UTF_8.name())
        // JSON-RPC errors may use non-200 status codes. Redirects are never followed.
        if (status != 200 && !text.contains("\"jsonrpc\"")) {
            throw RpcException(RpcException.Kind.HTTP, "http $status")
        }
        return text
    }

    private fun readLine(input: BufferedInputStream, maxLength: Int): String? {
        val out = ByteArrayOutputStream()
        while (out.size() <= maxLength) {
            val value = input.read()
            if (value < 0) {
                if (out.size() == 0) return null
                throw EOFException("truncated HTTP line")
            }
            if (value == 10) {
                val bytes = out.toByteArray()
                val size = if (bytes.isNotEmpty() && bytes.last() == 13.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, size, Charsets.ISO_8859_1)
            }
            out.write(value)
        }
        throw RpcException(RpcException.Kind.MALFORMED, "HTTP line too long")
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) throw EOFException("truncated HTTP body")
            offset += count
        }
        return result
    }

    private fun readToEof(input: BufferedInputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return out.toByteArray()
            if (out.size().toLong() + count > maxBytes) throw RpcException(RpcException.Kind.MALFORMED, "HTTP response too large")
            out.write(buffer, 0, count)
        }
    }

    private fun readChunked(input: BufferedInputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var trailerBytes = 0
        while (true) {
            val line = readLine(input, 8 * 1024) ?: throw EOFException("truncated chunk header")
            val sizeText = line.substringBefore(';')
            if (!sizeText.matches(Regex("[0-9A-Fa-f]{1,16}"))) throw RpcException(RpcException.Kind.MALFORMED, "invalid HTTP chunk size")
            val size = sizeText.toLongOrNull(16) ?: throw RpcException(RpcException.Kind.MALFORMED, "invalid HTTP chunk size")
            if (size == 0L) {
                while (true) {
                    val trailer = readLine(input, 8 * 1024) ?: throw EOFException("truncated HTTP trailers")
                    trailerBytes += trailer.length + 2
                    if (trailerBytes > 16 * 1024) throw RpcException(RpcException.Kind.MALFORMED, "HTTP trailers too large")
                    if (trailer.isEmpty()) return out.toByteArray()
                    if (trailer.indexOf(':') <= 0) throw RpcException(RpcException.Kind.MALFORMED, "invalid HTTP trailer")
                }
            }
            if (size > maxBytes || out.size().toLong() + size > maxBytes) {
                throw RpcException(RpcException.Kind.MALFORMED, "HTTP response too large")
            }
            out.write(readExact(input, size.toInt()))
            if (readLine(input, 2) != "") throw RpcException(RpcException.Kind.MALFORMED, "invalid HTTP chunk terminator")
        }
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.matches(Regex("[0-9.]+")) || host.matches(Regex("(?i)(0x[0-9a-f]+|[0-9a-f]+\\.[0-9a-f.]+)"))
}
data class AccountData(val address: SolanaPublicKey, val lamports: Long, val owner: SolanaPublicKey, val executable: Boolean, private val raw: ByteArray) {
    val data: ByteArray get() = raw.copyOf()
    override fun equals(other: Any?) = other is AccountData &&
        other.address == address && other.lamports == lamports && other.owner == owner &&
        other.executable == executable && other.raw.contentEquals(raw)
    override fun hashCode() = 31 * (31 * (31 * address.hashCode() + lamports.hashCode()) + owner.hashCode()) + raw.contentHashCode()
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
            val host = u.host?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return null
            if (u.scheme != "https" || u.rawUserInfo != null || u.rawFragment != null ||
                u.port !in -1..65535 || isLocalHost(host) || isPrivateIpLiteral(host)
            ) return null
            if (t.any { it.isWhitespace() || it.code > 0x7e }) return null
            return t
        }

        private fun isLocalHost(host: String): Boolean =
            host == "localhost" || host.endsWith(".localhost") ||
                host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".home.arpa")

        /**
         * Validate all DNS answers before the transport opens a socket. If even one A/AAAA answer is non-public,
         * refuse the hostname rather than allowing resolver-order fallback to reach a private/special-use target.
         */
        internal fun validateResolvedAddresses(host: String, addresses: Array<InetAddress>): List<InetAddress> {
            if (addresses.isEmpty() || addresses.any { !isPublicResolvedAddress(it) }) {
                throw RpcException(RpcException.Kind.MALFORMED, "endpoint resolves to non-public address")
            }
            return addresses.toList()
        }

        internal fun isPublicResolvedAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) return false
            val bytes = address.address
            if (bytes.size == 4) {
                val a = bytes[0].toInt() and 0xff
                val b = bytes[1].toInt() and 0xff
                val c = bytes[2].toInt() and 0xff
                return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && b in 64..127) ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && (b == 0 || b == 2 || b == 168)) ||
                    (a == 192 && b == 88 && c == 99) ||
                    (a == 198 && (b in 18..19 || b == 51)) ||
                    (a == 203 && b == 0 && c == 113))
            }
            if (bytes.size == 16) {
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                val third = bytes[2].toInt() and 0xff
                // Only global-unicast 2000::/3 is accepted. This rejects unspecified, mapped, ULA,
                // link-local, site-local, multicast, NAT64 well-known and other non-global prefixes.
                if (first and 0xe0 != 0x20) return false
                if (first == 0x20 && second == 0x01 && third and 0xfe == 0x00) return false // 2001::/23 special use
                if (first == 0x20 && second == 0x01 && third == 0x0d && (bytes[3].toInt() and 0xff) == 0xb8) return false // documentation
                if (first == 0x20 && second == 0x02) return false // deprecated 6to4
                if (first == 0x3f && second == 0xfe) return false // deprecated 6bone
                return true
            }
            return false
        }

        /** Private/special IP literals are rejected before any request is made. */
        private fun isPrivateIpLiteral(host: String): Boolean {
            val numeric = host.contains(':') || host.matches(Regex("[0-9.]+")) ||
                host.matches(Regex("(?i)(0x[0-9a-f]+|[0-9a-f]+\\.[0-9a-f.]+)"))
            if (!numeric) return false
            val address = try { InetAddress.getByName(host) } catch (_: Exception) { return true }
            return !isPublicResolvedAddress(address)
        }
    }
}
