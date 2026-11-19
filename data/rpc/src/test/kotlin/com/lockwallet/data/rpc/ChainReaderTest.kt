package com.lockwallet.data.rpc

import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.state.StakeLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class ChainReaderTest {
    private val fx = MiniJson.parse(File(FakeRpc.fixtures(), "tx-oracle.json").readText()) as Map<*, *>
    private val keys = fx["keys"] as Map<*, *>
    private val acc = fx["accounts"] as Map<*, *>
    private fun k(n: String) = SolanaPublicKey.fromBase58(keys[n] as String)
    private fun b64(hex: String) = Base64.getEncoder().encodeToString(hex.hexBytes())
    private fun account(owner: SolanaPublicKey, hex: String, lamports: Long = 2_039_280) =
        """{"lamports":$lamports,"owner":"${owner.toBase58()}","data":["${b64(hex)}","base64"],"executable":false,"rentEpoch":0}"""

    private fun net(f: FakeRpc): NetworkManager {
        f.on(FakeRpc.A, "getGenesisHash") { "\"${Cluster.DEVNET.genesisHash}\"" }
        return NetworkManager(NetworkConfig(Cluster.DEVNET, FakeRpc.A, null), f)
    }

    @Test fun tokensWithPolicyAndAtaFlag() {
        val f = FakeRpc(); val n = net(f)
        val bob = k("bob")
        // bob owns a legacy ATA; a fabricated Token-2022 account with a PermanentDelegate mint must be refused.
        val evilMint = SolanaPublicKey.fromBase58("So11111111111111111111111111111111111111112")
        val evilMintHex = "00".repeat(44) + "06" + "01" + "00".repeat(36) + "00".repeat(83) + "01" + "0c00" + "2000" + "00".repeat(32)
        val evilAcctHex = evilMint.toByteArray().joinToString("") { "%02x".format(it) } + bob.toByteArray().joinToString("") { "%02x".format(it) } +
            "0a00000000000000" + "00".repeat(36) + "01" + "00".repeat(12) + "00".repeat(8) + "00".repeat(36)
        val evilAcct = SolanaPublicKey.fromBase58("ZtSqp8BQkKFvahawCS9Mf15gzFuedeWWDkYap3qQEe4")
        f.on(FakeRpc.A, "getTokenAccountsByOwner") { body ->
            if (body.contains(ProgramIds.SPL_TOKEN_LEGACY.toBase58()))
                """{"context":{"slot":1},"value":[{"pubkey":"${k("bob_ata").toBase58()}","account":${account(ProgramIds.SPL_TOKEN_LEGACY, acc["bob_ata_legacy_hex"] as String)}}]}"""
            else """{"context":{"slot":1},"value":[{"pubkey":"${evilAcct.toBase58()}","account":${account(ProgramIds.TOKEN_2022, evilAcctHex)}}]}"""
        }
        f.on(FakeRpc.A, "getMultipleAccounts") { body ->
            val ks = body.substringAfter("[[").substringBefore("]").split(",").map { it.trim('"') }
            ks.joinToString(",", """{"context":{"slot":1},"value":[""", "]}") { key ->
                when (key) {
                    k("mint").toBase58() -> account(ProgramIds.SPL_TOKEN_LEGACY, acc["mint_legacy_hex"] as String)
                    evilMint.toBase58() -> account(ProgramIds.TOKEN_2022, evilMintHex)
                    else -> "null" // Metaplex PDA: no metadata
                }
            }
        }
        val t = ChainReader(n).tokens(bob)
        assertEquals(2, t.size)
        val legacy = t.first { it.program == ProgramIds.SPL_TOKEN_LEGACY }
        assertTrue(legacy.sendable); assertTrue(legacy.isAta); assertEquals(6, legacy.decimals); assertEquals(5_000_000, legacy.state.amount)
        val evil = t.first { it.program == ProgramIds.TOKEN_2022 }
        assertFalse(evil.sendable); assertFalse(evil.closable)
        assertTrue(evil.verdict.reasons.single().startsWith("PermanentDelegate"))
    }

    @Test fun stakesFromSeedsWithLifecycleEvenWithoutGetProgramAccounts() {
        val f = FakeRpc(); val n = net(f)
        val payer = k("payer")
        f.on(FakeRpc.A, "getEpochInfo") { """{"absoluteSlot":10,"blockHeight":10,"epoch":1,"slotIndex":2,"slotsInEpoch":432000,"transactionCount":1}""" }
        f.on(FakeRpc.A, "getMultipleAccounts") { body ->
            val n2 = body.substringAfter("[[").substringBefore("]").split(",").size
            val first = account(ProgramIds.STAKE, acc["stake0_hex"] as String, 2_002_282_880)
            """{"context":{"slot":1},"value":[$first${",null".repeat(n2 - 1)}]}"""
        }
        f.on(FakeRpc.A, "getProgramAccounts") { throw RpcException(RpcException.Kind.RPC_ERROR, "disabled") }
        f.on(FakeRpc.A, "getInflationReward") { """[{"epoch":0,"effectiveSlot":5,"amount":1234,"postBalance":2002284114,"commission":7}]""" }
        val (s, ep) = ChainReader(n).stakes(payer)
        assertEquals(1L, ep.epoch)
        val v = s.single()
        assertEquals(ChainReader.stakeAddress(payer, 0), v.address); assertEquals("stake:0", v.seed)
        assertEquals(StakeLifecycle.INACTIVE, v.lifecycle) // deactivated at epoch 0, now epoch 1
        assertTrue(v.controlledBy(payer)); assertEquals(1234L, v.lastReward!!.amount)
        assertEquals(1, ChainReader(n).nextStakeSeed(payer))
    }

    @Test fun transactionDetailAndValidatorsParse() {
        val f = FakeRpc(); val c = RpcClient(FakeRpc.A, f)
        f.on(FakeRpc.A, "getTransaction") { """{"slot":1,"blockTime":1700000000,"meta":{"err":null,"fee":5000,"preBalances":[100,0],"postBalances":[50,45],
            "preTokenBalances":[],"postTokenBalances":[{"accountIndex":1,"mint":"${k("mint").toBase58()}","owner":"${k("bob").toBase58()}","uiTokenAmount":{"amount":"15","decimals":6,"uiAmount":0.000015,"uiAmountString":"0.000015"}}]},
            "transaction":{"message":{"accountKeys":["${k("payer").toBase58()}","${k("bob").toBase58()}"],"header":{},"instructions":[],"recentBlockhash":"x"},"signatures":["s"]}}""" }
        val d = c.getTransaction("s")!!
        assertEquals(5000L, d.fee); assertEquals(listOf(100L, 0L), d.preBalances); assertEquals(15L, d.tokenChanges.single().postUnits)
        f.on(FakeRpc.A, "getVoteAccounts") { """{"current":[{"votePubkey":"${k("vote").toBase58()}","nodePubkey":"${k("bob").toBase58()}","activatedStake":42,"commission":7,"lastVote":99,"epochVoteAccount":true,"epochCredits":[[1,2,3]],"rootSlot":1}],"delinquent":[]}""" }
        val v = c.getVoteAccounts().single()
        assertEquals(7, v.commission); assertFalse(v.delinquent); assertEquals(42L, v.activatedStake)
    }

    /** 5.0: epochCredits and the bps commission are parsed strictly; malformed optional fields reject the answer. */
    @Test fun voteAccountsCreditsAndBpsParsedStrictly() {
        val f = FakeRpc(); val c = RpcClient(FakeRpc.A, f)
        fun body(extra: String) = """{"current":[{"votePubkey":"${k("vote").toBase58()}","nodePubkey":"${k("bob").toBase58()}","activatedStake":42,"commission":5,"lastVote":99,"epochVoteAccount":true,"rootSlot":1$extra}],"delinquent":[]}"""
        f.on(FakeRpc.A, "getVoteAccounts") { body(""","epochCredits":[[10,500,100],[11,900,500]],"inflationRewardsCommissionBps":500""") }
        val v = c.getVoteAccounts().single()
        assertEquals(listOf(400L, 400L), v.epochCredits.map { it.earned }); assertEquals(500, v.commissionBps)
        f.on(FakeRpc.A, "getVoteAccounts") { body("") }
        assertEquals(emptyList<EpochCredit>(), c.getVoteAccounts().single().epochCredits)
        assertEquals(null, c.getVoteAccounts().single().commissionBps)
        for (bad in listOf(""","epochCredits":[[1,2]]""", ""","epochCredits":{"a":1}""", ""","inflationRewardsCommissionBps":10001""", ""","inflationRewardsCommissionBps":-1""")) {
            f.on(FakeRpc.A, "getVoteAccounts") { body(bad) }
            val e = runCatching { c.getVoteAccounts() }.exceptionOrNull()
            assertTrue(bad, e is RpcException && e.kind == RpcException.Kind.MALFORMED)
        }
        f.on(FakeRpc.A, "getVoteAccounts") { body(""","epochCredits":[${(1..50).joinToString(",") { "[$it,${it * 10},${it * 10 - 5}]" }}]""") }
        assertEquals(10, c.getVoteAccounts().single().epochCredits.size) // bounded
    }

    /** A-67: shape seen on real mainnet (5.0 review) — a sentinel row of u64::MAX must not reject the whole list. */
    @Test fun voteAccountsWithU64SentinelRowStillParse() {
        val f = FakeRpc(); val c = RpcClient(FakeRpc.A, f)
        val m = "18446744073709551615"
        f.on(FakeRpc.A, "getVoteAccounts") { """{"current":[{"votePubkey":"${k("vote").toBase58()}","nodePubkey":"${k("bob").toBase58()}","activatedStake":42,"commission":5,"lastVote":99,"epochVoteAccount":true,"rootSlot":1,"epochCredits":[[1062,91677889,88790020],[$m,$m,$m],[1064,630082545,93057615]],"inflationRewardsCommissionBps":500}],"delinquent":[]}""" }
        val v = c.getVoteAccounts().single()
        assertEquals(listOf(1062L, 1064L), v.epochCredits.map { it.epoch })
        f.on(FakeRpc.A, "getVoteAccounts") { """{"current":[{"votePubkey":"${k("vote").toBase58()}","nodePubkey":"${k("bob").toBase58()}","activatedStake":42,"commission":5,"lastVote":99,"epochVoteAccount":true,"rootSlot":1,"epochCredits":[[1,2,"x"]]}],"delinquent":[]}""" }
        assertTrue(runCatching { c.getVoteAccounts() }.exceptionOrNull() is RpcException)
    }
}
