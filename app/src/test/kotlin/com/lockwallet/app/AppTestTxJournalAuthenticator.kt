package com.lockwallet.app

import com.lockwallet.data.rpc.TxJournalAuthenticator
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Test-only authenticator. A fixed private test key makes journal tests deterministic without
 * depending on AndroidKeyStore, which is intentionally unavailable in the JVM/Robolectric layer.
 * Never referenced by the production source set.
 */
internal class AppTestTxJournalAuthenticator : TxJournalAuthenticator {
    @Volatile private var provisioned = false
    private val key = SecretKeySpec(ByteArray(32) { (it * 7 + 19).toByte() }, "HmacSHA256")

    @Synchronized override fun mac(data: ByteArray, createIfMissing: Boolean): ByteArray {
        if (!provisioned) {
            if (!createIfMissing) throw com.lockwallet.data.rpc.TxJournalKeyMissingException()
            provisioned = true
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(data)
    }
}
