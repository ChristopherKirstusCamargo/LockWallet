package com.lockwallet.custody.local

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.lockwallet.custody.local.keystore.AndroidKeystoreKekStore
import com.lockwallet.custody.local.pin.AttemptClock
import java.io.File

/** elapsedRealtime (monotonic, includes sleep) + BOOT_COUNT; wall clock is never trusted for lockouts. */
class AndroidAttemptClock(private val context: Context) : AttemptClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun bootCount(): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Settings.SettingNotFoundException) {
        -1
    }
}

object LocalCustody {
    /** noBackupFilesDir: excluded from backup by the platform, in addition to data_extraction_rules. */
    fun create(context: Context): LocalSecureProvider {
        val app = context.applicationContext
        return LocalSecureProvider(File(app.noBackupFilesDir, "vault"), AndroidKeystoreKekStore(app), AndroidAttemptClock(app),
            bioKeys = com.lockwallet.custody.local.keystore.AndroidBioKeyStore(app))
    }
}
