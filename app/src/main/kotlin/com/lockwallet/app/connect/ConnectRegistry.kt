package com.lockwallet.app.connect

import com.lockwallet.data.rpc.Cluster
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** One dApp authorization (Lock Connect). Public data only: identity as claimed by the dApp, wallet address, cluster. */
data class DappSession(
    val id: String,
    val name: String,
    val uri: String?,
    val callingPackage: String?,
    val walletId: String,
    val address: String,
    val cluster: Cluster,
    val createdAtMs: Long,
    val lastUsedMs: Long,
    val expiresAtMs: Long,
)

/**
 * Lock Connect session list (spec §23.4: short session, clear revocation). The MWA auth token's scope is our
 * session id; every reauthorize/sign request is checked here, so revoking in the app takes effect immediately
 * even though walletlib keeps its own token table. Stored in noBackupFilesDir, written atomically.
 */
class ConnectRegistry(private val file: File, private val now: () -> Long = System::currentTimeMillis, private val random: SecureRandom = SecureRandom()) {
    companion object {
        const val VALIDITY_MS = 15L * 60 * 1000
        const val MAX_SESSIONS = 32
    }

    @Synchronized fun list(): List<DappSession> {
        val all = read()
        val live = all.filter { it.expiresAtMs > now() }
        if (live.size != all.size) write(live)
        return live.sortedByDescending { it.lastUsedMs }
    }

    @Synchronized fun create(name: String, uri: String?, pkg: String?, walletId: String, address: String, cluster: Cluster): DappSession {
        val id = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val t = now()
        val s = DappSession(id, name.take(64), uri?.take(200), pkg?.take(120), walletId, address, cluster, t, t, t + VALIDITY_MS)
        write((list() + s).sortedByDescending { it.lastUsedMs }.take(MAX_SESSIONS))
        return s
    }

    /** The live session whose id equals the MWA authorization scope, or null (unknown, revoked or expired). */
    @Synchronized fun find(scope: ByteArray?): DappSession? {
        if (scope == null || scope.size != 32) return null
        val id = String(scope, Charsets.US_ASCII)
        return list().firstOrNull { it.id == id }
    }

    @Synchronized fun touch(id: String) { write(list().map { if (it.id == id) it.copy(lastUsedMs = now()) else it }) }
    @Synchronized fun revoke(id: String) { write(list().filterNot { it.id == id }) }
    @Synchronized fun revokeWallet(walletId: String) { write(list().filterNot { it.walletId == walletId }) }
    @Synchronized fun revokeAll() { write(emptyList()) }

    private fun read(): List<DappSession> = try {
        if (!file.exists() || file.length() > 256 * 1024) emptyList() else {
            val o = JSONObject(file.readText())
            if (o.optInt("v") != 1) emptyList() else {
                val a = o.getJSONArray("sessions")
                (0 until a.length()).mapNotNull { i ->
                    val s = a.getJSONObject(i)
                    val cl = runCatching { Cluster.valueOf(s.getString("cluster")) }.getOrNull()?.takeIf { it.usable } ?: return@mapNotNull null
                    DappSession(s.getString("id"), s.getString("name"), s.optString("uri").ifEmpty { null }, s.optString("pkg").ifEmpty { null },
                        s.getString("wallet"), s.getString("address"), cl, s.getLong("created"), s.getLong("used"), s.getLong("expires"))
                }
            }
        }
    } catch (_: org.json.JSONException) { emptyList() }

    private fun write(list: List<DappSession>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("name", s.name).put("uri", s.uri ?: "").put("pkg", s.callingPackage ?: "")
                .put("wallet", s.walletId).put("address", s.address).put("cluster", s.cluster.name)
                .put("created", s.createdAtMs).put("used", s.lastUsedMs).put("expires", s.expiresAtMs))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out -> out.write(JSONObject().put("v", 1).put("sessions", a).toString().toByteArray()); out.fd.sync() }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
