package com.lockwallet.custody.local.storage

import java.io.File
import java.security.MessageDigest
import java.util.Base64

enum class WalletRole(val code: String) {
    /** Lock Vault — long-term storage, never connects to dApps. */
    VAULT("V"),
    /** Lock Connect — dApp wallet, always a separate seed from any Lock Vault (ADR 0016). */
    CONNECT("C");
    companion object { fun of(c: String) = entries.firstOrNull { it.code == c } }
}

enum class WalletState(val code: String) {
    ACTIVE("A"),
    /** Hardware KEK permanently invalidated: file preserved read-only, recovery via phrase/LWRP. */
    QUARANTINED("Q");
    companion object { fun of(c: String) = entries.firstOrNull { it.code == c } }
}

/** Non-secret wallet metadata (names, public keys, layout). Secrets live only in the LWLE envelopes. */
data class WalletEntry(
    val walletIdHex: String,
    val name: String,
    val role: WalletRole,
    val materialKind: Int,
    val derivationProfile: Int,
    val accountIndex: Int,
    val publicKeyHex: String,
    val seedGroupHex: String,
    val providerId: String,
    val createdAtMs: Long,
    val state: WalletState,
)

/** Vault-level config: one KEK per vault, shared by all its envelopes. */
data class VaultConfig(
    val vaultIdHex: String,
    val aliasIdHex: String,
    val authModeId: Int,
    /** Backward-compatible default for registries written before variable-length PINs were offered. */
    val pinLength: Int = 6,
)

class RegistryFormatException(msg: String) : Exception(msg)

/**
 * Line-oriented canonical registry (`LWREG 1`), written atomically, with a SHA-256 trailer to detect
 * corruption. Unknown versions are refused without rewriting (no downgrade).
 */
class WalletRegistry(private val file: File, private val atomic: AtomicFiles) {

    data class Snapshot(val vault: VaultConfig?, val wallets: List<WalletEntry>)

    fun load(): Snapshot {
        if (!file.exists()) return Snapshot(null, emptyList())
        return decode(file.readBytes())
    }

    fun save(s: Snapshot) {
        val bytes = encode(s)
        atomic.write(file, bytes) { decode(it) }
    }

    companion object {
        const val HEADER = "LWREG 1"
        const val MAX_NAME = 32

        fun encode(s: Snapshot): ByteArray {
            val sb = StringBuilder().append(HEADER).append('\n')
            s.vault?.let {
                if (it.pinLength !in 6..12) throw RegistryFormatException("pin length")
                sb.append("vault\t${it.vaultIdHex}\t${it.aliasIdHex}\t${it.authModeId}\t${it.pinLength}\n")
            }
            for (w in s.wallets) {
                val name = Base64.getEncoder().encodeToString(w.name.toByteArray(Charsets.UTF_8))
                sb.append("wallet\t${w.walletIdHex}\t$name\t${w.role.code}\t${w.materialKind}\t${w.derivationProfile}\t${w.accountIndex}\t" +
                    "${w.publicKeyHex}\t${w.seedGroupHex}\t${w.providerId}\t${w.createdAtMs}\t${w.state.code}\n")
            }
            val body = sb.toString().toByteArray(Charsets.UTF_8)
            return body + "sha256\t${sha(body)}\n".toByteArray(Charsets.US_ASCII)
        }

        fun decode(bytes: ByteArray): Snapshot {
            if (bytes.size > 1_000_000) throw RegistryFormatException("too large")
            val text = String(bytes, Charsets.UTF_8)
            val lines = text.split('\n')
            if (lines.isEmpty() || lines[0] != HEADER) throw RegistryFormatException("unsupported registry version")
            if (lines.last() != "") throw RegistryFormatException("truncated")
            val trailer = lines[lines.size - 2]
            if (!trailer.startsWith("sha256\t")) throw RegistryFormatException("missing checksum")
            val body = bytes.copyOfRange(0, bytes.size - (trailer.length + 1))
            if (sha(body) != trailer.removePrefix("sha256\t")) throw RegistryFormatException("checksum mismatch")
            var vault: VaultConfig? = null
            val wallets = mutableListOf<WalletEntry>()
            for (line in lines.subList(1, lines.size - 2)) {
                val f = line.split('\t')
                when (f[0]) {
                    "vault" -> {
                        if (f.size !in 4..5 || vault != null) throw RegistryFormatException("bad vault line")
                        val authMode = f[3].toIntOrNull() ?: throw RegistryFormatException("auth")
                        val pinLength = if (f.size == 4) 6 else
                            f[4].toIntOrNull()?.takeIf { it in 6..12 } ?: throw RegistryFormatException("pin length")
                        vault = VaultConfig(hex16(f[1]), hex16(f[2]), authMode, pinLength)
                    }
                    "wallet" -> {
                        if (f.size != 12) throw RegistryFormatException("bad wallet line")
                        val name = String(Base64.getDecoder().decode(f[2]), Charsets.UTF_8)
                        wallets += WalletEntry(
                            hex16(f[1]), name, WalletRole.of(f[3]) ?: throw RegistryFormatException("role"),
                            f[4].toInt(), f[5].toInt(), f[6].toInt(), hexN(f[7], 32), hex16(f[8]), f[9], f[10].toLong(),
                            WalletState.of(f[11]) ?: throw RegistryFormatException("state"),
                        )
                    }
                    else -> throw RegistryFormatException("unknown line")
                }
            }
            if (wallets.map { it.walletIdHex }.toSet().size != wallets.size) throw RegistryFormatException("duplicate id")
            return Snapshot(vault, wallets)
        }

        private fun hex16(s: String) = hexN(s, 16)
        private fun hexN(s: String, n: Int): String {
            if (s.length != n * 2 || !s.all { it in '0'..'9' || it in 'a'..'f' }) throw RegistryFormatException("hex")
            return s
        }

        private fun sha(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
