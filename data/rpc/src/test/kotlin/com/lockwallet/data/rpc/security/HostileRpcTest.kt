package com.lockwallet.data.rpc.security

import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.tx.Blockhash
import com.lockwallet.data.rpc.RpcClient
import com.lockwallet.data.rpc.RpcException
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private val KEY = SolanaPublicKey.fromBase58("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv")
private val BH = Blockhash(ByteArray(32) { 3 })

/** Every typed RPC call the wallet makes, with a check of the parsed value when one is returned. */
internal val CALLS: List<Pair<String, (RpcClient) -> Any?>> = listOf(
    "getGenesisHash" to { c -> c.getGenesisHash().also { assertTrue(it.length <= 512 * 1024) } },
    "getBalance" to { c -> c.getBalance(KEY).also { assertTrue("negative balance $it", it >= 0) } },
    "getLatestBlockhash" to { c -> c.getLatestBlockhash().also { assertTrue(it.lastValidBlockHeight >= 0) } },
    "isBlockhashValid" to { c -> c.isBlockhashValid(BH) },
    "getBlockHeight" to { c -> c.getBlockHeight().also { assertTrue(it >= 0) } },
    "blockHeightForExpiry" to { c -> c.blockHeightForExpiry().also { assertTrue(it >= 0) } },
    "getSlot" to { c -> c.getSlot().also { assertTrue(it >= 0) } },
    "getBlockTime" to { c -> c.getBlockTime(5) },
    "getFeeForMessage" to { c -> c.getFeeForMessage(ByteArray(40)).also { assertTrue(it == null || it >= 0) } },
    "getMinimumBalanceForRentExemption" to { c -> c.getMinimumBalanceForRentExemption(200).also { assertTrue(it >= 0) } },
    "getStakeMinimumDelegation" to { c -> c.getStakeMinimumDelegation().also { assertTrue(it >= 0) } },
    "simulate" to { c -> c.simulate(ByteArray(100), listOf(KEY)) },
    "sendTransaction" to { c -> c.sendTransaction(ByteArray(100)) },
    "getSignatureStatuses" to { c -> c.getSignatureStatuses(listOf("1".repeat(88)), true).also { assertTrue(it.size == 1) } },
    "getSignaturesForAddress" to { c -> c.getSignaturesForAddress(KEY).also { assertTrue(it.size <= 1000) } },
    "getTransaction" to { c -> c.getTransaction("1".repeat(88)) },
    "getRecentPrioritizationFees" to { c -> c.getRecentPrioritizationFees(listOf(KEY)).also { l -> assertTrue(l.all { it >= 0 }) } },
    "getAccountInfo" to { c -> c.getAccountInfo(KEY) },
    "getMultipleAccounts" to { c -> c.getMultipleAccounts(listOf(KEY, ProgramIds.SYSTEM)).also { assertTrue(it.size == 2) } },
    "getTokenAccountsByOwner" to { c -> c.getTokenAccountsByOwner(KEY, ProgramIds.SPL_TOKEN_LEGACY) },
    "getStakeAccountsByWithdrawer" to { c -> c.getStakeAccountsByWithdrawer(ProgramIds.STAKE, KEY) },
    "getVoteAccounts" to { c -> c.getVoteAccounts() },
    "getEpochInfo" to { c -> c.getEpochInfo().also { assertTrue(it.blockHeight >= 0 && it.absoluteSlot >= 0) } },
    "getInflationReward" to { c -> c.getInflationReward(listOf(KEY), 10).also { assertTrue(it.size == 1) } },
)

/** Hostile `result` payloads: wrong types, overflow, negative, missing/duplicated fields, huge and deep values. */
internal val RESULTS = listOf(
    "null", "true", "0", "-1", "1.5", "1e400", "18446744073709551616", "-9223372036854775809", "\"x\"", "\"\"", "[]", "{}",
    "[null]", "{\"value\":null}", "{\"context\":{\"slot\":1},\"value\":-5}", "{\"context\":{\"slot\":1},\"value\":\"5\"}",
    "{\"context\":{\"slot\":1},\"value\":[null,null,null]}", "{\"context\":{\"slot\":1},\"value\":{\"err\":null}}",
    "{\"value\":{\"blockhash\":\"0OIl\",\"lastValidBlockHeight\":1}}", "{\"value\":{\"blockhash\":\"${"1".repeat(44)}\",\"lastValidBlockHeight\":-1}}",
    "[" .repeat(40) + "]".repeat(40), "\"" + "A".repeat(200_000) + "\"", "{\"a\":1,\"a\":2}",
    "{\"context\":{\"slot\":1},\"value\":{\"data\":[\"!!!notbase64\",\"base64\"],\"lamports\":1,\"owner\":\"11111111111111111111111111111111\",\"executable\":false,\"rentEpoch\":0}}",
    "[{\"slot\":-1,\"prioritizationFee\":-7}]", "[{\"signature\":\"\",\"slot\":1,\"err\":null,\"blockTime\":-1}]",
    "{\"current\":[{\"votePubkey\":\"bad\",\"nodePubkey\":\"bad\",\"activatedStake\":-1,\"commission\":300}],\"delinquent\":[]}",
    "{\"absoluteSlot\":-1,\"blockHeight\":-1,\"epoch\":-1,\"slotIndex\":0,\"slotsInEpoch\":0}",
    "[{\"epoch\":1,\"amount\":-1,\"postBalance\":-1,\"commission\":-1}, null]", "\"\\u0000\\ud800\"",
)

@RunWith(Parameterized::class)
class HostileRpcResultTest(private val method: String, private val payload: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} <- {1}")
        fun data(): List<Array<Any>> = CALLS.flatMap { (m, _) -> RESULTS.map { arrayOf<Any>(m, if (it.length > 60) it.take(57) + "…" else it) } }
    }

    @Test fun parsedStrictlyOrRefusedAsRpcError() {
        val raw = RESULTS.first { (if (it.length > 60) it.take(57) + "…" else it) == payload }
        val c = RpcClient("https://hostile.example") { _, body, _ ->
            val id = Regex("\"id\":(\\d+)").find(body)!!.groupValues[1]
            """{"jsonrpc":"2.0","id":$id,"result":$raw}"""
        }
        val call = CALLS.first { it.first == method }.second
        try { call(c) } catch (_: RpcException) { /* refused: fine */ } catch (e: AssertionError) { throw e } catch (e: Throwable) {
            fail("$method leaked ${e::class.java.simpleName}: ${e.message?.take(200)} for $payload")
        }
    }
}

/** Hostile envelopes: not JSON, wrong id, error objects, truncation, gigantic bodies. */
@RunWith(Parameterized::class)
class HostileRpcEnvelopeTest(private val method: String, private val envelope: Int) {
    companion object {
        val ENVELOPES: List<(String) -> String> = listOf(
            { _ -> "" }, { _ -> "<html>502</html>" }, { _ -> "null" }, { _ -> "[]" }, { id -> """{"jsonrpc":"2.0","id":${id.toLong() + 1},"result":5}""" },
            { _ -> """{"jsonrpc":"2.0","result":5}""" }, { id -> """{"jsonrpc":"2.0","id":"$id","result":5}""" },
            { id -> """{"jsonrpc":"2.0","id":$id,"error":{"code":429,"message":"slow down"}}""" },
            { id -> """{"jsonrpc":"2.0","id":$id,"error":{"code":-32005,"message":"${"x".repeat(5000)}"}}""" },
            { id -> """{"jsonrpc":"2.0","id":$id,"error":"boom"}""" }, { id -> """{"jsonrpc":"2.0","id":$id}""" },
            { id -> """{"jsonrpc":"2.0","id":$id,"result":5""" }, { id -> """{"jsonrpc":"2.0","id":$id,"result":5}garbage""" },
            { id -> """{"jsonrpc":"2.0","id":$id,"result":""" + "[".repeat(10_000) },
            { _ -> throw RpcException(RpcException.Kind.TRANSPORT, "reset") },
        )
        @JvmStatic @Parameterized.Parameters(name = "{0} envelope {1}")
        fun data(): List<Array<Any>> = CALLS.flatMap { (m, _) -> ENVELOPES.indices.map { arrayOf<Any>(m, it) } }
    }

    @Test fun refusedAsRpcError() {
        val c = RpcClient("https://hostile.example") { _, body, _ -> ENVELOPES[envelope](Regex("\"id\":(\\d+)").find(body)!!.groupValues[1]) }
        try { CALLS.first { it.first == method }.second(c); fail("$method accepted hostile envelope $envelope") }
        catch (_: RpcException) {} catch (e: AssertionError) { throw e } catch (e: Throwable) { fail("$method leaked ${e::class.java.simpleName}") }
    }
}
