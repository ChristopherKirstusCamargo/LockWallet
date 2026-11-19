package com.lockwallet.core.solana.state

import com.lockwallet.core.solana.ByteReader
import com.lockwallet.core.solana.MalformedException
import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.SolanaPublicKey

/**
 * Token-2022 default-deny policy (spec v2.0 §14.2, errata E-26). Only extensions explicitly analysed are
 * accepted; every other — including unknown/future types — refuses. TLV is parsed from the raw account
 * bytes offline (spec: `:core:solana` analyses ByteArray; RPC only transports it).
 */
object TokenPolicy {
    /** spl-token-2022 `ExtensionType` (u16), verified against interface/src/extension/mod.rs (G-T22-1). */
    enum class Ext(val type: Int, val label: String, val risk: String?) {
        TRANSFER_FEE_CONFIG(1, "TransferFeeConfig", "o valor recebido pode ser menor (taxa na transferência)"),
        TRANSFER_FEE_AMOUNT(2, "TransferFeeAmount", "taxas retidas na conta"),
        MINT_CLOSE_AUTHORITY(3, "MintCloseAuthority", "a mint pode ser fechada pela autoridade"),
        CONFIDENTIAL_TRANSFER_MINT(4, "ConfidentialTransferMint", "transferência confidencial não suportada"),
        CONFIDENTIAL_TRANSFER_ACCOUNT(5, "ConfidentialTransferAccount", "transferência confidencial não suportada"),
        DEFAULT_ACCOUNT_STATE(6, "DefaultAccountState", "contas podem nascer congeladas"),
        IMMUTABLE_OWNER(7, "ImmutableOwner", null),
        MEMO_TRANSFER(8, "MemoTransfer", "exige memo; não suportado"),
        NON_TRANSFERABLE(9, "NonTransferable", "token não transferível"),
        INTEREST_BEARING(10, "InterestBearingConfig", "saldo exibido depende de juros"),
        CPI_GUARD(11, "CpiGuard", "muda as regras da conta"),
        PERMANENT_DELEGATE(12, "PermanentDelegate", "um terceiro pode transferir ou queimar seus tokens a qualquer momento"),
        NON_TRANSFERABLE_ACCOUNT(13, "NonTransferableAccount", "token não transferível"),
        TRANSFER_HOOK(14, "TransferHook", "executa um programa extra em cada transferência"),
        TRANSFER_HOOK_ACCOUNT(15, "TransferHookAccount", "executa um programa extra em cada transferência"),
        CONFIDENTIAL_TRANSFER_FEE_CONFIG(16, "ConfidentialTransferFeeConfig", "não suportado"),
        CONFIDENTIAL_TRANSFER_FEE_AMOUNT(17, "ConfidentialTransferFeeAmount", "não suportado"),
        METADATA_POINTER(18, "MetadataPointer", null),
        TOKEN_METADATA(19, "TokenMetadata", null),
        GROUP_POINTER(20, "GroupPointer", "não analisado"),
        TOKEN_GROUP(21, "TokenGroup", "não analisado"),
        GROUP_MEMBER_POINTER(22, "GroupMemberPointer", "não analisado"),
        TOKEN_GROUP_MEMBER(23, "TokenGroupMember", "não analisado"),
        CONFIDENTIAL_MINT_BURN(24, "ConfidentialMintBurn", "não suportado"),
        SCALED_UI_AMOUNT(25, "ScaledUiAmount", "valor exibido diverge da unidade base"),
        PAUSABLE(26, "Pausable", "a autoridade pode pausar transferências"),
        PAUSABLE_ACCOUNT(27, "PausableAccount", "a autoridade pode pausar transferências"),
        PERMISSIONED_BURN(28, "PermissionedBurn", "não analisado"),
        ;
        companion object { fun of(t: Int) = entries.firstOrNull { it.type == t } }
    }

    /** The only extensions accepted (mint: metadata pointer + in-mint metadata; account: immutable owner). */
    val ALLOWED_MINT = setOf(Ext.METADATA_POINTER, Ext.TOKEN_METADATA)
    val ALLOWED_ACCOUNT = setOf(Ext.IMMUTABLE_OWNER)

    data class Verdict(val allowed: Boolean, val reasons: List<String>) {
        companion object { val OK = Verdict(true, emptyList()) }
    }

    fun judge(mintExts: List<Int>, accountExts: List<Int>): Verdict {
        val reasons = mutableListOf<String>()
        for (t in mintExts) {
            val e = Ext.of(t)
            if (e == null) reasons += "extensão desconhecida #$t (recusada por padrão)"
            else if (e !in ALLOWED_MINT) reasons += "${e.label}: ${e.risk ?: "não analisada"}"
        }
        for (t in accountExts) {
            val e = Ext.of(t)
            if (e == null) reasons += "extensão de conta desconhecida #$t (recusada por padrão)"
            else if (e !in ALLOWED_ACCOUNT) reasons += "${e.label}: ${e.risk ?: "não analisada"}"
        }
        return if (reasons.isEmpty()) Verdict.OK else Verdict(false, reasons.distinct())
    }
}

data class TokenMetadata(val name: String, val symbol: String)

data class MintState(
    val program: SolanaPublicKey,
    val supply: Long,
    val decimals: Int,
    val mintAuthority: SolanaPublicKey?,
    val freezeAuthority: SolanaPublicKey?,
    val extensions: List<Int>,
    /** TokenMetadata in the mint itself (Token-2022); never an identity (spec §14.3). */
    val metadata: TokenMetadata?,
    /** Address the MetadataPointer points to (null if none). */
    val metadataPointer: SolanaPublicKey?,
)

data class TokenAccountState(
    val program: SolanaPublicKey,
    val mint: SolanaPublicKey,
    val owner: SolanaPublicKey,
    val amount: Long,
    val delegate: SolanaPublicKey?,
    val delegatedAmount: Long,
    /** 0 uninitialized, 1 initialized, 2 frozen. */
    val state: Int,
    val isNative: Boolean,
    val closeAuthority: SolanaPublicKey?,
    val extensions: List<Int>,
) { val frozen: Boolean get() = state == 2 }

/** Parsers for SPL Token (legacy) and Token-2022 base layouts + TLV. Strict; malformed ⇒ [MalformedException]. */
object TokenState {
    const val MINT_LEN = 82
    const val ACCOUNT_LEN = 165
    private const val TYPE_MINT = 1
    private const val TYPE_ACCOUNT = 2

    private fun ByteReader.cOptionKey(): SolanaPublicKey? {
        val tag = u32(); val k = bytes(32)
        return when (tag) { 0L -> null; 1L -> SolanaPublicKey.of(k); else -> throw MalformedException("coption") }
    }

    fun parseMint(program: SolanaPublicKey, data: ByteArray): MintState {
        require(program in ProgramIds.TOKEN_PROGRAMS)
        val r = ByteReader(data)
        val mintAuth = r.cOptionKey(); val supply = r.u64(); val decimals = r.u8()
        val init = r.u8(); val freeze = r.cOptionKey()
        if (init != 1) throw MalformedException("mint not initialized")
        if (decimals > 18) throw MalformedException("decimals")
        var exts = emptyList<Int>(); var meta: TokenMetadata? = null; var pointer: SolanaPublicKey? = null
        if (data.size != MINT_LEN) {
            if (program != ProgramIds.TOKEN_2022) throw MalformedException("legacy mint size")
            if (data.size <= ACCOUNT_LEN) throw MalformedException("extended mint size")
            if (data.copyOfRange(MINT_LEN, ACCOUNT_LEN).any { it.toInt() != 0 }) throw MalformedException("mint padding")
            if (data[ACCOUNT_LEN].toInt() != TYPE_MINT) throw MalformedException("account type")
            val tlv = tlv(data, ACCOUNT_LEN + 1)
            exts = tlv.map { it.first }
            tlv.firstOrNull { it.first == TokenPolicy.Ext.TOKEN_METADATA.type }?.let { meta = parseTokenMetadata(it.second) }
            tlv.firstOrNull { it.first == TokenPolicy.Ext.METADATA_POINTER.type }?.let {
                if (it.second.size != 64) throw MalformedException("metadata pointer")
                val addr = it.second.copyOfRange(32, 64)
                pointer = if (addr.all { b -> b.toInt() == 0 }) null else SolanaPublicKey.of(addr)
            }
        }
        return MintState(program, supply, decimals, mintAuth, freeze, exts, meta, pointer)
    }

    fun parseAccount(program: SolanaPublicKey, data: ByteArray): TokenAccountState {
        require(program in ProgramIds.TOKEN_PROGRAMS)
        val r = ByteReader(data)
        val mint = r.pubkey(); val owner = r.pubkey(); val amount = r.u64()
        val delegate = r.cOptionKey(); val state = r.u8()
        val nativeTag = r.u32(); r.u64()
        if (nativeTag > 1) throw MalformedException("coption")
        val delegated = r.u64(); val close = r.cOptionKey()
        if (state !in 1..2) throw MalformedException("account state")
        var exts = emptyList<Int>()
        if (data.size != ACCOUNT_LEN) {
            if (program != ProgramIds.TOKEN_2022 || data.size <= ACCOUNT_LEN) throw MalformedException("account size")
            if (data[ACCOUNT_LEN].toInt() != TYPE_ACCOUNT) throw MalformedException("account type")
            exts = tlv(data, ACCOUNT_LEN + 1).map { it.first }
        }
        return TokenAccountState(program, mint, owner, amount, delegate, delegated, state, nativeTag == 1L, close, exts)
    }

    /** TLV entries: u16 type, u16 length, value. Type 0 (uninitialized) terminates only if the rest is zero padding. */
    internal fun tlv(data: ByteArray, start: Int): List<Pair<Int, ByteArray>> {
        val r = ByteReader(data, start)
        val out = mutableListOf<Pair<Int, ByteArray>>()
        while (r.remaining > 0) {
            if (r.remaining < 4) { if (data.copyOfRange(r.position, data.size).any { it.toInt() != 0 }) throw MalformedException("tlv tail"); break }
            val t = r.u16(); val len = r.u16()
            if (t == 0) {
                if (len != 0 || data.copyOfRange(r.position, data.size).any { it.toInt() != 0 }) throw MalformedException("tlv uninitialized entry")
                break
            }
            if (out.any { it.first == t }) throw MalformedException("duplicate extension")
            out += t to r.bytes(len)
        }
        return out
    }

    /** spl-token-metadata-interface `TokenMetadata` (borsh). */
    fun parseTokenMetadata(v: ByteArray): TokenMetadata {
        val r = ByteReader(v)
        r.skip(32); r.skip(32)
        val name = borshString(r, 200); val symbol = borshString(r, 64)
        return TokenMetadata(clean(name), clean(symbol))
    }

    /** Metaplex Token Metadata account (legacy tokens): key=4, update authority, mint, name, symbol. */
    fun parseMetaplex(data: ByteArray, expectedMint: SolanaPublicKey): TokenMetadata? {
        val r = ByteReader(data)
        if (r.u8() != 4) return null
        r.skip(32)
        if (r.pubkey() != expectedMint) return null
        val name = borshString(r, 200); val symbol = borshString(r, 64)
        return TokenMetadata(clean(name), clean(symbol))
    }

    private fun borshString(r: ByteReader, max: Int): String {
        val n = r.u32(); if (n > max) throw MalformedException("string length")
        return String(r.bytes(n.toInt()), Charsets.UTF_8)
    }

    /** Display hygiene: strip NULs/control/bidi characters, cap length. Never used as identity. */
    fun clean(s: String): String = s.filterNot { it.isISOControl() || it in '\u200B'..'\u200F' || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' || it == '\uFEFF' }
        .trim().take(32)
}
