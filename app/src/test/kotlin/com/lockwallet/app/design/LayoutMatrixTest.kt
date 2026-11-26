package com.lockwallet.app.design

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.lockwallet.app.MainActivity
import com.lockwallet.app.ui.AnimatedNumber
import com.lockwallet.app.ui.AssetRow
import com.lockwallet.app.ui.C
import com.lockwallet.app.ui.Compact
import com.lockwallet.app.ui.FitText
import com.lockwallet.app.ui.Group
import com.lockwallet.app.ui.Ic
import com.lockwallet.app.ui.ListRow
import com.lockwallet.app.ui.Motion
import com.lockwallet.app.ui.SolLogo
import com.lockwallet.data.price.Fiat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import java.math.BigDecimal

internal val THEMES = listOf(true, false)
internal val FONT_SCALES = listOf(0.85f, 1f, 1.3f, 2f)
internal val WIDTHS = listOf(320, 360, 412, 600)
internal val HUGE = listOf("0", "1234.56", "999999.99", "1259000000", "987654321098765432109876")

private fun DpRect.inside(o: DpRect, slack: Float = 0.5f) =
    left.value >= o.left.value - slack && right.value <= o.right.value + slack && top.value >= o.top.value - slack && bottom.value <= o.bottom.value + slack

/** Common base: offline app, animations off (layouts are measured at rest), theme restored afterwards. */
abstract class LayoutBase(private val dark: Boolean, private val fontScale: Float, private val widthDp: Int) {
    @get:Rule(order = 0) val offline = object : ExternalResource() {
        override fun before() { MainActivity.rpcOverride = { null }; MainActivity.priceOverride = com.lockwallet.data.price.PriceService(emptyList(), { 0L }); Motion.enabled = false }
        override fun after() { MainActivity.priceOverride = null; MainActivity.rpcOverride = null; C.dark = true; Motion.enabled = true; Compact.enabled = true }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    protected fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.runOnUiThread {
            C.dark = dark
            rule.activity.setContent {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    Box(Modifier.testTag("frame").width(widthDp.dp).background(C.bg)) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    protected fun frame() = rule.onNodeWithTag("frame").getBoundsInRoot()
}

/** Hero balance: every magnitude fits one line at every width, font scale and theme (never clipped or wrapped). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class HeroBalanceLayoutTest(private val dark: Boolean, private val fontScale: Float, private val widthDp: Int, private val value: String) :
    LayoutBase(dark, fontScale, widthDp) {
    companion object {
        @JvmStatic @Parameters(name = "dark={0} font={1} w={2} v={3}")
        fun data(): List<Array<Any>> = THEMES.flatMap { t -> FONT_SCALES.flatMap { f -> WIDTHS.flatMap { w -> HUGE.map { arrayOf<Any>(t, f, w, it) } } } }
    }

    @Test fun fitsOneLine() {
        val text = Compact.fiat(BigDecimal(value), Fiat.BRL)
        show { Box(Modifier.testTag("hero")) { AnimatedNumber(text, com.lockwallet.app.ui.HERO_SIZE) } }
        val f = frame(); val n = rule.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("$text overflows: $n vs $f", n.inside(f))
        val h = n.bottom.value - n.top.value
        assertTrue("$text wrapped? height $h", h < 64 * fontScale * 1.9f)
        assertTrue(text.length <= 24)
    }
}

/** Asset rows: long names and huge values never overlap or leave the row. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class AssetRowLayoutTest(private val dark: Boolean, private val fontScale: Float, private val widthDp: Int, private val name: String) :
    LayoutBase(dark, fontScale, widthDp) {
    companion object {
        private val NAMES = listOf("SOL", "Token com um nome realmente muito comprido para uma linha", "W".repeat(64))
        @JvmStatic @Parameters(name = "dark={0} font={1} w={2} name={3}")
        fun data(): List<Array<Any>> = THEMES.flatMap { t -> FONT_SCALES.flatMap { f -> WIDTHS.flatMap { w -> NAMES.map { arrayOf<Any>(t, f, w, it) } } } }
    }

    @Test fun noOverlap() {
        val right = Compact.fiat(BigDecimal("987654321098765432"), Fiat.BRL)
        show { AssetRow(logo = { SolLogo() }, name = name, sub = Compact.sol(Long.MAX_VALUE), right = right, change = BigDecimal("-0.1234"), tag = "row") {} }
        val f = frame(); val row = rule.onNodeWithTag("row").getBoundsInRoot()
        assertTrue("row $row outside $f", row.inside(f))
        val r = rule.onNodeWithText(right, useUnmergedTree = true).getBoundsInRoot()
        val n = rule.onNodeWithText(name, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("value outside row", r.inside(row))
        assertTrue("name $n overlaps value $r", n.right.value <= r.left.value + 0.5f)
    }
}

/** Settings rows: long titles and values keep the chevron on screen and never overlap. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRowLayoutTest(private val dark: Boolean, private val fontScale: Float, private val widthDp: Int) : LayoutBase(dark, fontScale, widthDp) {
    companion object {
        @JvmStatic @Parameters(name = "dark={0} font={1} w={2}")
        fun data(): List<Array<Any>> = THEMES.flatMap { t -> FONT_SCALES.flatMap { f -> WIDTHS.map { arrayOf<Any>(t, f, it) } } }
    }

    @Test fun rowsStayInside() {
        val longTitle = "Frase de recuperação da carteira principal de investimentos"
        show {
            Column {
                Group("Segurança") {
                    ListRow(longTitle, Ic.KEY, value = "Conta de investimentos muito longa", tag = "r1") {}
                    ListRow("Moeda", Ic.COIN, value = "R$ · BRL", tag = "r2") {}
                }
            }
        }
        val f = frame()
        for (t in listOf("r1", "r2")) assertTrue("$t outside", rule.onNodeWithTag(t).getBoundsInRoot().inside(f))
        val title = rule.onNodeWithText(longTitle, useUnmergedTree = true).getBoundsInRoot()
        val value = rule.onNodeWithText("Conta de investimentos muito longa", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("title $title overlaps value $value", title.right.value <= value.left.value + 0.5f)
    }
}

/** Review amount: one line, inside the screen, for any amount and unit. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewAmountLayoutTest(private val dark: Boolean, private val fontScale: Float, private val widthDp: Int, private val units: Long) :
    LayoutBase(dark, fontScale, widthDp) {
    companion object {
        @JvmStatic @Parameters(name = "dark={0} font={1} w={2} units={3}")
        fun data(): List<Array<Any>> = THEMES.flatMap { t -> FONT_SCALES.flatMap { f -> WIDTHS.flatMap { w -> listOf(1L, 1_234_567_890_123L, Long.MAX_VALUE).map { arrayOf<Any>(t, f, w, it) } } } }
    }

    @Test fun fits() {
        val text = Compact.units(units, 9) + " SOL"
        show { FitText(text, C.text, androidx.compose.ui.unit.TextUnit(40f, androidx.compose.ui.unit.TextUnitType.Sp), androidx.compose.ui.text.font.FontWeight.Black, Modifier.testTag("amt")) }
        val b = rule.onNodeWithTag("amt").getBoundsInRoot()
        assertTrue("$text outside", b.inside(frame()))
        assertTrue("$text wrapped", b.bottom.value - b.top.value < 40 * fontScale * 1.9f)
    }
}
