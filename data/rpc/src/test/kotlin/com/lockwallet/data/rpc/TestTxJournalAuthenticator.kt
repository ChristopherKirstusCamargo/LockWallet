package com.lockwallet.data.rpc

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Deterministic test-only MAC provider. The production app supplies Android Keystore;
 * JVM tests use this isolated key so persistence and tamper-detection behavior can be exercised.
 */
internal class TestTxJournalAuthenticator : TxJournalAuthenticator {
    @Volatile private var provisioned = false
    private val key = SecretKeySpec(ByteArray(32) { (it * 11 + 23).toByte() }, "HmacSHA256")

    @Synchronized override fun mac(data: ByteArray, createIfMissing: Boolean): ByteArray {
        if (!provisioned) {
            if (!createIfMissing) throw TxJournalKeyMissingException()
            provisioned = true
        }
        return Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(data)
        }
    }
}
