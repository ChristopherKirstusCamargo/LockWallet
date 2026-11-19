package com.lockwallet.custody.local.envelope

import com.lockwallet.core.crypto.Argon2id
import com.lockwallet.core.crypto.DerivationProfile
import com.lockwallet.core.crypto.KdfProfile
import com.lockwallet.core.crypto.SecretBytes
import com.lockwallet.core.crypto.aead.AeadAuthenticationException
import com.lockwallet.core.crypto.aead.AesGcm
import com.lockwallet.core.crypto.lwrp.LwrpPayload
import com.lockwallet.core.crypto.wipe
import com.lockwallet.custody.local.keystore.KekException
import com.lockwallet.custody.local.keystore.KekOperation

/** Wrong PIN, tampered ciphertext and wrong vault are indistinguishable by design (ADR 0002). */
class EnvelopeAuthException : Exception("envelope could not be opened")

/** Inputs that are random in production and fixed in regression vectors. */
class SealRandomness(val dek: ByteArray, val seedNonce: ByteArray, val dekPinNonce: ByteArray) {
    init { require(dek.size == 32 && seedNonce.size == 12 && dekPinNonce.size == 12) }
    fun wipe() { dek.wipe() }
}

/**
 * ADR 0001 hierarchy: material --AES-GCM(DEK)--> seed_ct; DEK --AES-GCM(pin_key=Argon2id(PIN))--> dek_pin;
 * dek_pin --Keystore AES-GCM(KEK)--> dek_pin_blob. Distinct AAD domains, all bound to header H.
 */
object EnvelopeCrypto {
    private val KDF = KdfProfile.OWASP_V1

    fun derivePinKey(pinAscii: SecretBytes, salt: ByteArray): SecretBytes = pinAscii.withBytes { Argon2id.derive(it, salt, KDF) }

    fun seal(
        vaultId: ByteArray, materialId: ByteArray, kind: Int, profile: Int, accountIndex: Int, expectedPubkey: ByteArray,
        pinSalt: ByteArray, aliasId: ByteArray, authMode: Int, material: SecretBytes, pinKey: SecretBytes,
        rnd: SealRandomness, kek: KekOperation,
    ): LocalEnvelope {
        val len = material.size
        val h = LocalEnvelope.headerOf(vaultId, materialId, kind, profile, accountIndex, expectedPubkey,
            LocalEnvelope.KDF_OWASP_V1, pinSalt, aliasId, authMode, len)
        SecretBytes.copyOf(rnd.dek).use { dek ->
            val seedCt = material.withBytes { AesGcm.encrypt(dek, rnd.seedNonce, LocalEnvelope.aad(LocalEnvelope.DOMAIN_SEED, h), it) }
            val dekPin = dek.withBytes { AesGcm.encrypt(pinKey, rnd.dekPinNonce, LocalEnvelope.aad(LocalEnvelope.DOMAIN_PIN, h), it) }
            val blob = kek.finish(LocalEnvelope.aad(LocalEnvelope.DOMAIN_HW, h), dekPin)
            val iv = kek.iv()
            // dek_pin is only ever persisted inside the KEK wrap (ADR 0001, E-23): offline PIN guessing needs the KEK.
            dekPin.wipe()
            return LocalEnvelope(vaultId, materialId, kind, profile, accountIndex, expectedPubkey, LocalEnvelope.KDF_OWASP_V1,
                pinSalt, aliasId, authMode, len, rnd.seedNonce, seedCt, rnd.dekPinNonce, iv, blob)
        }
    }

    /** Step 1 (hardware): KEK-unwrap dek_pin_blob. Throws [KekException] (BAD_TAG ⇒ tampered). */
    fun unwrapHardware(env: LocalEnvelope, kek: KekOperation): ByteArray =
        kek.finish(LocalEnvelope.aad(LocalEnvelope.DOMAIN_HW, env.header()), env.hwCt())

    /** Step 2 (PIN): dek_pin → DEK → material, then pubkey re-derivation. Throws [EnvelopeAuthException]. */
    fun openWithPinKey(env: LocalEnvelope, dekPin: ByteArray, pinKey: SecretBytes): SecretBytes {
        val h = env.header()
        try {
            AesGcm.decrypt(pinKey, env.dekPinNonce(), LocalEnvelope.aad(LocalEnvelope.DOMAIN_PIN, h), dekPin).use { dek ->
                if (dek.size != 32) throw EnvelopeAuthException()
                val material = AesGcm.decrypt(dek, env.seedNonce(), LocalEnvelope.aad(LocalEnvelope.DOMAIN_SEED, h), env.seedCt())
                if (material.size != env.materialLength || !pubkeyMatches(env, material)) {
                    material.close(); throw EnvelopeAuthException()
                }
                return material
            }
        } catch (_: AeadAuthenticationException) {
            throw EnvelopeAuthException()
        }
    }

    /** Re-derives the public key from the material (BIP-39 checksum + SLIP-0010, or RFC 8032 keypair consistency). */
    fun pubkeyMatches(env: LocalEnvelope, material: SecretBytes): Boolean = try {
        val payload = material.withBytes { m ->
            if (env.materialKind == LocalEnvelope.KIND_BIP39) {
                LwrpPayload.bip39(m, DerivationProfile.ofId(env.derivationProfile)!!, env.accountIndex, env.expectedPubkey())
            } else {
                LwrpPayload.keypair(m, env.expectedPubkey())
            }
        }
        payload.use { it.verifyPublicKey() }
        true
    } catch (_: Exception) {
        false
    }
}
