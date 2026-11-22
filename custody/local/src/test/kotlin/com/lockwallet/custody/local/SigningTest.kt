package com.lockwallet.custody.local

import com.lockwallet.core.crypto.Ed25519Signer
import com.lockwallet.core.crypto.Slip10Ed25519
import com.lockwallet.core.solana.ix.SystemIx
import com.lockwallet.core.solana.tx.Blockhash
import com.lockwallet.core.solana.tx.LegacyMessage
import com.lockwallet.custody.local.keystore.AuthMode
import com.lockwallet.custody.local.material.PendingMaterial
import com.lockwallet.custody.local.storage.WalletRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/** Custody signs only analyzer-approved bytes bound by hash; signatures match the independent oracle. */
class SigningTest {
    @get:Rule val tmp = TemporaryFolder()
    private val kek = FakeKekStore()
    private val pin = listOf(8, 3, 5 + 4, 2 - 1, 14 - 7, 2 * 2).joinToString("")
    private val tx = Fixtures.json("tx-oracle.json")
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun reason(block: () -> Unit) = (runCatching(block).exceptionOrNull() as CustodyException).reason

    private fun session(p: LocalSecureProvider, m: PendingMaterial): UnlockedWallet {
        val t = p.beginNewVault(AuthMode.PIN_ONLY)
        val w = p.seal(t, m, m.discover(1)[0], "W", WalletRole.VAULT, newPin = pin.toCharArray())
        val u = p.beginUnlock(w.id)
        return (p.completeUnlock(u, pin.toCharArray()) as UnlockResult.Success).session
    }

    @Test fun keypairWalletSignatureEqualsOracleAndRefusesAnythingUnapproved() {
        val seed = hex(tx.getAsJsonObject("keys")["payer_seed_hex"].asString)
        val json = (seed + Slip10Ed25519.publicKeyOf(seed)).joinToString(",", "[", "]") { (it.toInt() and 0xff).toString() }
        val p = LocalSecureProvider(tmp.root, kek, FakeClock())
        PendingMaterial.fromKeypairJson(json.toCharArray()).use { m ->
            session(p, m).use { s ->
                val msg = hex(tx.getAsJsonObject("signature")["message_hex"].asString)
                assertEquals(tx.getAsJsonObject("signature")["signature_hex"].asString,
                    s.signApproved(msg, sha(msg)).joinToString("") { "%02x".format(it) })
                // Hash of a different message (what was shown ≠ what is signed).
                assertEquals(CustodyException.Reason.NOT_APPROVED, reason { s.signApproved(msg, sha(msg + 0)) })
                // Analyzer-refused bytes, even with a matching hash.
                tx.getAsJsonArray("negative").map { it.asJsonObject }.forEach {
                    val bad = hex(it["message_hex"].asString)
                    assertEquals(it["id"].asString, CustodyException.Reason.NOT_APPROVED, reason { s.signApproved(bad, sha(bad)) })
                }
            }
        }
    }

    @Test fun mnemonicWalletSignsWithItsDerivedKeyOnly() {
        val p = LocalSecureProvider(tmp.root, kek, FakeClock())
        val phrase = Fixtures.json("lockwallet-spec-23-1.json")["mnemonic"].asString
        PendingMaterial.fromPhrase(phrase.toCharArray()).use { m ->
            session(p, m).use { s ->
                val me = s.info.address
                val other = com.lockwallet.core.solana.SolanaPublicKey.fromBase58(tx.getAsJsonObject("keys")["bob"].asString)
                val msg = LegacyMessage.compile(me, Blockhash(ByteArray(32) { 3 }), listOf(SystemIx.transfer(me, other, 10))).serialize()
                val sig = s.signApproved(msg, sha(msg))
                assertTrue(Ed25519Signer.verify(me.toByteArray(), msg, sig))
                // Same transfer but paid by someone else: refused (fee payer must be this wallet).
                val foreign = LegacyMessage.compile(other, Blockhash(ByteArray(32) { 3 }), listOf(SystemIx.transfer(other, me, 10))).serialize()
                assertEquals(CustodyException.Reason.NOT_APPROVED, reason { s.signApproved(foreign, sha(foreign)) })
            }
        }
    }
}
