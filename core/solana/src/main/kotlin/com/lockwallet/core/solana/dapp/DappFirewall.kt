package com.lockwallet.core.solana.dapp

import com.lockwallet.core.solana.MalformedException
import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.analyzer.Finding
import com.lockwallet.core.solana.analyzer.Op
import com.lockwallet.core.solana.analyzer.TransactionAnalyzer
import com.lockwallet.core.solana.hex
import com.lockwallet.core.solana.sha256
import com.lockwallet.core.solana.tx.AccountMeta
import com.lockwallet.core.solana.tx.Instruction
import com.lockwallet.core.solana.tx.LegacyMessage
import com.lockwallet.core.solana.tx.UnsupportedFormatException
import com.lockwallet.core.solana.tx.WireTransaction

/** How the firewall understood one instruction of a dApp transaction (Phase H, ADR 0022). */
enum class IxKind {
    /** Allowlisted program and instruction, fully decoded by the analyzer rules. */
    DECODED,
    /** SPL Memo with printable UTF-8 text. */
    MEMO,
    /** A named program whose instructions Lock Wallet does not decode (e.g. a precompile). Raw display + strong ack. */
    KNOWN_UNDECODED,
    /** Unknown program. Raw display + strong ack; simulation deltas are mandatory. */
    UNKNOWN,
    /** Never signed for a dApp: non-allowlisted instruction of a core program, loaders, vote, lookup tables. */
    FORBIDDEN,
}

enum class DappBlock { MALFORMED, UNSUPPORTED_FORMAT, FEE_PAYER_MISMATCH, EXTRA_SIGNER, EMPTY, FORBIDDEN_INSTRUCTION, UNKNOWN_PROGRAM, TOO_MANY_INSTRUCTIONS, FEE_TOO_HIGH, FEE_UNPRICEABLE }

enum class DappWarning { RAW_PROGRAM, WALLET_SIGNS_FOR_RAW_PROGRAM, WALLET_WRITABLE_IN_RAW_PROGRAM, PRIORITY_FEE, TOKEN_DELEGATE_SET, WITHDRAW_TO_OTHER, MANY_INSTRUCTIONS }

class DappInstruction(
    val index: Int,
    val program: SolanaPublicKey,
    val programName: String?,
    val kind: IxKind,
    val ops: List<Op>,
    val memo: String?,
    val accounts: List<AccountMeta>,
    val dataLength: Int,
    /** First bytes of instruction data, hex (display only). */
    val dataPreviewHex: String,
    /** Analyzer findings that made a core-program instruction FORBIDDEN. */
    val reasons: List<Finding>,
)

class DappVerdict internal constructor(
    messageBytes: ByteArray,
    val message: LegacyMessage?,
    val instructions: List<DappInstruction>,
    val blockers: List<DappBlock>,
    val warnings: List<DappWarning>,
) {
    private val bytes = messageBytes.copyOf()
    val messageBytes: ByteArray get() = bytes.copyOf()
    /** Network fee upper bound computed from the bytes (base + priority), null if not priceable. */
    val maxFeeLamports: Long? get() = message?.let { com.lockwallet.core.solana.tx.FeeMath.maxFee(it) }
    val messageHash: ByteArray = sha256(bytes)
    val signable: Boolean get() = blockers.isEmpty() && message != null
    /** True when at least one instruction cannot be decoded: the user must explicitly accept the raw view. */
    val needsRawAck: Boolean get() = instructions.any { it.kind == IxKind.UNKNOWN || it.kind == IxKind.KNOWN_UNDECODED }
    /** Accounts the transaction may write (raw view). */
    val writableAccounts: List<SolanaPublicKey> get() = message?.let { m -> m.accountKeys.filterIndexed { i, _ -> m.isWritable(i) } }.orEmpty()
}

/**
 * Lock Connect firewall over exact message bytes:
 * - core instructions must be decoded by the same analyzer as native wallet transactions;
 * - loaders, governance programs, arbitrary and undecoded third-party programs block;
 * - runtime signature-verification precompiles remain available, with explicit acknowledgement and cross-RPC simulation.
 */
object DappFirewall {
    const val MAX_INSTRUCTIONS = 8
    const val WARN_INSTRUCTIONS = 4

    val MEMO_V1: SolanaPublicKey = SolanaPublicKey.fromBase58("Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo")
    val MEMO_V2: SolanaPublicKey = SolanaPublicKey.fromBase58("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")
    private val FORBIDDEN_PROGRAMS: Map<SolanaPublicKey, String> = mapOf(
        "BPFLoaderUpgradeab1e11111111111111111111111" to "BPF Loader (upgradeable)",
        "BPFLoader2111111111111111111111111111111111" to "BPF Loader 2",
        "BPFLoader1111111111111111111111111111111111" to "BPF Loader",
        "LoaderV411111111111111111111111111111111111" to "Loader v4",
        "NativeLoader1111111111111111111111111111111" to "Native Loader",
        "AddressLookupTab1e1111111111111111111111111" to "Address Lookup Table",
        "Config1111111111111111111111111111111111111" to "Config",
        "Feature111111111111111111111111111111111111" to "Feature Gate",
    ).mapKeys { SolanaPublicKey.fromBase58(it.key) } + (ProgramIds.VOTE to "Vote") + (ProgramIds.STAKE_CONFIG to "Stake Config")
    private val KNOWN_UNDECODED: Map<SolanaPublicKey, String> = mapOf(
        "Ed25519SigVerify111111111111111111111111111" to "Ed25519 (pré-compilado)",
        "KeccakSecp256k11111111111111111111111111111" to "Secp256k1 (pré-compilado)",
        "Secp256r1SigVerify1111111111111111111111111" to "Secp256r1 (pré-compilado)",
    ).mapKeys { SolanaPublicKey.fromBase58(it.key) }
    private val CORE_NAMES = mapOf(
        ProgramIds.SYSTEM to "System Program", ProgramIds.SPL_TOKEN_LEGACY to "SPL Token", ProgramIds.TOKEN_2022 to "Token-2022",
        ProgramIds.ASSOCIATED_TOKEN to "Associated Token Account", ProgramIds.COMPUTE_BUDGET to "Compute Budget", ProgramIds.STAKE to "Stake Program",
    )
    /** Ignored when decoding one instruction in isolation (they depend on the rest of the message, reported separately). */
    private val ISOLATION_ARTIFACTS = setOf(Finding.UNEXPECTED_WRITABLE, Finding.DUPLICATE_COMPUTE_BUDGET, Finding.FEE_TOO_HIGH)

    fun programName(p: SolanaPublicKey): String? = CORE_NAMES[p] ?: FORBIDDEN_PROGRAMS[p] ?: KNOWN_UNDECODED[p]
        ?: if (p == MEMO_V1 || p == MEMO_V2) "SPL Memo" else null

    fun inspect(messageBytes: ByteArray, wallet: SolanaPublicKey): DappVerdict {
        val msg = try { LegacyMessage.parse(messageBytes) }
            catch (_: UnsupportedFormatException) { return DappVerdict(messageBytes, null, emptyList(), listOf(DappBlock.UNSUPPORTED_FORMAT), emptyList()) }
            catch (_: MalformedException) { return DappVerdict(messageBytes, null, emptyList(), listOf(DappBlock.MALFORMED), emptyList()) }
            catch (_: IllegalArgumentException) { return DappVerdict(messageBytes, null, emptyList(), listOf(DappBlock.MALFORMED), emptyList()) }
        val blocks = linkedSetOf<DappBlock>(); val warns = linkedSetOf<DappWarning>()
        if (msg.numRequiredSignatures != 1) blocks += DappBlock.EXTRA_SIGNER
        if (msg.feePayer != wallet) blocks += DappBlock.FEE_PAYER_MISMATCH
        if (msg.instructions.isEmpty()) blocks += DappBlock.EMPTY
        if (msg.instructions.size > MAX_INSTRUCTIONS) blocks += DappBlock.TOO_MANY_INSTRUCTIONS
        else if (msg.instructions.size > WARN_INSTRUCTIONS) warns += DappWarning.MANY_INSTRUCTIONS
        var computeLimits = 0; var computePrices = 0
        val out = msg.instructions.mapIndexed { i, ix ->
            val program = msg.accountKeys[ix.programIndex]
            val metas = ix.accountIndexes.map { AccountMeta(msg.accountKeys[it], msg.isSigner(it), msg.isWritable(it)) }
            val data = ix.data
            val preview = data.copyOfRange(0, minOf(data.size, 24)).hex()
            fun raw(kind: IxKind, reasons: List<Finding> = emptyList(), memo: String? = null, ops: List<Op> = emptyList()) =
                DappInstruction(i, program, programName(program), kind, ops, memo, metas, data.size, preview, reasons)
            if (msg.isWritable(ix.programIndex)) { blocks += DappBlock.FORBIDDEN_INSTRUCTION; return@mapIndexed raw(IxKind.FORBIDDEN, listOf(Finding.UNEXPECTED_WRITABLE)) }
            when {
                program in CORE_NAMES -> {
                    val a = decodeAlone(program, metas, data, wallet, msg)
                    val bad = a.findings.filter { (it.blocking && it !in ISOLATION_ARTIFACTS) || it == Finding.WITHDRAW_TO_OTHER }
                    a.findings.forEach { f -> when (f) {
                        Finding.PRIORITY_FEE -> warns += DappWarning.PRIORITY_FEE
                        Finding.TOKEN_DELEGATE_SET -> warns += DappWarning.TOKEN_DELEGATE_SET
                        Finding.WITHDRAW_TO_OTHER -> warns += DappWarning.WITHDRAW_TO_OTHER
                        else -> {}
                    } }
                    a.ops.forEach { if (it is Op.ComputeUnitLimit) computeLimits++; if (it is Op.ComputeUnitPrice) computePrices++ }
                    if (bad.isNotEmpty() || a.ops.isEmpty()) { blocks += DappBlock.FORBIDDEN_INSTRUCTION; raw(IxKind.FORBIDDEN, bad.ifEmpty { listOf(Finding.UNKNOWN_INSTRUCTION) }) }
                    else raw(IxKind.DECODED, ops = a.ops)
                }
                program == MEMO_V1 || program == MEMO_V2 -> {
                    val text = MessagePolicy.printableText(data, 566)
                    if (text != null) raw(IxKind.MEMO, memo = text) else {
                        blocks += DappBlock.UNKNOWN_PROGRAM
                        rawProgram(raw(IxKind.FORBIDDEN), wallet, warns)
                    }
                }
                program in FORBIDDEN_PROGRAMS -> { blocks += DappBlock.FORBIDDEN_INSTRUCTION; raw(IxKind.FORBIDDEN) }
                program in KNOWN_UNDECODED -> rawProgram(raw(IxKind.KNOWN_UNDECODED), wallet, warns)
                else -> {
                    blocks += DappBlock.UNKNOWN_PROGRAM
                    rawProgram(raw(IxKind.FORBIDDEN), wallet, warns)
                }
            }
        }
        if (computeLimits > 1 || computePrices > 1) blocks += DappBlock.FORBIDDEN_INSTRUCTION
        // Fee-drain guard over the whole message: priority fee bounded from the bytes (limit × price).
        val budget = com.lockwallet.core.solana.tx.FeeMath.budget(msg)
        when {
            budget == null -> blocks += DappBlock.FEE_UNPRICEABLE
            budget.priorityLamports > TransactionAnalyzer.MAX_PRIORITY_LAMPORTS -> blocks += DappBlock.FEE_TOO_HIGH
        }
        return DappVerdict(messageBytes, msg, out, blocks.toList(), warns.toList())
    }

    private fun rawProgram(ix: DappInstruction, wallet: SolanaPublicKey, warns: MutableSet<DappWarning>): DappInstruction {
        warns += DappWarning.RAW_PROGRAM
        ix.accounts.firstOrNull { it.key == wallet }?.let {
            if (it.signer) warns += DappWarning.WALLET_SIGNS_FOR_RAW_PROGRAM
            if (it.writable) warns += DappWarning.WALLET_WRITABLE_IN_RAW_PROGRAM
        }
        return ix
    }

    /** Decodes one core-program instruction with the wallet analyzer, in a one-instruction message paid by the wallet. */
    private fun decodeAlone(program: SolanaPublicKey, metas: List<AccountMeta>, data: ByteArray, wallet: SolanaPublicKey, full: LegacyMessage) =
        try {
            val mini = LegacyMessage.compile(wallet, full.recentBlockhash, listOf(Instruction(program, metas, data))).serialize()
            TransactionAnalyzer.analyze(mini, wallet)
        } catch (_: IllegalArgumentException) {
            TransactionAnalyzer.analyze(ByteArray(0), wallet)
        }

    /**
     * Custody gate: true only if [messageBytes] is the reviewed message ([approvedHash]), the firewall finds no blocker,
     * and raw instructions (if any) were explicitly acknowledged.
     */
    fun permits(messageBytes: ByteArray, wallet: SolanaPublicKey, approvedHash: ByteArray, rawAcknowledged: Boolean): Boolean {
        val v = inspect(messageBytes, wallet)
        return v.messageHash.contentEquals(approvedHash) && v.signable && (!v.needsRawAck || rawAcknowledged)
    }
}

/** Off-chain message policy for `sign_messages` / SIWS (spec §2.3: only what can be shown). */
object MessagePolicy {
    const val MAX_BYTES = 2048

    enum class Refusal { EMPTY, TOO_LONG, NOT_TEXT, LOOKS_LIKE_TRANSACTION }

    fun check(bytes: ByteArray): Refusal? {
        if (bytes.isEmpty()) return Refusal.EMPTY
        if (bytes.size > MAX_BYTES) return Refusal.TOO_LONG
        if (looksLikeTransaction(bytes)) return Refusal.LOOKS_LIKE_TRANSACTION
        if (printableText(bytes, MAX_BYTES) == null) return Refusal.NOT_TEXT
        return null
    }

    /** A message must never be something the network would accept as a transaction or message. */
    fun looksLikeTransaction(b: ByteArray): Boolean {
        if ((b[0].toInt() and 0xff) in 0x80..0xbf) return true // versioned prefix / not valid UTF-8 start
        if (runCatching { LegacyMessage.parse(b) }.isSuccess) return true
        return runCatching { val (_, m) = WireTransaction.split(b); LegacyMessage.parse(m) }.isSuccess
    }

    /** Strict UTF-8 text without control characters (except \n \r \t), bidi overrides or invisible characters. */
    fun printableText(b: ByteArray, max: Int): String? {
        if (b.isEmpty() || b.size > max) return null
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val s = try { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() } catch (_: java.nio.charset.CharacterCodingException) { return null }
        // Bound visual spam while retaining ordinary SIWS line breaks.
        if (s.count { it == '\n' || it == '\r' } > 8) return null
        val whitespace = s.count { it.isWhitespace() }
        if (whitespace * 100 > s.length * 45) return null
        for (ch in s) {
            val c = ch.code
            if (c < 0x20 && ch != '\n' && ch != '\r' && ch != '\t') return null
            if (c in 0x7f..0x9f) return null
            if (c in 0x200b..0x200f || c in 0x202a..0x202e || c in 0x2066..0x2069 || c == 0xfeff || c == 0x061c) return null
        }
        return s
    }
}
