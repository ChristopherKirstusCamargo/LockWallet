package com.lockwallet.data.rpc

import com.lockwallet.core.solana.MalformedException
import com.lockwallet.core.solana.Pda
import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.analyzer.MintFacts
import com.lockwallet.core.solana.analyzer.TokenAccountFacts
import com.lockwallet.core.solana.state.MintState
import com.lockwallet.core.solana.state.StakeAccountState
import com.lockwallet.core.solana.state.StakeLifecycle
import com.lockwallet.core.solana.state.StakeState
import com.lockwallet.core.solana.state.TokenAccountState
import com.lockwallet.core.solana.state.TokenPolicy
import com.lockwallet.core.solana.state.TokenState

/** A token account owned by the wallet, with its mint facts and the Token-2022 default-deny verdict. */
data class TokenHolding(
    val account: SolanaPublicKey,
    val state: TokenAccountState,
    val mint: MintState?,
    val verdict: TokenPolicy.Verdict,
    val name: String?,
    val symbol: String?,
    val isAta: Boolean,
) {
    val program: SolanaPublicKey get() = state.program
    val decimals: Int get() = mint?.decimals ?: 0
    /** Sendable only with parsed mint, allowed policy, not frozen. Metadata never authorises anything (spec §14.3). */
    val sendable: Boolean get() = mint != null && verdict.allowed && !state.frozen && state.amount > 0
    val closable: Boolean get() = verdict.allowed && !state.frozen && (state.amount == 0L || state.isNative) && state.closeAuthority.let { it == null || it == state.owner }
    fun accountFacts() = TokenAccountFacts(account, program, state.mint, state.owner, state.amount, state.delegate != null, state.frozen)
    fun mintFacts() = mint?.let { MintFacts(state.mint, program, it.decimals, verdict, it.freezeAuthority != null) }
}

/** [rewardsChecked]: getInflationReward for the last epoch answered (so a null [lastReward] means "no reward", not "unknown"). */
data class StakeView(val address: SolanaPublicKey, val lamports: Long, val state: StakeAccountState, val lifecycle: StakeLifecycle, val seed: String?, val lastReward: InflationReward?,
    val rewardsChecked: Boolean = false) {
    /** Actions only for accounts this wallet fully controls (never touch authorities — spec §23.2). */
    fun controlledBy(w: SolanaPublicKey) = state.staker == w && state.withdrawer == w
}

/** Read-only chain queries for the UI. Every value comes from the RPC; nothing is invented. Blocking. */
class ChainReader(private val net: NetworkManager) {
    fun balance(owner: SolanaPublicKey): Long = net.withFallback { it.getBalance(owner) }
    fun rentExempt(size: Long): Long = net.withFallback { it.getMinimumBalanceForRentExemption(size) }
    fun account(k: SolanaPublicKey): AccountData? = net.withFallback { it.getAccountInfo(k) }
    fun epoch(): EpochInfo = net.withFallback { it.getEpochInfo() }
    fun validators(): List<ValidatorInfo> = net.withFallback { it.getVoteAccounts() }
    fun minimumDelegation(): Long = net.withFallback { it.getStakeMinimumDelegation() }
    fun history(owner: SolanaPublicKey, limit: Int = 20): List<SignatureInfo> = net.withFallback { it.getSignaturesForAddress(owner, limit) }
    fun detail(sig: String): TransactionDetail? = net.withFallback { it.getTransaction(sig) }

    /** Network fee of the message these instructions compile to (fee does not depend on amounts). Null = unknown. */
    fun fee(payer: SolanaPublicKey, ixs: List<com.lockwallet.core.solana.tx.Instruction>): Long? = net.withFallback { c ->
        val bh = c.getLatestBlockhash()
        c.getFeeForMessage(com.lockwallet.core.solana.tx.LegacyMessage.compile(payer, bh.blockhash, ixs).serialize())
    }

    fun tokens(owner: SolanaPublicKey): List<TokenHolding> {
        val accounts = ProgramIds.TOKEN_PROGRAMS.flatMap { p ->
            net.withFallback { it.getTokenAccountsByOwner(owner, p) }.mapNotNull { a ->
                if (a.owner != p) return@mapNotNull null
                try { a.address to TokenState.parseAccount(p, a.data) } catch (_: MalformedException) { null }
            }
        }.filter { it.second.owner == owner }
        val mintKeys = accounts.map { it.second.mint }.distinct()
        val mints = HashMap<SolanaPublicKey, MintState?>()
        mintKeys.chunked(100).forEach { chunk ->
            // Mint extensions decide whether a token can be sent. A one-node fallback is not enough
            // for this security decision; if independent RPCs disagree or one is unavailable, fail closed.
            val verified = runCatching { net.getMultipleAccountsVerified(chunk) }.getOrNull()
            if (verified == null) {
                chunk.forEach { mints[it] = null }
            } else {
                verified.forEachIndexed { i, a ->
                    mints[chunk[i]] = a?.takeIf { it.owner in ProgramIds.TOKEN_PROGRAMS }?.let { acc ->
                        try { TokenState.parseMint(acc.owner, acc.data) } catch (_: MalformedException) { null }
                    }
                }
            }
        }
        // Legacy metadata (Metaplex PDA); Token-2022 metadata only when stored in the mint itself.
        val legacyMints = mintKeys.filter { mints[it]?.program == ProgramIds.SPL_TOKEN_LEGACY }
        val metaplex = HashMap<SolanaPublicKey, Pair<String, String>>()
        legacyMints.chunked(100).forEach { chunk ->
            val pdas = chunk.map { Pda.metaplexMetadata(it) }
            try {
                net.withFallback { it.getMultipleAccounts(pdas) }.forEachIndexed { i, a ->
                    if (a != null && a.owner == ProgramIds.METAPLEX_METADATA) {
                        try { TokenState.parseMetaplex(a.data, chunk[i])?.let { m -> metaplex[chunk[i]] = m.name to m.symbol } } catch (_: MalformedException) {}
                    }
                }
            } catch (_: RpcException) { /* metadata is optional decoration */ }
        }
        return accounts.map { (addr, st) ->
            val m = mints[st.mint]
            val verdict = when {
                m == null -> TokenPolicy.Verdict(false, listOf("mint ilegível ou ausente"))
                m.program != st.program -> TokenPolicy.Verdict(false, listOf("programa da mint diverge da conta"))
                else -> TokenPolicy.judge(m.extensions, st.extensions)
            }
            val inMint = m?.metadata?.takeIf { m.metadataPointer == st.mint }
            val (name, symbol) = inMint?.let { it.name to it.symbol } ?: metaplex[st.mint] ?: (null to null)
            TokenHolding(addr, st, m, verdict, name?.ifBlank { null }, symbol?.ifBlank { null }, Pda.associatedTokenAddress(owner, st.mint, st.program) == addr)
        }.sortedWith(compareByDescending<TokenHolding> { it.state.amount > 0 }.thenBy { it.symbol ?: it.state.mint.toBase58() })
    }

    companion object {
        const val STAKE_SEEDS = 16
        fun stakeSeed(i: Int) = "stake:$i"
        fun stakeAddress(owner: SolanaPublicKey, i: Int) = Pda.createWithSeed(owner, stakeSeed(i), ProgramIds.STAKE)
    }

    /** Stake accounts from the wallet's deterministic seeds plus (best effort) any other with this withdrawer. */
    fun stakes(owner: SolanaPublicKey): Pair<List<StakeView>, EpochInfo> {
        val ep = epoch()
        val seeded = (0 until STAKE_SEEDS).map { stakeAddress(owner, it) }
        val found = LinkedHashMap<SolanaPublicKey, Pair<AccountData, String?>>()
        net.withFallback { it.getMultipleAccounts(seeded) }.forEachIndexed { i, a -> if (a != null) found[seeded[i]] = a to stakeSeed(i) }
        try { net.withFallback { it.getStakeAccountsByWithdrawer(ProgramIds.STAKE, owner) }.forEach { if (it.address !in found) found[it.address] = it to null } }
        catch (_: RpcException) { /* some public RPCs disable getProgramAccounts */ }
        val views = found.values.mapNotNull { (a, seed) ->
            if (a.owner != ProgramIds.STAKE) return@mapNotNull null
            val st = try { StakeState.parse(a.data) } catch (_: MalformedException) { return@mapNotNull null }
            StakeView(a.address, a.lamports, st, StakeState.lifecycle(st, ep.epoch), seed, null)
        }
        val rewards: List<InflationReward?>? = if (ep.epoch > 0 && views.isNotEmpty()) try {
            net.withFallback { it.getInflationReward(views.map { v -> v.address }, ep.epoch - 1) }
        } catch (_: RpcException) { null } else null
        return views.mapIndexed { i, v -> v.copy(lastReward = rewards?.getOrNull(i), rewardsChecked = rewards != null) } to ep
    }

    /** First seed index with no account yet (for a new stake account / split destination). */
    fun nextStakeSeed(owner: SolanaPublicKey): Int {
        val seeded = (0 until STAKE_SEEDS * 4).map { stakeAddress(owner, it) }
        val used = seeded.chunked(100).flatMap { c -> net.withFallback { it.getMultipleAccounts(c) } }
        return used.indexOfFirst { it == null }.takeIf { it >= 0 } ?: throw RpcException(RpcException.Kind.MALFORMED, "no free stake seed")
    }
}
