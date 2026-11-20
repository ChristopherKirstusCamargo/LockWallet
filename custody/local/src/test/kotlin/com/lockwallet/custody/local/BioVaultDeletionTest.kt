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
import java.nio.ByteBuffer
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

    @Test fun pendingKeyMarkerAllowsErasureAfterCrashBeforeStateCommit() {
        val dir = File(tmp.root, "bio")
        dir.mkdirs()
        val alias = ByteArray(16) { 0x4d }
        keys.create(alias)
        // Same durable marker written before BioVault creates its Keystore policy key.
        val marker = File(dir, "key.pending")
        val bytes = ByteBuffer.allocate(20).putInt(0x4C574250).put(alias).array()
        AtomicFiles().write(marker, bytes) { require(it.contentEquals(bytes)) }
        assertTrue(keys.exists(alias))

        val vault = BioVault(dir, keys, clock, AtomicFiles(), SecureRandom())
        vault.disable()

        assertFalse(keys.exists(alias))
        assertFalse(marker.exists())
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun perWalletKeyReferenceIsErasedWhenProcessDiesBeforeRecordCommit() {
        val dir = File(tmp.root, "bio")
        dir.mkdirs()
        val wallet = ByteArray(16) { 0x2c }
        val alias = ByteArray(16) { 0x3d }
        keys.create(alias)
        val marker = File(dir, "${hex(wallet)}.keyref")
        val bytes = ByteBuffer.allocate(36).putInt(0x4C57424B).put(wallet).put(alias).array()
        AtomicFiles().write(marker, bytes) { require(it.contentEquals(bytes)) }

        val vault = BioVault(dir, keys, clock, AtomicFiles(), SecureRandom())
        vault.disable()

        assertFalse(keys.exists(alias))
        assertFalse(marker.exists())
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test fun removingPersistedRecordAlsoErasesItsKeyReference() {
        val dir = File(tmp.root, "bio")
        val vault = BioVault(dir, keys, clock, AtomicFiles(), SecureRandom())
        vault.enable()
        val wallet = ByteArray(16) { 0x4e }
        val walletHex = hex(wallet)
        val header = "marker-leftover".toByteArray()
        SecretBytes.copyOf(ByteArray(32) { 0x5f }).use { vault.write(wallet, header, it) }
        val record = requireNotNull(vault.record(walletHex, header))
        val marker = File(dir, "$walletHex.keyref")
        assertTrue("per-wallet key reference must remain for the record lifetime", marker.exists())

        vault.remove(walletHex)

        assertFalse(keys.exists(record.aliasId))
        assertFalse(marker.exists())
        assertFalse(vault.has(walletHex))
    }

    @Test fun disablingAfterInterruptedWritesFindsAliasesInTemporaryFiles() {
        val dir = File(tmp.root, "bio")
        val vault = BioVault(dir, keys, clock, AtomicFiles(), SecureRandom())
        vault.enable()
        val stateAlias = requireNotNull(vault.state()).aliasId
        val wallet = ByteArray(16) { 0x6b }
        val id = hex(wallet)
        val header = "interrupted-header".toByteArray()
        SecretBytes.copyOf(ByteArray(32) { 0x73 }).use { vault.write(wallet, header, it) }
        val record = requireNotNull(vault.record(id, header))

        // Simulate process death after the temp files are fully written but before atomic rename.
        val state = File(dir, "state.lwbs")
        state.copyTo(File(dir, "state.lwbs.tmp"), overwrite = true)
        assertTrue(state.delete())
        val recordFile = File(dir, "$id.lwbio")
        recordFile.copyTo(File(dir, "$id.lwbio.tmp"), overwrite = true)
        assertTrue(recordFile.delete())

        assertTrue(keys.exists(stateAlias))
        assertTrue(keys.exists(record.aliasId))
        vault.disable()

        assertFalse(keys.exists(stateAlias))
        assertFalse(keys.exists(record.aliasId))
        assertTrue(dir.listFiles().isNullOrEmpty())
        assertTrue(keys.keys.isEmpty())
        assertTrue(keys.macKeys.isEmpty())
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
