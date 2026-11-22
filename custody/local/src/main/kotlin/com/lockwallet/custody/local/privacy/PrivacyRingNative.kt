package com.lockwallet.custody.local.privacy

/**
 * JNI bridge to the pinned Zolana Default Ring SDK.
 *
 * The Rust side holds viewing/nullifier keys only for this PIN-authorized
 * in-memory session. It never receives the wallet seed or signing key.
 */
internal object PrivacyRingNative {
    @Volatile private var loaded = false
    @Volatile private var attempted = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        if (attempted) throw IllegalStateException("Biblioteca nativa de privacidade indisponível")
        attempted = true
        try {
            System.loadLibrary("lockwallet_privacy")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            throw IllegalStateException("Biblioteca nativa de privacidade não foi compilada para este dispositivo")
        } catch (_: SecurityException) {
            throw IllegalStateException("O sistema bloqueou a biblioteca nativa de privacidade")
        }
    }

    @JvmStatic external fun nativeDerivationMessage(publicKey: ByteArray): ByteArray
    @JvmStatic external fun nativeOpenWallet(publicKey: ByteArray, derivationSeed: ByteArray): Long
    @JvmStatic external fun nativeShieldedAddress(handle: Long): String
    @JvmStatic external fun nativeScanSol(handle: Long, rpcUrl: String): String
    @JvmStatic external fun nativePrepareDepositSol(handle: Long, rpcUrl: String, amountLamports: Long): ByteArray
    @JvmStatic external fun nativePrepareTransferSol(handle: Long, rpcUrl: String, shieldedRecipient: String, amountLamports: Long): ByteArray
    @JvmStatic external fun nativePrepareWithdrawSol(handle: Long, rpcUrl: String, publicRecipient: String, amountLamports: Long): ByteArray
    @JvmStatic external fun nativePreparedReview(handle: Long): String
    @JvmStatic external fun nativeCancelPrepared(handle: Long)
    @JvmStatic external fun nativeVerifyAndConsume(handle: Long, message: ByteArray, approvedHash: ByteArray, operation: String): Boolean
    @JvmStatic external fun nativeCloseWallet(handle: Long)

    fun derivationMessage(publicKey: ByteArray): ByteArray {
        ensureLoaded()
        require(publicKey.size == 32)
        return nativeDerivationMessage(publicKey)
    }

    fun openWallet(publicKey: ByteArray, derivationSeed: ByteArray): Long {
        ensureLoaded()
        require(publicKey.size == 32 && derivationSeed.size == 64)
        return nativeOpenWallet(publicKey, derivationSeed).also {
            check(it > 0L) { "A sessão de privacidade não foi aberta" }
        }
    }

    fun shieldedAddress(handle: Long): String = nativeShieldedAddress(checked(handle))
    fun scanSol(handle: Long, rpcUrl: String): String = nativeScanSol(checked(handle), rpcUrl)
    fun prepareDepositSol(handle: Long, rpcUrl: String, amountLamports: Long): ByteArray =
        nativePrepareDepositSol(checked(handle), rpcUrl, amountLamports)
    fun prepareTransferSol(handle: Long, rpcUrl: String, recipient: String, amountLamports: Long): ByteArray =
        nativePrepareTransferSol(checked(handle), rpcUrl, recipient, amountLamports)
    fun prepareWithdrawSol(handle: Long, rpcUrl: String, recipient: String, amountLamports: Long): ByteArray =
        nativePrepareWithdrawSol(checked(handle), rpcUrl, recipient, amountLamports)
    fun preparedReview(handle: Long): String = nativePreparedReview(checked(handle))
    fun cancelPrepared(handle: Long) = nativeCancelPrepared(checked(handle))
    fun verifyAndConsume(handle: Long, message: ByteArray, hash: ByteArray, operation: String): Boolean =
        nativeVerifyAndConsume(checked(handle), message, hash, operation)
    fun closeWallet(handle: Long) {
        if (loaded && handle > 0L) nativeCloseWallet(handle)
    }

    private fun checked(handle: Long): Long {
        ensureLoaded()
        require(handle > 0L) { "Sessão de privacidade encerrada" }
        return handle
    }
}
