package com.lockwallet.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfilePhotoStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun normalizesStoresAndRemovesPhotoLocally() {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val source = ByteArrayOutputStream().also {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }.toByteArray()
        bitmap.recycle()

        val store = ProfilePhotoStore(temp.newFolder("private-profile"))
        val normalized = store.prepare(source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(normalized, 0, normalized.size, bounds)
        assertTrue(bounds.outWidth in 1..512)
        assertTrue(bounds.outHeight in 1..512)

        store.savePrepared("wallet-id", normalized)
        assertNotNull(store.file("wallet-id"))
        assertNotNull(store.path("wallet-id"))
        store.remove("wallet-id")
        assertNull(store.file("wallet-id"))
    }

    @Test fun rejectsMalformedAndOversizedInput() {
        val store = ProfilePhotoStore(temp.newFolder("bad-profile"))
        var malformedRejected = false
        try { store.prepare(byteArrayOf(1, 2, 3, 4)) }
        catch (_: ProfilePhotoException) { malformedRejected = true }
        assertTrue(malformedRejected)

        var oversizedRejected = false
        try { store.prepare(ByteArray(10 * 1024 * 1024 + 1)) }
        catch (_: ProfilePhotoException) { oversizedRejected = true }
        assertTrue(oversizedRejected)
        assertFalse(store.file("wallet-id")?.exists() ?: false)
    }
}
