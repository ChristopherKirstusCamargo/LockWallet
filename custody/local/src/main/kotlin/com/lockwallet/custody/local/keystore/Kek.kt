package com.lockwallet.custody.local.keystore

import javax.crypto.Cipher

/** How a vault KEK is gated (ADR 0002). Ids are the LWLE auth_mode byte. */
enum class AuthMode(val id: Int) {
    /** KEK usable while the app runs, no per-use user auth. Offline PIN guessing possible if the KEK is abused on-device. */
    PIN_ONLY(0x01),
    /** Legacy: KEK requires BIOMETRIC_STRONG per use; retained for existing LWLE files. */
    PIN_AND_BIOMETRIC(0x02),
    /** New vaults: per-use BIOMETRIC_STRONG OR Android device credential, never an unauthenticated KEK operation. */
    PIN_AND_DEVICE_AUTH(0x03);

    companion object { fun ofId(id: Int) = entries.firstOrNull { it.id == id } }
}

/** What KeyInfo actually reports — never inferred from a feature flag (StrongBox only if confirmed). */
enum class KeySecurityLevel { STRONGBOX, TRUSTED_ENVIRONMENT, UNKNOWN_SECURE, SOFTWARE, UNKNOWN }

class KekException(val kind: Kind, cause: Throwable? = null) : Exception("KEK: $kind", cause) {
    enum class Kind {
        /** KeyPermanentlyInvalidatedException (e.g. biometric enrollment changed / lock screen removed). Quarantine. */
        INVALIDATED,
        /** Alias missing or unrecoverable. Never recreated silently (spec §7.4). */
        NOT_FOUND,
        /** Operation attempted without the required biometric authentication. */
        AUTH_REQUIRED,
        /** The hardware wrap tag did not verify (tampered blob) — treated like a wrong PIN by the UI. */
        BAD_TAG,
        /** Keystore unavailable / other provider failure. Fail closed, data preserved. */
        UNAVAILABLE,
    }
}

/** One Keystore AES-GCM operation. [cipher] is what the UI hands to BiometricPrompt.CryptoObject when needed. */
interface KekOperation {
    val cipher: Cipher?
    val requiresUserAuth: Boolean
    /** True only when KeyInfo confirms AUTH_DEVICE_CREDENTIAL is an accepted authenticator. */
    val allowsDeviceCredential: Boolean
    /** IV chosen by the Keystore (encrypt) or given (decrypt). */
    fun iv(): ByteArray
    fun finish(aad: ByteArray, input: ByteArray): ByteArray
}

/** Hardware KEK store. Production: [AndroidKeystoreKekStore]. Tests use a software fake (test source set only). */
interface KekStore {
    fun create(aliasId: ByteArray, mode: AuthMode): KeySecurityLevel
    fun exists(aliasId: ByteArray): Boolean
    fun securityLevel(aliasId: ByteArray): KeySecurityLevel?
    fun encryptor(aliasId: ByteArray): KekOperation
    fun decryptor(aliasId: ByteArray, iv: ByteArray): KekOperation
    /** Non-exportable HMAC key for PIN-attempt state, created with the vault and never biometric-gated. */
    fun createAttemptMacKey(aliasId: ByteArray)
    fun attemptMac(aliasId: ByteArray, data: ByteArray): ByteArray
    fun deleteAttemptMacKey(aliasId: ByteArray)

    /** Only on explicit user removal of a quarantined/empty vault, never as error recovery. */
    fun delete(aliasId: ByteArray)

    companion object {
        fun alias(aliasId: ByteArray): String = "lw.kek." + aliasId.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun attemptMacAlias(aliasId: ByteArray): String = "lw.pinmac." + aliasId.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
