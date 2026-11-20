package com.lockwallet.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.rules.ExternalResource
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lockwallet.app.ui.WordGrid
import com.lockwallet.app.ui.secretWords
import com.lockwallet.custody.local.api.RecoveryPasswords
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Generated LWRP password (v3 review): what is shown is exactly what encrypts the file, and every word stays fully
 * on screen on a 320 dp phone with a 1.3× font (v2 drew it on one clipped canvas line).
 */
@RunWith(RobolectricTestRunner::class)
class ExportPasswordLayoutTest {
    @get:Rule(order = 0) val offline = object : ExternalResource() {
        override fun before() { MainActivity.txJournalAuthenticatorOverride = AppTestTxJournalAuthenticator(); MainActivity.rpcOverride = { null }; MainActivity.priceOverride = com.lockwallet.data.price.PriceService(emptyList(), { 0L }) }
        override fun after() { MainActivity.txJournalAuthenticatorOverride = null; MainActivity.priceOverride = null; MainActivity.rpcOverride = null }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Test fun generatedPasswordIs8WordsAndTheShownWordsRejoinToIt() {
        val seen = HashSet<String>()
        repeat(64) {
            val pw = RecoveryPasswords.generate()
            assertTrue(pw.size >= RecoveryPasswords.MIN_BYTES)
            val words = secretWords(pw)
            assertEquals(8, words.size)
            assertTrue(words.all { w -> w.isNotEmpty() && w.all { it in 'a'..'z' } })
            val rejoined = words.flatMapIndexed { i, w -> (if (i > 0) listOf(' ') else emptyList()) + w.toList() }.toCharArray()
            assertArrayEquals(pw, rejoined)
            seen += String(pw)
        }
        assertEquals(64, seen.size)
    }

    @Test fun secretWordsTolerateExtraSpaces() {
        assertEquals(listOf("ab", "cd"), secretWords("  ab   cd ".toCharArray()).map { String(it) })
        assertEquals(emptyList<String>(), secretWords(CharArray(0)).map { String(it) })
    }

    @Config(qualifiers = "w320dp-h568dp")
    @Test fun allEightWordsFitA320dpPhoneAtLargeFont() = layoutCheck(1.3f)

    @Config(qualifiers = "w320dp-h568dp")
    @Test fun allEightWordsFitA320dpPhoneAtHugeFont() = layoutCheck(2.0f)

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun allEightWordsFitARegularPhone() = layoutCheck(1.0f)

    private fun layoutCheck(scale: Float) {
        // Longest BIP-39 words (8 letters) — worst case.
        val pw = "abstract absorb accident acoustic actress adjust advance aerobic".toCharArray()
        val words = secretWords(pw)
        rule.runOnUiThread { rule.activity.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { WordGrid(words, tag = "export-password") }
            }
        } }
        rule.waitForIdle()
        rule.onNodeWithTag("reveal-words").performClick()
        val root = rule.onRoot().getBoundsInRoot()
        for (i in 1..8) {
            val b = rule.onNodeWithTag("word-$i", useUnmergedTree = true).getBoundsInRoot()
            assertTrue("word $i off screen: $b vs $root", b.left >= root.left && b.right <= root.right)
            // The canvas shrinks text to its width down to 60 %: the cell must give it at least that much room.
            val needed = 8 * 17 * 0.62f * scale * 0.6f   // ≈ bold glyph width (dp) × chars × min shrink
            assertTrue("word $i cell too narrow: ${b.right - b.left} < $needed", (b.right - b.left).value >= needed)
        }
    }
}
