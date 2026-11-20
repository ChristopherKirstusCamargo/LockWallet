package com.lockwallet.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lockwallet.core.solana.Base58
import com.lockwallet.core.solana.tx.LegacyMessage
import com.lockwallet.core.solana.tx.TransactionState
import com.lockwallet.core.solana.tx.WireTransaction
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.LocalSecureProvider
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.data.rpc.Cluster
import com.lockwallet.data.rpc.RpcException
import com.lockwallet.data.rpc.RpcTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Scripted mainnet endpoint for the app layer (never touches the network). */
class AppFakeRpc : RpcTransport {
    val handlers = HashMap<String, (String) -> String>()
    val sent = mutableListOf<String>()
    /** address → (owner program, lamports, data) */
    val accounts = HashMap<String, Triple<String, Long, ByteArray>>()
    val simulatedAccounts = HashMap<String, Triple<String, Long, ByteArray>>()
    fun simulatedAccountJson(k: String): String = simulatedAccounts[k]?.let { (o, l, d) ->
        """{"lamports":$l,"owner":"$o","data":["${java.util.Base64.getEncoder().encodeToString(d)}","base64"],"executable":false,"rentEpoch":0}"""
    } ?: acct(k)
    private fun keys(body: String): List<String> = Regex("\"([1-9A-HJ-NP-Za-km-z]{32,44})\"").findAll(body.substringAfter("\"params\"")).map { it.groupValues[1] }.toList()
    private fun acct(k: String): String = accounts[k]?.let { (o, l, d) ->
        """{"lamports":$l,"owner":"$o","data":["${java.util.Base64.getEncoder().encodeToString(d)}","base64"],"executable":false,"rentEpoch":0}"""
    } ?: "null"
    override fun post(url: String, body: String, maxBytes: Int): String {
        val m = Regex("\"method\":\"(\\w+)\"").find(body)!!.groupValues[1]
        val h = handlers[m] ?: throw RpcException(RpcException.Kind.TRANSPORT, "down")
        val rid = Regex("\"id\":(\\d+)").find(body)!!.groupValues[1]
        return """{"jsonrpc":"2.0","id":$rid,"result":${h(body)}}"""
    }
    init {
        handlers["getGenesisHash"] = { "\"${Cluster.MAINNET.genesisHash}\"" }
        handlers["getBalance"] = { """{"context":{"slot":1},"value":2000000000}""" }
        handlers["getMinimumBalanceForRentExemption"] = { "890880" }
        handlers["getAccountInfo"] = { b -> """{"context":{"slot":1},"value":${acct(keys(b).first())}}""" }
        handlers["getMultipleAccounts"] = { b -> """{"context":{"slot":1},"value":[${keys(b).joinToString(",") { acct(it) }}]}""" }
        handlers["getTokenAccountsByOwner"] = { b ->
            val ks = keys(b); val owner = com.lockwallet.core.solana.SolanaPublicKey.fromBase58(ks[0]).toByteArray(); val prog = ks[1]
            "{\"context\":{\"slot\":1},\"value\":[" + accounts.filter { (_, a) -> a.first == prog && a.third.size >= 165 && a.third.copyOfRange(32, 64).contentEquals(owner) }
                .map { (k, _) -> "{\"pubkey\":\"$k\",\"account\":${acct(k)}}" }.joinToString(",") + "]}"
        }
        handlers["getFeeForMessage"] = { """{"context":{"slot":1},"value":5000}""" }
        handlers["getLatestBlockhash"] = { """{"context":{"slot":1},"value":{"blockhash":"4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY","lastValidBlockHeight":250}}""" }
        handlers["getBlockHeight"] = { "100" }
        handlers["isBlockhashValid"] = { """{"context":{"slot":1},"value":true}""" }
        handlers["getRecentPrioritizationFees"] = { """[{"slot":1,"prioritizationFee":0},{"slot":2,"prioritizationFee":50000}]""" }
        handlers["simulateTransaction"] = { """{"context":{"slot":1},"value":{"err":null,"logs":[],"unitsConsumed":150,"accounts":null}}""" }
        handlers["getSignaturesForAddress"] = { "[]" }
        handlers["getEpochInfo"] = { """{"epoch":20,"slotIndex":100,"slotsInEpoch":432000,"absoluteSlot":8640100,"blockHeight":100}""" }
        handlers["getVoteAccounts"] = { """{"current":[{"votePubkey":"4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU","nodePubkey":"GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv","commission":5,"activatedStake":5000000000000,"lastVote":1,"epochVoteAccount":true,"epochCredits":[],"rootSlot":0}],"delinquent":[]}""" }
        handlers["getStakeMinimumDelegation"] = { """{"context":{"slot":1},"value":1000000000}""" }
        handlers["getInflationReward"] = { b -> "[" + keys(b).joinToString(",") { "null" } + "]" }
        handlers["getSignatureStatuses"] = { """{"context":{"slot":1},"value":[{"slot":5,"confirmations":null,"err":null,"confirmationStatus":"finalized"}]}""" }
        handlers["sendTransaction"] = { body ->
            val b64 = Regex("\"params\":\\[\"([A-Za-z0-9+/=]+)\"").find(body)!!.groupValues[1]
            sent += b64
            val sig = WireTransaction.split(java.util.Base64.getDecoder().decode(b64)).first.first()
            "\"${Base58.encode(sig)}\""
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class ChainFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val rpc = AppFakeRpc()

    private fun controller(): AppController {
        val p = LocalSecureProvider(tmp.newFolder(), SoftKek(), Clock())
        return AppController(p, DeviceSecurityCapabilityManager(ctx, p), CoroutineScope(Dispatchers.Default), Prefs(ctx), null, { _, _ -> true },
            transport = rpc, dataDir = tmp.newFolder()).also { it.start() }
    }

    private fun AppController.await() {
        val end = System.currentTimeMillis() + 20_000
        Thread.sleep(30)
        while (busy && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("timed out", !busy)
    }

    private fun restore(c: AppController) {
        c.startFlow(FlowMode.RESTORE)
        c.submitRestore(RestoreKind.PHRASE, "neither lonely flavor argue grass remind eye tag avocado spot unusual intact".toCharArray()); c.await()
        c.chooseAccount(c.draft!!.material!!.discover(1).first())
        c.submitName("Teste")
        c.setNewPin("839174".toCharArray())
        c.sealNewVault(AuthMode.PIN_ONLY); c.await()
    }

    @Test fun sendSolReviewSignPersistAndConfirm() {
        val c = controller(); restore(c)
        runBlocking { c.chain.refreshHome() }
        assertEquals(2_000_000_000L, c.chain.balance.valueOrNull())
        val to = "GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv"
        c.chain.reviewSol(to, "0,5", max = false); c.await()
        assertEquals(c.error, Screen.Review, c.screen)
        val p = c.chain.prepared!!
        assertTrue(p.signable)
        assertNotNull(c.chain.review!!.ackText) // first send to this address
        assertTrue(c.chain.review!!.warnings.isNotEmpty()) // destination is a new account
        c.chain.authorize(); c.await()
        assertTrue(c.screen is Screen.Authorize)
        c.submitPin("839174".toCharArray(), (c.screen as Screen.Authorize).purpose); c.await()
        val st = c.screen as Screen.TxStatus
        assertEquals("same bytes to every endpoint", 1, rpc.sent.distinct().size)
        val (sigs, msg) = WireTransaction.split(java.util.Base64.getDecoder().decode(rpc.sent.distinct().single()))
        assertArrayEquals(p.messageBytes, msg)
        assertEquals(Base58.encode(sigs.single()), st.signature)
        assertEquals(500_000_000L, LegacyMessage.parse(msg).let { java.nio.ByteBuffer.wrap(it.instructions.last().data, 4, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long })
        runBlocking { c.chain.reconcileTick() }
        assertEquals(TransactionState.FINALIZED, c.chain.record(st.signature)!!.state)
        // Same recipient again: no first-send acknowledgement any more.
        c.chain.reviewSol(to, "0,1", max = false); c.await()
        assertEquals(null, c.chain.review!!.ackText)
        c.chain.cancelReview()
    }

    @Test fun badInputsAndInsufficientFundsNeverReachReview() {
        val c = controller(); restore(c)
        c.chain.reviewSol("not-an-address", "1", false); c.await()
        assertEquals(com.lockwallet.app.ui.S.BAD_ADDRESS, c.error)
        c.chain.reviewSol("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", "1e3", false); c.await()
        assertEquals(com.lockwallet.app.ui.S.BAD_AMOUNT, c.error)
        c.chain.reviewSol("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", "5", false); c.await()
        assertTrue(c.error!!.startsWith("Saldo insuficiente"))
        c.chain.reviewSol("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", "0,0001", false); c.await()
        assertTrue(c.error!!.contains("conta nova"))
        assertEquals(0, rpc.sent.size)
    }

    @Test fun simulationFailureShowsBlockedReviewWithoutSignButton() {
        rpc.handlers["simulateTransaction"] = { """{"context":{"slot":1},"value":{"err":{"InstructionError":[0,{"Custom":1}]},"logs":["x"],"unitsConsumed":0,"accounts":null}}""" }
        val c = controller(); restore(c)
        c.chain.reviewSol("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", "0,5", false); c.await()
        assertEquals(Screen.Review, c.screen)
        assertTrue(!c.chain.prepared!!.signable)
        c.chain.authorize(); c.await()
        assertEquals(Screen.Review, c.screen)
    }

    @Test fun customRpcMustBeHttpsAndMainnetAndCanBeReset() {
        val c = controller(); restore(c)
        c.chain.saveEndpoints("http://insecure.example", ""); c.await()
        assertEquals(com.lockwallet.app.ui.S.URL_BAD, c.error)
        rpc.handlers["getGenesisHash"] = { "\"${Cluster.DEVNET.genesisHash}\"" }
        c.chain.saveEndpoints("https://test-cluster.example", ""); c.await()
        assertEquals(com.lockwallet.app.ui.S.urlOther(Cluster.DEVNET), c.error)
        assertEquals(com.lockwallet.data.rpc.NetworkConfig.MAINNET_DEFAULT.primaryUrl, c.chain.config.primaryUrl)
        rpc.handlers["getGenesisHash"] = { "\"${Cluster.MAINNET.genesisHash}\"" }
        c.chain.saveEndpoints("https://my-node.example", ""); c.await()
        assertEquals(c.error, "https://my-node.example", c.chain.config.primaryUrl)
        assertTrue(c.prefs.customRpc)
        c.chain.resetEndpoints()
        assertEquals(com.lockwallet.data.rpc.NetworkConfig.MAINNET_DEFAULT.primaryUrl, c.chain.config.primaryUrl)
        assertEquals(Cluster.MAINNET, c.chain.config.cluster)
    }

    /** Own transactions carry a compute budget (they would not land on mainnet without one) and the fee shown covers it. */
    @Test fun ownSendCarriesComputeBudgetAndShowsItsFee() {
        val c = controller(); restore(c)
        c.chain.reviewSol("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv", "0,5", false); c.await()
        val pt = c.chain.prepared!!
        val msg = pt.analysis.message!!
        val cb = com.lockwallet.core.solana.ProgramIds.COMPUTE_BUDGET
        assertTrue(msg.instructions.count { msg.accountKeys[it.programIndex] == cb } == 2)
        val bound = com.lockwallet.core.solana.tx.FeeMath.maxFee(msg)!!
        assertTrue("fee ${pt.feeLamports} must cover $bound", pt.feeLamports!! >= bound)
        assertTrue(bound > 5000 && bound <= 5000 + 1_000_000)
    }

    private val mintA = com.lockwallet.core.solana.SolanaPublicKey.fromBase58("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")
    private val mintB = com.lockwallet.core.solana.SolanaPublicKey.fromBase58("2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo")

    @Test fun tokenSendCreatesRecipientAtaAndToken2022HookIsBlocked() {
        val c = controller(); restore(c)
        val me = c.chain.wallet()!!.address
        val P = com.lockwallet.core.solana.ProgramIds
        val Pda = com.lockwallet.core.solana.Pda
        rpc.accounts[mintA.toBase58()] = Triple(P.SPL_TOKEN_LEGACY.toBase58(), 1_461_600, TokenImages.mint(6))
        val myAta = Pda.associatedTokenAddress(me, mintA, P.SPL_TOKEN_LEGACY)
        rpc.accounts[myAta.toBase58()] = Triple(P.SPL_TOKEN_LEGACY.toBase58(), 2_039_280, TokenImages.account(mintA.toByteArray(), me.toByteArray(), 5_000_000))
        // Token-2022 mint with a TransferHook (type 14): must be shown and blocked.
        rpc.accounts[mintB.toBase58()] = Triple(P.TOKEN_2022.toBase58(), 2_000_000, TokenImages.mint(0, t22Exts = listOf(14 to ByteArray(64))))
        val myB = Pda.associatedTokenAddress(me, mintB, P.TOKEN_2022)
        rpc.accounts[myB.toBase58()] = Triple(P.TOKEN_2022.toBase58(), 2_100_000, TokenImages.account(mintB.toByteArray(), me.toByteArray(), 10, t22 = true))
        runBlocking { c.chain.refreshTokens() }
        val list = c.chain.tokens.valueOrNull()!!
        assertEquals(2, list.size)
        val a = list.single { it.state.mint == mintA }; val b = list.single { it.state.mint == mintB }
        assertTrue(a.sendable && a.isAta)
        assertTrue(!b.verdict.allowed && !b.sendable)
        assertTrue(b.verdict.reasons.single().startsWith("TransferHook"))
        // Send 1.5 A to a wallet without an ATA: CreateIdempotent + TransferChecked, shown in the review.
        val bob = "GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv"
        c.chain.reviewToken(myAta.toBase58(), bob, "1,5"); c.await()
        assertEquals(c.error, Screen.Review, c.screen)
        val p = c.chain.prepared!!
        assertTrue(p.blockers.toString() + p.analysis.findings, p.signable)
        assertEquals(listOf("CreateAta", "TokenTransfer"), p.analysis.ops.map { it::class.simpleName }.filterNot { it!!.startsWith("ComputeUnit") })
        assertTrue(c.chain.review!!.warnings.any { it.contains("será criada") })
        c.chain.cancelReview()
        c.chain.reviewToken(myAta.toBase58(), bob, "6"); c.await()
        assertTrue(c.error!!.startsWith("Saldo insuficiente"))
        c.chain.reviewToken(myB.toBase58(), bob, "1"); c.await()
        assertTrue(c.error!!.startsWith(com.lockwallet.app.ui.S.TOKEN_BLOCKED))
        // Close an empty account: deposit back to the wallet.
        rpc.accounts[myAta.toBase58()] = Triple(P.SPL_TOKEN_LEGACY.toBase58(), 2_039_280, TokenImages.account(mintA.toByteArray(), me.toByteArray(), 0))
        c.chain.reviewClose(myAta.toBase58()); c.await()
        assertEquals(c.error, Screen.Review, c.screen)
        assertTrue(c.chain.prepared!!.signable)
        assertEquals("TokenClose", c.chain.prepared!!.analysis.ops.last()::class.simpleName)
        c.chain.cancelReview()
        assertEquals(0, rpc.sent.size)
    }

    private fun stakeImage(me: ByteArray, voter: ByteArray?, stake: Long, act: Long, deact: Long): ByteArray {
        fun le(v: Long, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 0xff).toByte() }
        var b = le(if (voter == null) 1 else 2, 4) + le(2_282_880, 8) + me + me + le(0, 8) + le(0, 8) + ByteArray(32)
        if (voter != null) b = b + voter + le(stake, 8) + le(act, 8) + le(deact, 8) + ByteArray(8) + le(0, 8) + ByteArray(1)
        return b + ByteArray(200 - b.size)
    }

    @Test fun nativeStakingLifecycleThroughTheAnalyzer() {
        val c = controller(); restore(c)
        val me = c.chain.wallet()!!.address
        val P = com.lockwallet.core.solana.ProgramIds
        val vote = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
        rpc.handlers["getMinimumBalanceForRentExemption"] = { b -> if (b.contains("[200")) "2282880" else "890880" }
        // Create + initialize + delegate.
        c.chain.reviewStakeCreate("0,5", vote); c.await()
        assertTrue(c.error!!.contains("mínimo de delegação"))
        c.chain.reviewStakeCreate("1,5", "11111111111111111111111111111112"); c.await()
        assertEquals(com.lockwallet.app.ui.S.UNKNOWN_VALIDATOR, c.error)
        c.chain.reviewStakeCreate("1,5", vote); c.await()
        assertEquals(c.error, Screen.Review, c.screen)
        val p = c.chain.prepared!!
        assertTrue(p.blockers.toString() + p.analysis.findings, p.signable)
        assertEquals(listOf("CreateAccountWithSeed", "StakeInitialize", "StakeDelegate"), p.analysis.ops.map { it::class.simpleName }.filterNot { it!!.startsWith("ComputeUnit") })
        c.chain.authorize(); c.await()
        c.submitPin("839174".toCharArray(), (c.screen as Screen.Authorize).purpose); c.await()
        assertTrue(c.screen is Screen.TxStatus)
        assertEquals("same bytes to every endpoint", 1, rpc.sent.distinct().size)
        runBlocking { c.chain.reconcileTick() }
        // Accounts on chain: seed 0 active, seed 1 and 2 inactive (same state ⇒ mergeable).
        val v = com.lockwallet.core.solana.SolanaPublicKey.fromBase58(vote).toByteArray()
        val s0 = com.lockwallet.data.rpc.ChainReader.stakeAddress(me, 0).toBase58()
        val s1 = com.lockwallet.data.rpc.ChainReader.stakeAddress(me, 1).toBase58()
        val s2 = com.lockwallet.data.rpc.ChainReader.stakeAddress(me, 2).toBase58()
        rpc.accounts[s0] = Triple(P.STAKE.toBase58(), 3_002_282_880, stakeImage(me.toByteArray(), v, 3_000_000_000, 10, -1))
        rpc.accounts[s1] = Triple(P.STAKE.toBase58(), 1_002_282_880, stakeImage(me.toByteArray(), v, 1_000_000_000, 10, 15))
        rpc.accounts[s2] = Triple(P.STAKE.toBase58(), 2_282_880, stakeImage(me.toByteArray(), null, 0, 0, -1))
        runBlocking { c.chain.refreshStakes() }
        val list = c.chain.stakes.valueOrNull()!!.first
        assertEquals(listOf("ACTIVE", "INACTIVE", "INITIALIZED"), list.map { it.lifecycle.name })
        fun ops() = c.chain.prepared!!.let { assertTrue(it.blockers.toString() + it.analysis.findings, it.signable); it.analysis.ops.map { o -> o::class.simpleName }.filterNot { o -> o!!.startsWith("ComputeUnit") } }
        c.chain.reviewDeactivate(s0); c.await(); assertEquals(listOf("StakeDeactivate"), ops()); c.chain.cancelReview()
        c.chain.reviewWithdraw(s0); c.await(); assertEquals(com.lockwallet.app.ui.S.STAKE_NOT_INACTIVE, c.error)
        c.chain.reviewWithdraw(s1); c.await(); assertEquals(listOf("StakeWithdraw"), ops()); c.chain.cancelReview()
        c.chain.reviewSplit(s0, "1"); c.await(); assertEquals(listOf("CreateAccountWithSeed", "StakeSplit"), ops()); c.chain.cancelReview()
        c.chain.reviewSplit(s0, "2,5"); c.await(); assertTrue(c.error!!.startsWith("Valor alto demais"))
        assertEquals(listOf(s2), c.chain.mergeCandidates(list[1], me).map { it.address.toBase58() })
        c.chain.reviewMerge(s1, s2); c.await(); assertEquals(listOf("StakeMerge"), ops()); c.chain.cancelReview()
        c.chain.reviewMerge(s0, s1); c.await(); assertEquals(com.lockwallet.app.ui.S.MERGE_INCOMPATIBLE, c.error)
        assertEquals("same bytes to every endpoint", 1, rpc.sent.distinct().size)
    }
}

/** Hand-built SPL account images (layout per spl-token / token-2022 state). */
object TokenImages {
    private fun le(v: Long, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 0xff).toByte() }
    fun mint(decimals: Int, freeze: ByteArray? = null, t22Exts: List<Pair<Int, ByteArray>>? = null): ByteArray {
        var b = le(1, 4) + ByteArray(32) { 9 } + le(1_000_000, 8) + byteArrayOf(decimals.toByte(), 1) +
            (if (freeze == null) ByteArray(36) else le(1, 4) + freeze)
        if (t22Exts != null) { b = b + ByteArray(165 - 82) + byteArrayOf(1); t22Exts.forEach { (t, d) -> b = b + le(t.toLong(), 2) + le(d.size.toLong(), 2) + d } }
        return b
    }
    fun account(mint: ByteArray, owner: ByteArray, amount: Long, t22: Boolean = false): ByteArray {
        var b = mint + owner + le(amount, 8) + ByteArray(36) + byteArrayOf(1) + ByteArray(12) + le(0, 8) + ByteArray(36)
        if (t22) b = b + byteArrayOf(2) + le(7, 2) + le(0, 2)
        return b
    }
}
