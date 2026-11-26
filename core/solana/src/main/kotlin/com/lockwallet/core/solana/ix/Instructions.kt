package com.lockwallet.core.solana.ix

import com.lockwallet.core.solana.ByteWriter
import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.tx.AccountMeta
import com.lockwallet.core.solana.tx.Instruction

private fun w(k: SolanaPublicKey) = AccountMeta(k, signer = false, writable = true)
private fun r(k: SolanaPublicKey) = AccountMeta(k, signer = false, writable = false)
private fun sw(k: SolanaPublicKey) = AccountMeta(k, signer = true, writable = true)
private fun sr(k: SolanaPublicKey) = AccountMeta(k, signer = true, writable = false)

/** System Program (bincode u32 discriminants). */
object SystemIx {
    const val CREATE_ACCOUNT_WITH_SEED = 3L
    const val TRANSFER = 2L

    fun transfer(from: SolanaPublicKey, to: SolanaPublicKey, lamports: Long) =
        Instruction(ProgramIds.SYSTEM, listOf(sw(from), w(to)), ByteWriter().u32(TRANSFER).u64(lamports).toByteArray())

    /** base == from (single signer), as solana-program emits it. */
    fun createAccountWithSeed(from: SolanaPublicKey, to: SolanaPublicKey, seed: String, lamports: Long, space: Long, owner: SolanaPublicKey): Instruction {
        val s = seed.toByteArray(Charsets.UTF_8)
        val data = ByteWriter().u32(CREATE_ACCOUNT_WITH_SEED).pubkey(from).u64(s.size.toLong()).bytes(s).u64(lamports).u64(space).pubkey(owner).toByteArray()
        return Instruction(ProgramIds.SYSTEM, listOf(sw(from), w(to)), data)
    }
}

/** SPL Token / Token-2022 shared instruction layouts. */
object TokenIx {
    const val CLOSE_ACCOUNT = 9
    const val TRANSFER_CHECKED = 12

    fun transferChecked(program: SolanaPublicKey, source: SolanaPublicKey, mint: SolanaPublicKey, dest: SolanaPublicKey, owner: SolanaPublicKey, amount: Long, decimals: Int): Instruction {
        require(program in ProgramIds.TOKEN_PROGRAMS)
        return Instruction(program, listOf(w(source), r(mint), w(dest), sr(owner)), ByteWriter().u8(TRANSFER_CHECKED).u64(amount).u8(decimals).toByteArray())
    }

    fun closeAccount(program: SolanaPublicKey, account: SolanaPublicKey, dest: SolanaPublicKey, owner: SolanaPublicKey): Instruction {
        require(program in ProgramIds.TOKEN_PROGRAMS)
        return Instruction(program, listOf(w(account), w(dest), sr(owner)), byteArrayOf(CLOSE_ACCOUNT.toByte()))
    }
}

object AtaIx {
    const val CREATE_IDEMPOTENT = 1

    fun createIdempotent(payer: SolanaPublicKey, ata: SolanaPublicKey, owner: SolanaPublicKey, mint: SolanaPublicKey, tokenProgram: SolanaPublicKey) =
        Instruction(ProgramIds.ASSOCIATED_TOKEN, listOf(sw(payer), w(ata), r(owner), r(mint), r(ProgramIds.SYSTEM), r(tokenProgram)), byteArrayOf(CREATE_IDEMPOTENT.toByte()))
}

object ComputeBudgetIx {
    const val MAX_UNITS = 1_400_000L
    fun setUnitLimit(units: Long) = Instruction(ProgramIds.COMPUTE_BUDGET, emptyList(), ByteWriter().u8(2).u32(units).toByteArray())
    fun setUnitPrice(microLamports: Long) = Instruction(ProgramIds.COMPUTE_BUDGET, emptyList(), ByteWriter().u8(3).u64(microLamports).toByteArray())
}

/** Native Stake Program (bincode StakeInstruction). Never liquid staking. */
object StakeIx {
    const val INITIALIZE = 0L
    const val DELEGATE = 2L
    const val SPLIT = 3L
    const val WITHDRAW = 4L
    const val DEACTIVATE = 5L
    const val MERGE = 7L
    /** StakeStateV2 size (bytes). Rent is always fetched from RPC for this size (spec §12.3). */
    const val STAKE_ACCOUNT_SIZE = 200L

    /** Staker = withdrawer = [authority]; no lockup, no custodian. */
    fun initialize(stake: SolanaPublicKey, authority: SolanaPublicKey) = Instruction(
        ProgramIds.STAKE, listOf(w(stake), r(ProgramIds.SYSVAR_RENT)),
        ByteWriter().u32(INITIALIZE).pubkey(authority).pubkey(authority).u64(0).u64(0).bytes(ByteArray(32)).toByteArray(),
    )

    fun delegate(stake: SolanaPublicKey, vote: SolanaPublicKey, staker: SolanaPublicKey) = Instruction(
        ProgramIds.STAKE,
        listOf(w(stake), r(vote), r(ProgramIds.SYSVAR_CLOCK), r(ProgramIds.SYSVAR_STAKE_HISTORY), r(ProgramIds.STAKE_CONFIG), sr(staker)),
        ByteWriter().u32(DELEGATE).toByteArray(),
    )

    fun deactivate(stake: SolanaPublicKey, staker: SolanaPublicKey) =
        Instruction(ProgramIds.STAKE, listOf(w(stake), r(ProgramIds.SYSVAR_CLOCK), sr(staker)), ByteWriter().u32(DEACTIVATE).toByteArray())

    fun withdraw(stake: SolanaPublicKey, to: SolanaPublicKey, withdrawer: SolanaPublicKey, lamports: Long) = Instruction(
        ProgramIds.STAKE, listOf(w(stake), w(to), r(ProgramIds.SYSVAR_CLOCK), r(ProgramIds.SYSVAR_STAKE_HISTORY), sr(withdrawer)),
        ByteWriter().u32(WITHDRAW).u64(lamports).toByteArray(),
    )

    fun split(stake: SolanaPublicKey, dest: SolanaPublicKey, staker: SolanaPublicKey, lamports: Long) =
        Instruction(ProgramIds.STAKE, listOf(w(stake), w(dest), sr(staker)), ByteWriter().u32(SPLIT).u64(lamports).toByteArray())

    fun merge(dest: SolanaPublicKey, source: SolanaPublicKey, staker: SolanaPublicKey) = Instruction(
        ProgramIds.STAKE, listOf(w(dest), w(source), r(ProgramIds.SYSVAR_CLOCK), r(ProgramIds.SYSVAR_STAKE_HISTORY), sr(staker)),
        ByteWriter().u32(MERGE).toByteArray(),
    )
}
