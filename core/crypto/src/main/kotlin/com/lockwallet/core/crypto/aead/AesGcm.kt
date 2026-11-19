package com.lockwallet.core.crypto.aead

import com.lockwallet.core.crypto.SecretBytes
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** Authentication failed. Deliberately carries no detail (wrong key ≡ tampered data, spec §6.2). */
class AeadAuthenticationException : Exception("authentication failed")

/**
 * Software AES-256-GCM, 96-bit nonce, 128-bit tag (NIST SP 800-38D), Bouncy Castle lightweight API.
 * Nonces are always fresh random values chosen by the caller; each key encrypts exactly one object
 * per file version (ADR 0001), so random nonces never repeat under one key in practice.
 */
object AesGcm {
    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val TAG_LEN = 16

    /** Returns ciphertext‖tag (caller owns it; if the plaintext was secret the result is not, but wipe the plaintext). */
    fun encrypt(key: SecretBytes, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        run(true, key, nonce, aad, plaintext)

    /** Returns the plaintext as an owned secret, or throws [AeadAuthenticationException]. */
    fun decrypt(key: SecretBytes, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): SecretBytes {
        if (ciphertextAndTag.size < TAG_LEN) throw AeadAuthenticationException()
        return SecretBytes.adopt(run(false, key, nonce, aad, ciphertextAndTag))
    }

    private fun run(enc: Boolean, key: SecretBytes, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        require(nonce.size == NONCE_LEN && key.size == KEY_LEN)
        val c = GCMBlockCipher.newInstance(AESEngine.newInstance())
        key.withBytes { k -> c.init(enc, AEADParameters(KeyParameter(k), TAG_LEN * 8, nonce, aad)) }
        val out = ByteArray(c.getOutputSize(input.size))
        var n = c.processBytes(input, 0, input.size, out, 0)
        try {
            n += c.doFinal(out, n)
        } catch (_: InvalidCipherTextException) {
            out.fill(0)
            throw AeadAuthenticationException()
        }
        return if (n == out.size) out else out.copyOf(n).also { out.fill(0) }
    }
}
