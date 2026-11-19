package com.lockwallet.custody.local

import android.content.ContentResolver
import android.net.Uri
import com.lockwallet.core.crypto.lwrp.LwrpHeader
import com.lockwallet.core.solana.SolanaPublicKey
import java.io.InputStream

/** LWRP export states (spec §10.5): only VERIFIED counts as a usable backup. */
enum class ExportState { CREATED, WRITTEN, VERIFIED, FAILED }

/**
 * Storage Access Framework I/O for recovery packages. The user picks the destination/source (no storage
 * permission). Reads are bounded to the largest valid LWRP size + 1 so huge files are refused cheaply.
 */
class RecoveryDocumentIo(private val resolver: ContentResolver, private val provider: LocalSecureProvider) {

    /** Writes, flushes, reads back from the provider and re-opens it with the password (verify-after-export). */
    fun exportAndVerify(uri: Uri, bytes: ByteArray, password: CharArray, expected: SolanaPublicKey): ExportState {
        try {
            val out = resolver.openOutputStream(uri, "wt") ?: return ExportState.FAILED
            out.use { it.write(bytes); it.flush() }
        } catch (_: Exception) {
            return ExportState.FAILED
        }
        val back = try { readBounded(uri, LwrpHeader.MAX_FILE_SIZE) } catch (_: Exception) { null } ?: return ExportState.WRITTEN
        if (!back.contentEquals(bytes)) return ExportState.WRITTEN
        return if (provider.verifyRecoveryPackage(back, password, expected)) ExportState.VERIFIED else ExportState.WRITTEN
    }

    /** Returns the file bytes, or null if larger than [max]. */
    fun readBounded(uri: Uri, max: Int): ByteArray? {
        val input: InputStream = resolver.openInputStream(uri) ?: return null
        input.use { s ->
            val buf = ByteArray(max + 1)
            var n = 0
            while (n < buf.size) {
                val r = s.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n > max) return null
            return buf.copyOf(n)
        }
    }
}
