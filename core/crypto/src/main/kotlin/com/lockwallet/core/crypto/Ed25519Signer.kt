package com.lockwallet.core.crypto

import org.bouncycastle.math.ec.rfc8032.Ed25519

/** RFC 8032 Ed25519 (pure, no prehash) via Bouncy Castle. Used only inside :custody:local. */
object Ed25519Signer {
    const val SIGNATURE_LEN = 64

    fun sign(privateKey32: SecretBytes, message: ByteArray): ByteArray {
        require(privateKey32.size == 32)
        val sig = ByteArray(SIGNATURE_LEN)
        privateKey32.withBytes { sk -> Ed25519.sign(sk, 0, message, 0, message.size, sig, 0) }
        return sig
    }

    fun verify(publicKey32: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey32.size != 32 || signature.size != SIGNATURE_LEN) return false
        return try {
            Ed25519.verify(signature, 0, publicKey32, 0, message, 0, message.size)
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
