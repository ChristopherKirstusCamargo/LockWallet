package com.lockwallet.app

import java.io.ByteArrayOutputStream

/**
 * Strict GIF87a/89a structure walk for animated profile photos (pure Kotlin, no decoder involved).
 *
 * Accepts only well-formed files within fixed limits and rewrites them block by block, dropping everything that is
 * not needed to show the animation: comment extensions, plain-text extensions and every application extension except
 * the NETSCAPE2.0/ANIMEXTS1.0 loop count (XMP and other embedded metadata never reach the stored file). Image data
 * is copied as-is; the platform decoder later decodes it at avatar size.
 */
object GifSanitizer {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_SIDE = 1024
    const val MAX_FRAMES = 300

    data class Gif(val bytes: ByteArray, val width: Int, val height: Int, val frames: Int)

    class Invalid(message: String) : Exception(message)

    fun isGif(b: ByteArray): Boolean = b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
        b[3] == '8'.code.toByte() && (b[4] == '7'.code.toByte() || b[4] == '9'.code.toByte()) && b[5] == 'a'.code.toByte()

    fun sanitize(input: ByteArray): Gif {
        if (input.size > MAX_BYTES) throw Invalid("too large")
        if (!isGif(input) || input.size < 13 + 1) throw Invalid("not a gif")
        var p = 0
        fun u8(): Int { if (p >= input.size) throw Invalid("truncated"); return input[p++].toInt() and 0xff }
        fun u16(): Int { val lo = u8(); val hi = u8(); return lo or (hi shl 8) }
        val out = ByteArrayOutputStream(input.size)
        fun copy(from: Int, to: Int) = out.write(input, from, to - from)

        p = 6
        val w = u16(); val h = u16()
        val flags = u8(); u8(); u8() // background index, aspect ratio
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) throw Invalid("dimensions")
        if (flags and 0x80 != 0) { val n = 3 * (1 shl ((flags and 7) + 1)); if (p + n > input.size) throw Invalid("truncated"); p += n }
        copy(0, p)

        fun skipSubBlocks() { while (true) { val n = u8(); if (n == 0) return; if (p + n > input.size) throw Invalid("truncated"); p += n } }

        var frames = 0
        while (true) {
            val start = p
            when (u8()) {
                0x2C -> { // image descriptor
                    val x = u16(); val y = u16(); val fw = u16(); val fh = u16(); val lf = u8()
                    if (fw <= 0 || fh <= 0 || x + fw > w || y + fh > h) throw Invalid("frame bounds")
                    if (lf and 0x80 != 0) { val n = 3 * (1 shl ((lf and 7) + 1)); if (p + n > input.size) throw Invalid("truncated"); p += n }
                    val lzw = u8(); if (lzw < 2 || lzw > 8) throw Invalid("lzw")
                    skipSubBlocks()
                    if (++frames > MAX_FRAMES) throw Invalid("frames")
                    copy(start, p)
                }
                0x21 -> { // extension
                    val label = u8()
                    when (label) {
                        0xF9 -> { // graphic control: fixed 4-byte block
                            if (u8() != 4) throw Invalid("gce"); if (p + 4 > input.size) throw Invalid("truncated"); p += 4
                            if (u8() != 0) throw Invalid("gce")
                            copy(start, p)
                        }
                        0xFF -> {
                            val n = u8(); if (p + n > input.size) throw Invalid("truncated")
                            val id = String(input, p, n, Charsets.US_ASCII); p += n
                            skipSubBlocks()
                            if (n == 11 && (id == "NETSCAPE2.0" || id == "ANIMEXTS1.0")) copy(start, p)
                        }
                        else -> skipSubBlocks() // comments (0xFE), plain text (0x01), unknown: dropped
                    }
                }
                0x3B -> { out.write(0x3B); break }
                else -> throw Invalid("block")
            }
        }
        if (frames == 0) throw Invalid("no frames")
        return Gif(out.toByteArray(), w, h, frames)
    }
}
