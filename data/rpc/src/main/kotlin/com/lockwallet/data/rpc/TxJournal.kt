package com.lockwallet.data.rpc

import com.lockwallet.core.solana.tx.TransactionState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

/**
 * Keystore-backed authenticator for the transaction journal. Implementations must never create a key when
 * [createIfMissing] is false: a missing/invalidated key means existing journal data cannot be trusted.
 */
class TxJournalKeyMissingException : IllegalStateException("transaction journal authentication key missing")

fun interface TxJournalAuthenticator {
    /** With createIfMissing=false, throw [TxJournalKeyMissingException] only when the alias truly does not exist. */
    fun mac(data: ByteArray, createIfMissing: Boolean): ByteArray
}

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

/**
 * Authenticated JSON journal. The MAC covers the exact UTF-8 payload (including every signed transaction byte)
 * with a domain separator. Writes remain temp → fsync → read-back/authenticate → atomic rename.
 *
 * A legacy unauthenticated array is migrated conservatively: signatures and display metadata remain, but signed
 * bytes are discarded and every state becomes UNKNOWN. This forces chain reconciliation and prevents a modified
 * legacy file from supplying bearer transaction bytes for rebroadcast.
 *
 * HMAC detects file edits, not deletion or restoration of an older complete, valid file. The chain remains the
 * source of truth for transaction status; this class does not claim hardware anti-rollback protection.
 */
class TxJournal(private val file: File, private val authenticator: TxJournalAuthenticator) {
    companion object {
        const val MAX = 300
        private const val FORMAT_VERSION = 1L
        private val MAC_DOMAIN = "LockWallet/TxJournal/v1\u0000".toByteArray(Charsets.US_ASCII)
        private val ENVELOPE_KEYS = setOf("v", "payload", "mac")
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private val FILE_PROBE = "LockWallet/TxJournal/file-presence/v1".toByteArray(Charsets.US_ASCII)
    }

    private val lock = Any()

    fun all(): List<TxRecord> = synchronized(lock) { load() }

    fun put(r: TxRecord) = synchronized(lock) {
        val list = load().filterNot { it.signature == r.signature && it.cluster == r.cluster } + r
        save(list.sortedBy { it.createdAtMs }.takeLast(MAX))
    }

    private fun load(): List<TxRecord> {
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        if (file.exists()) {
            // The main journal wins whenever it authenticates. A stale but valid snapshot in .tmp must never
            // replace the current file. A crash before rename cannot have reached submit(), because put() did
            // not return; therefore discarding that uncommitted temp is safe.
            val mainRecords = runCatching { readAuthenticated(file) }.getOrNull()
            if (mainRecords != null) {
                if (tmp.exists() && !tmp.delete()) throw IllegalStateException("transaction journal temp cleanup failed")
                return mainRecords
            }
        }
        if (tmp.exists()) {
            try {
                // Recover only if the main file is absent or fails authentication/structure checks.
                val recovered = readAuthenticated(tmp)
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return recovered
            } catch (e: Exception) {
                if (!file.exists()) throw IllegalStateException("transaction journal recovery failed", e)
                if (!tmp.delete() && tmp.exists()) throw IllegalStateException("transaction journal temp cleanup failed", e)
            }
        }
        if (!file.exists()) {
            // A previously provisioned Keystore key is a durable witness that this journal existed.
            // If the file vanished while the key remains, do not treat the wallet as having no pending transaction.
            try {
                authenticator.mac(FILE_PROBE, createIfMissing = false)
            } catch (_: TxJournalKeyMissingException) {
                return emptyList()
            } catch (e: Exception) {
                throw IllegalStateException("journal authentication key unavailable", e)
            }
            throw IllegalStateException("authenticated transaction journal is missing")
        }
        if (file.length() !in 1..MAX_FILE_BYTES.toLong()) throw IllegalStateException("journal size invalid")
        val root = try {
            MiniJson.parse(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalStateException("journal corrupted", e)
        }

        // One-time, fail-safe migration from the v5.0.2 unsigned JSON-array format. Once an HMAC key
        // exists, an unsigned array is a downgrade/tamper attempt and must never be re-authenticated as legacy.
        if (root is List<*>) {
            try {
                authenticator.mac(FILE_PROBE, createIfMissing = false)
                throw IllegalStateException("unauthenticated journal rejected after authentication was provisioned")
            } catch (_: TxJournalKeyMissingException) {
                // No key has ever authenticated a journal on this installation: eligible for legacy migration.
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("journal authentication key unavailable during legacy migration", e)
            }
            val migrated = try {
                J.arr(root).map { value ->
                    decode(J.obj(value)).copy(
                        signedTxB64 = null,
                        state = TransactionState.UNKNOWN,
                        statusVerified = false,
                        detail = "Legacy journal entry; transaction status must be reconciled on-chain",
                    )
                }.sortedBy { it.createdAtMs }.takeLast(MAX)
            } catch (e: Exception) {
                throw IllegalStateException("legacy journal corrupted", e)
            }
            save(migrated)
            return migrated
        }

        val envelope = try { J.obj(root) } catch (e: Exception) {
            throw IllegalStateException("journal format rejected", e)
        }
        if (envelope.keys != ENVELOPE_KEYS) throw IllegalStateException("journal format rejected")
        if (try { J.long(envelope["v"]) } catch (e: Exception) { -1L } != FORMAT_VERSION) {
            throw IllegalStateException("journal version unsupported")
        }

        val payload = try { J.str(envelope["payload"]) } catch (e: Exception) {
            throw IllegalStateException("journal payload missing", e)
        }
        val suppliedMac = try { Base64.getDecoder().decode(J.str(envelope["mac"])) } catch (e: Exception) {
            throw IllegalStateException("journal authentication tag malformed", e)
        }
        if (suppliedMac.size != 32) throw IllegalStateException("journal authentication tag malformed")

        // Do not generate a replacement Keystore key during verification. A missing key must fail closed.
        val expectedMac = try { authenticator.mac(macInput(payload), createIfMissing = false) } catch (e: Exception) {
            throw IllegalStateException("journal authentication key unavailable", e)
        }
        if (expectedMac.size != 32 || !MessageDigest.isEqual(expectedMac, suppliedMac)) {
            throw IllegalStateException("journal authentication failed")
        }

        return try {
            J.arr(MiniJson.parse(payload)).map { decode(J.obj(it)) }
        } catch (e: Exception) {
            throw IllegalStateException("authenticated journal payload malformed", e)
        }
    }

    private fun save(list: List<TxRecord>) {
        require(list.size <= MAX)
        val payload = encodeRecords(list)
        val tag = authenticator.mac(macInput(payload), createIfMissing = true)
        if (tag.size != 32) throw IllegalStateException("journal authenticator returned invalid tag")
        val encoded = """{"v":$FORMAT_VERSION,"payload":${JsonOut.str(payload)},"mac":${JsonOut.str(Base64.getEncoder().encodeToString(tag))}}"""
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) throw IllegalStateException("journal size limit exceeded")

        val parent = file.absoluteFile.parentFile
        if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("journal directory unavailable")
        val tmp = File(parent, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(encoded.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        // Verify the bytes actually persisted to the temporary file before replacing the previous journal.
        val persisted = tmp.readText(Charsets.UTF_8)
        if (persisted != encoded) {
            tmp.delete()
            throw IllegalStateException("journal read-back mismatch")
        }
        verifyWrittenEnvelope(persisted, list.size)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun verifyWrittenEnvelope(encoded: String, expectedCount: Int) {
        val records = decodeAuthenticatedEnvelope(encoded)
        if (records.size != expectedCount) throw IllegalStateException("journal read-back count mismatch")
    }

    private fun readAuthenticated(source: File): List<TxRecord> {
        if (source.length() !in 1..MAX_FILE_BYTES.toLong()) throw IllegalStateException("journal temp size invalid")
        return decodeAuthenticatedEnvelope(source.readText(Charsets.UTF_8))
    }

    private fun decodeAuthenticatedEnvelope(encoded: String): List<TxRecord> {
        val envelope = J.obj(MiniJson.parse(encoded))
        if (envelope.keys != ENVELOPE_KEYS) throw IllegalStateException("journal temp format rejected")
        val version = try { J.long(envelope["v"]) } catch (e: Exception) {
            throw IllegalStateException("journal temp version malformed", e)
        }
        if (version != FORMAT_VERSION) throw IllegalStateException("journal temp version unsupported")
        val payload = J.str(envelope["payload"])
        val suppliedMac = Base64.getDecoder().decode(J.str(envelope["mac"]))
        if (suppliedMac.size != 32) throw IllegalStateException("journal temp authentication tag malformed")
        val expectedMac = authenticator.mac(macInput(payload), createIfMissing = false)
        if (expectedMac.size != 32 || !MessageDigest.isEqual(expectedMac, suppliedMac)) {
            throw IllegalStateException("journal temp authentication failed")
        }
        return J.arr(MiniJson.parse(payload)).map { decode(J.obj(it)) }
    }

    private fun macInput(payload: String): ByteArray =
        MAC_DOMAIN + payload.toByteArray(Charsets.UTF_8)

    private fun encodeRecords(list: List<TxRecord>): String = list.joinToString(",", "[", "]") { r ->
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
