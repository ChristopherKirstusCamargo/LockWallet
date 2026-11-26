package com.lockwallet.custody.local.keystore

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Biometric unlock key (ADR 0021): an RSA key pair whose PRIVATE half lives in the Keystore, usable only after a
 * BIOMETRIC_STRONG authentication bound to this exact operation (CryptoObject, auth-per-use) and invalidated when a
 * new biometric is enrolled. The public half encrypts without any prompt, so new wallets can be enrolled while the
 * material is already in memory after a PIN unlock.
 */
interface BioKeyStore {
    fun create(aliasId: ByteArray): KeySecurityLevel
    fun exists(aliasId: ByteArray): Boolean
    /** X.509 SubjectPublicKeyInfo of the RSA public key. */
    fun publicKey(aliasId: ByteArray): ByteArray
    /** RSA-OAEP decryption; its [BioOperation.cipher] must go to BiometricPrompt.CryptoObject before [BioOperation.finish]. */
    fun decryptor(aliasId: ByteArray): BioOperation
    /**
     * HMAC-SHA256 with a companion Keystore key created with the pair (no user auth; non-exportable). Authenticates the
     * biometric policy state so that editing the file (e.g. to extend the 72 h window) turns the feature off.
     */
    fun mac(aliasId: ByteArray, data: ByteArray): ByteArray
    /** Only on explicit user action (turning the feature off) or after the OS invalidated the key. */
    fun delete(aliasId: ByteArray)

    companion object {
        fun alias(aliasId: ByteArray): String = "lw.bio." + aliasId.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun macAlias(aliasId: ByteArray): String = "lw.biom." + aliasId.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** OAEP SHA-256 with MGF1-SHA-1: the combination Android Keystore supports on every API level ≥ 23. */
        val OAEP: OAEPParameterSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

        /** Software encryption with the public key (no user authentication needed for public-key operations). */
        fun encrypt(publicKeyX509: ByteArray, plaintext: ByteArray): ByteArray {
            val pk: PublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyX509))
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.ENCRYPT_MODE, pk, OAEP)
            return c.doFinal(plaintext)
        }
    }
}

interface BioOperation {
    val cipher: Cipher
    /** Throws [KekException] (AUTH_REQUIRED without a fresh biometric, INVALIDATED, UNAVAILABLE). */
    fun finish(input: ByteArray): ByteArray
}
