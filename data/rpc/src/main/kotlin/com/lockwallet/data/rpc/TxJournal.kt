package com.lockwallet.data.rpc

import com.lockwallet.core.solana.tx.TransactionState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class TxKind { SEND_SOL, SEND_TOKEN, CLOSE_TOKEN, STAKE_CREATE, STAKE_DEACTIVATE, STAKE_WITHDRAW, STAKE_SPLIT, STAKE_MERGE, DAPP }

/**
 * Persisted record of a signed transaction (spec §13.2): written atomically BEFORE the first submit, with the
 * exact signed bytes reused for every rebroadcast. Contains only public data (signed transactions become public
 * once sent; until then they are bearer instruments — the file lives in no-backup app storage).
 */
data class TxRecord(
    val signature: String,
    val cluster: Cluster,
    val wallet: String,
    val kind: TxKind,
    val summary: String,
    val counterparty: String?,
    val amountUnits: Long?,
    val decimals: Int?,
    val mint: String?,
    val signedTxB64: String?,
    val lastValidBlockHeight: Long?,
    val feeLamports: Long?,
    val state: TransactionState,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastBroadcastMs: Long,
    val broadcasts: Int,
    val detail: String?,
    /** True only when both configured, genesis-verified RPC sources agreed on this status. */
    val statusVerified: Boolean = false,
) {
    /** Unknown or single-source terminal results must never unlock a replacement payment. */
    val unresolved: Boolean get() =
        state in setOf(TransactionState.SIGNED, TransactionState.SUBMITTED, TransactionState.PROCESSED, TransactionState.CONFIRMED, TransactionState.UNKNOWN) ||
            (state in setOf(TransactionState.FAILED, TransactionState.FINALIZED) && !statusVerified)
}

/** Atomic JSON file of the last [MAX] records (temp → fsync → rename). */
class TxJournal(private val file: File) {
    companion object { const val MAX = 300 }
    private val lock = Any()

    fun all(): List<TxRecord> = synchronized(lock) { load() }

    fun put(r: TxRecord) = synchronized(lock) {
        val list = load().filterNot { it.signature == r.signature && it.cluster == r.cluster } + r
        save(list.sortedBy { it.createdAtMs }.takeLast(MAX))
    }

    private fun load(): List<TxRecord> {
        if (!file.exists()) return emptyList()
        val root = try { J.arr(MiniJson.parse(file.readText())) } catch (_: Exception) { throw IllegalStateException("journal corrupted") }
        return root.map { decode(J.obj(it)) }
    }

    private fun save(list: List<TxRecord>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out -> out.write(encode(list).toByteArray(Charsets.UTF_8)); out.fd.sync() }
        if (J.arr(MiniJson.parse(tmp.readText())).size != list.size) throw IllegalStateException("journal read-back")
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun encode(list: List<TxRecord>): String = list.joinToString(",", "[", "]") { r ->
        fun s(v: String?) = v?.let { JsonOut.str(it) } ?: "null"
        fun n(v: Long?) = v?.toString() ?: "null"
        """{"sig":${s(r.signature)},"cluster":${s(r.cluster.name)},"wallet":${s(r.wallet)},"kind":${s(r.kind.name)},"summary":${s(r.summary)},""" +
            """"cp":${s(r.counterparty)},"amount":${n(r.amountUnits)},"dec":${n(r.decimals?.toLong())},"mint":${s(r.mint)},"tx":${s(r.signedTxB64)},""" +
            """"lvbh":${n(r.lastValidBlockHeight)},"fee":${n(r.feeLamports)},"state":${s(r.state.name)},"created":${r.createdAtMs},"updated":${r.updatedAtMs},""" +
            """"lastb":${r.lastBroadcastMs},"nb":${r.broadcasts},"detail":${s(r.detail)},"proof":${r.statusVerified}}"""
    }

    private fun decode(o: Map<String, Any?>): TxRecord {
        fun s(k: String) = o[k] as? String
        fun n(k: String) = o[k]?.let { J.long(it) }
        return TxRecord(J.str(o["sig"]), Cluster.valueOf(J.str(o["cluster"])), J.str(o["wallet"]), TxKind.valueOf(J.str(o["kind"])), J.str(o["summary"]),
            s("cp"), n("amount"), n("dec")?.toInt(), s("mint"), s("tx"), n("lvbh"), n("fee"), TransactionState.valueOf(J.str(o["state"])),
            J.long(o["created"]), J.long(o["updated"]), J.long(o["lastb"]), J.long(o["nb"]).toInt(), s("detail"), o["proof"] == true)
    }
}
