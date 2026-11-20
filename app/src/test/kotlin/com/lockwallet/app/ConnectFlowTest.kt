package com.lockwallet.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lockwallet.app.connect.AuthorizeAnswer
import com.lockwallet.app.connect.AuthorizeRequestModel
import com.lockwallet.app.connect.ConnectModel
import com.lockwallet.app.connect.DappIdentity
import com.lockwallet.app.connect.SignAnswer
import com.lockwallet.app.connect.SignKind
import com.lockwallet.app.connect.SignRequestModel
import com.lockwallet.app.connect.SiwsRequest
import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.ix.SystemIx
import com.lockwallet.core.solana.ix.StakeIx
import com.lockwallet.core.solana.tx.AccountMeta
import com.lockwallet.core.solana.tx.Blockhash
import com.lockwallet.core.solana.tx.Instruction
import com.lockwallet.core.solana.tx.LegacyMessage
import com.lockwallet.core.solana.tx.WireTransaction
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.LocalSecureProvider
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.storage.WalletRole
import com.lockwallet.data.rpc.Cluster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class RecAuth : AuthorizeAnswer {
    val calls = mutableListOf<String>(); var scope: ByteArray? = null; var siwsSig: ByteArray? = null; var siwsMsg: ByteArray? = null
    override fun approve(address: ByteArray, label: String, scope: ByteArray, siwsMessage: ByteArray?, siwsSignature: ByteArray?) {
        calls += "approve"; this.scope = scope; siwsMsg = siwsMessage; siwsSig = siwsSignature
    }
    override fun decline() { calls += "decline" }
    override fun clusterNotSupported() { calls += "cluster" }
}

class RecSign : SignAnswer {
    val calls = mutableListOf<String>(); var payloads: Array<ByteArray>? = null; var sigs: Array<ByteArray>? = null
    override fun signedPayloads(p: Array<ByteArray>) { calls += "signed"; payloads = p }
    override fun signatures(s: Array<ByteArray>) { calls += "sigs"; sigs = s }
    override fun notSubmitted(s: Array<ByteArray>) { calls += "notSubmitted" }
    override fun decline() { calls += "decline" }
    override fun invalid(valid: BooleanArray) { calls += "invalid" }
    override fun authorizationNotValid() { calls += "authInvalid" }
    override fun tooMany() { calls += "tooMany" }
}

@RunWith(RobolectricTestRunner::class)
class ConnectFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val rpc = AppFakeRpc()
    private val dapp = DappIdentity("Exemplo dApp", "https://exemplo.app", "app.exemplo")
    private val bob = SolanaPublicKey.fromBase58("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")
    private val unknownProgram = SolanaPublicKey.fromBase58("9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin")

    private fun controller(role: WalletRole): AppController {
        val p = LocalSecureProvider(tmp.newFolder(), SoftKek(), Clock())
        val c = AppController(p, DeviceSecurityCapabilityManager(ctx, p), CoroutineScope(Dispatchers.Default), Prefs(ctx), null, { _, _ -> true },
            transport = rpc, dataDir = tmp.newFolder()).also { it.start() }
        c.startFlow(FlowMode.RESTORE)
        c.draft!!.role = role
        c.submitRestore(RestoreKind.PHRASE, "neither lonely flavor argue grass remind eye tag avocado spot unusual intact".toCharArray()); c.await()
        c.chooseAccount(c.draft!!.material!!.discover(1).first())
        c.submitName("Connect")
        c.setNewPin("listOf(8, 3, 5 + 4, 2 - 1, 14 - 7, 2 * 2).joinToString("")".toCharArray())
        c.sealNewVault(AuthMode.PIN_ONLY); c.await()
        return c
    }

    private fun AppController.await() {
        val end = System.currentTimeMillis() + 20_000
        Thread.sleep(30)
        while (busy && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("timed out", !busy)
    }

    private fun AppController.authorizeDapp(): ByteArray {
        val a = RecAuth()
        connect.enqueue(AuthorizeRequestModel(dapp, "solana:mainnet", Cluster.MAINNET, null, a))
        connect.approveAuthorize()
        assertEquals(listOf("approve"), a.calls)
        return a.scope!!
    }

    private fun unsignedTx(me: SolanaPublicKey, vararg ix: Instruction) =
        WireTransaction.unsigned(LegacyMessage.compile(me, Blockhash.fromBase58("4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY"), ix.toList()))

    private fun simulateWith(walletPostLamports: Long) {
        rpc.handlers["isBlockhashValid"] = { """{"context":{"slot":1},"value":true}""" }
        rpc.handlers["simulateTransaction"] = { body ->
            val raw = body.substringAfter("\"addresses\":[", "").substringBefore("]")
            val addresses = Regex("\"([1-9A-HJ-NP-Za-km-z]{32,44})\"").findAll(raw).map { it.groupValues[1] }.toList()
            val accounts = addresses.mapIndexed { index, address ->
                if (index == 0) """{"lamports":$walletPostLamports,"owner":"11111111111111111111111111111111","data":["","base64"],"executable":false,"rentEpoch":0}"""
                else rpc.simulatedAccountJson(address)
            }
            """{"context":{"slot":1},"value":{"err":null,"logs":[],"unitsConsumed":300,"accounts":[${accounts.joinToString(",")}]}}"""
        }
    }

    private fun stakeState(staker: SolanaPublicKey, withdrawer: SolanaPublicKey, delegated: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(200).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putInt(2)
        b.putLong(2_282_880L)
        b.put(staker.toByteArray())
        b.put(withdrawer.toByteArray())
        b.putLong(0L)
        b.putLong(-1L)
        b.put(SolanaPublicKey.fromBase58("11111111111111111111111111111111").toByteArray())
        b.put(SolanaPublicKey.fromBase58("Vote111111111111111111111111111111111111111").toByteArray())
        b.putLong(delegated)
        b.putLong(1L)
        b.putLong(-1L)
        return b.array()
    }

    private fun awaitReviews(c: AppController) {
        val end = System.currentTimeMillis() + 10_000
        while (c.connect.reviews == null && c.connect.reviewError == null && System.currentTimeMillis() < end) Thread.sleep(10)
        assertNotNull(c.connect.reviewError ?: "ok", c.connect.reviews)
    }

    private fun AppController.pin() {
        assertTrue(screen.toString(), screen is Screen.Authorize)
        submitPin("listOf(8, 3, 5 + 4, 2 - 1, 14 - 7, 2 * 2).joinToString("")".toCharArray(), (screen as Screen.Authorize).purpose); await()
    }

    @Test fun authorizeThenSignTransactionThroughFirewallAndPin() {
        val c = controller(WalletRole.CONNECT)
        val me = c.wallets.single().address
        rpc.accounts[me.toBase58()] = Triple("11111111111111111111111111111111", 2_000_000_000L, ByteArray(0))
        val scope = c.authorizeDapp()
        val session = c.connect.sessionFor(scope, me.toByteArray(), "solana:mainnet")!!
        simulateWith(2_000_000_000L - 1000 - 5000)
        val s = RecSign()
        c.connect.enqueue(SignRequestModel(dapp, session, SignKind.TRANSACTIONS, listOf(unsignedTx(me, SystemIx.transfer(me, bob, 1000))), s))
        awaitReviews(c)
        val rv = c.connect.reviews!!.single()
        assertTrue(rv.prepProblems.toString() + rv.postProblems + rv.verdict.blockers, rv.signable)
        assertEquals(-6000L, rv.solDelta)
        c.connect.approveSign()
        c.pin()
        assertEquals(listOf("signed"), s.calls)
        val (sigs, msg) = WireTransaction.split(s.payloads!!.single())
        assertTrue(Ed25519.verify(sigs.single(), 0, me.toByteArray(), 0, msg, 0, msg.size))
        assertTrue("sign-only never broadcasts", rpc.sent.isEmpty())
        // Revoked in the app ⇒ the next request with that token is not valid.
        c.connect.revoke(session.id)
        assertNull(c.connect.sessionFor(scope, me.toByteArray(), "solana:mainnet"))
    }

    @Test fun unknownProgramWithWritableStakeAccountIsBlockedBeforeSimulation() {
        val c = controller(WalletRole.CONNECT)
        val me = c.wallets.single().address
        val session = c.connect.sessionFor(c.authorizeDapp(), null, null)!!
        val stake = bob
        rpc.accounts[me.toBase58()] = Triple(ProgramIds.SYSTEM.toBase58(), 2_000_000_000L, ByteArray(0))
        rpc.accounts[stake.toBase58()] = Triple(ProgramIds.STAKE.toBase58(), 2_000_000_000L, stakeState(me, me, 1_000_000_000L))
        val raw = Instruction(unknownProgram, listOf(AccountMeta(me, true, true), AccountMeta(stake, false, true)), byteArrayOf(7, 7))
        val answer = RecSign()
        c.connect.enqueue(SignRequestModel(dapp, session, SignKind.TRANSACTIONS, listOf(unsignedTx(me, raw)), answer))
        awaitReviews(c)
        val review = c.connect.reviews!!.single()
        assertFalse(review.signable)
        assertTrue(com.lockwallet.core.solana.dapp.DappBlock.UNKNOWN_PROGRAM in review.verdict.blockers)
        assertTrue(answer.calls.isEmpty())
    }

    @Test fun changedStakeDelegationIsRejectedByPostStateComparison() {
        val c = controller(WalletRole.CONNECT)
        val me = c.wallets.single().address
        val session = c.connect.sessionFor(c.authorizeDapp(), null, null)!!
        val stake = bob
        val vote = SolanaPublicKey.fromBase58("Vote111111111111111111111111111111111111111")
        val before = stakeState(me, me, 1_000_000_000L)
        val after = stakeState(me, me, 900_000_000L)
        rpc.accounts[me.toBase58()] = Triple(ProgramIds.SYSTEM.toBase58(), 2_000_000_000L, ByteArray(0))
        rpc.accounts[stake.toBase58()] = Triple(ProgramIds.STAKE.toBase58(), 2_000_000_000L, before)
        rpc.simulatedAccounts[stake.toBase58()] = Triple(ProgramIds.STAKE.toBase58(), 2_000_000_000L, after)
        simulateWith(2_000_000_000L)
        val answer = RecSign()
        c.connect.enqueue(SignRequestModel(dapp, session, SignKind.TRANSACTIONS, listOf(unsignedTx(me, StakeIx.delegate(stake, vote, me))), answer))
        awaitReviews(c)
        val review = c.connect.reviews!!.single()
        assertFalse(review.signable)
        assertTrue(review.postProblems.any { it.contains(stake.toBase58()) })
        assertTrue(answer.calls.isEmpty())
    }

    @Test fun unknownProgramCannotBeOverriddenByRawAcknowledgement() {
        val c = controller(WalletRole.CONNECT)
        val me = c.wallets.single().address
        val session = c.connect.sessionFor(c.authorizeDapp(), null, null)!!
        val raw = Instruction(unknownProgram, listOf(AccountMeta(me, true, true)), byteArrayOf(7, 7))
        val answer = RecSign()
        c.connect.enqueue(SignRequestModel(dapp, session, SignKind.SIGN_AND_SEND, listOf(unsignedTx(me, raw)), answer))
        awaitReviews(c)
        val review = c.connect.reviews!!.single()
        assertFalse(review.signable)
        assertTrue(com.lockwallet.core.solana.dapp.DappBlock.UNKNOWN_PROGRAM in review.verdict.blockers)
        c.connect.rawAck = true
        c.connect.approveSign()
        assertTrue("blocked program must never reach PIN authorization", c.screen !is Screen.Authorize)
        assertTrue(answer.calls.isEmpty())
        c.connect.decline()
        assertEquals(listOf("decline"), answer.calls)
    }

    @Test fun testClustersVaultWalletsAndBadMessagesAreRefused() {
        val c = controller(WalletRole.VAULT)
        // Test clusters / unknown chain: rejected before anything is shown (real money only).
        for (chain in listOf("solana:localnet", "ethereum:1", "")) {
            val a = RecAuth()
            c.connect.enqueue(AuthorizeRequestModel(dapp, chain, ConnectModel.clusterOf(chain), null, a))
            assertEquals(chain, listOf("cluster"), a.calls)
        }
        // Known test clusters: explained to the user, and approving is still refused (real money only, no test networks).
        for (chain in listOf("solana:devnet", "solana:testnet")) {
            val a = RecAuth()
            c.connect.enqueue(AuthorizeRequestModel(dapp, chain, ConnectModel.clusterOf(chain), null, a))
            assertTrue(chain, c.connect.reviewError!!.contains("rede principal"))
            c.connect.approveAuthorize()
            assertEquals(chain, listOf("cluster"), a.calls)
            assertNull(c.connect.current)
        }
        // Lock Vault wallets are never offered to dApps.
        val a2 = RecAuth()
        c.connect.enqueue(AuthorizeRequestModel(dapp, "solana:mainnet", Cluster.MAINNET, null, a2))
        assertTrue(c.connect.connectWallets().isEmpty())
        c.connect.approveAuthorize()
        assertTrue(a2.calls.isEmpty())
        c.connect.decline()
        assertEquals(listOf("decline"), a2.calls)
    }

    @Test fun siwsAndMessagesSignTextOnly() {
        val c = controller(WalletRole.CONNECT)
        val me = c.wallets.single().address
        val a = RecAuth()
        val siws = SiwsRequest("exemplo.app", "Entrar", "https://exemplo.app", "solana:mainnet", null, null, null, "n1") { addr ->
            "exemplo.app wants you to sign in with your Solana account:\n${com.lockwallet.core.solana.Base58.encode(addr)}\n\nEntrar\n\nNonce: n1"
        }
        c.connect.enqueue(AuthorizeRequestModel(dapp, "solana:mainnet", Cluster.MAINNET, siws, a))
        c.connect.approveAuthorize()
        c.pin()
        assertEquals(listOf("approve"), a.calls)
        assertTrue(Ed25519.verify(a.siwsSig!!, 0, me.toByteArray(), 0, a.siwsMsg!!, 0, a.siwsMsg!!.size))
        val session = c.connect.sessionFor(a.scope, me.toByteArray(), "solana:mainnet")!!
        val s = RecSign()
        val text = "Olá, dApp".toByteArray()
        c.connect.enqueue(SignRequestModel(dapp, session, SignKind.MESSAGES, listOf(text), s))
        c.connect.approveSign(); c.pin()
        assertEquals(listOf("signed"), s.calls)
        val out = s.payloads!!.single()
        assertArrayEquals(text, out.copyOfRange(0, text.size))
        assertTrue(Ed25519.verify(out, text.size, me.toByteArray(), 0, text, 0, text.size))
        // A transaction disguised as a "message" is refused.
        val s2 = RecSign()
        val tx = LegacyMessage.compile(me, Blockhash(ByteArray(32) { 1 }), listOf(SystemIx.transfer(me, bob, 1))).serialize()
        c.connect.enqueue(SignRequestModel(dapp, session, SignKind.MESSAGES, listOf(tx), s2))
        c.connect.approveSign()
        assertEquals(listOf("invalid"), s2.calls)
    }
}
