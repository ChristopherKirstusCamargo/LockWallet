package com.lockwallet.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Line icon set drawn on a 24-unit grid (no icon dependency, no bitmaps; crisp at any size, follows the theme). */
enum class Ic { BACK, CLOSE, PLUS, SEND, RECEIVE, STAKE, SETTINGS, HISTORY, HELP, PERSON, SCAN, COPY, CHEVRON, CHEVRON_DOWN,
    LOCK, SHIELD, EYE, EYE_OFF, KEY, GLOBE, FINGER, WARN, CHECK, SEARCH, PASTE, TRASH, WALLET, BOLT, INFO, LINK, MOON, COIN,
    FILE, CHIP, CLOCK, ARROW_UP, ARROW_DOWN, SPLIT, MERGE, HOME, VIBRATE, PHONE, X_CIRCLE, SPARK, CHART, SWAP, IMAGE, RINGS, CONTACTS, GAUGE, LIST_CHECK }

@Composable
fun Icon(ic: Ic, color: Color = C.text, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { Pen(this, color).draw(ic) }
}

private class Pen(val d: DrawScope, val color: Color) {
    val u = d.size.minDimension / 24f
    val st = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun pl(pts: FloatArray, close: Boolean = false) = d.drawPath(Path().apply {
        moveTo(pts[0] * u, pts[1] * u); var i = 2
        while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
        if (close) close()
    }, color, style = st)
    fun l(vararg f: Float) = pl(f)
    fun poly(vararg f: Float) = pl(f, close = true)
    fun o(x: Float, y: Float, r: Float) = d.drawCircle(color, r * u, Offset(x * u, y * u), style = st)
    fun dot(x: Float, y: Float, r: Float = 1.1f) = d.drawCircle(color, r * u, Offset(x * u, y * u))
    fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) = d.drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u), style = st)
    fun oval(x: Float, y: Float, w: Float, h: Float) = d.drawOval(color, Offset(x * u, y * u), Size(w * u, h * u), style = st)
    fun arc(x: Float, y: Float, r: Float, start: Float, sweep: Float) =
        d.drawArc(color, start, sweep, false, Offset((x - r) * u, (y - r) * u), Size(2 * r * u, 2 * r * u), style = st)

    fun draw(ic: Ic) {
        when (ic) {
            Ic.BACK -> l(15f, 5f, 8f, 12f, 15f, 19f)
            Ic.CLOSE -> { l(6f, 6f, 18f, 18f); l(18f, 6f, 6f, 18f) }
            Ic.PLUS -> { l(12f, 5f, 12f, 19f); l(5f, 12f, 19f, 12f) }
            Ic.SEND, Ic.ARROW_UP -> { l(12f, 19f, 12f, 5f); l(6f, 11f, 12f, 5f, 18f, 11f) }
            Ic.RECEIVE, Ic.ARROW_DOWN -> { l(12f, 5f, 12f, 19f); l(6f, 13f, 12f, 19f, 18f, 13f) }
            Ic.STAKE -> { poly(12f, 3f, 20f, 7.5f, 12f, 12f, 4f, 7.5f); l(4f, 12f, 12f, 16.5f, 20f, 12f); l(4f, 16.5f, 12f, 21f, 20f, 16.5f) }
            Ic.SETTINGS -> { o(12f, 12f, 3f); for (k in 0 until 8) { val a = Math.toRadians(k * 45.0); l(12f + 6.2f * Math.cos(a).toFloat(), 12f + 6.2f * Math.sin(a).toFloat(), 12f + 8.6f * Math.cos(a).toFloat(), 12f + 8.6f * Math.sin(a).toFloat()) }; o(12f, 12f, 6.2f) }
            Ic.HISTORY, Ic.CLOCK -> { o(12f, 12f, 8.5f); l(12f, 7.5f, 12f, 12f, 15f, 14f) }
            Ic.HELP -> { o(12f, 12f, 9f); arc(12f, 9.8f, 2.6f, 180f, 225f); l(12f, 12.6f, 12f, 13.8f); dot(12f, 16.8f) }
            Ic.INFO -> { o(12f, 12f, 9f); l(12f, 11f, 12f, 16.5f); dot(12f, 7.8f) }
            Ic.PERSON -> { o(12f, 8.5f, 3.8f); arc(12f, 21f, 7.5f, 200f, 140f) }
            Ic.SCAN -> { l(4f, 9f, 4f, 4f, 9f, 4f); l(15f, 4f, 20f, 4f, 20f, 9f); l(20f, 15f, 20f, 20f, 15f, 20f); l(9f, 20f, 4f, 20f, 4f, 15f); l(7.5f, 12f, 16.5f, 12f) }
            Ic.COPY -> { rr(8f, 8f, 12f, 12f, 2.5f); l(16f, 5f, 16f, 4f, 4f, 4f, 4f, 16f, 5f, 16f) }
            Ic.CHEVRON -> l(9.5f, 6f, 15.5f, 12f, 9.5f, 18f)
            Ic.CHEVRON_DOWN -> l(6f, 9.5f, 12f, 15.5f, 18f, 9.5f)
            Ic.RINGS -> { o(9f, 12f, 5.8f); o(15f, 12f, 5.8f) }
            Ic.CONTACTS -> { rr(5f, 3.5f, 14f, 17f, 2.5f); o(12f, 10f, 2.6f); arc(12f, 18.5f, 4.6f, 205f, 130f); l(3.5f, 8f, 5f, 8f); l(3.5f, 12f, 5f, 12f); l(3.5f, 16f, 5f, 16f) }
            Ic.GAUGE -> { arc(12f, 14f, 8.5f, 160f, 220f); l(12f, 14f, 15.8f, 9.2f); dot(12f, 14f) }
            Ic.LIST_CHECK -> { l(4f, 7f, 6f, 9f, 9.5f, 5.5f); l(12f, 7.5f, 20f, 7.5f); l(4f, 15f, 6f, 17f, 9.5f, 13.5f); l(12f, 15.5f, 20f, 15.5f) }
            Ic.LOCK -> { rr(5f, 10.5f, 14f, 10f, 2.5f); arc(12f, 9f, 4f, 180f, 180f); l(8f, 9f, 8f, 10.5f); l(16f, 9f, 16f, 10.5f); dot(12f, 15.5f) }
            Ic.SHIELD -> { poly(12f, 3f, 19.5f, 6f, 19f, 12.5f, 12f, 21f, 5f, 12.5f, 4.5f, 6f); l(8.8f, 12f, 11f, 14.2f, 15.4f, 9.8f) }
            Ic.EYE -> { pl(floatArrayOf(2.5f, 12f, 7f, 6.5f, 12f, 5.5f, 17f, 6.5f, 21.5f, 12f, 17f, 17.5f, 12f, 18.5f, 7f, 17.5f), close = true); o(12f, 12f, 3f) }
            Ic.EYE_OFF -> { pl(floatArrayOf(2.5f, 12f, 7f, 6.5f, 12f, 5.5f, 17f, 6.5f, 21.5f, 12f, 17f, 17.5f, 12f, 18.5f, 7f, 17.5f), close = true); l(4f, 4f, 20f, 20f) }
            Ic.KEY -> { o(8f, 15f, 4f); l(10.8f, 12.2f, 19f, 4f); l(16f, 7f, 18.5f, 9.5f); l(14f, 9f, 15.8f, 10.8f) }
            Ic.GLOBE -> { o(12f, 12f, 9f); l(3f, 12f, 21f, 12f); oval(8f, 3f, 8f, 18f) }
            Ic.FINGER -> { arc(12f, 12f, 8.5f, 200f, 140f); arc(12f, 13f, 5.5f, 180f, 180f); l(6.5f, 13f, 6.5f, 16f); l(17.5f, 13f, 17.5f, 15f); arc(12f, 13f, 2.5f, 180f, 180f); l(9.5f, 13f, 9.5f, 19f); l(14.5f, 13f, 14.5f, 20f); l(12f, 13f, 12f, 21f) }
            Ic.WARN -> { poly(12f, 3.5f, 21.5f, 20f, 2.5f, 20f); l(12f, 9.5f, 12f, 14f); dot(12f, 17f) }
            Ic.CHECK -> l(5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Ic.SEARCH -> { o(10.5f, 10.5f, 6.5f); l(15.5f, 15.5f, 20f, 20f) }
            Ic.PASTE -> { rr(5f, 5f, 14f, 16f, 2.5f); rr(9f, 3f, 6f, 4f, 1.5f); l(9f, 12f, 15f, 12f); l(9f, 16f, 13f, 16f) }
            Ic.TRASH -> { l(4f, 7f, 20f, 7f); l(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f); l(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f); l(10f, 11f, 10f, 16f); l(14f, 11f, 14f, 16f) }
            Ic.WALLET -> { rr(3.5f, 6f, 17f, 13f, 3f); l(6f, 6f, 15f, 3.5f, 16f, 6f); rr(14f, 10.5f, 6.5f, 4f, 2f) }
            Ic.BOLT -> poly(13f, 2.5f, 5f, 13.5f, 11f, 13.5f, 10f, 21.5f, 19f, 10f, 13f, 10f)
            Ic.LINK -> { arc(8.5f, 15.5f, 3.6f, 45f, 270f); arc(15.5f, 8.5f, 3.6f, 225f, 270f); l(9f, 15f, 15f, 9f) }
            Ic.MOON -> { arc(12f, 12f, 8.5f, 60f, 270f); arc(16.5f, 8f, 6f, 100f, 120f) }
            Ic.COIN -> { o(12f, 12f, 8.5f); o(12f, 12f, 5f) }
            Ic.FILE -> { poly(6f, 3f, 14f, 3f, 19f, 8f, 19f, 21f, 6f, 21f); l(14f, 3f, 14f, 8f, 19f, 8f) }
            Ic.CHIP -> { rr(6f, 6f, 12f, 12f, 2f); rr(9.5f, 9.5f, 5f, 5f, 1f); for (x in listOf(9f, 12f, 15f)) { l(x, 3f, x, 6f); l(x, 18f, x, 21f); l(3f, x, 6f, x); l(18f, x, 21f, x) } }
            Ic.SPLIT -> { l(12f, 21f, 12f, 12f); l(12f, 12f, 5f, 4f); l(12f, 12f, 19f, 4f); l(5f, 8.5f, 5f, 4f, 9.5f, 4f); l(14.5f, 4f, 19f, 4f, 19f, 8.5f) }
            Ic.MERGE -> { l(12f, 3f, 12f, 12f); l(12f, 12f, 5f, 20f); l(12f, 12f, 19f, 20f); l(8f, 6.5f, 12f, 3f, 16f, 6.5f) }
            Ic.HOME -> { l(4f, 10.5f, 12f, 4f, 20f, 10.5f); l(6f, 9f, 6f, 20f, 18f, 20f, 18f, 9f); rr(10f, 14f, 4f, 6f, 1f) }
            Ic.VIBRATE -> { rr(8f, 4f, 8f, 16f, 2f); l(4.5f, 8f, 4.5f, 16f); l(19.5f, 8f, 19.5f, 16f) }
            Ic.PHONE -> { rr(7f, 2.5f, 10f, 19f, 2.5f); l(10.5f, 18.5f, 13.5f, 18.5f) }
            Ic.X_CIRCLE -> { o(12f, 12f, 9f); l(9f, 9f, 15f, 15f); l(15f, 9f, 9f, 15f) }
            Ic.CHART -> { l(4f, 4f, 4f, 20f, 20f, 20f); l(7f, 15f, 11f, 11f, 14f, 13.5f, 19.5f, 7.5f); l(15.5f, 7.5f, 19.5f, 7.5f, 19.5f, 11.5f) }
            Ic.SWAP -> { l(5f, 9f, 19f, 9f); l(15f, 5f, 19f, 9f, 15f, 13f); l(19f, 15f, 5f, 15f); l(9f, 11f, 5f, 15f, 9f, 19f) }
            Ic.IMAGE -> { rr(3.5f, 5f, 17f, 14f, 2.5f); o(9f, 10f, 1.6f); l(5f, 17f, 10f, 13f, 13f, 15.5f, 15.5f, 12.5f, 19f, 16f) }
            Ic.SPARK -> { l(12f, 3f, 12f, 8f); l(12f, 16f, 12f, 21f); l(3f, 12f, 8f, 12f); l(16f, 12f, 21f, 12f); l(6f, 6f, 8.5f, 8.5f); l(15.5f, 15.5f, 18f, 18f); l(18f, 6f, 15.5f, 8.5f); l(8.5f, 15.5f, 6f, 18f) }
        }
    }
}
