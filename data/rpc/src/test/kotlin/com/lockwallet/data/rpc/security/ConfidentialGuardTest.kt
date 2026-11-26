package com.lockwallet.data.rpc.security

import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.data.rpc.Cluster
import com.lockwallet.data.rpc.FakeRpc
import com.lockwallet.data.rpc.JsonOut
import com.lockwallet.data.rpc.NetworkConfig
import com.lockwallet.data.rpc.NetworkManager
import com.lockwallet.data.rpc.RpcException
import com.lockwallet.data.rpc.TxCoordinator
import com.lockwallet.data.rpc.TxJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Confidential balance (Privacy Rings): the public wallet may lose at most what was reviewed (the deposit, for
 * deposits only), the network fee and a fixed rent allowance. A tampered message or lying builder that would drain
 * one lamport more is refused before any signature; anything within the bound is allowed.
 */
@RunWith(Parameterized::class)
class ConfidentialGuardTest(private val op: String, private val amount: Long, private val delta: Long) {
    companion object {
        private val DELTAS = listOf(-1_000_000_000L, -1L, 0L, 1L, 1_000_000L, 1_000_000_000_000L, Long.MIN_VALUE) // MIN = wallet gains
        @JvmStatic @Parameterized.Parameters(name = "{0} {1} drain {2}")
        fun data(): List<Array<Any>> = listOf("deposit_sol", "transfer_sol", "withdraw_sol").flatMap { o ->
            listOf(1_000L, 1_000_000L, 1_000_000_000L, 1_000_000_000_000_000L).flatMap { a -> DELTAS.map { arrayOf<Any>(o, a, it) } }
        }
        private const val FEE = 5_000L
    }
    @get:Rule val tmp = TemporaryFolder()
    private val payer = SolanaPublicKey.fromBase58("5vftMkHL72JaJG6ExQfGAsT2uGVHpRR7oTNUPMs68Y2N")

    @Test fun drainNeverExceedsTheReviewedBound() {
        val allowed = (if (op == "deposit_sol") amount else 0L) + FEE + TxCoordinator.PRIVACY_RENT_ALLOWANCE
        val after = 1_000L
        val before = if (delta == Long.MIN_VALUE) 500L else after + allowed + delta
        if (before < 0) return
        val f = FakeRpc()
        for (u in listOf(FakeRpc.A, FakeRpc.B)) {
            f.on(u, "getGenesisHash") { JsonOut.str(Cluster.DEVNET.genesisHash) }
            f.on(u, "getBlockHeight") { "100" }
            f.on(u, "getFeeForMessage") { """{"context":{"slot":1},"value":$FEE}""" }
            f.on(u, "getBalance") { """{"context":{"slot":1},"value":$before}""" }
            f.on(u, "simulateTransaction") { """{"context":{"slot":1},"value":{"err":null,"logs":[],"unitsConsumed":150,"accounts":[{"lamports":$after,"owner":"11111111111111111111111111111111","data":["","base64"],"executable":false,"rentEpoch":0}]}}""" }
        }
        val co = TxCoordinator(NetworkManager(NetworkConfig(Cluster.DEVNET, FakeRpc.A, FakeRpc.B), f), TxJournal(tmp.newFile("j.json").also { it.delete() })) { 1L }
        val msg = byteArrayOf(0x81.toByte(), 1, 0, 0, 0, 0, 0, 0) + ByteArray(32) { 3 } + byteArrayOf(0, 1) + payer.toByteArray()
        val r = runCatching { co.preparePrivacyV1(payer, msg, op, "x", payer.toBase58(), amount, 250L) }
        val drain = before - after
        if (drain > allowed) {
            assertTrue("$op drained $drain > $allowed but was accepted", r.exceptionOrNull() is RpcException)
        } else {
            val p = r.getOrThrow()
            assertTrue(p.signable); assertEquals(FEE, p.feeLamports); co.cancelPrivacy(p)
        }
    }
}
