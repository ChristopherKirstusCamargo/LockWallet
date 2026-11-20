package com.lockwallet.custody.local.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import android.app.KeyguardManager
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-256-GCM KEK (ADR 0001/0002). StrongBox is *requested* when the device declares it and
 * the real level is read back from KeyInfo; nothing claims StrongBox unless KeyInfo confirms it.
 */
class AndroidKeystoreKekStore(private val context: Context) : KekStore {
    private val ks: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override fun create(aliasId: ByteArray, mode: AuthMode): KeySecurityLevel {
        val alias = KekStore.alias(aliasId)
        if (ks.containsAlias(alias)) throw KekException(KekException.Kind.UNAVAILABLE) // never overwrite a KEK
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        try {
            generate(alias, mode, strongBox)
        } catch (e: StrongBoxUnavailableException) {
            generate(alias, mode, false)
        } catch (e: Exception) {
            if (e is KekException) throw e
            throw KekException(KekException.Kind.UNAVAILABLE, e)
        }
        return securityLevel(aliasId) ?: KeySecurityLevel.UNKNOWN
    }

    private fun generate(alias: String, mode: AuthMode, strongBox: Boolean) {
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure == true) b.setUnlockedDeviceRequired(true)
        if (mode == AuthMode.PIN_AND_BIOMETRIC) {
            b.setUserAuthenticationRequired(true)
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) // auth-per-use
            b.setInvalidatedByBiometricEnrollment(true)
        }
        if (strongBox) b.setIsStrongBoxBacked(true)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(b.build()) }.generateKey()
    }

    override fun exists(aliasId: ByteArray): Boolean = try {
        ks.containsAlias(KekStore.alias(aliasId))
    } catch (_: Exception) {
        false
    }

    override fun securityLevel(aliasId: ByteArray): KeySecurityLevel? = try {
        val key = key(aliasId)
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> KeySecurityLevel.UNKNOWN_SECURE
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurityLevel.SOFTWARE
            else -> KeySecurityLevel.UNKNOWN
        }
    } catch (_: Exception) {
        null
    }

    override fun encryptor(aliasId: ByteArray): KekOperation = op(aliasId) { c, k -> c.init(Cipher.ENCRYPT_MODE, k) }

    override fun decryptor(aliasId: ByteArray, iv: ByteArray): KekOperation {
        require(iv.size == 12)
        return op(aliasId) { c, k -> c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
    }

    override fun delete(aliasId: ByteArray) {
        ks.deleteEntry(KekStore.alias(aliasId))
    }

    private fun key(aliasId: ByteArray): SecretKey = try {
        ks.getKey(KekStore.alias(aliasId), null) as? SecretKey ?: throw KekException(KekException.Kind.NOT_FOUND)
    } catch (e: UnrecoverableKeyException) {
        // The alias may still exist: this exception does not prove that the key is absent.
        // Preserve every wallet and surface a Keystore failure; only a null getKey result is NOT_FOUND.
        throw KekException(KekException.Kind.UNAVAILABLE, e)
    }

    private fun op(aliasId: ByteArray, init: (Cipher, SecretKey) -> Unit): KekOperation {
        val k = key(aliasId)
        val c = Cipher.getInstance(TRANSFORMATION)
        try {
            init(c, k)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KekException(KekException.Kind.INVALIDATED, e)
        } catch (e: UserNotAuthenticatedException) {
            throw KekException(KekException.Kind.AUTH_REQUIRED, e)
        } catch (e: Exception) {
            throw KekException(KekException.Kind.UNAVAILABLE, e)
        }
        val needsAuth = try {
            (SecretKeyFactory.getInstance(k.algorithm, PROVIDER).getKeySpec(k, KeyInfo::class.java) as KeyInfo).isUserAuthenticationRequired
        } catch (_: Exception) {
            true // fail towards prompting
        }
        return object : KekOperation {
            override val cipher: Cipher = c
            override val requiresUserAuth: Boolean = needsAuth
            override fun iv(): ByteArray = c.iv.copyOf()
            override fun finish(aad: ByteArray, input: ByteArray): ByteArray = try {
                c.updateAAD(aad)
                c.doFinal(input)
            } catch (e: AEADBadTagException) {
                throw KekException(KekException.Kind.BAD_TAG, e)
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw KekException(KekException.Kind.INVALIDATED, e)
            } catch (e: UserNotAuthenticatedException) {
                throw KekException(KekException.Kind.AUTH_REQUIRED, e)
            } catch (e: IllegalBlockSizeException) {
                // Keystore reports a missing per-use authorization as IllegalBlockSizeException wrapping KeyStoreException.
                throw KekException(KekException.Kind.AUTH_REQUIRED, e)
            } catch (e: Exception) {
                throw KekException(KekException.Kind.UNAVAILABLE, e)
            }
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
