package com.lockwallet.app.connect

import android.os.Bundle
import android.widget.Toast
import com.lockwallet.app.MainActivity

/**
 * Lock Connect entry point (ADR 0022): the only other exported component. Receives `solana-wallet:` VIEW intents
 * from dApps on this device, accepts only local MWA association, and hosts the same secured UI as MainActivity
 * (FLAG_SECURE, overlay protections, PIN/Seed Vault per signature). It accepts nothing else and finishes — back to
 * the dApp — when the MWA session ends.
 */
class LockConnectActivity : MainActivity() {
    private var bridge: MwaBridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = callingActivity?.packageName ?: referrer?.takeIf { it.scheme == "android-app" }?.host
        val b = MwaBridge(controller.connect, pkg)
        controller.connect.onSessionEnd = { if (!isFinishing) finish() }
        when (b.start(this, intent?.data)) {
            MwaBridge.Start.OK -> bridge = b
            MwaBridge.Start.REMOTE_REFUSED -> { Toast.makeText(this, REMOTE_REFUSED, Toast.LENGTH_LONG).show(); finish() }
            MwaBridge.Start.NOT_MWA -> finish()
        }
    }

    override fun onDestroy() {
        bridge?.close(); bridge = null
        super.onDestroy()
    }

    companion object {
        const val REMOTE_REFUSED = "Lock Wallet só aceita dApps no próprio aparelho (associação local). Conexão remota recusada."
    }
}
