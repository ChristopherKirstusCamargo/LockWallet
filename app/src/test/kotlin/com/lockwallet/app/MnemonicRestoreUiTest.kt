package com.lockwallet.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.lockwallet.app.ui.App
import com.lockwallet.app.ui.C
import com.lockwallet.app.ui.Platform
import com.lockwallet.custody.local.DeviceSecurityCapabilityManager
import com.lockwallet.custody.local.LocalSecureProvider
import com.lockwallet.data.price.PriceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class MnemonicRestoreUiTest {
    @get:Rule(order = 0) val offline = object : ExternalResource() {
        override fun before() {
            MainActivity.rpcOverride = { null }
            MainActivity.priceOverride = PriceService(emptyList(), { 0L })
        }
        override fun after() {
            MainActivity.priceOverride = null
            MainActivity.rpcOverride = null
            C.dark = true
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val tmp = TemporaryFolder()

    @Test fun restoreShowsAndSelectsEnglishBip39SuggestionWithoutExposingPhrase() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val provider = LocalSecureProvider(tmp.newFolder(), SoftKek(), Clock())
        val c = AppController(
            provider,
            DeviceSecurityCapabilityManager(ctx, provider),
            CoroutineScope(Dispatchers.Default),
            Prefs(ctx),
            PriceService(emptyList(), { 0L }),
            { _, _ -> true },
            transport = null,
            dataDir = tmp.newFolder(),
        ).also { it.start(); it.startFlow(FlowMode.RESTORE) }

        rule.runOnUiThread { rule.activity.setContent { App(c, Platform({}, {}, {})) } }
        rule.waitForIdle()
        rule.onNodeWithTag("custody-local").performClick()
        rule.onNodeWithTag("role-vault").performClick()
        rule.onNodeWithTag("r-phrase").performClick()
        rule.onNodeWithTag("phrase-input").performTextInput("neither lonely fla")
        rule.onNodeWithTag("mnemonic-suggestion-flavor").assertIsDisplayed().performClick()
        // The recovery field is intentionally a secret semantics node; never inspect its plaintext in accessibility.
        // The selected exact candidate must disappear from the suggestion list after completion.
        rule.onNodeWithTag("mnemonic-suggestion-flavor").assertDoesNotExist()
    }
}
