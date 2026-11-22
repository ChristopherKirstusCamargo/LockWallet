package com.lockwallet.core.solana.tx

import com.lockwallet.core.solana.Base58
import com.lockwallet.core.solana.ByteReader
import com.lockwallet.core.solana.ByteWriter
import com.lockwallet.core.solana.MalformedException
import com.lockwallet.core.solana.SolanaPublicKey

data class AccountMeta(val key: SolanaPublicKey, val signer: Boolean, val writable: Boolean)

class Instruction(val program: SolanaPublicKey, val accounts: List<AccountMeta>, data: ByteArray) {
    private val d = data.copyOf()
    val data: ByteArray get() = d.copyOf()
}

class CompiledInstruction(val programIndex: Int, val accountIndexes: IntArray, data: ByteArray) {
    private val d = data.copyOf()
    val data: ByteArray get() = d.copyOf()
    val dataSize: Int get() = d.size
}

/** 32-byte recent blockhash. */
class Blockhash(bytes: ByteArray) {
    private val b = bytes.copyOf().also { require(it.size == 32) { "blockhash must be 32 bytes" } }
    fun toByteArray() = b.copyOf()
    fun toBase58() = Base58.encode(b)
    override fun equals(other: Any?) = other is Blockhash && other.b.contentEquals(b)
    override fun hashCode() = b.contentHashCode()
    override fun toString() = toBase58()
    companion object {
        fun fromBase58(s: String): Blockhash = Blockhash(requireNotNull(Base58.decodeOrNull(s, 32)?.takeIf { it.size == 32 }) { "bad blockhash" })
    }
}

/**
 * Legacy Solana message (the only format Lock Wallet signs — ADR 0007). [compile] follows solana-message
 * `CompiledKeys`: payer first, then signer-writable, signer-readonly, writable, readonly, each group in
 * pubkey byte order. [parse] is strict: canonical shortvecs, no trailing bytes, all indexes in range.
 */
class LegacyMessage private constructor(
    val numRequiredSignatures: Int,
    val numReadonlySigned: Int,
    val numReadonlyUnsigned: Int,
    val accountKeys: List<SolanaPublicKey>,
    val recentBlockhash: Blockhash,
    val instructions: List<CompiledInstruction>,
) {
    val feePayer: SolanaPublicKey get() = accountKeys[0]

    fun isSigner(i: Int) = i < numRequiredSignatures
    fun isWritable(i: Int) = if (i < numRequiredSignatures) i < numRequiredSignatures - numReadonlySigned
        else i < accountKeys.size - numReadonlyUnsigned

    fun serialize(): ByteArray {
        val w = ByteWriter().u8(numRequiredSignatures).u8(numReadonlySigned).u8(numReadonlyUnsigned)
        w.shortVec(accountKeys.size); accountKeys.forEach { w.pubkey(it) }
        w.bytes(recentBlockhash.toByteArray())
        w.shortVec(instructions.size)
        instructions.forEach { ix ->
            w.u8(ix.programIndex); w.shortVec(ix.accountIndexes.size); ix.accountIndexes.forEach { w.u8(it) }
            val d = ix.data; w.shortVec(d.size); w.bytes(d)
        }
        return w.toByteArray()
    }

    companion object {
        const val MAX_TX_SIZE = 1232

        fun compile(payer: SolanaPublicKey, blockhash: Blockhash, ixs: List<Instruction>): LegacyMessage {
            class Meta(var signer: Boolean, var writable: Boolean, var invoked: Boolean)
            val metas = sortedMapOf<SolanaPublicKey, Meta>(compareBy<SolanaPublicKey> { it.toByteArray().joinToString("") { b -> "%02x".format(b.toInt() and 0xff) } })
            ixs.forEach { ix ->
                metas.getOrPut(ix.program) { Meta(false, false, false) }.invoked = true
                ix.accounts.forEach { a -> metas.getOrPut(a.key) { Meta(false, false, false) }.apply { signer = signer || a.signer; writable = writable || a.writable } }
            }
            metas.remove(payer)
            val sw = mutableListOf(payer); val sr = mutableListOf<SolanaPublicKey>(); val w = mutableListOf<SolanaPublicKey>(); val r = mutableListOf<SolanaPublicKey>()
            metas.forEach { (k, m) -> when { m.signer && m.writable -> sw += k; m.signer -> sr += k; m.writable -> w += k; else -> r += k } }
            val keys = sw + sr + w + r
            require(keys.size <= 255) { "too many accounts" }
            val index = keys.withIndex().associate { it.value to it.index }
            val compiled = ixs.map { ix -> CompiledInstruction(index.getValue(ix.program), ix.accounts.map { index.getValue(it.key) }.toIntArray(), ix.data) }
            return LegacyMessage(sw.size + sr.size, sr.size, r.size, keys, blockhash, compiled)
        }

        fun parse(bytes: ByteArray): LegacyMessage {
            val r = ByteReader(bytes)
            val h0 = r.u8()
            if (h0 and 0x80 != 0) throw UnsupportedFormatException()
            val h1 = r.u8(); val h2 = r.u8()
            val n = r.shortVec()
            if (n == 0 || n > 255) throw MalformedException("account count")
            val keys = List(n) { r.pubkey() }
            if (keys.toSet().size != n) throw MalformedException("duplicate account key")
            if (h0 == 0 || h0 > n || h1 >= h0 || h2 > n - h0) throw MalformedException("header")
            val bh = Blockhash(r.bytes(32))
            val ni = r.shortVec()
            val ixs = List(ni) {
                val p = r.u8()
                if (p >= n || p == 0) throw MalformedException("program index")
                val na = r.shortVec()
                val idx = IntArray(na) { r.u8().also { i -> if (i >= n) throw MalformedException("account index") } }
                val dl = r.shortVec()
                CompiledInstruction(p, idx, r.bytes(dl))
            }
            r.expectEnd()
            return LegacyMessage(h0, h1, h2, keys, bh, ixs)
        }
    }
}

/** Versioned (v0/v1) message: refused by scope decision (errata E-19). */
class UnsupportedFormatException : IllegalArgumentException("formato não suportado pela Lock Wallet (decisão de escopo)")

/** Wire transaction: shortvec(signature count) ‖ signatures ‖ message. */
object WireTransaction {
    /**
     * Solana V1 wire format: V1 message followed by the required 64-byte
     * signatures. Unlike Legacy/v0, V1 has no signature-count prefix.
     */
    fun assembleV1(message: ByteArray, signatures: List<ByteArray>): ByteArray {
        require(message.isNotEmpty() && (message[0].toInt() and 0xff) == 0x81) { "not a Solana V1 message" }
        require(signatures.size == 1 && signatures.all { it.size == 64 }) { "V1 signer count" }
        val size = message.size + signatures.sumOf { it.size }
        require(size <= 4_096) { "V1 transaction too large" }
        val w = ByteWriter().bytes(message)
        signatures.forEach { w.bytes(it) }
        return w.toByteArray()
    }

    /** Unsigned V1 transaction used only by simulateTransaction(sigVerify=false). */
    fun unsignedV1(message: ByteArray): ByteArray =
        assembleV1(message, listOf(ByteArray(64)))

    fun assemble(message: ByteArray, signatures: List<ByteArray>): ByteArray {
        require(signatures.all { it.size == 64 })
        val w = ByteWriter().shortVec(signatures.size)
        signatures.forEach { w.bytes(it) }
        val tx = w.bytes(message).toByteArray()
        require(tx.size <= LegacyMessage.MAX_TX_SIZE) { "transaction too large" }
        return tx
    }

    /** Unsigned form for simulateTransaction(sigVerify=false). */
    fun unsigned(message: LegacyMessage): ByteArray = assemble(message.serialize(), List(message.numRequiredSignatures) { ByteArray(64) })

    fun split(tx: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val r = ByteReader(tx)
        val n = r.shortVec()
        val sigs = List(n) { r.bytes(64) }
        return sigs to r.bytes(r.remaining)
    }
}
