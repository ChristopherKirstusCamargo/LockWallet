package com.lockwallet.app

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Animated GIF profile photos: strict structure, limits, metadata stripped, one stored file, local only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GifProfilePhotoTest {
    @get:Rule val temp = TemporaryFolder()

    private fun gif(frames: Int, w: Int = 1, h: Int = 1, frameW: Int = 1, comment: Boolean = true, xmp: Boolean = true, junk: Boolean = true,
                    lzw: Int = 2, truncate: Boolean = false): ByteArray {
        val o = ByteArrayOutputStream()
        fun u16(v: Int) { o.write(v and 0xff); o.write(v shr 8) }
        o.write("GIF89a".toByteArray()); u16(w); u16(h); o.write(0x80); o.write(0); o.write(0)
        o.write(byteArrayOf(0, 0, 0, -1, -1, -1))
        o.write(byteArrayOf(0x21, 0xFF.toByte(), 11)); o.write("NETSCAPE2.0".toByteArray()); o.write(byteArrayOf(3, 1, 0, 0, 0))
        if (comment) { o.write(byteArrayOf(0x21, 0xFE.toByte(), 5)); o.write("hello".toByteArray()); o.write(0) }
        if (xmp) { o.write(byteArrayOf(0x21, 0xFF.toByte(), 11)); o.write("XMP DataXMP".toByteArray()); o.write(byteArrayOf(4)); o.write("GPS!".toByteArray()); o.write(0) }
        repeat(frames) { i ->
            o.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0))
            o.write(0x2C); u16(0); u16(0); u16(frameW); u16(1); o.write(0)
            o.write(byteArrayOf(lzw.toByte(), 2, if (i % 2 == 0) 0x44 else 0x4C, 1, 0))
        }
        o.write(0x3B)
        if (junk) o.write("APPENDED-PAYLOAD".toByteArray())
        val b = o.toByteArray()
        return if (truncate) b.copyOf(b.size - 30) else b
    }

    @Test fun sanitizerKeepsAnimationAndDropsMetadataAndTrailingData() {
        val g = GifSanitizer.sanitize(gif(3))
        assertEquals(3, g.frames); assertEquals(1, g.width)
        val text = String(g.bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("NETSCAPE2.0"))
        assertFalse(text.contains("hello")); assertFalse(text.contains("XMP")); assertFalse(text.contains("GPS")); assertFalse(text.contains("APPENDED"))
        assertEquals(0x3B, g.bytes.last().toInt())
        // Idempotent: a sanitized file passes unchanged.
        assertTrue(g.bytes.contentEquals(GifSanitizer.sanitize(g.bytes).bytes))
    }

    @Test fun sanitizerRejectsMalformedOrExcessiveFiles() {
        fun rejects(b: ByteArray) = try { GifSanitizer.sanitize(b); fail("accepted") } catch (_: GifSanitizer.Invalid) {}
        rejects(gif(GifSanitizer.MAX_FRAMES + 1, junk = false))
        rejects(gif(2, w = GifSanitizer.MAX_SIDE + 1))
        rejects(gif(2, frameW = 2))            // frame wider than the logical screen
        rejects(gif(2, lzw = 12))
        rejects(gif(2, truncate = true))
        rejects(gif(0))
        rejects("GIF89a".toByteArray())
        rejects(ByteArray(GifSanitizer.MAX_BYTES + 1).also { "GIF89a".toByteArray().copyInto(it) })
        GifSanitizer.sanitize(gif(GifSanitizer.MAX_FRAMES, junk = false))
    }

    @Test fun animatedGifIsStoredAsGifAndReplacesOrIsReplacedByStatic() {
        val store = ProfilePhotoStore(temp.newFolder("p"))
        val prepared = store.prepare(gif(4))
        assertTrue(GifSanitizer.isGif(prepared))
        store.savePrepared("w", prepared)
        val f = store.file("w")!!
        assertTrue(f.name.endsWith(".gif"))
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("GPS"))

        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        store.savePrepared("w", store.prepare(png))
        assertTrue(store.file("w")!!.name.endsWith(".webp"))
        assertEquals(1, f.parentFile!!.listFiles()!!.count { it.isFile })

        store.savePrepared("w", prepared)
        assertTrue(store.file("w")!!.name.endsWith(".gif"))
        assertEquals(1, f.parentFile!!.listFiles()!!.count { it.isFile })
        store.remove("w")
        assertNull(store.file("w"))
    }

    @Test fun singleFrameGifBecomesStaticWebp() {
        val out = ProfilePhotoStore(temp.newFolder("s")).prepare(gif(1))
        assertFalse(GifSanitizer.isGif(out))
        assertTrue(String(out, 0, 4, Charsets.US_ASCII) == "RIFF")
    }

    @Test fun oversizedGifIsRefusedWithAClearMessage() {
        try { ProfilePhotoStore(temp.newFolder("x")).prepare(gif(2, w = 2000, h = 10, frameW = 1)); fail() }
        catch (e: ProfilePhotoException) { assertTrue(e.message!!.contains("1024")) }
    }

    @Test fun storedPictureDecodesForDisplay() {
        val store = ProfilePhotoStore(temp.newFolder("d"))
        store.savePrepared("w", store.prepare(gif(3)))
        assertNotNull(com.lockwallet.app.ui.decodePicture(store.file("w"), null, 96))
        assertNull(com.lockwallet.app.ui.decodePicture(null, "not an image".toByteArray(), 96))
    }
}
