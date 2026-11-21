package com.lockwallet.custody.local

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.keystore.KekException
import com.lockwallet.custody.local.keystore.KekOperation
import com.lockwallet.custody.local.keystore.KekStore
import com.lockwallet.custody.local.keystore.KeySecurityLevel
import com.lockwallet.custody.local.pin.AttemptClock
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object Fixtures {
    private val dir = File(checkNotNull(System.getProperty("lockwallet.fixtures")))
    fun json(name: String): JsonObject = JsonParser.parseString(File(dir, name).readText()).asJsonObject
}

internal fun String.hex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Test-only software stand-in for the Android Keystore KEK. Models auth-per-use, invalidation and loss. */
internal class FakeKekStore : KekStore {
    class Entry(val key: ByteArray, val mode: AuthMode, var invalidated: Boolean = false)
    val keys = mutableMapOf<String, Entry>()
    private val attemptMacKeys = mutableMapOf<String, ByteArray>()
    var level = KeySecurityLevel.TRUSTED_ENVIRONMENT
    /** When set, the next encryptor uses this IV (regression vectors). */
    var fixedIv: ByteArray? = null
    var biometricsPresented = 0

    fun put(aliasId: ByteArray, key: ByteArray, mode: AuthMode) { keys[KekStore.alias(aliasId)] = Entry(key, mode) }
    fun invalidateAll() = keys.values.forEach { it.invalidated = true }

    override fun create(aliasId: ByteArray, mode: AuthMode): KeySecurityLevel {
        val a = KekStore.alias(aliasId)
        if (a in keys) throw KekException(KekException.Kind.UNAVAILABLE)
        keys[a] = Entry(ByteArray(32).also(SecureRandom()::nextBytes), mode)
        return level
    }
    override fun exists(aliasId: ByteArray) = KekStore.alias(aliasId) in keys
    override fun securityLevel(aliasId: ByteArray) = if (exists(aliasId)) level else null
    override fun delete(aliasId: ByteArray) { keys.remove(KekStore.alias(aliasId)) }
    override fun createAttemptMacKey(aliasId: ByteArray) {
        val alias = KekStore.attemptMacAlias(aliasId)
        check(alias !in attemptMacKeys)
        attemptMacKeys[alias] = ByteArray(32).also(SecureRandom()::nextBytes)
    }
    override fun attemptMac(aliasId: ByteArray, data: ByteArray): ByteArray {
        val key = attemptMacKeys[KekStore.attemptMacAlias(aliasId)] ?: throw KekException(KekException.Kind.NOT_FOUND)
        return javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(data)
    }
    override fun deleteAttemptMacKey(aliasId: ByteArray) { attemptMacKeys.remove(KekStore.attemptMacAlias(aliasId)) }
    override fun encryptor(aliasId: ByteArray) = op(aliasId, null)
    override fun decryptor(aliasId: ByteArray, iv: ByteArray) = op(aliasId, iv)

    private fun op(aliasId: ByteArray, iv: ByteArray?): KekOperation {
        val e = keys[KekStore.alias(aliasId)] ?: throw KekException(KekException.Kind.NOT_FOUND)
        if (e.invalidated) throw KekException(KekException.Kind.INVALIDATED)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(e.key, "AES")
        if (iv == null) {
            val fixed = fixedIv
            if (fixed != null) c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, fixed)) else c.init(Cipher.ENCRYPT_MODE, key)
        } else c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val needs = e.mode != AuthMode.PIN_ONLY
        return object : KekOperation {
            var authorised = !needs
            override val cipher: Cipher? = if (needs) c else null
            override val requiresUserAuth = needs
            override val allowsDeviceCredential = e.mode == AuthMode.PIN_AND_DEVICE_AUTH
            override fun iv(): ByteArray = c.iv
            override fun finish(aad: ByteArray, input: ByteArray): ByteArray {
                if (needs && biometricsPresented <= 0) throw KekException(KekException.Kind.AUTH_REQUIRED)
                if (needs) biometricsPresented--
                return try { c.updateAAD(aad); c.doFinal(input) } catch (x: AEADBadTagException) { throw KekException(KekException.Kind.BAD_TAG, x) }
            }
        }
    }
}

internal class FakeClock(var elapsed: Long = 1_000_000L, var boot: Int = 7) : AttemptClock {
    override fun elapsedRealtimeMs() = elapsed
    override fun bootCount() = boot
}

/** Test-only software stand-in for the Keystore RSA biometric key: models auth-per-use and invalidation. */
internal class FakeBioKeyStore : com.lockwallet.custody.local.keystore.BioKeyStore {
    val keys = mutableMapOf<String, java.security.KeyPair>()
    var invalidated = false
    /** Number of successful biometric authentications "presented" (each authorises exactly one decryption). */
    var biometrics = 0
    override fun create(aliasId: ByteArray): KeySecurityLevel {
        val a = com.lockwallet.custody.local.keystore.BioKeyStore.alias(aliasId)
        if (a in keys) throw KekException(KekException.Kind.UNAVAILABLE)
        keys[a] = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(); invalidated = false
        macKeys[com.lockwallet.custody.local.keystore.BioKeyStore.macAlias(aliasId)] = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        return KeySecurityLevel.TRUSTED_ENVIRONMENT
    }
    override fun exists(aliasId: ByteArray) = com.lockwallet.custody.local.keystore.BioKeyStore.alias(aliasId) in keys
    override fun publicKey(aliasId: ByteArray): ByteArray =
        keys[com.lockwallet.custody.local.keystore.BioKeyStore.alias(aliasId)]?.public?.encoded ?: throw KekException(KekException.Kind.NOT_FOUND)
    val macKeys = mutableMapOf<String, ByteArray>()
    override fun mac(aliasId: ByteArray, data: ByteArray): ByteArray {
        val k = macKeys[com.lockwallet.custody.local.keystore.BioKeyStore.macAlias(aliasId)] ?: throw KekException(KekException.Kind.NOT_FOUND)
        return javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(k, "HmacSHA256")) }.doFinal(data)
    }
    override fun delete(aliasId: ByteArray) {
        keys.remove(com.lockwallet.custody.local.keystore.BioKeyStore.alias(aliasId))
        macKeys.remove(com.lockwallet.custody.local.keystore.BioKeyStore.macAlias(aliasId))
    }
    override fun decryptor(aliasId: ByteArray): com.lockwallet.custody.local.keystore.BioOperation {
        val kp = keys[com.lockwallet.custody.local.keystore.BioKeyStore.alias(aliasId)] ?: throw KekException(KekException.Kind.NOT_FOUND)
        if (invalidated) throw KekException(KekException.Kind.INVALIDATED)
        val c = Cipher.getInstance(com.lockwallet.custody.local.keystore.BioKeyStore.TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, kp.private, com.lockwallet.custody.local.keystore.BioKeyStore.OAEP)
        return object : com.lockwallet.custody.local.keystore.BioOperation {
            override val cipher: Cipher = c
            override fun finish(input: ByteArray): ByteArray {
                if (invalidated) throw KekException(KekException.Kind.INVALIDATED)
                if (biometrics <= 0) throw KekException(KekException.Kind.AUTH_REQUIRED)
                biometrics--
                return c.doFinal(input)
            }
        }
    }
}
