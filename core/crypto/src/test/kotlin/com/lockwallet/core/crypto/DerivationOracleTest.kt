package com.lockwallet.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec §23.1 vector, step by step (entropy, seed, master key/chain code, per-path private key, chain code,
 * public key) against the independent oracle output (python-mnemonic + bip_utils + solders, which agree).
 * Address (Base58) equality is asserted in :core:solana. Never "fix" code to match: a mismatch must be
 * localised to the exact step (spec §23.1).
 */
class DerivationOracleTest {
    private val spec = Fixtures.json("lockwallet-spec-23-1.json")

    @Test fun spec231EveryStep() {
        val m = spec["mnemonic"].asString.toCharArray()
        Bip39.mnemonicToEntropy(m).use { e -> e.withBytes { assertArrayEquals(spec["entropy_hex"].asString.hex(), it) } }
        Bip39.mnemonicToSeed(m).use { seed ->
            seed.withBytes { s ->
                assertArrayEquals("BIP-39 seed", spec["seed_hex"].asString.hex(), s)
                Slip10Ed25519.masterNode(s).use { node ->
                    node.key.withBytes { assertArrayEquals("master k", spec["master_private_key_hex"].asString.hex(), it) }
                    node.chainCode.withBytes { assertArrayEquals("master c", spec["master_chain_code_hex"].asString.hex(), it) }
                }
                spec.getAsJsonArray("derivations").forEach { d ->
                    val o = d.asJsonObject
                    val path = HardenedPath.parse(o["path"].asString)
                    Slip10Ed25519.deriveNode(s, path).use { node ->
                        node.key.withBytes { assertArrayEquals("$path k", o["private_key_hex"].asString.hex(), it) }
                        node.chainCode.withBytes { assertArrayEquals("$path c", o["chain_code_hex"].asString.hex(), it) }
                    }
                    val profile = DerivationProfile.valueOf(o["profile"].asString)
                    Slip10Ed25519.deriveSolanaKey(s, profile, o["account_index"].asInt).use { k ->
                        assertArrayEquals("$path pub", o["public_key_hex"].asString.hex(), k.publicKey())
                    }
                }
            }
        }
    }

    @Test fun oracleDerivationsAllSizesBothProfiles() {
        val vectors = Fixtures.json("oracle-derivations.json").getAsJsonArray("vectors")
        assertEquals(15, vectors.size())
        vectors.forEach { v ->
            val o = v.asJsonObject
            val m = Bip39.entropyToMnemonic(o["entropy_hex"].asString.hex())
            assertEquals(o["mnemonic"].asString, String(m))
            Bip39.mnemonicToSeed(m).use { seed ->
                seed.withBytes { assertArrayEquals(o["seed_hex"].asString.hex(), it) }
            }
        }
    }
}
