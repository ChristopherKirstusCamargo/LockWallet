package com.lockwallet.app.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Owner rules (design 3.0, "gourmet"): exactly two monochrome themes — white-on-black and black-on-white — equally
 * polished, with layered glass and a soft frosted "wool" grain. The only hues in the app are green/red for price
 * change. Status is told by icon + words, never colour.
 */
class Palette(
    val bg: Color, val text: Color, val dim: Color, val faint: Color, val glass: Color, val glassStrong: Color,
    val glassBorder: Color, val glow: Color, val scrim: Color, val up: Color, val down: Color,
    /** Top-edge highlight of glass (light catching the rim) and the bottom rim. */
    val edgeHi: Color, val edgeLo: Color,
    /** Soft vertical sheen inside glass (top) and the frosted grain strength. */
    val sheen: Color, val grain: Float,
)

// 4.0 "true glass": thinner fills (more see-through), sheets at ~78% over a blurred backdrop, crisper rims so the
// edges still read. Contrast is pinned by GlassContrastTest (text ≥ 7:1 even over the opposite colour behind a sheet).
internal val Dark = Palette(
    bg = Color(0xFF000000), text = Color(0xFFFFFFFF), dim = Color(0xFF8E8E93), faint = Color(0xFF3A3A3C),
    glass = Color(0x0FFFFFFF), glassStrong = Color(0xC70C0C0E), glassBorder = Color(0x24FFFFFF), glow = Color(0x38FFFFFF),
    scrim = Color(0x8C000000), up = Color(0xFF34C759), down = Color(0xFFFF453A),
    edgeHi = Color(0x57FFFFFF), edgeLo = Color(0x0FFFFFFF), sheen = Color(0x0FFFFFFF), grain = 0.04f,
)
internal val Light = Palette(
    bg = Color(0xFFFFFFFF), text = Color(0xFF000000), dim = Color(0xFF636368), faint = Color(0xFFD1D1D6),
    glass = Color(0x07000000), glassStrong = Color(0xC7F7F7F9), glassBorder = Color(0x1C000000), glow = Color(0x24000000),
    scrim = Color(0x5C000000), up = Color(0xFF248A3D), down = Color(0xFFD70015),
    edgeHi = Color(0x1A000000), edgeLo = Color(0x24000000), sheen = Color(0x80FFFFFF), grain = 0.03f,
)

object C {
    /** Selected theme (source of truth; persisted by the controller). */
    var dark by mutableStateOf(true)
    /** 1 = dark, 0 = light; animated by [LockTheme] so a theme switch cross-fades every colour instead of snapping. */
    internal var mix by mutableFloatStateOf(1f)
    private fun pick(f: Palette.() -> Color): Color {
        val m = mix
        return if (m >= 1f) Dark.f() else if (m <= 0f) Light.f() else lerp(Light.f(), Dark.f(), m)
    }
    val bg get() = pick { bg }
    val text get() = pick { text }
    val dim get() = pick { dim }
    val faint get() = pick { faint }
    val glass get() = pick { glass }
    val glassStrong get() = pick { glassStrong }
    val glassBorder get() = pick { glassBorder }
    val glow get() = pick { glow }
    val scrim get() = pick { scrim }
    val edgeHi get() = pick { edgeHi }
    val edgeLo get() = pick { edgeLo }
    val sheen get() = pick { sheen }
    val grain: Float get() = Light.grain + (Dark.grain - Light.grain) * mix
    /** Price change only. */
    val up get() = pick { up }
    val down get() = pick { down }
    /** Monochrome aliases kept for call sites that name intent. */
    val accent get() = text
    val ok get() = text
    val warn get() = text
    val risk get() = text
    val surface get() = glass
    val line get() = glassBorder
}

/** Shared motion: springs for movement, emphasized easing for fades/colour. */
object Motion {
    /** Material "emphasized" curve: quick start, long soft landing. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    fun <T> soft() = spring<T>(dampingRatio = 0.88f, stiffness = 360f)
    fun <T> bouncy() = spring<T>(dampingRatio = 0.62f, stiffness = 460f)
    fun <T> snappy() = spring<T>(dampingRatio = 0.92f, stiffness = Spring.StiffnessMediumLow)
    fun <T> gentle() = spring<T>(dampingRatio = 1f, stiffness = 220f)
    fun <T> fade(ms: Int = 260) = tween<T>(ms, easing = Emphasized)
    val slide = spring<IntOffset>(dampingRatio = 0.9f, stiffness = 340f)
    val sheet = spring<IntOffset>(dampingRatio = 0.86f, stiffness = 300f)
}

/** Spacing scale (4-pt grid). */
object Space {
    val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp
    /** Page gutter: tighter on narrow phones (≤ 360 dp) so content keeps its width. */
    @Composable fun gutter(): Dp = if (LocalConfiguration.current.screenWidthDp <= 360) 16.dp else 22.dp
}

/** Type scale (sp). One place, so every screen uses the same rhythm. */
object Type {
    val display = 52.sp; val hero = 44.sp; val title = 30.sp; val titleCompact = 26.sp; val headline = 21.sp
    val body = 16.sp; val bodySmall = 15.sp; val caption = 13.sp; val label = 12.sp
}

/** Visual effects capability. RenderEffect blur needs API 31 (our minSdk); otherwise a denser scrim stands in. */
object Fx {
    val blurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    /** Backdrop blur radius for content behind sheets, menus and the drawer. */
    val backdrop = 28.dp
}

/** Blur that degrades gracefully: no-op (and no cost) at 0 or where RenderEffect is missing. */
fun Modifier.softBlur(radius: Dp): Modifier = if (!Fx.blurSupported || radius <= 0.5.dp) this else this.blur(radius)

// ---------------------------------------------------------------------------------------------------- wool grain

/**
 * Frosted "wool" grain: a tiny tileable noise texture (96×96, fixed seed, built once — < 1 ms) drawn at a few percent
 * alpha over glass, which gives the surfaces a soft felt/frosted feel instead of flat plastic.
 */
internal object Wool {
    private const val N = 96
    val bitmap: ImageBitmap by lazy {
        val px = IntArray(N * N)
        var s = 0x2545F491L
        for (i in px.indices) {
            s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
            val v = ((s ushr 24) and 0xFF).toInt()
            // Soft distribution around mid-grey (sum of two samples): fewer harsh specks.
            val w = ((v + ((s ushr 40) and 0xFF).toInt()) / 2)
            px[i] = (0xFF shl 24) or (w shl 16) or (w shl 8) or w
        }
        android.graphics.Bitmap.createBitmap(px, N, N, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val brush: ShaderBrush by lazy { ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)) }
}

/**
 * Layered glass: translucent fill → soft top sheen → frosted grain → rim light (bright top edge fading to a faint
 * bottom edge). Everything is cached per size with drawWithCache: no allocation per frame.
 */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), strong: Boolean = false): Modifier {
    // Strong glass (sheets/menus) is see-through only where the content behind is really blurred; otherwise near-opaque.
    val fill = if (strong) C.glassStrong.let { if (Fx.blurSupported) it else it.copy(alpha = maxOf(it.alpha, 0.94f)) } else C.glass
    val sheen = C.sheen; val hi = C.edgeHi; val lo = C.edgeLo; val grain = C.grain
    return this.clip(shape).drawWithCache {
        val outline: Outline = shape.createOutline(size, layoutDirection, this)
        val sheenBrush = Brush.verticalGradient(0f to sheen, 0.55f to Color.Transparent, startY = 0f, endY = size.height)
        val rim = Brush.verticalGradient(listOf(hi, lo), startY = 0f, endY = size.height)
        val stroke = Stroke(width = 1.dp.toPx())
        onDrawBehind {
            drawOutline(outline, fill)
            drawOutline(outline, sheenBrush)
            drawOutline(outline, Wool.brush, alpha = grain)
            drawOutline(outline, rim, style = stroke)
        }
    }
}

/** Soft monochrome glow behind glass surfaces (blurred with RenderEffect; plain radial gradients as fallback). */
@Composable
fun AmbientBackground() {
    val glow = C.glow
    val bg = C.bg
    val m = Modifier.fillMaxSize().background(bg)
    Canvas(if (Fx.blurSupported) m.blur(110.dp) else m) {
        fun orb(c: Color, r: Float, at: Offset) =
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center = at, radius = r), radius = r, center = at)
        orb(glow, size.minDimension * 0.62f, Offset(size.width * 0.12f, size.height * 0.05f))
        orb(glow.copy(alpha = glow.alpha * 0.7f), size.minDimension * 0.46f, Offset(size.width * 0.98f, size.height * 0.40f))
        orb(glow.copy(alpha = glow.alpha * 0.45f), size.minDimension * 0.5f, Offset(size.width * 0.3f, size.height * 0.98f))
    }
}

/** Haptic vocabulary (respects the user's "Vibração" setting). */
enum class Haptic { TICK, CONFIRM, REJECT, PRESS, SOFT }

val LocalHaptics = staticCompositionLocalOf<(Haptic) -> Unit> { {} }

@Composable
fun rememberHapticPerformer(enabled: () -> Boolean): (Haptic) -> Unit {
    val view = LocalView.current
    return { h ->
        if (enabled()) view.performHapticFeedback(when (h) {
            Haptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.CONFIRM -> HapticFeedbackConstants.CONFIRM
            Haptic.REJECT -> HapticFeedbackConstants.REJECT
            Haptic.PRESS -> HapticFeedbackConstants.CONTEXT_CLICK
            Haptic.SOFT -> HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        })
    }
}

@Composable
fun LockTheme(haptics: (Haptic) -> Unit = {}, content: @Composable () -> Unit) {
    // Theme cross-fade (420 ms, emphasized). Starts at the current theme: no flash on launch or in tests.
    val target = if (C.dark) 1f else 0f
    val anim = remember { Animatable(target) }
    LaunchedEffect(target) { C.mix = anim.value; anim.animateTo(target, tween(420, easing = Motion.Emphasized)) { C.mix = value } }
    MaterialTheme(
        colorScheme = if (C.dark) darkColorScheme(primary = C.text, onPrimary = C.bg, background = C.bg, surface = C.bg, onSurface = C.text,
            onBackground = C.text, outline = C.glassBorder, surfaceVariant = C.glass)
        else lightColorScheme(primary = C.text, onPrimary = C.bg, background = C.bg, surface = C.bg, onSurface = C.text,
            onBackground = C.text, outline = C.glassBorder, surfaceVariant = C.glass),
    ) { CompositionLocalProvider(LocalHaptics provides haptics, content = content) }
}
