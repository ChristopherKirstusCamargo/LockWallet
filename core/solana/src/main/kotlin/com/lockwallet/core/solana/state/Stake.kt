package com.lockwallet.core.solana.state

import com.lockwallet.core.solana.ByteReader
import com.lockwallet.core.solana.MalformedException
import com.lockwallet.core.solana.SolanaPublicKey

/** Native stake account (StakeStateV2, 200 bytes). The f64 warmup field is skipped, never decoded. */
data class StakeAccountState(
    /** 1 Initialized, 2 Stake (delegated). */
    val kind: Int,
    val rentExemptReserve: Long,
    val staker: SolanaPublicKey,
    val withdrawer: SolanaPublicKey,
    val lockupUnixTimestamp: Long,
    val lockupEpoch: Long,
    val custodian: SolanaPublicKey,
    val voter: SolanaPublicKey?,
    val delegatedStake: Long,
    val activationEpoch: Long,
    val deactivationEpoch: Long,
) {
    val hasLockup: Boolean get() = lockupUnixTimestamp != 0L || lockupEpoch != 0L
}

enum class StakeLifecycle { INITIALIZED, ACTIVATING, ACTIVE, DEACTIVATING, INACTIVE }

object StakeState {
    const val SIZE = 200
    /** u64::MAX sentinel for "never deactivated". */
    const val EPOCH_MAX = -1L

    fun parse(data: ByteArray): StakeAccountState {
        if (data.size != SIZE) throw MalformedException("stake size")
        val r = ByteReader(data)
        val kind = r.u32().toInt()
        if (kind != 1 && kind != 2) throw MalformedException("stake state $kind")
        val reserve = r.u64(); val staker = r.pubkey(); val withdrawer = r.pubkey()
        val ts = r.i64(); val epoch = r.u64Raw(); val custodian = r.pubkey()
        if (kind == 1) return StakeAccountState(1, reserve, staker, withdrawer, ts, epoch, custodian, null, 0, 0, EPOCH_MAX)
        val voter = r.pubkey(); val stake = r.u64(); val act = r.u64Raw(); val deact = r.u64Raw()
        return StakeAccountState(2, reserve, staker, withdrawer, ts, epoch, custodian, voter, stake, act, deact)
    }

    /**
     * Lifecycle by epochs. Effective stake with cluster warmup/cooldown limits needs StakeHistory and is
     * labelled as an estimate in the UI; small stakes on Devnet/Testnet normally (de)activate in one epoch.
     */
    fun lifecycle(s: StakeAccountState, currentEpoch: Long): StakeLifecycle {
        if (s.kind == 1) return StakeLifecycle.INITIALIZED
        val deactivated = s.deactivationEpoch != EPOCH_MAX
        if (!deactivated) return if (java.lang.Long.compareUnsigned(currentEpoch, s.activationEpoch) <= 0) StakeLifecycle.ACTIVATING else StakeLifecycle.ACTIVE
        if (s.activationEpoch == s.deactivationEpoch) return if (currentEpoch <= s.deactivationEpoch) StakeLifecycle.DEACTIVATING else StakeLifecycle.INACTIVE
        return if (java.lang.Long.compareUnsigned(currentEpoch, s.deactivationEpoch) <= 0) StakeLifecycle.DEACTIVATING else StakeLifecycle.INACTIVE
    }
}
