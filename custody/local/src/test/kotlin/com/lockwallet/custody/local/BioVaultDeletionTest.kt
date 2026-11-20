package com.lockwallet.custody.local

import com.lockwallet.core.crypto.SecretBytes
import com.lockwallet.custody.local.bio.BioVault
import com.lockwallet.custody.local.storage.AtomicFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom

class BioVaultDeletionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val keys = FakeBioKeyStore()
    private val clock = FakeClock()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun removingOneWalletCryptoErasesOnlyItsBiometricKey() {
        val vault = BioVault(File(tmp.root, "bio"), keys, clock, AtomicFiles(), SecureRandom())
        vault.enable()
        val stateAlias = requireNotNull(vault.state()).aliasId
        val walletA = ByteArray(16) { 0x11 }
        val walletB = ByteArray(16) { 0x22 }
        val headerA = "header-A".toByteArray()
        val headerB = "header-B".toByteArray()

        SecretBytes.copyOf(ByteArray(32) { 0x31 }).use { vault.write(walletA, headerA, it) }
        SecretBytes.copyOf(ByteArray(32) { 0x42 }).use { vault.write(walletB, headerB, it) }

        val recordA = requireNotNull(vault.record(hex(walletA), headerA))
        val recordB = requireNotNull(vault.record(hex(walletB), headerB))
        assertNotEquals(recordA.aliasId.toList(), recordB.aliasId.toList())
        assertTrue(keys.exists(recordA.aliasId))
        assertTrue(keys.exists(recordB.aliasId))

        vault.remove(hex(walletA))

        assertFalse(keys.exists(recordA.aliasId))
        assertTrue(keys.exists(recordB.aliasId))
        assertTrue(keys.exists(stateAlias))
        assertNotNull(vault.record(hex(walletB), headerB))
        assertFalse(vault.has(hex(walletA)))
    }

    @Test fun disablingBiometricsDeletesEveryRecordKeyAndPolicyKeyBeforeFiles() {
        val vault = BioVault(File(tmp.root, "bio"), keys, clock, AtomicFiles(), SecureRandom())
        vault.enable()
        val stateAlias = requireNotNull(vault.state()).aliasId
        val wallet = ByteArray(16) { 0x5a }
        val header = "header".toByteArray()
        SecretBytes.copyOf(ByteArray(32) { 0x63 }).use { vault.write(wallet, header, it) }
        val record = requireNotNull(vault.record(hex(wallet), header))
        assertTrue(keys.exists(record.aliasId))

        vault.disable()

        assertFalse(keys.exists(record.aliasId))
        assertFalse(keys.exists(stateAlias))
        assertTrue(File(tmp.root, "bio").listFiles().isNullOrEmpty())
        assertTrue(keys.keys.isEmpty())
        assertTrue(keys.macKeys.isEmpty())
    }
}
