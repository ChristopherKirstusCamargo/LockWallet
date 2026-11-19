package com.lockwallet.app

import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.data.rpc.StakeView

/**
 * SOL the wallet holds: liquid balance plus every stake account it can withdraw (active, activating, deactivating,
 * inactive, or just initialized) — rewards compound into those accounts, so they are counted too.
 * [total] is known only when both parts are; [lastEpochReward] is shown only when the chain answered for every
 * counted account and the answer is consistent (never estimated). Pure.
 */
data class SolHoldings(val liquid: Long?, val staked: Long?, val total: Long?, val lastEpochReward: Long?, val stakeAccounts: Int)

object Holdings {
    fun of(liquid: Long?, stakes: List<StakeView>?, epoch: Long?, owner: SolanaPublicKey?): SolHoldings {
        val mine = if (owner == null) null else stakes?.filter { it.state.withdrawer == owner }
        val staked = mine?.let { l -> sumOrNull(l.map { it.lamports }) }
        val total = if (liquid != null && staked != null) sumOrNull(listOf(liquid, staked)) else null
        return SolHoldings(liquid, staked, total, reward(mine, epoch), mine?.size ?: 0)
    }

    /** Exact sum; negative input or overflow (hostile RPC) ⇒ unknown. */
    fun sumOrNull(xs: List<Long>): Long? {
        var acc = 0L
        for (x in xs) { if (x < 0) return null; acc = try { Math.addExact(acc, x) } catch (_: ArithmeticException) { return null } }
        return acc
    }

    /** Sum of last-epoch inflation rewards; null unless every account was checked and every reward is plausible. */
    fun reward(mine: List<StakeView>?, epoch: Long?): Long? {
        if (mine.isNullOrEmpty() || epoch == null || epoch <= 0) return null
        if (mine.any { !it.rewardsChecked }) return null
        val amounts = ArrayList<Long>()
        for (v in mine) {
            val r = v.lastReward ?: continue
            if (r.epoch != epoch - 1 || r.amount < 0 || r.amount > v.lamports || r.postBalance < r.amount) return null
            amounts += r.amount
        }
        return sumOrNull(amounts)?.takeIf { it > 0 }
    }
}
