package com.lockwallet.app.connect

import com.lockwallet.data.rpc.Cluster
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

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
 *
 * When [mac] is provided (production) the file is an authenticated envelope: `LWCR1\n` + base64(payload) +
 * `\n` + base64(HMAC-SHA256). A missing/corrupt/tampered/legacy-unauthenticated file yields an empty list
 * (fail closed): sessions live at most 15 minutes and dApps simply re-authorize. Null [mac] (JVM tests)
 * keeps writing the plain JSON of the old format.
 */
class ConnectRegistry(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val mac: ((ByteArray) -> ByteArray)? = null,
) {
    companion object {
        const val VALIDITY_MS = 15L * 60 * 1000
        const val MAX_SESSIONS = 32
        private const val MAGIC = "LWCR1\n"
        private const val MAC_LEN = 32
        private val MAC_DOMAIN = "LockWallet/Connect/v1\u0000".toByteArray(Charsets.US_ASCII)
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

    private fun read(): List<DappSession> {
        if (!file.exists() || file.length() > 256 * 1024) return emptyList()
        val bytes = try { file.readBytes() } catch (_: Exception) { return emptyList() }
        val text = String(bytes, Charsets.UTF_8)
        if (mac != null) {
            if (!text.startsWith(MAGIC)) {
                // Legacy unauthenticated JSON (pre-5.0.6) or injected file: refuse it and rewrite the empty
                // authenticated registry so the plain format never comes back through this path.
                runCatching { write(emptyList()) }
                return emptyList()
            }
            val parts = text.removePrefix(MAGIC).split('\n')
            if (parts.size != 2 || parts.any { it.isEmpty() }) return emptyList()
            val payload = try { Base64.getDecoder().decode(parts[0]) } catch (_: Exception) { return emptyList() }
            val supplied = try { Base64.getDecoder().decode(parts[1]) } catch (_: Exception) { return emptyList() }
            val expected = try { mac.invoke(MAC_DOMAIN + payload) } catch (_: Exception) { null }
            if (expected == null || expected.size != MAC_LEN || supplied.size != MAC_LEN ||
                !MessageDigest.isEqual(expected, supplied)
            ) return emptyList()
            return decodeSessions(payload)
        }
        return try { decodeSessions(bytes) } catch (_: org.json.JSONException) { emptyList() }
    }

    private fun decodeSessions(payload: ByteArray): List<DappSession> {
        val o = JSONObject(String(payload, Charsets.UTF_8))
        if (o.optInt("v") != 1) return emptyList()
        val a = o.getJSONArray("sessions")
        return (0 until a.length()).mapNotNull { i ->
            val s = a.getJSONObject(i)
            val cl = runCatching { Cluster.valueOf(s.getString("cluster")) }.getOrNull()?.takeIf { it.usable } ?: return@mapNotNull null
            DappSession(s.getString("id"), s.getString("name"), s.optString("uri").ifEmpty { null }, s.optString("pkg").ifEmpty { null },
                s.getString("wallet"), s.getString("address"), cl, s.getLong("created"), s.getLong("used"), s.getLong("expires"))
        }
    }

    private fun write(list: List<DappSession>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("name", s.name).put("uri", s.uri ?: "").put("pkg", s.callingPackage ?: "")
                .put("wallet", s.walletId).put("address", s.address).put("cluster", s.cluster.name)
                .put("created", s.createdAtMs).put("used", s.lastUsedMs).put("expires", s.expiresAtMs))
        }
        val payload = JSONObject().put("v", 1).put("sessions", a).toString().toByteArray(Charsets.UTF_8)
        val out = if (mac == null) payload else {
            val tag = try { mac.invoke(MAC_DOMAIN + payload) } catch (_: Exception) { null }
                ?: throw IllegalStateException("session registry authentication unavailable")
            if (tag.size != MAC_LEN) throw IllegalStateException("session registry MAC length")
            (MAGIC + Base64.getEncoder().encodeToString(payload) + "\n" +
                Base64.getEncoder().encodeToString(tag)).toByteArray(Charsets.UTF_8)
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { f -> f.write(out); f.fd.sync() }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
