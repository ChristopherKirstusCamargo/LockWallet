package com.lockwallet.custody.local.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Crash-injection points used by tests to prove the write protocol (ADR 0001 migration rules). */
enum class WriteStep { TEMP_WRITTEN, TEMP_SYNCED, READ_BACK_VALIDATED, RENAMED }

/**
 * temp → fsync → read-back → validate → atomic rename (→ best-effort dir fsync). The previous file is never
 * touched until the new content is fully validated; a crash at any step leaves either the old or the new file.
 */
class AtomicFiles(private val crashHook: (WriteStep) -> Unit = {}) {

    fun write(target: File, bytes: ByteArray, validate: (ByteArray) -> Unit) {
        val dir = target.absoluteFile.parentFile
        if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create dir")
        val tmp = File(dir, target.name + TMP_SUFFIX)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            crashHook(WriteStep.TEMP_WRITTEN)
            out.flush()
            out.fd.sync()
        }
        crashHook(WriteStep.TEMP_SYNCED)
        val back = tmp.readBytes()
        if (!back.contentEquals(bytes)) { tmp.delete(); throw IOException("read-back mismatch") }
        validate(back)
        crashHook(WriteStep.READ_BACK_VALIDATED)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            tmp.delete(); throw IOException("atomic rename unsupported")
        }
        crashHook(WriteStep.RENAMED)
    }

    /** Leftover temp files from an interrupted write are ignored by readers and removed here. */
    fun cleanup(dir: File) {
        dir.listFiles()?.filter { it.name.endsWith(TMP_SUFFIX) }?.forEach { it.delete() }
    }

    companion object { const val TMP_SUFFIX = ".tmp" }
}
