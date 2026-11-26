package com.lockwallet.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import com.lockwallet.app.ui.Help
import com.lockwallet.app.ui.HelpTopic
import com.lockwallet.app.ui.S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Branding: the name is exactly "Lock Wallet", no test-network wording anywhere visible, a proper adaptive icon. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandingTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val banned = Regex("devnet|testnet|phantom|solflare|backpack|ledger|metamask|trust\\s+wallet|uniswap|exodus|coinbase\\s+wallet", RegexOption.IGNORE_CASE)

    @Test fun appLabelIsExactlyLockWallet() {
        assertEquals("Lock Wallet", ctx.packageManager.getApplicationLabel(ctx.applicationInfo).toString())
        assertEquals("Lock Wallet", ctx.getString(R.string.app_name))
        val main = ctx.packageManager.getActivityInfo(android.content.ComponentName(ctx, MainActivity::class.java), 0)
        assertEquals("Lock Wallet", main.loadLabel(ctx.packageManager).toString())
    }

    @Test fun noUserVisibleStringContainsExternalWalletBranding() {
        // 1) Every string resource of the app (all qualifiers).
        val res = File(System.getProperty("lockwallet.resDir")!!)
        val xml = res.walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile!!.name.startsWith("values") }.toList()
        assertTrue(xml.isNotEmpty())
        for (f in xml) {
            val text = f.readText()
            Regex("<string[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
                assertTrue("${f.name}: ${m.value}", !banned.containsMatchIn(m.groupValues[1]))
            }
        }
        // 2) Every UI string constant and every help topic.
        val ui = S::class.java.declaredFields.filter { it.type == String::class.java }.map { it.isAccessible = true; it.get(null) as String }
        assertTrue(ui.size > 50)
        ui.forEach { assertTrue(it, !banned.containsMatchIn(it)) }
        val topics = Help::class.java.declaredFields.filter { it.type == HelpTopic::class.java }.map { it.isAccessible = true; it.get(Help) as HelpTopic }
        assertTrue(topics.size > 30)
        topics.forEach { t -> listOf(t.title, t.what, t.why, t.example).forEach { assertTrue(t.title, !banned.containsMatchIn(it)) } }
    }

    @Test fun launcherIconIsAdaptiveWithMonochromeAndRoundVariant() {
        val info = ctx.applicationInfo
        for (id in listOf(info.icon, R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val d = ctx.getDrawable(id)
            assertTrue(d is AdaptiveIconDrawable)
            d as AdaptiveIconDrawable
            assertNotNull(d.background); assertNotNull(d.foreground); assertNotNull(d.monochrome)
        }
        assertNotNull(ctx.getDrawable(R.drawable.splash_icon))
        // Renders crisply at every launcher size and the mark is really there (not a blank plate).
        for (px in listOf(48, 72, 96, 144, 192, 512)) {
            val b = render(ctx.getDrawable(R.mipmap.ic_launcher)!!, px, Mask.CIRCLE)
            val centre = b.getPixel((px * 0.38f).toInt(), (px * 0.62f).toInt())     // padlock body (beside the keyhole)
            val corner = b.getPixel((px * 0.25f).toInt(), (px * 0.25f).toInt())  // dark background
            assertTrue("px=$px", Color.red(centre) > 180 && Color.red(corner) < 80)
        }
        System.getProperty("lockwallet.iconPreview")?.takeIf { it.isNotEmpty() }?.let { out ->
            File(out).parentFile?.mkdirs()
            File(out).outputStream().use { render(ctx.getDrawable(R.mipmap.ic_launcher)!!, 512, Mask.SQUIRCLE).compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Contact sheet for review: masks, sizes, themed (monochrome) icon on light and dark wallpapers.
            File(File(out).parentFile, "icon-sheet.png").outputStream().use { sheet().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private enum class Mask { CIRCLE, SQUIRCLE, ROUNDED, TEARDROP }

    private fun maskPath(m: Mask, s: Float): Path = Path().apply {
        when (m) {
            Mask.CIRCLE -> addCircle(s / 2, s / 2, s / 2, Path.Direction.CW)
            Mask.ROUNDED -> addRoundRect(RectF(0f, 0f, s, s), s * 0.18f, s * 0.18f, Path.Direction.CW)
            Mask.SQUIRCLE -> addRoundRect(RectF(0f, 0f, s, s), s * 0.32f, s * 0.32f, Path.Direction.CW)
            Mask.TEARDROP -> addRoundRect(RectF(0f, 0f, s, s), floatArrayOf(s / 2, s / 2, s / 2, s / 2, s * 0.12f, s * 0.12f, s / 2, s / 2), Path.Direction.CW)
        }
    }

    /** Adaptive icon rendering as launchers do it: 108 dp layers, the inner 72 dp visible, clipped by the mask. */
    private fun render(d: Drawable, px: Int, mask: Mask, mono: Boolean = false, monoFg: Int = Color.BLACK, monoBg: Int = Color.WHITE): Bitmap {
        val a = d as AdaptiveIconDrawable
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.save(); c.clipPath(maskPath(mask, px.toFloat()))
        val full = (px * 108f / 72f).toInt(); val off = (px - full) / 2
        if (mono) {
            c.drawColor(monoBg)
            val m = a.monochrome!!.mutate(); m.setBounds(off, off, off + full, off + full); m.setTint(monoFg); m.draw(c)
        } else {
            a.background.setBounds(off, off, off + full, off + full); a.background.draw(c)
            a.foreground.setBounds(off, off, off + full, off + full); a.foreground.draw(c)
        }
        c.restore()
        return b
    }

    private fun sheet(): Bitmap {
        val w = 1200; val h = 560
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        c.drawColor(Color.rgb(128, 128, 134))
        val icon = ctx.getDrawable(R.mipmap.ic_launcher)!!
        var x = 30f
        for (m in Mask.entries) { c.drawBitmap(render(icon, 220, m), x, 30f, null); x += 250f }
        x = 30f
        for (s in listOf(192, 144, 96, 72, 48)) { c.drawBitmap(render(icon, s, Mask.CIRCLE), x, 290f + (192 - s) / 2f, null); x += s + 30f }
        c.drawBitmap(render(icon, 192, Mask.CIRCLE, mono = true, monoFg = Color.rgb(30, 50, 40), monoBg = Color.rgb(200, 230, 210)), x + 20f, 290f, null)
        c.drawBitmap(render(icon, 192, Mask.CIRCLE, mono = true, monoFg = Color.rgb(200, 230, 210), monoBg = Color.rgb(30, 50, 40)), x + 240f, 290f, null)
        Paint().xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        return b
    }
}
