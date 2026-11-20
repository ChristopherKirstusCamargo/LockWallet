package com.lockwallet.custody.seedvault

import android.content.Intent
import android.net.Uri
import com.lockwallet.core.crypto.Ed25519Signer
import com.lockwallet.core.crypto.SecretBytes
import com.lockwallet.core.solana.SolanaPublicKey
import com.lockwallet.core.solana.ProgramIds
import com.lockwallet.core.solana.ix.SystemIx
import com.lockwallet.core.solana.analyzer.TransactionAnalyzer
import com.lockwallet.core.solana.tx.AccountMeta
import com.lockwallet.core.solana.tx.Instruction
import com.lockwallet.core.solana.tx.LegacyMessage
import com.lockwallet.custody.local.CustodyException
import com.lockwallet.custody.local.storage.WalletRole
import com.lockwallet.custody.local.storage.WalletState
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Software stand-in for Seed Vault (tests only): holds a test key and signs whatever it is given. */
class FakeGateway(var present: Boolean = true) : SeedVaultGateway {
    val sk = ByteArray(32) { (it * 7 + 1).toByte() }
    val pk = ByteArray(32).also { Ed25519.generatePublicKey(sk, 0, it, 0) }
    var granted = mutableSetOf(42L)
    var tamper = false
    var deauthorized = mutableListOf<Long>()
    override fun available() = present
    override fun accessGranted() = true
    override fun permission(): String? = null
    override fun seedIntent(source: SeedSource) = Intent("seed." + source.name)
    override fun parseSeedResult(source: SeedSource, resultCode: Int, data: Intent?): Long = if (resultCode == -1) 42L else throw SeedVaultFailure("cancel")
    override fun publicKeyIntent(authToken: Long, path: Uri) = Intent("pk")
    override fun parsePublicKey(resultCode: Int, data: Intent?) = pk to Uri.parse("bip32:/m/44'/501'/0'/0'")
    override fun signTransactionIntent(authToken: Long, path: Uri, payload: ByteArray) = Intent("sign").putExtra("p", payload)
    override fun signMessageIntent(authToken: Long, path: Uri, payload: ByteArray) = Intent("signmsg").putExtra("p", payload)
    override fun parseSignature(resultCode: Int, data: Intent?, message: Boolean): ByteArray {
        if (resultCode != -1) throw SeedVaultFailure("declined")
        val m = data!!.getByteArrayExtra("p")!!.let { if (tamper) it + byteArrayOf(0) else it }
        return Ed25519Signer.sign(SecretBytes.copyOf(sk), m)
    }
    override fun authorizedTokens() = granted.toSet()
    override fun deauthorize(authToken: Long) { deauthorized += authToken; granted.remove(authToken) }
    override fun accountPath(account: Int): Uri = Uri.parse("bip44:/$account'/0'")
}

private val BH = com.lockwallet.core.solana.tx.Blockhash(ByteArray(32) { 5 })

@RunWith(RobolectricTestRunner::class)
class SeedVaultProviderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val bob = SolanaPublicKey.fromBase58("GcXbfQ5yY3uxCyBNDPBbR5FjumHf89E7YHXuULfGDBBv")

    @Test fun registersSignsThroughAnalyzerAndVerifiesSignature() {
        val g = FakeGateway()
        val p = SeedVaultProvider(tmp.root, g)
        assertTrue(p.capabilities().available)
        val w = p.add("Saga", WalletRole.VAULT, 42L, g.pk, g.accountPath(0), emptyList())
        assertEquals(com.lockwallet.custody.local.api.CustodyProviderId.SEED_VAULT, w.provider)
        assertEquals(WalletState.ACTIVE, w.state)
        // Persisted and re-read.
        assertEquals(w.address, SeedVaultProvider(tmp.root, g).wallets().single().address)
        val msg = LegacyMessage.compile(w.address, BH, listOf(SystemIx.transfer(w.address, bob, 1000))).serialize()
        val hash = TransactionAnalyzer.analyze(msg, w.address).messageHash
        val intent = p.signTransactionRequest(w.id, msg, hash)
        val sig = p.finishSignature(w.id, msg, -1, intent)
        assertTrue(Ed25519Signer.verify(g.pk, msg, sig))
        // Wrong hash, unknown program, foreign fee payer: refused before Seed Vault is ever asked.
        try { p.signTransactionRequest(w.id, msg, ByteArray(32)); fail() } catch (e: CustodyException) { assertEquals(CustodyException.Reason.NOT_APPROVED, e.reason) }
        val evil = LegacyMessage.compile(w.address, BH,
            listOf(Instruction(ProgramIds.METAPLEX_METADATA, listOf(AccountMeta(w.address, true, true)), byteArrayOf(1)))).serialize()
        try { p.signTransactionRequest(w.id, evil, TransactionAnalyzer.analyze(evil, w.address).messageHash); fail() } catch (e: CustodyException) { assertEquals(CustodyException.Reason.NOT_APPROVED, e.reason) }
        val foreign = LegacyMessage.compile(bob, BH, listOf(SystemIx.transfer(bob, w.address, 1))).serialize()
        try { p.signTransactionRequest(w.id, foreign, TransactionAnalyzer.analyze(foreign, w.address).messageHash); fail() } catch (e: CustodyException) { assertEquals(CustodyException.Reason.NOT_APPROVED, e.reason) }
        // A signature over other bytes is rejected; a cancelled request is reported as declined.
        g.tamper = true
        try { p.finishSignature(w.id, msg, -1, intent); fail() } catch (e: CustodyException) { assertEquals(CustodyException.Reason.BAD_SIGNATURE, e.reason) }
        try { p.finishSignature(w.id, msg, 0, intent); fail() } catch (e: CustodyException) { assertEquals(CustodyException.Reason.SEED_VAULT_DECLINED, e.reason) }
    }

    @Test fun permissiveExtraCheckCannotReleaseABlockedInstruction() {
        val g = FakeGateway()
        val p = SeedVaultProvider(tmp.root, g)
        val w = p.add("Saga", WalletRole.VAULT, 42L, g.pk, g.accountPath(0), emptyList())
        val evil = LegacyMessage.compile(w.address, BH,
            listOf(Instruction(ProgramIds.METAPLEX_METADATA, listOf(AccountMeta(w.address, true, true)), byteArrayOf(1)))).serialize()
        val h = TransactionAnalyzer.analyze(evil, w.address).messageHash
        try { p.signTransactionRequest(w.id, evil, h) { _, _ -> true }; fail() } catch (e: CustodyException) { assertEquals(CustodyException.Reason.NOT_APPROVED, e.reason) }
        // And an extra check can still narrow an allowed transaction.
        val ok = LegacyMessage.compile(w.address, BH, listOf(SystemIx.transfer(w.address, bob, 1000))).serialize()
        try { p.signTransactionRequest(w.id, ok, TransactionAnalyzer.analyze(ok, w.address).messageHash) { _, _ -> false }; fail() } catch (e: CustodyException) { assertEquals(CustodyException.Reason.NOT_APPROVED, e.reason) }
    }

    @Test fun rolesNeverShareASeedAndRevokedTokensAreQuarantined() {
        val g = FakeGateway()
        val p = SeedVaultProvider(tmp.root, g)
        val w = p.add("Saga", WalletRole.VAULT, 42L, g.pk, g.accountPath(0), emptyList())
        try { p.add("Connect", WalletRole.CONNECT, 42L, ByteArray(32) { 3 }, g.accountPath(1), emptyList()); fail() }
        catch (e: CustodyException) { assertEquals(CustodyException.Reason.ROLE_CONFLICT, e.reason) }
        try { p.add("Dup", WalletRole.VAULT, 42L, g.pk, g.accountPath(0), emptyList()); fail() }
        catch (e: CustodyException) { assertEquals(CustodyException.Reason.DUPLICATE_WALLET, e.reason) }
        g.granted.clear()
        assertEquals(WalletState.QUARANTINED, p.wallets().single().state)
        p.remove(w.id)
        assertEquals(listOf(42L), g.deauthorized)
        assertTrue(p.wallets().isEmpty())
    }

    @Test fun unavailableDeviceSaysSo() {
        val p = SeedVaultProvider(tmp.root, FakeGateway(present = false))
        assertTrue(!p.capabilities().available)
        assertEquals(SeedVaultProvider.UNAVAILABLE, p.capabilities().unavailableReason)
    }
}
