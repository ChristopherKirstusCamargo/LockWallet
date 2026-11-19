package com.lockwallet.custody.local.keystore

import javax.crypto.Cipher

/** How a vault KEK is gated (ADR 0002). Ids are the LWLE auth_mode byte. */
enum class AuthMode(val id: Int) {
    /** KEK usable while the app runs, no per-use user auth. Offline PIN guessing possible if the KEK is abused on-device. */
    PIN_ONLY(0x01),
    /** KEK requires BIOMETRIC_STRONG per use, released only through BiometricPrompt.CryptoObject. */
    PIN_AND_BIOMETRIC(0x02);

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
    /** Only on explicit user removal of a quarantined/empty vault, never as error recovery. */
    fun delete(aliasId: ByteArray)

    companion object {
        fun alias(aliasId: ByteArray): String = "lw.kek." + aliasId.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
