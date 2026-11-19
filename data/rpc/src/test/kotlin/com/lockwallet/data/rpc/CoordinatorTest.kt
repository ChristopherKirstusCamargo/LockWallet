package com.lockwallet.data.rpc

import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.ix.SystemIx
import com.lockwallet.core.solana.tx.Instruction
import com.lockwallet.core.solana.tx.AccountMeta
import com.lockwallet.core.solana.tx.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class CoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val payer = SolanaPublicKey.fromBase58("5vftMkHL72JaJG6ExQfGAsT2uGVHpRR7oTNUPMs68Y2N")
    private val bob = SolanaPublicKey.fromBase58("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv")
    private val bh = "4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY"
    private lateinit var f: FakeRpc
    private lateinit var journal: TxJournal
    private lateinit var co: TxCoordinator
    private var height = 100L
    private var status: String = "null"
    private var clock = 1_000_000L
    private val sig = ByteArray(64) { 7 }
    private val sigB58 = com.lockwallet.core.solana.Base58.encode(sig)

    @Before fun setUp() {
        f = FakeRpc()
        f.on(FakeRpc.A, "getGenesisHash") { "\"${Cluster.DEVNET.genesisHash}\"" }
        f.on(FakeRpc.A, "getLatestBlockhash") { """{"context":{"slot":1},"value":{"blockhash":"$bh","lastValidBlockHeight":250}}""" }
        f.on(FakeRpc.A, "getFeeForMessage") { """{"context":{"slot":1},"value":5000}""" }
        f.on(FakeRpc.A, "simulateTransaction") { """{"context":{"slot":1},"value":{"err":null,"logs":["Program 11111111111111111111111111111111 success"],"unitsConsumed":150,"accounts":[{"lamports":900,"owner":"11111111111111111111111111111111","data":["","base64"],"executable":false,"rentEpoch":0}]}}""" }
        f.on(FakeRpc.A, "sendTransaction") { "\"$sigB58\"" }
        f.on(FakeRpc.A, "getBlockHeight") { "$height" }
        f.on(FakeRpc.A, "isBlockhashValid") { """{"context":{"slot":1},"value":${height <= 250}}""" }
        f.on(FakeRpc.A, "getSignatureStatuses") { """{"context":{"slot":1},"value":[$status]}""" }
        journal = TxJournal(tmp.newFile("j.json").also { it.delete() })
        co = TxCoordinator(NetworkManager(NetworkConfig(Cluster.DEVNET, FakeRpc.A, null), f), journal) { clock }
    }

    private fun intent(ixs: List<Instruction> = listOf(SystemIx.transfer(payer, bob, 1000))) =
        TxIntent(TxKind.SEND_SOL, payer, "1000 lamports", bob.toBase58(), 1000, 9, null, ixs)

    @Test fun happyPathPersistsBeforeSendAndReachesFinalized() {
        val p = co.prepare(intent())
        assertTrue(p.blockers.toString(), p.signable); assertEquals(TransactionState.AWAITING_AUTHORIZATION, p.state)
        assertEquals(5000L, p.feeLamports); assertEquals(900L, p.simulatedPayerLamports)
        var seenSignedBeforeSend = false
        f.on(FakeRpc.A, "sendTransaction") { seenSignedBeforeSend = journal.all().single().state == TransactionState.SIGNED; "\"$sigB58\"" }
        val r = co.submit(p, sig)
        assertTrue(seenSignedBeforeSend)
        assertEquals(TransactionState.SUBMITTED, r.state)
        status = """{"slot":5,"confirmations":1,"err":null,"confirmationStatus":"confirmed"}"""
        val c = co.reconcile(r); assertEquals(TransactionState.CONFIRMED, c.state)
        status = """{"slot":5,"confirmations":null,"err":null,"confirmationStatus":"finalized"}"""
        assertEquals(TransactionState.FINALIZED, co.reconcile(c).state)
        assertEquals(TransactionState.FINALIZED, journal.all().single().state)
    }

    @Test fun simulationFailureBlocksSigning() {
        f.on(FakeRpc.A, "simulateTransaction") { """{"context":{"slot":1},"value":{"err":{"InstructionError":[0,{"Custom":1}]},"logs":["insufficient lamports"],"unitsConsumed":0,"accounts":null}}""" }
        val p = co.prepare(intent())
        assertFalse(p.signable); assertEquals(listOf(PrepBlocker.SIMULATION_FAILED), p.blockers); assertEquals(TransactionState.CANCELLED, p.state)
        try { co.submit(p, sig); fail() } catch (_: IllegalStateException) {}
        assertEquals(0, f.count("sendTransaction"))
    }

    @Test fun simulationOrFeeUnavailableBlocksByDefault() {
        f.handlers[FakeRpc.A]!!.remove("simulateTransaction")
        assertEquals(listOf(PrepBlocker.SIMULATION_UNAVAILABLE), co.prepare(intent()).blockers)
        // RPC fee unknown: the bound computed from the bytes is used (never "free").
        setUp(); f.on(FakeRpc.A, "getFeeForMessage") { """{"context":{"slot":1},"value":null}""" }
        assertEquals(5000L, co.prepare(intent()).feeLamports)
    }

    @Test fun lyingRpcCannotUnderstateTheFee() {
        f.on(FakeRpc.A, "getFeeForMessage") { """{"context":{"slot":1},"value":1}""" }
        val b = ComputeBudget(1_500, 100_000)
        val p = co.prepare(intent().let { TxIntent(it.kind, it.wallet, it.summary, it.counterparty, it.amountUnits, it.decimals, it.mint, it.instructions, autoBudget = true, budget = b) })
        assertTrue(p.blockers.toString(), p.signable)
        assertEquals(5000L + 150L, p.feeLamports)          // 1 500 CU × 0.1 lamport
        assertEquals(b.totalFeeLamports, p.feeLamports)
    }

    @Test fun autoBudgetMeasuresUnitsAndPricesFromRecentFees() {
        f.on(FakeRpc.A, "getRecentPrioritizationFees") { """[{"slot":1,"prioritizationFee":0},{"slot":2,"prioritizationFee":50000},{"slot":3,"prioritizationFee":80000},{"slot":4,"prioritizationFee":90000}]""" }
        val p = co.prepare(TxIntent(TxKind.SEND_SOL, payer, "x", bob.toBase58(), 1000, 9, null, listOf(SystemIx.transfer(payer, bob, 1000)), autoBudget = true))
        assertTrue(p.blockers.toString(), p.signable)
        val b = com.lockwallet.core.solana.tx.FeeMath.budget(p.analysis.message!!)!!
        assertEquals(150L * 6 / 5 + 1_000, b.unitLimit)       // probe reported 150 CU
        assertEquals(90_000L, b.microLamportsPerUnit)          // p75 of non-zero
        assertEquals(2, f.count("simulateTransaction"))        // probe + final, both before any signature
        // Fee market empty/unavailable: floor price; absurd market: capped.
        f.on(FakeRpc.A, "getRecentPrioritizationFees") { "[]" }
        assertEquals(TxCoordinator.PRICE_FLOOR, co.priorityPrice(listOf(payer)))
        f.on(FakeRpc.A, "getRecentPrioritizationFees") { """[{"slot":1,"prioritizationFee":900000000000}]""" }
        assertEquals(TxCoordinator.PRICE_CAP, co.priorityPrice(listOf(payer)))
        f.handlers[FakeRpc.A]!!.remove("getRecentPrioritizationFees")
        assertEquals(TxCoordinator.PRICE_FLOOR, co.priorityPrice(listOf(payer)))
        // Total priority of our own transactions never exceeds 0.001 SOL whatever the limit.
        f.on(FakeRpc.A, "getRecentPrioritizationFees") { """[{"slot":1,"prioritizationFee":2000000}]""" }
        assertTrue(co.budgetFor(1_400_000, listOf(payer)).priorityLamports <= TxCoordinator.MAX_OWN_PRIORITY)
    }

    @Test fun broadcastGoesToEveryEndpointAndOneAcceptanceIsEnough() {
        f.on(FakeRpc.B, "getGenesisHash") { "\"${Cluster.DEVNET.genesisHash}\"" }
        f.on(FakeRpc.B, "sendTransaction") { "\"$sigB58\"" }
        co = TxCoordinator(NetworkManager(NetworkConfig(Cluster.DEVNET, FakeRpc.A, FakeRpc.B), f), journal) { clock }
        f.on(FakeRpc.A, "sendTransaction") { throw RpcException(RpcException.Kind.RATE_LIMITED, "429") }
        val r = co.submit(co.prepare(intent()), sig)
        assertEquals(TransactionState.SUBMITTED, r.state)
        assertEquals(1, f.calls.count { it.first == FakeRpc.B && it.second == "sendTransaction" })
    }

    @Test fun analyzerBlockSkipsNetworkSimulation() {
        val evil = Instruction(SolanaPublicKey.fromBase58("metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s"), listOf(AccountMeta(payer, true, true)), byteArrayOf(1))
        val p = co.prepare(intent(listOf(evil)))
        assertEquals(listOf(PrepBlocker.ANALYZER), p.blockers)
        assertEquals(0, f.count("simulateTransaction"))
    }

    @Test fun unresolvedTransactionBlocksANewOneUntilExpiredWithEvidence() {
        f.on(FakeRpc.A, "sendTransaction") { throw RpcException(RpcException.Kind.TRANSPORT, "timeout") }
        val r = co.submit(co.prepare(intent()), sig)
        assertEquals(TransactionState.UNKNOWN, r.state)
        assertEquals(listOf(PrepBlocker.BUSY), co.prepare(intent()).blockers)

        // Still valid: rebroadcast exactly the same signed bytes, never a rebuilt transaction.
        val sent = mutableListOf<String>()
        f.on(FakeRpc.A, "sendTransaction") { b -> sent += Regex("\\[\"([^\"]+)\"").find(b)!!.groupValues[1]; "\"$sigB58\"" }
        clock += 5_000
        val r2 = co.reconcile(r)
        assertEquals(listOf(r.signedTxB64), sent)
        assertEquals(TransactionState.UNKNOWN, r2.state)
        assertTrue(Base64.getDecoder().decode(sent.single()).size < 1232)

        // Past lastValidBlockHeight and not found with history search => EXPIRED; then a new send is allowed.
        height = 251
        val r3 = co.reconcile(r2)
        assertEquals(TransactionState.EXPIRED, r3.state)
        assertTrue(co.prepare(intent()).signable)
    }

    @Test fun divergentSecondaryKeepsUnknown() {
        f.on(FakeRpc.B, "getGenesisHash") { "\"${Cluster.DEVNET.genesisHash}\"" }
        f.on(FakeRpc.B, "getSignatureStatuses") { """{"context":{"slot":1},"value":[{"slot":9,"confirmations":1,"err":null,"confirmationStatus":"processed"}]}""" }
        co = TxCoordinator(NetworkManager(NetworkConfig(Cluster.DEVNET, FakeRpc.A, FakeRpc.B), f), journal) { clock }
        f.on(FakeRpc.A, "sendTransaction") { throw RpcException(RpcException.Kind.RPC_ERROR, "Transaction simulation failed") }
        val r = co.submit(co.prepare(intent()), sig)
        assertEquals(TransactionState.UNKNOWN, r.state); assertTrue(r.detail!!.contains("simulation"))
        height = 300
        assertEquals(TransactionState.UNKNOWN, co.reconcile(r).state)
        assertEquals(listOf(PrepBlocker.BUSY), co.prepare(intent()).blockers)
    }

    /** Double-payment guard: a lying primary says "expired" but an independent secondary still accepts the blockhash. */
    @Test fun hostilePrimaryCannotDeclareExpiryAlone() {
        var secondaryHeight = 200L
        f.on(FakeRpc.B, "getGenesisHash") { "\"${Cluster.DEVNET.genesisHash}\"" }
        f.on(FakeRpc.B, "getSignatureStatuses") { """{"context":{"slot":1},"value":[null]}""" }
        f.on(FakeRpc.B, "getBlockHeight") { "$secondaryHeight" }
        f.on(FakeRpc.B, "isBlockhashValid") { """{"context":{"slot":1},"value":${secondaryHeight <= 250}}""" }
        co = TxCoordinator(NetworkManager(NetworkConfig(Cluster.DEVNET, FakeRpc.A, FakeRpc.B), f), journal) { clock }
        f.on(FakeRpc.A, "sendTransaction") { throw RpcException(RpcException.Kind.TRANSPORT, "timeout") }
        f.on(FakeRpc.B, "sendTransaction") { throw RpcException(RpcException.Kind.TRANSPORT, "timeout") }
        val r = co.submit(co.prepare(intent()), sig)
        height = 999                                   // primary lies: far past lastValidBlockHeight
        assertEquals(TransactionState.UNKNOWN, co.reconcile(r).state)
        f.handlers[FakeRpc.B]!!.remove("getBlockHeight") // secondary unreachable: still no expiry
        assertEquals(TransactionState.UNKNOWN, co.reconcile(r).state)
        f.on(FakeRpc.B, "getBlockHeight") { "$secondaryHeight" }
        secondaryHeight = 260                          // both agree the blockhash is dead and nothing landed
        assertEquals(TransactionState.EXPIRED, co.reconcile(r).state)
    }

    @Test fun failedOnChainIsTerminal() {
        val r = co.submit(co.prepare(intent()), sig)
        status = """{"slot":5,"confirmations":1,"err":{"InstructionError":[0,"Custom"]},"confirmationStatus":"confirmed"}"""
        val x = co.reconcile(r)
        assertEquals(TransactionState.FAILED, x.state)
        assertEquals(x, co.reconcile(x))
    }

    @Test fun journalSurvivesReloadAndRejectsCorruption() {
        co.submit(co.prepare(intent()), sig)
        val file = java.io.File(tmp.root, "j.json")
        assertEquals(1, TxJournal(file).all().size)
        assertEquals(sigB58, TxJournal(file).all().single().signature)
        file.writeText("[{\"sig\":")
        try { TxJournal(file).all(); fail() } catch (_: IllegalStateException) {}
    }
}
