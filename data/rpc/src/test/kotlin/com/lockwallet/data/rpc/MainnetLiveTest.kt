package com.lockwallet.data.rpc

import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.ix.StakeIx
import com.lockwallet.core.solana.ix.SystemIx
import com.lockwallet.core.solana.tx.FeeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom

/**
 * Real Mainnet gate (2.0.0). Opt-in only: ./gradlew :data:rpc:test -Plockwallet.mainnet=true
 * Without funds and without signing anything: the real runtime simulates (sigVerify=false) our exact messages,
 * with the compute budget the app attaches, paid by a funded public account (a validator identity). Nothing is sent.
 */
class MainnetLiveTest {
    @get:Rule val tmp = TemporaryFolder()
    private val live = System.getProperty("lockwallet.mainnet") == "true"
    private fun net() = NetworkManager(NetworkConfig.MAINNET_DEFAULT, HttpsRpcTransport())
    private fun key() = SolanaPublicKey.of(ByteArray(32).also { SecureRandom().nextBytes(it) })

    @Test fun realMainnetAcceptsOurMessagesInSimulation() {
        assumeTrue(live)
        val net = net(); val chain = ChainReader(net)
        val validators = chain.validators().filter { !it.delinquent }
        assertTrue(validators.size > 100)
        val v = validators.maxBy { it.activatedStake }
        val payer = v.node
        assertTrue("payer has funds", chain.balance(payer) > 5_000_000_000)
        val co = TxCoordinator(net, TxJournal(tmp.newFile("s.json").also { it.delete() }, TestTxJournalAuthenticator))
        val dest = key()
        val rent0 = chain.rentExempt(0)
        assertTrue(rent0 > 0)  // read from the chain, never hard-coded (650 240 lamports in Nov 2026)

        // SOL send with the fixed budget used by "send max": the fee is exact before building.
        val b = co.budgetFor(TxCoordinator.SOL_TRANSFER_UNITS, listOf(payer, dest))
        val p1 = co.prepare(TxIntent(TxKind.SEND_SOL, payer, "sim", dest.toBase58(), rent0, 9, null, listOf(SystemIx.transfer(payer, dest, rent0)), autoBudget = true, budget = b))
        assertTrue("${p1.blockers} ${p1.simulation?.err} ${p1.simulation?.logs}", p1.signable)
        assertEquals(b.totalFeeLamports, p1.feeLamports)
        assertTrue((p1.simulation?.unitsConsumed ?: 0) < TxCoordinator.SOL_TRANSFER_UNITS)

        // Native stake create + initialize + delegate with a measured compute limit.
        val rent = chain.rentExempt(StakeIx.STAKE_ACCOUNT_SIZE)
        val min = chain.minimumDelegation()
        val seed = "stake:live" + (System.nanoTime() % 100000)
        val stake = com.lockwallet.core.solana.Pda.createWithSeed(payer, seed, com.lockwallet.core.solana.ProgramIds.STAKE)
        val ixs = listOf(
            SystemIx.createAccountWithSeed(payer, stake, seed, rent + min, 200, com.lockwallet.core.solana.ProgramIds.STAKE),
            StakeIx.initialize(stake, payer),
            StakeIx.delegate(stake, v.vote, payer))
        val ctx = com.lockwallet.core.solana.analyzer.AnalysisContext(validators = validators.associate { it.vote to it.delinquent })
        val p2 = co.prepare(TxIntent(TxKind.STAKE_CREATE, payer, "sim", v.vote.toBase58(), rent, 9, null, ixs, ctx, autoBudget = true))
        assertTrue("${p2.blockers} ${p2.analysis.findings} ${p2.simulation?.err} ${p2.simulation?.logs}", p2.signable)
        val budget = FeeMath.budget(p2.analysis.message!!)!!
        assertTrue(budget.explicitLimit && budget.unitLimit < 200_000 && budget.microLamportsPerUnit >= TxCoordinator.PRICE_FLOOR)
        assertTrue(budget.priorityLamports <= TxCoordinator.MAX_OWN_PRIORITY)
        assertTrue(co.stillValid(p2))
        val (stakes, ep) = chain.stakes(dest)
        assertTrue(stakes.isEmpty() && ep.epoch > 0)
        assertTrue(chain.tokens(dest).isEmpty())
    }
}
