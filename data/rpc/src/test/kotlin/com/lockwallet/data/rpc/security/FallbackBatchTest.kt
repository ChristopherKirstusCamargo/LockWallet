package com.lockwallet.data.rpc.security

import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.data.rpc.Cluster
import com.lockwallet.data.rpc.FakeRpc
import com.lockwallet.data.rpc.NetworkConfig
import com.lockwallet.data.rpc.NetworkManager
import com.lockwallet.data.rpc.RpcClient
import com.lockwallet.data.rpc.RpcException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.random.Random

/**
 * Regression for a bug found by the live Mainnet gate: the default fallback endpoint (PublicNode) rejects
 * getMultipleAccounts with more than 10 keys, so whenever the primary was rate-limited, every read of more than
 * 10 accounts (stake seeds, token metadata) failed. For every request size 1..100, with the primary rate-limited and
 * a fallback that enforces the 10-key cap, the read must succeed, keep request order and never send a call above the cap.
 */
@RunWith(Parameterized::class)
class FallbackBatchTest(private val size: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} keys") fun data(): List<Array<Any>> = (1..100).map { arrayOf<Any>(it) }
        private const val SYSTEM = "11111111111111111111111111111111"
        fun keys(n: Int, seed: Int) = Random(seed).let { r -> List(n) { SolanaPublicKey.of(r.nextBytes(32)) } }
    }

    @Test fun fallbackReadsAnySizeInOrderWithinTheCap() {
        val keys = keys(size, size)
        val lamportsOf = keys.withIndex().associate { (i, k) -> k.toBase58() to 1_000L + i }
        val f = FakeRpc()
        f.on(FakeRpc.A, "getGenesisHash") { "\"${Cluster.MAINNET.genesisHash}\"" }
        f.on(FakeRpc.B, "getGenesisHash") { "\"${Cluster.MAINNET.genesisHash}\"" }
        f.on(FakeRpc.A, "getMultipleAccounts") { throw RpcException(RpcException.Kind.RATE_LIMITED, "429") }
        var maxSeen = 0
        f.on(FakeRpc.B, "getMultipleAccounts") { body ->
            val ks = body.substringAfter("[[").substringBefore("]").split(",").map { it.trim('"') }
            maxSeen = maxOf(maxSeen, ks.size)
            if (ks.size > 10) throw RpcException(RpcException.Kind.RPC_ERROR, "A maximum of 10 accounts is allowed")
            ks.joinToString(",", """{"context":{"slot":1},"value":[""", "]}") { k ->
                // every third key has no account: nulls must stay in their slot
                if ((lamportsOf.getValue(k) % 3) == 0L) "null"
                else """{"lamports":${lamportsOf.getValue(k)},"owner":"$SYSTEM","data":["","base64"],"executable":false,"rentEpoch":0}"""
            }
        }
        val net = NetworkManager(NetworkConfig(Cluster.MAINNET, FakeRpc.A, FakeRpc.B), f, sleeper = {})
        val got = net.withFallback { it.getMultipleAccounts(keys) }
        assertEquals(size, got.size)
        got.forEachIndexed { i, a ->
            val want = 1_000L + i
            if (want % 3 == 0L) assertEquals(null, a)
            else { assertEquals(keys[i], a!!.address); assertEquals(want, a.lamports) }
        }
        assertTrue("batch of $maxSeen keys", maxSeen in 1..RpcClient.MULTIPLE_ACCOUNTS_BATCH)
        assertEquals(1 + (size + 9) / 10, f.count("getMultipleAccounts")) // A refuses the first batch once; B serves every batch
    }
}
