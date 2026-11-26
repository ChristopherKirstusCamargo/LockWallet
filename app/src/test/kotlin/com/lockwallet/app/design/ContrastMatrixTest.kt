package com.lockwallet.app.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.lockwallet.app.ui.Dark
import com.lockwallet.app.ui.Light
import com.lockwallet.app.ui.Palette
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private fun ratio(a: Color, b: Color): Double { val la = a.luminance() + 0.05; val lb = b.luminance() + 0.05; return maxOf(la, lb) / minOf(la, lb).toDouble() }

/**
 * WCAG contrast of the shared glass material in both themes, over every backdrop the blur can produce behind a
 * floating surface: from the plain background to the opposite colour, in 5 % steps. Floors: primary text ≥ 7:1 (AAA)
 * everywhere; secondary text ≥ 4.5:1 (AA) up to a 30 % mix and ≥ 3:1 (AA large/UI) up to 60 %; price colours ≥ 3:1;
 * rims visible but subtle.
 */
@RunWith(Parameterized::class)
class ContrastMatrixTest(private val theme: String, private val surface: String, private val mixPct: Int) {
    companion object {
        private val SURFACES = listOf("panel", "nested", "sheet", "pill")
        @JvmStatic @Parameterized.Parameters(name = "{0} {1} over {2}% opposite")
        fun data(): List<Array<Any>> = listOf("dark", "light").flatMap { t -> SURFACES.flatMap { s -> (0..100 step 5).map { arrayOf<Any>(t, s, it) } } }
    }

    @Test fun legible() {
        val p: Palette = if (theme == "dark") Dark else Light
        val m = mixPct / 100f
        val backdrop = lerp(p.bg, p.text, m) // what the blur averages to behind the surface
        val panelLike = surface == "panel" || surface == "nested"
        // Panels (cards, settings groups) lie on the page itself, whose blurred background stays within ~10 % of bg;
        // floating surfaces (sheets, dialogs, send/confirm, pill) are checked over the full range.
        val effective = if (panelLike) lerp(p.bg, p.text, minOf(m, 0.10f)) else backdrop
        val surf = when (surface) {
            "panel" -> p.glass.compositeOver(effective)
            "nested" -> p.glass.compositeOver(p.glass.compositeOver(effective))
            else -> p.glassStrong.compositeOver(effective)
        }
        val t = ratio(p.text, surf)
        assertTrue("$theme $surface text $t at $mixPct%", t >= 7.0)
        val d = ratio(p.dim, surf)
        if (mixPct <= 30) assertTrue("$theme $surface dim $d at $mixPct%", d >= 4.5)
        if (mixPct <= 60) assertTrue("$theme $surface dim $d at $mixPct%", d >= 3.0)
        if (mixPct <= 30) {
            assertTrue("$theme $surface up ${ratio(p.up, surf)}", ratio(p.up, surf) >= 3.0)
            assertTrue("$theme $surface down ${ratio(p.down, surf)}", ratio(p.down, surf) >= 3.0)
        }
        val rim = p.glassBorder.compositeOver(surf)
        val r = ratio(rim, surf)
        assertTrue("$theme $surface rim $r", r in 1.05..2.5)
    }
}

/** Monochrome identity: every surface colour of both themes is grey; only price change has hue. */
@RunWith(Parameterized::class)
class MonochromeTest(private val theme: String, private val role: String) {
    companion object {
        private val ROLES = listOf("bg", "text", "dim", "faint", "glass", "glassStrong", "glassBorder", "glow", "scrim", "edgeHi", "edgeLo", "sheen")
        @JvmStatic @Parameterized.Parameters(name = "{0} {1}") fun data(): List<Array<Any>> = listOf("dark", "light").flatMap { t -> ROLES.map { arrayOf<Any>(t, it) } }
    }

    @Test fun grey() {
        val p = if (theme == "dark") Dark else Light
        val c = when (role) {
            "bg" -> p.bg; "text" -> p.text; "dim" -> p.dim; "faint" -> p.faint; "glass" -> p.glass; "glassStrong" -> p.glassStrong
            "glassBorder" -> p.glassBorder; "glow" -> p.glow; "scrim" -> p.scrim; "edgeHi" -> p.edgeHi; "edgeLo" -> p.edgeLo; else -> p.sheen
        }
        assertTrue("$theme $role $c", kotlin.math.abs(c.red - c.green) < 0.01f && kotlin.math.abs(c.green - c.blue) < 0.03f)
        if (role == "glassStrong") assertTrue("sheet must stay see-through", c.alpha in 0.55f..0.8f)
        if (role == "glass") assertTrue("panel glass must stay thin", c.alpha <= 0.06f)
    }
}
