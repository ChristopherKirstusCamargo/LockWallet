package com.lockwallet.core.solana.analyzer

import com.lockwallet.core.solana.ByteReader
import com.lockwallet.core.solana.MalformedException
import com.lockwallet.core.solana.Pda
import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.ix.ComputeBudgetIx
import com.lockwallet.core.solana.ix.StakeIx
import com.lockwallet.core.solana.sha256
import com.lockwallet.core.solana.state.TokenPolicy
import com.lockwallet.core.solana.tx.LegacyMessage
import com.lockwallet.core.solana.tx.UnsupportedFormatException

/** Semantic operation decoded from exact message bytes (spec §13.5). Immutable. */
sealed interface Op {
    data class SolTransfer(val from: SolanaPublicKey, val to: SolanaPublicKey, val lamports: Long) : Op
    data class CreateAccountWithSeed(val funder: SolanaPublicKey, val address: SolanaPublicKey, val seed: String, val lamports: Long, val space: Long, val owner: SolanaPublicKey) : Op
    data class StakeInitialize(val stake: SolanaPublicKey, val staker: SolanaPublicKey, val withdrawer: SolanaPublicKey) : Op
    data class StakeDelegate(val stake: SolanaPublicKey, val vote: SolanaPublicKey, val staker: SolanaPublicKey) : Op
    data class StakeDeactivate(val stake: SolanaPublicKey, val staker: SolanaPublicKey) : Op
    data class StakeWithdraw(val stake: SolanaPublicKey, val to: SolanaPublicKey, val withdrawer: SolanaPublicKey, val lamports: Long) : Op
    data class StakeSplit(val stake: SolanaPublicKey, val dest: SolanaPublicKey, val staker: SolanaPublicKey, val lamports: Long) : Op
    data class StakeMerge(val dest: SolanaPublicKey, val source: SolanaPublicKey, val staker: SolanaPublicKey) : Op
    data class TokenTransfer(val program: SolanaPublicKey, val source: SolanaPublicKey, val mint: SolanaPublicKey, val dest: SolanaPublicKey, val owner: SolanaPublicKey, val amount: Long, val decimals: Int) : Op
    data class TokenClose(val program: SolanaPublicKey, val account: SolanaPublicKey, val dest: SolanaPublicKey, val owner: SolanaPublicKey) : Op
    data class CreateAta(val payer: SolanaPublicKey, val ata: SolanaPublicKey, val owner: SolanaPublicKey, val mint: SolanaPublicKey, val tokenProgram: SolanaPublicKey) : Op
    data class ComputeUnitLimit(val units: Long) : Op
    data class ComputeUnitPrice(val microLamports: Long) : Op
}

enum class Finding {
    // blockers
    MALFORMED, UNSUPPORTED_FORMAT, EXTRA_SIGNER, FEE_PAYER_MISMATCH, UNKNOWN_PROGRAM, UNKNOWN_INSTRUCTION, BAD_ACCOUNTS,
    UNEXPECTED_WRITABLE, AUTHORITY_NOT_WALLET, LOCKUP_OR_CUSTODIAN, BAD_DERIVED_ADDRESS, STAKE_ACCOUNT_NOT_CREATED_HERE,
    DUPLICATE_COMPUTE_BUDGET, COMPUTE_LIMIT_TOO_HIGH, CLOSE_TO_THIRD_PARTY, SELF_TRANSFER, ZERO_AMOUNT,
    TOKEN_UNVERIFIED_CONTEXT, TOKEN_POLICY_BLOCKED, TOKEN_DECIMALS_MISMATCH, UNKNOWN_VALIDATOR, FEE_TOO_HIGH, EMPTY,
    // warnings (do not block)
    PRIORITY_FEE, WITHDRAW_TO_OTHER, DELINQUENT_VALIDATOR, TOKEN_DELEGATE_SET, MINT_FREEZE_AUTHORITY, NEW_RECIPIENT_ACCOUNT,
    ;
    val blocking: Boolean get() = ordinal <= EMPTY.ordinal
}

/** Facts fetched from chain by the data layer and checked here deterministically. */
data class TokenAccountFacts(val address: SolanaPublicKey, val program: SolanaPublicKey, val mint: SolanaPublicKey, val owner: SolanaPublicKey, val amount: Long, val hasDelegate: Boolean, val frozen: Boolean)
data class MintFacts(val mint: SolanaPublicKey, val program: SolanaPublicKey, val decimals: Int, val policy: TokenPolicy.Verdict, val hasFreezeAuthority: Boolean)
data class AnalysisContext(
    val tokenAccounts: Map<SolanaPublicKey, TokenAccountFacts> = emptyMap(),
    val mints: Map<SolanaPublicKey, MintFacts> = emptyMap(),
    /** Vote accounts listed by getVoteAccounts → delinquent flag. */
    val validators: Map<SolanaPublicKey, Boolean>? = null,
)

class Analysis internal constructor(
    val message: LegacyMessage?,
    messageBytes: ByteArray,
    val ops: List<Op>,
    val findings: List<Finding>,
) {
    private val bytes = messageBytes.copyOf()
    val messageBytes: ByteArray get() = bytes.copyOf()
    /** Binding between what was shown and what will be signed. */
    val messageHash: ByteArray = sha256(bytes)
    val blockers: List<Finding> get() = findings.filter { it.blocking }
    val warnings: List<Finding> get() = findings.filterNot { it.blocking }
    val signable: Boolean get() = blockers.isEmpty() && message != null
    /** Upper bound of the network fee computed from the bytes (base + priority), never from the RPC. */
    val maxFeeLamports: Long? get() = message?.let { com.lockwallet.core.solana.tx.FeeMath.maxFee(it) }

    /** Lamports leaving the fee payer through decoded ops (excludes the network fee and ATA rent). */
    val explicitOutflowLamports: Long get() = ops.sumOf {
        when (it) {
            is Op.SolTransfer -> it.lamports
            is Op.CreateAccountWithSeed -> it.lamports
            else -> 0L
        }
    }
}

/**
 * Transaction Security Analyzer (ADR 0007/0019): closed allowlist over the exact message bytes.
 * Anything not modelled blocks. With [AnalysisContext] it also cross-checks chain facts (token mint,
 * decimals, policy, validators); custody re-runs it statically before signing (no context needed there,
 * the approval hash binds the bytes the user saw).
 */
object TransactionAnalyzer {
    /** Highest priority fee (lamports) any transaction may carry: 0.005 SOL. Ours stay far below (≤ 0.001 SOL). */
    const val MAX_PRIORITY_LAMPORTS = 5_000_000L

    fun analyze(messageBytes: ByteArray, expectedFeePayer: SolanaPublicKey, context: AnalysisContext? = null): Analysis {
        val msg = try { LegacyMessage.parse(messageBytes) } catch (_: UnsupportedFormatException) {
            return Analysis(null, messageBytes, emptyList(), listOf(Finding.UNSUPPORTED_FORMAT))
        } catch (_: MalformedException) {
            return Analysis(null, messageBytes, emptyList(), listOf(Finding.MALFORMED))
        }
        val f = linkedSetOf<Finding>()
        if (msg.numRequiredSignatures != 1) f += Finding.EXTRA_SIGNER
        if (msg.feePayer != expectedFeePayer) f += Finding.FEE_PAYER_MISMATCH
        if (msg.instructions.isEmpty()) f += Finding.EMPTY
        val payer = msg.feePayer
        val ops = mutableListOf<Op>()
        val neededWritable = mutableSetOf(0)
        var limitSeen = false; var priceSeen = false
        val created = mutableSetOf<SolanaPublicKey>()

        for (ix in msg.instructions) {
            val program = msg.accountKeys[ix.programIndex]
            if (msg.isWritable(ix.programIndex)) f += Finding.UNEXPECTED_WRITABLE
            val a = ix.accountIndexes
            fun key(i: Int) = msg.accountKeys[a[i]]
            /** Checks count, writability and signer flags; spec says signer/writable must match the layout. */
            fun layout(n: Int, writable: Set<Int>, signer: Set<Int>, allowDup: Boolean = false): Boolean {
                if (a.size != n) { f += Finding.BAD_ACCOUNTS; return false }
                if (!allowDup && a.toSet().size != a.size) { f += Finding.BAD_ACCOUNTS; return false }
                for (i in 0 until n) {
                    if (i in writable) { if (!msg.isWritable(a[i])) { f += Finding.BAD_ACCOUNTS; return false }; neededWritable += a[i] }
                    if (i in signer && !msg.isSigner(a[i])) { f += Finding.BAD_ACCOUNTS; return false }
                }
                return true
            }
            fun fixed(i: Int, k: SolanaPublicKey): Boolean = (key(i) == k).also { if (!it) f += Finding.BAD_ACCOUNTS }

            if (ix.dataSize == 0) { f += Finding.UNKNOWN_INSTRUCTION; continue }
            try {
                val r = ByteReader(ix.data)
                when (program) {
                    ProgramIds.SYSTEM -> when (r.u32()) {
                        2L -> {
                            val lamports = r.u64(); r.expectEnd()
                            if (a.size == 2 && a[0] == a[1]) { f += Finding.SELF_TRANSFER; continue }
                            if (!layout(2, setOf(0, 1), setOf(0))) continue
                            if (lamports == 0L) f += Finding.ZERO_AMOUNT
                            ops += Op.SolTransfer(key(0), key(1), lamports)
                        }
                        3L -> {
                            val base = r.pubkey()
                            val seedLen = r.u64(); if (seedLen > Pda.MAX_SEED_LEN) throw MalformedException("seed")
                            val seedBytes = r.bytes(seedLen.toInt())
                            val lamports = r.u64(); val space = r.u64(); val owner = r.pubkey(); r.expectEnd()
                            if (!seedBytes.all { it in 0x21..0x7e }) throw MalformedException("seed charset")
                            val seed = String(seedBytes, Charsets.US_ASCII)
                            val ok = if (a.size == 3) a[2] == a[0] && layout(3, setOf(0, 1), setOf(0, 2), allowDup = true) && a[0] != a[1]
                                else layout(2, setOf(0, 1), setOf(0))
                            if (!ok) continue
                            if (base != payer || key(0) != payer) f += Finding.AUTHORITY_NOT_WALLET
                            if (owner != ProgramIds.STAKE || space != StakeIx.STAKE_ACCOUNT_SIZE) f += Finding.UNKNOWN_INSTRUCTION
                            if (Pda.createWithSeed(base, seed, owner) != key(1)) f += Finding.BAD_DERIVED_ADDRESS
                            created += key(1)
                            ops += Op.CreateAccountWithSeed(key(0), key(1), seed, lamports, space, owner)
                        }
                        else -> f += Finding.UNKNOWN_INSTRUCTION
                    }
                    ProgramIds.COMPUTE_BUDGET -> {
                        if (a.isNotEmpty()) { f += Finding.BAD_ACCOUNTS; continue }
                        when (r.u8()) {
                            2 -> { val u = r.u32(); r.expectEnd(); if (limitSeen) f += Finding.DUPLICATE_COMPUTE_BUDGET; limitSeen = true
                                if (u > ComputeBudgetIx.MAX_UNITS) f += Finding.COMPUTE_LIMIT_TOO_HIGH; ops += Op.ComputeUnitLimit(u) }
                            3 -> { val p = r.u64(); r.expectEnd(); if (priceSeen) f += Finding.DUPLICATE_COMPUTE_BUDGET; priceSeen = true
                                if (p > 0) f += Finding.PRIORITY_FEE; ops += Op.ComputeUnitPrice(p) }
                            else -> f += Finding.UNKNOWN_INSTRUCTION
                        }
                    }
                    ProgramIds.SPL_TOKEN_LEGACY, ProgramIds.TOKEN_2022 -> when (r.u8()) {
                        12 -> {
                            val amount = r.u64(); val decimals = r.u8(); r.expectEnd()
                            if (!layout(4, setOf(0, 2), setOf(3))) continue
                            if (key(3) != payer) f += Finding.AUTHORITY_NOT_WALLET
                            if (amount == 0L) f += Finding.ZERO_AMOUNT
                            ops += Op.TokenTransfer(program, key(0), key(1), key(2), key(3), amount, decimals)
                        }
                        9 -> {
                            r.expectEnd()
                            // dest == owner == wallet is the normal form (indexes 1 and 2 coincide)
                            if (a.size == 3 && (a[0] == a[1] || a[0] == a[2])) { f += Finding.BAD_ACCOUNTS; continue }
                            if (!layout(3, setOf(0, 1), setOf(2), allowDup = true)) continue
                            if (key(2) != payer) f += Finding.AUTHORITY_NOT_WALLET
                            if (key(1) != payer) f += Finding.CLOSE_TO_THIRD_PARTY
                            ops += Op.TokenClose(program, key(0), key(1), key(2))
                        }
                        else -> f += Finding.UNKNOWN_INSTRUCTION
                    }
                    ProgramIds.ASSOCIATED_TOKEN -> {
                        if (r.u8() != 1) { f += Finding.UNKNOWN_INSTRUCTION; continue }
                        r.expectEnd()
                        if (!layout(6, setOf(0, 1), setOf(0))) continue
                        if (!fixed(4, ProgramIds.SYSTEM)) continue
                        val tp = key(5)
                        if (tp !in ProgramIds.TOKEN_PROGRAMS) { f += Finding.UNKNOWN_PROGRAM; continue }
                        if (key(0) != payer) f += Finding.AUTHORITY_NOT_WALLET
                        if (Pda.associatedTokenAddress(key(2), key(3), tp) != key(1)) f += Finding.BAD_DERIVED_ADDRESS
                        ops += Op.CreateAta(key(0), key(1), key(2), key(3), tp)
                    }
                    ProgramIds.STAKE -> when (r.u32()) {
                        StakeIx.INITIALIZE -> {
                            val staker = r.pubkey(); val withdrawer = r.pubkey(); val ts = r.u64Raw(); val epoch = r.u64Raw(); val custodian = r.bytes(32); r.expectEnd()
                            if (!layout(2, setOf(0), emptySet()) || !fixed(1, ProgramIds.SYSVAR_RENT)) continue
                            if (staker != payer || withdrawer != payer) f += Finding.AUTHORITY_NOT_WALLET
                            if (ts != 0L || epoch != 0L || custodian.any { it.toInt() != 0 }) f += Finding.LOCKUP_OR_CUSTODIAN
                            if (key(0) !in created) f += Finding.STAKE_ACCOUNT_NOT_CREATED_HERE
                            ops += Op.StakeInitialize(key(0), staker, withdrawer)
                        }
                        StakeIx.DELEGATE -> {
                            r.expectEnd()
                            if (!layout(6, setOf(0), setOf(5))) continue
                            if (!fixed(2, ProgramIds.SYSVAR_CLOCK) || !fixed(3, ProgramIds.SYSVAR_STAKE_HISTORY) || !fixed(4, ProgramIds.STAKE_CONFIG)) continue
                            if (key(5) != payer) f += Finding.AUTHORITY_NOT_WALLET
                            context?.validators?.let { v ->
                                when (v[key(1)]) { null -> f += Finding.UNKNOWN_VALIDATOR; true -> f += Finding.DELINQUENT_VALIDATOR; false -> {} }
                            }
                            ops += Op.StakeDelegate(key(0), key(1), key(5))
                        }
                        StakeIx.DEACTIVATE -> {
                            r.expectEnd()
                            if (!layout(3, setOf(0), setOf(2)) || !fixed(1, ProgramIds.SYSVAR_CLOCK)) continue
                            if (key(2) != payer) f += Finding.AUTHORITY_NOT_WALLET
                            ops += Op.StakeDeactivate(key(0), key(2))
                        }
                        StakeIx.WITHDRAW -> {
                            val lamports = r.u64(); r.expectEnd()
                            if (a.size == 5 && (a[0] == a[1] || a[0] == a[4])) { f += Finding.BAD_ACCOUNTS; continue }
                            if (!layout(5, setOf(0, 1), setOf(4), allowDup = true)) continue
                            if (!fixed(2, ProgramIds.SYSVAR_CLOCK) || !fixed(3, ProgramIds.SYSVAR_STAKE_HISTORY)) continue
                            if (key(4) != payer) f += Finding.AUTHORITY_NOT_WALLET
                            if (key(1) != payer) f += Finding.WITHDRAW_TO_OTHER
                            if (lamports == 0L) f += Finding.ZERO_AMOUNT
                            ops += Op.StakeWithdraw(key(0), key(1), key(4), lamports)
                        }
                        StakeIx.SPLIT -> {
                            val lamports = r.u64(); r.expectEnd()
                            if (!layout(3, setOf(0, 1), setOf(2))) continue
                            if (key(2) != payer) f += Finding.AUTHORITY_NOT_WALLET
                            if (key(1) !in created) f += Finding.STAKE_ACCOUNT_NOT_CREATED_HERE
                            if (lamports == 0L) f += Finding.ZERO_AMOUNT
                            ops += Op.StakeSplit(key(0), key(1), key(2), lamports)
                        }
                        StakeIx.MERGE -> {
                            r.expectEnd()
                            if (!layout(5, setOf(0, 1), setOf(4))) continue
                            if (!fixed(2, ProgramIds.SYSVAR_CLOCK) || !fixed(3, ProgramIds.SYSVAR_STAKE_HISTORY)) continue
                            if (key(4) != payer) f += Finding.AUTHORITY_NOT_WALLET
                            ops += Op.StakeMerge(key(0), key(1), key(4))
                        }
                        else -> f += Finding.UNKNOWN_INSTRUCTION
                    }
                    else -> f += Finding.UNKNOWN_PROGRAM
                }
            } catch (_: MalformedException) {
                f += Finding.MALFORMED
            }
        }
        // Every writable account must be required writable by some decoded instruction.
        for (i in msg.accountKeys.indices) if (msg.isWritable(i) && i !in neededWritable) f += Finding.UNEXPECTED_WRITABLE
        context?.let { checkContext(ops, payer, it, f) }
        // Fee-drain guard: the priority fee is bounded from the bytes; above the cap nothing is signed.
        val budget = com.lockwallet.core.solana.tx.FeeMath.budget(msg)
        if (budget != null && budget.priorityLamports > MAX_PRIORITY_LAMPORTS) f += Finding.FEE_TOO_HIGH
        return Analysis(msg, messageBytes, ops, f.toList())
    }

    private fun checkContext(ops: List<Op>, payer: SolanaPublicKey, c: AnalysisContext, f: MutableSet<Finding>) {
        for (op in ops) when (op) {
            is Op.TokenTransfer -> {
                val src = c.tokenAccounts[op.source]; val mint = c.mints[op.mint]
                if (src == null || mint == null || src.mint != op.mint || src.owner != payer || src.program != op.program || mint.program != op.program || src.frozen) {
                    f += Finding.TOKEN_UNVERIFIED_CONTEXT; continue
                }
                if (mint.decimals != op.decimals) f += Finding.TOKEN_DECIMALS_MISMATCH
                if (!mint.policy.allowed) f += Finding.TOKEN_POLICY_BLOCKED
                if (mint.hasFreezeAuthority) f += Finding.MINT_FREEZE_AUTHORITY
                if (src.hasDelegate) f += Finding.TOKEN_DELEGATE_SET
                c.tokenAccounts[op.dest]?.let { d -> if (d.mint != op.mint || d.program != op.program || d.frozen) f += Finding.TOKEN_UNVERIFIED_CONTEXT }
            }
            is Op.TokenClose -> {
                val acc = c.tokenAccounts[op.account]
                if (acc == null || acc.owner != payer || acc.program != op.program || acc.frozen ||
                    (acc.amount != 0L && acc.mint != ProgramIds.NATIVE_MINT)) f += Finding.TOKEN_UNVERIFIED_CONTEXT
            }
            is Op.CreateAta -> {
                val mint = c.mints[op.mint]
                if (mint == null || mint.program != op.tokenProgram) f += Finding.TOKEN_UNVERIFIED_CONTEXT
                else if (!mint.policy.allowed) f += Finding.TOKEN_POLICY_BLOCKED
            }
            else -> {}
        }
    }
}
