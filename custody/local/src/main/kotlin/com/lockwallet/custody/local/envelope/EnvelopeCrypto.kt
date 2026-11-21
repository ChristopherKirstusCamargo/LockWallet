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
import java.security.MessageDigest

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
    private val WRITE_KDF = KdfProfile.STRONG_V2

    fun derivePinKey(pinAscii: SecretBytes, salt: ByteArray, kdfProfileId: Int = LocalEnvelope.KDF_STRONG_V2): SecretBytes {
        val profile = when (kdfProfileId) {
            LocalEnvelope.KDF_OWASP_V1 -> KdfProfile.OWASP_V1
            LocalEnvelope.KDF_STRONG_V2 -> WRITE_KDF
            else -> throw IllegalArgumentException("unsupported KDF profile")
        }
        return pinAscii.withBytes { Argon2id.derive(it, salt, profile) }
    }

    fun seal(
        vaultId: ByteArray, materialId: ByteArray, kind: Int, profile: Int, accountIndex: Int, expectedPubkey: ByteArray,
        pinSalt: ByteArray, aliasId: ByteArray, authMode: Int, material: SecretBytes, pinKey: SecretBytes,
        rnd: SealRandomness, kek: KekOperation, kdfProfileId: Int = LocalEnvelope.KDF_STRONG_V2,
    ): LocalEnvelope {
        val len = material.size
        val kdfProfile = when (kdfProfileId) {
            LocalEnvelope.KDF_OWASP_V1, LocalEnvelope.KDF_STRONG_V2 -> kdfProfileId
            else -> throw IllegalArgumentException("unsupported KDF profile")
        }
        val h = LocalEnvelope.headerOf(vaultId, materialId, kind, profile, accountIndex, expectedPubkey,
            kdfProfile, pinSalt, aliasId, authMode, len)
        SecretBytes.copyOf(rnd.dek).use { dek ->
            val seedCt = material.withBytes { AesGcm.encrypt(dek, rnd.seedNonce, LocalEnvelope.aad(LocalEnvelope.DOMAIN_SEED, h), it) }
            val dekPin = dek.withBytes { AesGcm.encrypt(pinKey, rnd.dekPinNonce, LocalEnvelope.aad(LocalEnvelope.DOMAIN_PIN, h), it) }
            val blob = kek.finish(LocalEnvelope.aad(LocalEnvelope.DOMAIN_HW, h), dekPin)
            val iv = kek.iv()
            // dek_pin is only ever persisted inside the KEK wrap (ADR 0001, E-23): offline PIN guessing needs the KEK.
            dekPin.wipe()
            return LocalEnvelope(vaultId, materialId, kind, profile, accountIndex, expectedPubkey, kdfProfile,
                pinSalt, aliasId, authMode, len, rnd.seedNonce, seedCt, rnd.dekPinNonce, iv, blob)
        }
    }

    /** Opens the PIN-wrapped DEK so a verified legacy envelope can be re-encrypted under a newer KDF. */
    fun unwrapDek(env: LocalEnvelope, dekPin: ByteArray, pinKey: SecretBytes): SecretBytes {
        try {
            val dek = AesGcm.decrypt(pinKey, env.dekPinNonce(), LocalEnvelope.aad(LocalEnvelope.DOMAIN_PIN, env.header()), dekPin)
            if (dek.size != 32) { dek.close(); throw EnvelopeAuthException() }
            return dek
        } catch (_: AeadAuthenticationException) {
            throw EnvelopeAuthException()
        }
    }

    /**
     * Re-encrypts both inner AES-GCM layers because the KDF profile is authenticated in the header/AAD.
     * The caller supplies a fresh KEK encrypt operation and performs the atomic file replacement only after
     * this function has generated a complete, authenticated envelope.
     */
    fun migrateToStrong(
        old: LocalEnvelope,
        dek: SecretBytes,
        material: SecretBytes,
        strongPinKey: SecretBytes,
        kek: KekOperation,
        random: java.security.SecureRandom,
    ): LocalEnvelope {
        val seedNonce = ByteArray(12).also(random::nextBytes)
        val dekPinNonce = ByteArray(12).also(random::nextBytes)
        val header = LocalEnvelope.headerOf(
            old.vaultId(), old.materialId(), old.materialKind, old.derivationProfile, old.accountIndex,
            old.expectedPubkey(), LocalEnvelope.KDF_STRONG_V2, old.pinSalt(), old.aliasId(), old.authMode,
            old.materialLength,
        )
        val seedCt = material.withBytes { AesGcm.encrypt(dek, seedNonce, LocalEnvelope.aad(LocalEnvelope.DOMAIN_SEED, header), it) }
        val dekPin = try {
            dek.withBytes { AesGcm.encrypt(strongPinKey, dekPinNonce, LocalEnvelope.aad(LocalEnvelope.DOMAIN_PIN, header), it) }
        } catch (e: Exception) {
            seedCt.wipe()
            throw e
        }
        try {
            val hwCt = kek.finish(LocalEnvelope.aad(LocalEnvelope.DOMAIN_HW, header), dekPin)
            val env = LocalEnvelope(
                old.vaultId(), old.materialId(), old.materialKind, old.derivationProfile, old.accountIndex,
                old.expectedPubkey(), LocalEnvelope.KDF_STRONG_V2, old.pinSalt(), old.aliasId(), old.authMode,
                old.materialLength, seedNonce, seedCt, dekPinNonce, kek.iv(), hwCt,
            )
            verifyStrongEnvelope(env, dek, material, strongPinKey, dekPin)
            return env
        } finally {
            dekPin.wipe()
        }
    }

    /** Full inner-layer read-back verification, independent of the file parser. */
    fun verifyStrongEnvelope(env: LocalEnvelope, dek: SecretBytes, expectedMaterial: SecretBytes, pinKey: SecretBytes, dekPin: ByteArray) {
        require(env.kdfProfile == LocalEnvelope.KDF_STRONG_V2)
        AesGcm.decrypt(pinKey, env.dekPinNonce(), LocalEnvelope.aad(LocalEnvelope.DOMAIN_PIN, env.header()), dekPin).use { recoveredDek ->
            val sameDek = recoveredDek.withBytes { got -> dek.withBytes { expected -> MessageDigest.isEqual(got, expected) } }
            if (recoveredDek.size != 32 || !sameDek) throw EnvelopeAuthException()
        }
        val material = AesGcm.decrypt(dek, env.seedNonce(), LocalEnvelope.aad(LocalEnvelope.DOMAIN_SEED, env.header()), env.seedCt())
        material.use { recovered ->
            val matches = recovered.withBytes { got -> expectedMaterial.withBytes { want -> MessageDigest.isEqual(got, want) } }
            if (!matches || !pubkeyMatches(env, recovered)) throw EnvelopeAuthException()
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
