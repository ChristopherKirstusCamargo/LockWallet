package com.lockwallet.data.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetworkTest {
    private val mainnet = "\"${Cluster.MAINNET.genesisHash}\""

    @Test fun customUrlSyntax() {
        assertNotNull(RpcClient.validateUrl("https://rpc.example.com/path?key=1"))
        for (bad in listOf("http://rpc.example.com", "https://", "https://user:pw@host", "https://h/#f", "ftp://h", "https://h /x", "https://exämple.com", "x".repeat(600)))
            assertNull(bad, RpcClient.validateUrl(bad))
    }

    @Test fun probeChecksGenesis() {
        val f = FakeRpc()
        f.on(FakeRpc.A, "getGenesisHash") { mainnet }
        f.on(FakeRpc.B, "getGenesisHash") { "\"${Cluster.DEVNET.genesisHash}\"" }
        f.on("https://c.example", "getGenesisHash") { "\"${Cluster.TESTNET.genesisHash}\"" }
        f.on("https://d.example", "getGenesisHash") { "\"11111111111111111111111111111111\"" }
        val n = NetworkManager(NetworkConfig.MAINNET_DEFAULT, f)
        assertEquals(EndpointCheck.Ok, n.check(FakeRpc.A, Cluster.MAINNET))
        assertEquals(EndpointCheck.OtherNetwork(Cluster.DEVNET.genesisHash, Cluster.DEVNET), n.check(FakeRpc.B, Cluster.MAINNET))
        assertEquals(Cluster.TESTNET, (n.check("https://c.example", Cluster.MAINNET) as EndpointCheck.OtherNetwork).known)
        assertEquals(null, (n.check("https://d.example", Cluster.MAINNET) as EndpointCheck.OtherNetwork).known)
        assertEquals(EndpointCheck.Unreachable, n.check("https://down.example", Cluster.MAINNET))
        assertEquals(EndpointCheck.BadUrl, n.check("http://x", Cluster.MAINNET))
    }

    @Test fun onlyMainnetIsUsable() {
        assertTrue(Cluster.MAINNET.usable); assertTrue(Cluster.entries.filter { it.usable } == listOf(Cluster.MAINNET))
        assertNull(Cluster.DEVNET.defaultUrl); assertNull(Cluster.TESTNET.defaultUrl); assertNull(Cluster.DEVNET.explorerTx("s"))
        val d = NetworkConfig.MAINNET_DEFAULT
        assertTrue(d.primaryUrl.startsWith("https://") && d.secondaryUrl!!.startsWith("https://") && d.primaryUrl != d.secondaryUrl)
        // A "mainnet" endpoint that actually serves Devnet is refused before any call.
        val f = FakeRpc(); f.on(FakeRpc.A, "getGenesisHash") { "\"${Cluster.DEVNET.genesisHash}\"" }; f.on(FakeRpc.A, "getBalance") { """{"context":{"slot":1},"value":5}""" }
        val n = NetworkManager(NetworkConfig(Cluster.MAINNET, FakeRpc.A, null), f)
        try { n.primary(); fail() } catch (e: RpcException) { assertEquals(RpcException.Kind.WRONG_NETWORK, e.kind) }
        assertEquals(0, f.count("getBalance"))
    }

    @Test fun wrongGenesisBlocksAllCalls() {
        val f = FakeRpc(); f.on(FakeRpc.A, "getGenesisHash") { "\"${Cluster.TESTNET.genesisHash}\"" }
        val n = NetworkManager(NetworkConfig(Cluster.MAINNET, FakeRpc.A, null), f)
        try { ChainReader(n).balance(com.lockwallet.core.solana.ProgramIds.SYSTEM); fail() } catch (e: RpcException) { assertEquals(RpcException.Kind.WRONG_NETWORK, e.kind) }
    }

    @Test fun fallbackToSecondaryOnAnyReadFailureButNeverToAnotherNetwork() {
        val f = FakeRpc()
        f.on(FakeRpc.A, "getGenesisHash") { mainnet }
        f.on(FakeRpc.B, "getGenesisHash") { mainnet }
        f.on(FakeRpc.B, "getBalance") { """{"context":{"slot":1},"value":42}""" }
        val n = NetworkManager(NetworkConfig(Cluster.MAINNET, FakeRpc.A, FakeRpc.B), f, sleeper = {})
        assertEquals(42L, ChainReader(n).balance(com.lockwallet.core.solana.ProgramIds.SYSTEM))
        // Public endpoints block some methods ("Request blocked", -32602): the secondary answers.
        f.on(FakeRpc.A, "getBalance") { throw RpcException(RpcException.Kind.RPC_ERROR, "Request blocked", -32602) }
        assertEquals(42L, ChainReader(n).balance(com.lockwallet.core.solana.ProgramIds.SYSTEM))
        // Garbage from the primary: the secondary answers.
        f.on(FakeRpc.A, "getBalance") { "{" }
        assertEquals(42L, ChainReader(n).balance(com.lockwallet.core.solana.ProgramIds.SYSTEM))
        // Secondary on another network is never used.
        f.on(FakeRpc.B, "getGenesisHash") { "\"${Cluster.DEVNET.genesisHash}\"" }
        val n2 = NetworkManager(NetworkConfig(Cluster.MAINNET, FakeRpc.A, FakeRpc.B), f, sleeper = {})
        try { ChainReader(n2).balance(com.lockwallet.core.solana.ProgramIds.SYSTEM); fail() } catch (e: RpcException) { assertEquals(RpcException.Kind.MALFORMED, e.kind) }
    }

    @Test fun rateLimitedEverywhereBacksOffOnceThenRetries() {
        val f = FakeRpc(); var slept = 0L; var n429 = 0
        f.on(FakeRpc.A, "getGenesisHash") { mainnet }; f.on(FakeRpc.B, "getGenesisHash") { mainnet }
        f.on(FakeRpc.A, "getBalance") { if (n429++ == 0) throw RpcException(RpcException.Kind.RATE_LIMITED, "429") else """{"context":{"slot":1},"value":7}""" }
        f.on(FakeRpc.B, "getBalance") { throw RpcException(RpcException.Kind.RATE_LIMITED, "429") }
        val n = NetworkManager(NetworkConfig(Cluster.MAINNET, FakeRpc.A, FakeRpc.B), f, sleeper = { slept += it })
        assertEquals(7L, ChainReader(n).balance(com.lockwallet.core.solana.ProgramIds.SYSTEM))
        assertEquals(NetworkManager.RATE_LIMIT_BACKOFF_MS, slept)
    }

    @Test fun replyForAnotherRequestIsRejected() {
        val c = RpcClient(FakeRpc.A) { _, _, _ -> """{"jsonrpc":"2.0","id":999,"result":5}""" }
        try { c.getBlockHeight(); fail() } catch (e: RpcException) { assertEquals(RpcException.Kind.MALFORMED, e.kind) }
        val noId = RpcClient(FakeRpc.A) { _, _, _ -> """{"jsonrpc":"2.0","result":5}""" }
        try { noId.getBlockHeight(); fail() } catch (e: RpcException) { assertEquals(RpcException.Kind.MALFORMED, e.kind) }
    }

    @Test fun simulationWithExtraAccountsIsMalformedNotACrash() {
        val c = RpcClient(FakeRpc.A) { _, b, _ ->
            val id = Regex("\"id\":(\\d+)").find(b)!!.groupValues[1]
            """{"jsonrpc":"2.0","id":$id,"result":{"context":{"slot":1},"value":{"err":null,"logs":[],"unitsConsumed":1,"accounts":[null,null,null]}}}"""
        }
        try { c.simulate(ByteArray(10), listOf(com.lockwallet.core.solana.ProgramIds.SYSTEM)); fail() } catch (e: RpcException) { assertEquals(RpcException.Kind.MALFORMED, e.kind) }
    }

    @Test fun strictResponses() {
        val f = FakeRpc(); val c = RpcClient(FakeRpc.A, f)
        for (bad in listOf("""{"context":{},"value":-1}""", """{"context":{},"value":1.5}""", """{"context":{},"value":"5"}""", """{"value":1,"value":2}""", """{"context":{},"value":18446744073709551616}"""))  {
            f.on(FakeRpc.A, "getBalance") { bad }
            try { c.getBalance(com.lockwallet.core.solana.ProgramIds.SYSTEM); fail(bad) } catch (e: RpcException) { assertEquals(RpcException.Kind.MALFORMED, e.kind) }
        }
        val err = RpcClient(FakeRpc.A) { _, _, _ -> """{"jsonrpc":"2.0","id":1,"error":{"code":429,"message":"Too many requests"}}""" }
        try { err.getBlockHeight(); fail() } catch (e: RpcException) { assertEquals(RpcException.Kind.RATE_LIMITED, e.kind) }
    }

    @Test fun explorerLinksAreMainnet() {
        assertEquals("https://explorer.solana.com/tx/abc", Cluster.MAINNET.explorerTx("abc"))
        assertEquals("https://explorer.solana.com/address/x", Cluster.MAINNET.explorerAddress("x"))
    }
}
