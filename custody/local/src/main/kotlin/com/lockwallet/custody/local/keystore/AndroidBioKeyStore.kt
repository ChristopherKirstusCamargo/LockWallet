package com.lockwallet.custody.local.keystore

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.RSAKeyGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.IllegalBlockSizeException

/** Android Keystore RSA-3072 biometric unlock key (ADR 0024). Never overwritten; never recreated silently. */
class AndroidBioKeyStore(private val context: Context) : BioKeyStore {
    private val ks: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override fun create(aliasId: ByteArray): KeySecurityLevel {
        val alias = BioKeyStore.alias(aliasId)
        if (ks.containsAlias(alias)) throw KekException(KekException.Kind.UNAVAILABLE)
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        try {
            try { generate(alias, strongBox, if (strongBox) 2048 else 3072) } catch (e: StrongBoxUnavailableException) { generate(alias, false, 3072) }
            generateMac(BioKeyStore.macAlias(aliasId))
        } catch (e: Exception) {
            if (e is KekException) throw e
            runCatching { ks.deleteEntry(alias) }; runCatching { ks.deleteEntry(BioKeyStore.macAlias(aliasId)) }
            throw KekException(KekException.Kind.UNAVAILABLE, e)
        }
        return level(alias)
    }

    private fun generate(alias: String, strongBox: Boolean, bits: Int) {
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
            .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(bits, RSAKeyGenParameterSpec.F4))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) // auth-per-use, biometric only
            .setInvalidatedByBiometricEnrollment(true)
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true) b.setUnlockedDeviceRequired(true)
        if (strongBox) b.setIsStrongBoxBacked(true)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER).apply { initialize(b.build()) }.generateKeyPair()
    }

    private fun generateMac(alias: String) {
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER).apply { init(spec) }.generateKey()
    }

    override fun mac(aliasId: ByteArray, data: ByteArray): ByteArray = try {
        val k = ks.getKey(BioKeyStore.macAlias(aliasId), null) as? SecretKey ?: throw KekException(KekException.Kind.NOT_FOUND)
        Mac.getInstance("HmacSHA256").apply { init(k) }.doFinal(data)
    } catch (e: KekException) { throw e } catch (e: Exception) { throw KekException(KekException.Kind.UNAVAILABLE, e) }

    private fun level(alias: String): KeySecurityLevel = try {
        val k = ks.getKey(alias, null) as PrivateKey
        val info = KeyFactory.getInstance(k.algorithm, PROVIDER).getKeySpec(k, KeyInfo::class.java)
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> KeySecurityLevel.UNKNOWN_SECURE
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurityLevel.SOFTWARE
            else -> KeySecurityLevel.UNKNOWN
        }
    } catch (_: Exception) { KeySecurityLevel.UNKNOWN }

    override fun exists(aliasId: ByteArray): Boolean = runCatching { ks.containsAlias(BioKeyStore.alias(aliasId)) }.getOrDefault(false)

    override fun publicKey(aliasId: ByteArray): ByteArray =
        ks.getCertificate(BioKeyStore.alias(aliasId))?.publicKey?.encoded ?: throw KekException(KekException.Kind.NOT_FOUND)

    override fun decryptor(aliasId: ByteArray): BioOperation {
        val k = (try { ks.getKey(BioKeyStore.alias(aliasId), null) } catch (e: Exception) { throw KekException(KekException.Kind.NOT_FOUND, e) })
            as? PrivateKey ?: throw KekException(KekException.Kind.NOT_FOUND)
        val c = Cipher.getInstance(BioKeyStore.TRANSFORMATION)
        try {
            c.init(Cipher.DECRYPT_MODE, k, BioKeyStore.OAEP)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KekException(KekException.Kind.INVALIDATED, e)
        } catch (e: Exception) {
            throw KekException(KekException.Kind.UNAVAILABLE, e)
        }
        return object : BioOperation {
            override val cipher: Cipher = c
            override fun finish(input: ByteArray): ByteArray = try {
                c.doFinal(input)
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw KekException(KekException.Kind.INVALIDATED, e)
            } catch (e: UserNotAuthenticatedException) {
                throw KekException(KekException.Kind.AUTH_REQUIRED, e)
            } catch (e: IllegalBlockSizeException) {
                throw KekException(KekException.Kind.AUTH_REQUIRED, e)
            } catch (e: Exception) {
                throw KekException(KekException.Kind.UNAVAILABLE, e)
            }
        }
    }

    override fun delete(aliasId: ByteArray) {
        runCatching { ks.deleteEntry(BioKeyStore.alias(aliasId)) }
        runCatching { ks.deleteEntry(BioKeyStore.macAlias(aliasId)) }
    }

    private companion object { const val PROVIDER = "AndroidKeyStore" }
}
