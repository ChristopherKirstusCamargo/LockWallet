package com.lockwallet.app.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lockwallet.app.ChainModel
import com.lockwallet.app.UserAmount
import com.lockwallet.app.readAndClearClipboard
import com.lockwallet.app.ui.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/** Typed amounts (pt-BR): ambiguous thousands, overflow, above-supply, hostile text are refused with a user error. */
@RunWith(Parameterized::class)
class UserAmountAbuseTest(private val text: String, private val lamports: Long?) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "''{0}'' -> {1}") fun data(): List<Array<Any?>> = listOf(
            "1" to 1_000_000_000L, "1,5" to 1_500_000_000L, "0,000000001" to 1L, "1.000" to null, "12.500" to null, "999.999" to null,
            "1.0000" to 1_000_000_000L, "1.00" to 1_000_000_000L, "0.500" to 500_000_000L, "1.000,5" to null, "1000000000" to 1_000_000_000_000_000_000L,
            "1000000000,000000001" to null, "9223372036" to null, "9999999999" to null, "-5" to null, "1e3" to null, "" to null, "١" to null,
            "1\u2009000" to null, "1,5 SOL" to null, "R$ 10" to null, "∞" to null, "1/2" to null, "0" to null, "00,1" to 100_000_000L,
            "  3  " to 3_000_000_000L, "3,0000000000" to null, "1.5" to 1_500_000_000L, "10.5" to 10_500_000_000L, "100.25" to 100_250_000_000L,
        ).map { arrayOf<Any?>(it.first, it.second) }
    }

    @Test fun parsed() {
        val r = runCatching { UserAmount.sol(text) }
        if (lamports == null) assertTrue("'$text' accepted as ${r.getOrNull()}", r.exceptionOrNull() is ChainModel.UserError)
        else assertEquals(lamports, r.getOrThrow())
        assertEquals(lamports, UserAmount.solOrNull(text))
    }
}

/** Every amount the app displays can be typed back exactly ("Máx" and edits never change the value). */
@RunWith(Parameterized::class)
class DisplayedAmountRoundTripTest(private val seed: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "seed {0}") fun data(): List<Array<Any>> = (1..150).map { arrayOf<Any>(it) }
    }

    @Test fun roundTrip() {
        val r = Random(seed * 11L)
        val v = 1L + (r.nextLong() and Long.MAX_VALUE) % UserAmount.MAX_SOL_LAMPORTS
        val shown = Fmt.sol(v)
        assertEquals(v, UserAmount.sol(shown))
        assertNull(UserAmount.solOrNull(Fmt.sol(UserAmount.MAX_SOL_LAMPORTS) + "1"))
    }
}

/** Secret paste reads once, caps the length and wipes the clipboard, for any content. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class SecretPasteTest(private val case: Int) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "case {0}") fun data(): List<Array<Any>> = (0 until 24).map { arrayOf<Any>(it) }
    }

    @Test fun readOnceCappedAndCleared() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val r = Random(case.toLong())
        val text: String? = when (case) {
            0 -> null; 1 -> ""; 2 -> "abandon ".repeat(11) + "about"; 3 -> "x".repeat(10_000); 4 -> "\u0000\u202E secret"
            5 -> "4".repeat(400); 6 -> "5".repeat(401); 7 -> "emoji 🔐".repeat(80)
            else -> (1..(1 + r.nextInt(900))).map { (32 + r.nextInt(0x2FFF)).toChar() }.joinToString("")
        }
        if (text == null) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("k", text))
        val got = readAndClearClipboard(ctx, 400)
        if (text == null) assertNull(got) else {
            assertEquals(text.take(400), got)
            assertTrue((got ?: "").length <= 400)
        }
        // Wiped: a second read (another app, keyboard history) gets nothing.
        val again = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        assertTrue("clipboard still holds '${again?.take(20)}'", again.isNullOrEmpty())
    }
}

/** Hostile intents: no external action resolves to the wallet; only the launcher entry exists. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class HostileIntentTest(private val name: String) {
    companion object {
        private const val A = "GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv"
        private fun intents(): Map<String, Intent> = linkedMapOf(
            "view-solana" to Intent(Intent.ACTION_VIEW, Uri.parse("solana:$A?amount=1000")),
            "view-solana-pay" to Intent(Intent.ACTION_VIEW, Uri.parse("solana:https://evil.example/tx")),
            "view-https" to Intent(Intent.ACTION_VIEW, Uri.parse("https://lockwallet.app/send?to=$A")),
            "view-wc" to Intent(Intent.ACTION_VIEW, Uri.parse("wc:abc@2?relay-protocol=irn")),
            "view-phantom" to Intent(Intent.ACTION_VIEW, Uri.parse("phantom://browse/https%3A%2F%2Fevil")),
            "view-lockwallet" to Intent(Intent.ACTION_VIEW, Uri.parse("lockwallet://send?to=$A")),
            "view-file" to Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/backup.lwrp")),
            "view-content" to Intent(Intent.ACTION_VIEW, Uri.parse("content://com.evil/backup.lwrp")),
            "send-text" to Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, A),
            "send-file" to Intent(Intent.ACTION_SEND).setType("application/octet-stream"),
            "send-multiple" to Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*"),
            "pick" to Intent(Intent.ACTION_PICK), "get-content" to Intent(Intent.ACTION_GET_CONTENT).setType("*/*"),
            "process-text" to Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"),
            "nfc" to Intent("android.nfc.action.NDEF_DISCOVERED"), "boot" to Intent(Intent.ACTION_BOOT_COMPLETED),
            "app-widget" to Intent("android.appwidget.action.APPWIDGET_UPDATE"), "search" to Intent(Intent.ACTION_SEARCH),
            "assist" to Intent(Intent.ACTION_ASSIST), "settings" to Intent("android.settings.APPLICATION_DETAILS_SETTINGS"),
            "sendto-sms" to Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:123")), "dial" to Intent(Intent.ACTION_DIAL),
            "main-no-launcher" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            "accessibility" to Intent("android.accessibilityservice.AccessibilityService"),
            "autofill" to Intent("android.service.autofill.AutofillService"), "ime" to Intent("android.view.InputMethod"),
            "provider-backup" to Intent("android.app.backup.BackupAgent"), "device-admin" to Intent("android.app.action.DEVICE_ADMIN_ENABLED"),
        )
        private val NAMES = listOf("view-solana", "view-solana-pay", "view-https", "view-wc", "view-phantom", "view-lockwallet", "view-file",
            "view-content", "send-text", "send-file", "send-multiple", "pick", "get-content", "process-text", "nfc", "boot", "app-widget",
            "search", "assist", "settings", "sendto-sms", "dial", "main-no-launcher", "accessibility", "autofill", "ime", "provider-backup", "device-admin")
        // Names only here: android.net.Uri exists only once Robolectric runs the test.
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun data(): List<Array<Any>> = NAMES.map { arrayOf<Any>(it) }
    }

    @Test fun nothingResolvesToTheWallet() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val all = intents(); assertEquals(NAMES.toSet(), all.keys)
        val intent = all.getValue(name).setPackage(ctx.packageName)
        val pm = ctx.packageManager
        assertTrue("$name resolves to an activity", pm.queryIntentActivities(intent, 0).isEmpty())
        assertTrue("$name resolves to a service", pm.queryIntentServices(intent, 0).isEmpty())
        assertTrue("$name resolves to a receiver", pm.queryBroadcastReceivers(intent, 0).isEmpty())
    }
}
