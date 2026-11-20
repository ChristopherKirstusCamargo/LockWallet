package com.lockwallet.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lockwallet.data.rpc.TxJournalAuthenticator
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Authenticates the local transaction journal with a non-exportable Android Keystore HMAC key.
 * A missing key may be created only when writing a new journal; verification never silently replaces it.
 */
class AndroidTxJournalAuthenticator : TxJournalAuthenticator {
    @Synchronized
    override fun mac(data: ByteArray, createIfMissing: Boolean): ByteArray {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        var key = store.getKey(ALIAS, null) as? SecretKey
        if (key == null) {
            if (!createIfMissing) throw IllegalStateException("transaction journal authentication key missing")
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(256)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKey()
            key = store.getKey(ALIAS, null) as? SecretKey
                ?: throw IllegalStateException("transaction journal authentication key unavailable")
        }
        return Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(data)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "lockwallet.txjournal.hmac.v1"
    }
}
