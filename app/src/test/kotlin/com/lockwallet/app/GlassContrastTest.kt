package com.lockwallet.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.lockwallet.app.ui.Dark
import com.lockwallet.app.ui.Light
import com.lockwallet.app.ui.Palette
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.0 "true glass" legibility guard (WCAG contrast). Glass got more see-through; these floors keep it readable:
 *  - text on panel glass over the background ≥ 15:1, secondary text ≥ 4.5:1;
 *  - text on a sheet (strong glass) ≥ 7:1 even if the opposite colour sits right behind it, unblurred (worst case);
 *  - secondary text on a sheet ≥ 4.5:1 over the plain background and ≥ 3.5:1 over a 50 % blurred mix.
 */
class GlassContrastTest {
    private fun ratio(a: Color, b: Color): Double { val la = a.luminance() + 0.05; val lb = b.luminance() + 0.05; return maxOf(la, lb) / minOf(la, lb).toDouble() }
    private fun check(name: String, p: Palette) {
        val panel = p.glass.compositeOver(p.bg)
        assertTrue("$name text/panel ${ratio(p.text, panel)}", ratio(p.text, panel) >= 15)
        assertTrue("$name dim/panel ${ratio(p.dim, panel)}", ratio(p.dim, panel) >= 4.5)
        val worst = p.glassStrong.compositeOver(p.text) // opposite colour right behind the sheet
        assertTrue("$name text/sheet-worst ${ratio(p.text, worst)}", ratio(p.text, worst) >= 7)
        val plain = p.glassStrong.compositeOver(p.bg)
        assertTrue("$name dim/sheet ${ratio(p.dim, plain)}", ratio(p.dim, plain) >= 4.5)
        val half = p.glassStrong.compositeOver(p.text.copy(alpha = 0.5f).compositeOver(p.bg))
        assertTrue("$name dim/sheet-blurred ${ratio(p.dim, half)}", ratio(p.dim, half) >= 3.5)
        // See-through, not opaque: a sheet must let at least ~20 % of the blurred backdrop show.
        assertTrue("$name sheet too opaque", p.glassStrong.alpha <= 0.8f)
        assertTrue("$name panel glass too thick", p.glass.alpha <= 0.06f)
        // Monochrome identity: every surface colour is a pure grey.
        for (c in listOf(p.bg, p.text, p.dim, p.faint, p.glass, p.glassStrong, p.glassBorder, p.glow)) {
            assertTrue("$name $c not grey", kotlin.math.abs(c.red - c.green) < 0.01f && kotlin.math.abs(c.green - c.blue) < 0.03f)
        }
    }
    @Test fun darkGlassStaysLegible() = check("dark", Dark)
    @Test fun lightGlassStaysLegible() = check("light", Light)
}
