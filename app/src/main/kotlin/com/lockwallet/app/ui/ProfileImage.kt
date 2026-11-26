package com.lockwallet.app.ui

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lockwallet.app.GifSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/** A decoded profile picture: static bitmap, or an animated GIF decoded lazily by the platform at avatar size. */
sealed interface ProfilePicture {
    class Static(val bitmap: ImageBitmap) : ProfilePicture
    class Animated(val drawable: AnimatedImageDrawable) : ProfilePicture
}

/**
 * Loads a local profile picture off the main thread. GIFs go through ImageDecoder with a target size equal to the
 * on-screen size (frame buffers stay small whatever the source); any failure falls back to the first frame, and a
 * file that cannot be decoded shows nothing (the caller draws initials).
 */
@Composable
fun rememberProfilePicture(path: String?, bytes: ByteArray?, revision: Int, size: Dp): ProfilePicture? {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceIn(16, 512)
    val state by produceState<ProfilePicture?>(null, path, bytes, revision, px) {
        value = withContext(Dispatchers.IO) { decodePicture(path?.let(::File), bytes, px) }
    }
    return state
}

internal fun decodePicture(file: File?, bytes: ByteArray?, px: Int): ProfilePicture? = runCatching {
    val data = bytes ?: file?.takeIf { it.isFile }?.readBytes() ?: return null
    if (GifSanitizer.isGif(data)) {
        runCatching {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                val s = info.size
                val scale = px.toFloat() / minOf(s.width, s.height).coerceAtLeast(1)
                if (scale < 1f) decoder.setTargetSize((s.width * scale).toInt().coerceAtLeast(1), (s.height * scale).toInt().coerceAtLeast(1))
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
            if (d is AnimatedImageDrawable) return ProfilePicture.Animated(d)
        }
    }
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, o)
    var sample = 1
    while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2
    BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()?.let { ProfilePicture.Static(it) }
}.getOrNull()

/**
 * Draws a [ProfilePicture] centre-cropped. Animation runs only while the screen is resumed, the composable is on
 * screen and animations are enabled; it stops on pause and on dispose (no work in the background).
 */
@Composable
fun ProfilePictureView(picture: ProfilePicture, modifier: Modifier = Modifier) {
    when (picture) {
        is ProfilePicture.Static -> Image(picture.bitmap, null, modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        is ProfilePicture.Animated -> {
            val d = picture.drawable
            var frame by remember(d) { mutableIntStateOf(0) }
            val owner = LocalLifecycleOwner.current
            DisposableEffect(d, owner) {
                d.callback = object : Drawable.Callback {
                    override fun invalidateDrawable(who: Drawable) { frame++ }
                    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}
                    override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
                }
                fun sync() { if (Motion.enabled && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) d.start() else d.stop() }
                val obs = LifecycleEventObserver { _, _ -> sync() }
                owner.lifecycle.addObserver(obs)
                sync()
                onDispose { owner.lifecycle.removeObserver(obs); d.stop(); d.callback = null }
            }
            Canvas(modifier.fillMaxSize()) {
                frame // read in the draw phase only: a new frame redraws, never recomposes
                val iw = d.intrinsicWidth.coerceAtLeast(1); val ih = d.intrinsicHeight.coerceAtLeast(1)
                val scale = maxOf(size.width / iw, size.height / ih)
                val w = (iw * scale).toInt(); val h = (ih * scale).toInt()
                val left = ((size.width - w) / 2).toInt(); val top = ((size.height - h) / 2).toInt()
                d.setBounds(left, top, left + w, top + h)
                drawIntoCanvas { d.draw(it.nativeCanvas) }
            }
        }
    }
}
