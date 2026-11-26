package com.lockwallet.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Safe-to-display profile image storage. This class never receives or references wallet secrets. */
class ProfilePhotoException(message: String) : Exception(message)

class ProfilePhotoStore(private val directory: File) {
    fun path(walletId: String): String? = file(walletId)?.absolutePath

    /** The stored photo: an animated GIF (sanitized) or a static WebP. Only one of the two exists at a time. */
    fun file(walletId: String): File? {
        val gif = File(directory, fileName(walletId, GIF))
        if (gif.isFile && gif.length() in 1L..GifSanitizer.MAX_BYTES.toLong()) return gif
        val target = File(directory, fileName(walletId, WEBP))
        return target.takeIf { it.isFile && it.length() in 1L..MAX_STORED_BYTES }
    }

    /**
     * Decode defensively, honor EXIF rotation, shrink to 512 px and re-encode without source metadata.
     * Animated GIFs (2+ frames) are kept animated: structure-checked and rewritten without metadata blocks by
     * [GifSanitizer], within size, dimension and frame limits; a single-frame GIF becomes a static WebP.
     */
    fun prepare(input: ByteArray): ByteArray {
        if (input.isEmpty() || input.size > MAX_INPUT_BYTES) {
            throw ProfilePhotoException("A imagem deve ter até 10 MB.")
        }
        if (GifSanitizer.isGif(input)) {
            if (input.size > GifSanitizer.MAX_BYTES) throw ProfilePhotoException("O GIF deve ter até 4 MB.")
            val gif = try { GifSanitizer.sanitize(input) } catch (e: GifSanitizer.Invalid) {
                throw ProfilePhotoException(when (e.message) {
                    "dimensions" -> "O GIF deve ter até ${GifSanitizer.MAX_SIDE} × ${GifSanitizer.MAX_SIDE} px."
                    "frames" -> "O GIF deve ter até ${GifSanitizer.MAX_FRAMES} quadros."
                    else -> "O arquivo não é um GIF válido."
                })
            }
            // The platform decoder must also accept the first frame, or the file is refused.
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(gif.bytes, 0, gif.bytes.size, probe)
            if (probe.outWidth <= 0 || probe.outHeight <= 0) throw ProfilePhotoException("O arquivo não é um GIF válido.")
            if (gif.frames >= 2) return gif.bytes
            return prepareStatic(gif.bytes)
        }
        return prepareStatic(input)
    }

    private fun prepareStatic(input: ByteArray): ByteArray {
        if (!hasSupportedImageSignature(input)) {
            throw ProfilePhotoException("O arquivo não é uma imagem válida ou possui dimensões excessivas.")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION ||
            width.toLong() * height.toLong() > MAX_PIXELS
        ) {
            throw ProfilePhotoException("O arquivo não é uma imagem válida ou possui dimensões excessivas.")
        }

        var sample = 1
        while (maxOf(width / sample, height / sample) > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, options)
            ?: throw ProfilePhotoException("Não foi possível abrir essa imagem.")
        var oriented: Bitmap = decoded
        try {
            val orientation = runCatching {
                ExifInterface(ByteArrayInputStream(input)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            if (!matrix.isIdentity) {
                oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (oriented !== decoded) decoded.recycle()
            }

            val output = ByteArrayOutputStream()
            if (!oriented.compress(Bitmap.CompressFormat.WEBP_LOSSY, 84, output)) {
                throw ProfilePhotoException("Não foi possível processar essa imagem.")
            }
            val bytes = output.toByteArray()
            if (bytes.isEmpty() || bytes.size > MAX_STORED_BYTES) {
                throw ProfilePhotoException("A imagem processada ficou grande demais.")
            }
            return bytes
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
            if (oriented !== decoded && !oriented.isRecycled) oriented.recycle()
        }
    }

    /** AtomicFile prevents a partial image replacing the previous valid photo. */
    fun savePrepared(walletId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            throw ProfilePhotoException("A imagem processada é inválida.")
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw ProfilePhotoException("Não foi possível criar o armazenamento privado da fotografia.")
        }
        val animated = GifSanitizer.isGif(bytes)
        if (animated && bytes.size > GifSanitizer.MAX_BYTES) throw ProfilePhotoException("A imagem processada é inválida.")
        if (!animated && bytes.size > MAX_STORED_BYTES) throw ProfilePhotoException("A imagem processada é inválida.")
        val atomic = AtomicFile(File(directory, fileName(walletId, if (animated) GIF else WEBP)))
        val stream = try { atomic.startWrite() } catch (_: Exception) {
            throw ProfilePhotoException("Não foi possível salvar a fotografia neste aparelho.")
        }
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (_: Exception) {
            atomic.failWrite(stream)
            throw ProfilePhotoException("Não foi possível salvar a fotografia neste aparelho.")
        }
        // Replacing an animated photo with a static one (or the reverse) leaves exactly one file.
        AtomicFile(File(directory, fileName(walletId, if (animated) WEBP else GIF))).delete()
    }

    fun remove(walletId: String) {
        AtomicFile(File(directory, fileName(walletId, WEBP))).delete()
        AtomicFile(File(directory, fileName(walletId, GIF))).delete()
    }

    /**
     * Reject arbitrary/bogus byte streams before calling platform decoders. BitmapFactory's
     * bounds-only path may accept malformed data on some Android/Robolectric versions.
     * The accepted signatures cover the image formats the Android picker commonly returns.
     */
    private fun hasSupportedImageSignature(input: ByteArray): Boolean {
        fun ascii(offset: Int, value: String): Boolean {
            if (offset < 0 || input.size < offset + value.length) return false
            return value.indices.all { input[offset + it].toInt() == value[it].code }
        }
        // JPEG
        if (input.size >= 3 && input[0] == 0xff.toByte() &&
            input[1] == 0xd8.toByte() && input[2] == 0xff.toByte()
        ) return true
        // PNG
        if (input.size >= 8 && input.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            )
        ) return true
        // WebP (RIFF container)
        if (input.size >= 12 && ascii(0, "RIFF") && ascii(8, "WEBP")) return true
        // GIF and BMP
        if (ascii(0, "GIF87a") || ascii(0, "GIF89a") || ascii(0, "BM")) return true
        // HEIF/HEIC and AVIF use the ISO-BMFF ftyp box.
        if (input.size >= 12 && ascii(4, "ftyp")) {
            val brand = String(input, 8, 4, Charsets.US_ASCII)
            if (brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")) return true
        }
        return false
    }

    private fun fileName(walletId: String, ext: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(walletId.toByteArray(Charsets.UTF_8))
        val key = digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$key.$ext"
    }

    companion object {
        private const val MAX_INPUT_BYTES = 10 * 1024 * 1024
        private const val MAX_STORED_BYTES = 2 * 1024 * 1024L
        private const val MAX_DIMENSION = 32_768
        private const val MAX_PIXELS = 120_000_000L
        private const val MAX_SIDE = 512
        private const val WEBP = "webp"
        private const val GIF = "gif"
    }
}
