package com.lockwallet.data.rpc

import com.lockwallet.core.solana.tx.TransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TxJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private class TestAuthenticator : TxJournalAuthenticator {
        private val key = ByteArray(32) { (it + 1).toByte() }
        var available = true
        private var created = false

        override fun mac(data: ByteArray, createIfMissing: Boolean): ByteArray {
            if (!available) throw IllegalStateException("test Keystore unavailable")
            if (!created && !createIfMissing) throw TxJournalKeyMissingException()
            if (createIfMissing) created = true
            return Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(key, "HmacSHA256"))
            }.doFinal(data)
        }
    }

    private fun record() = TxRecord(
        signature = "test-signature",
        cluster = Cluster.MAINNET,
        wallet = "wallet-address",
        kind = TxKind.SEND_SOL,
        summary = "transfer",
        counterparty = "recipient",
        amountUnits = 1_000_000_000L,
        decimals = 9,
        mint = null,
        signedTxB64 = "AQIDBA==",
        lastValidBlockHeight = 123L,
        feeLamports = 5_000L,
        state = TransactionState.SIGNED,
        createdAtMs = 10L,
        updatedAtMs = 10L,
        lastBroadcastMs = 0L,
        broadcasts = 0,
        detail = null,
    )

    @Test fun authenticatedJournalRoundTrips() {
        val file = File(tmp.root, "tx-journal.json")
        val auth = TestAuthenticator()
        TxJournal(file, auth).put(record())

        val loaded = TxJournal(file, auth).all()
        assertEquals(1, loaded.size)
        assertEquals(record(), loaded.single())
        assertTrue(file.readText().contains("\"v\":1"))
    }

    @Test fun payloadModificationFailsAuthentication() {
        val file = File(tmp.root, "tx-journal.json")
        val auth = TestAuthenticator()
        TxJournal(file, auth).put(record())
        val original = file.readText()
        assertTrue(original.contains("transfer"))
        file.writeText(original.replace("transfer", "transferr"))

        val reopened = runCatching { TxJournal(file, auth).all() }
        assertTrue("edited transaction journal must be rejected", reopened.isFailure)
    }

    @Test fun missingKeystoreKeyNeverCreatesReplacementDuringRead() {
        val file = File(tmp.root, "tx-journal.json")
        val auth = TestAuthenticator()
        TxJournal(file, auth).put(record())
        auth.available = false

        val reopened = runCatching { TxJournal(file, auth).all() }
        assertTrue("unverifiable journal must fail closed", reopened.isFailure)
    }

    @Test fun deletingPreviouslyAuthenticatedJournalFailsClosed() {
        val file = File(tmp.root, "tx-journal.json")
        val auth = TestAuthenticator()
        TxJournal(file, auth).put(record())
        assertTrue(file.delete())

        val reopened = runCatching { TxJournal(file, auth).all() }
        assertTrue("deleted journal must not be treated as an empty wallet history", reopened.isFailure)
    }

    @Test fun authenticatedTempFileIsRecoveredAfterInterruptedAtomicRename() {
        val file = File(tmp.root, "tx-journal.json")
        val auth = TestAuthenticator()
        TxJournal(file, auth).put(record())
        val temp = File(tmp.root, "tx-journal.json.tmp")
        file.copyTo(temp, overwrite = true)
        assertTrue(file.delete())

        val recovered = TxJournal(file, auth).all()
        assertEquals(1, recovered.size)
        assertEquals(record(), recovered.single())
        assertTrue(file.exists())
        assertFalse(temp.exists())
    }

    @Test fun staleAuthenticatedTempNeverReplacesCurrentJournal() {
        val file = File(tmp.root, "tx-journal.json")
        val auth = TestAuthenticator()
        val journal = TxJournal(file, auth)
        journal.put(record())
        val oldSnapshot = File(tmp.root, "old-snapshot.json")
        file.copyTo(oldSnapshot, overwrite = true)

        val newer = record().copy(
            signature = "newer-signature",
            createdAtMs = 20L,
            updatedAtMs = 20L,
            state = TransactionState.SUBMITTED,
        )
        journal.put(newer)
        val temp = File(tmp.root, "tx-journal.json.tmp")
        oldSnapshot.copyTo(temp, overwrite = true)

        val loaded = TxJournal(file, auth).all()
        assertTrue(loaded.any { it.signature == "newer-signature" })
        assertFalse(loaded.any { it.signature == "test-signature" })
        assertFalse(temp.exists())
    }

    @Test fun unauthenticatedArrayCannotDowngradeAnAlreadyAuthenticatedJournal() {
        val file = File(tmp.root, "tx-journal.json")
        val auth = TestAuthenticator()
        TxJournal(file, auth).put(record())
        file.writeText("[]")

        val reopened = runCatching { TxJournal(file, auth).all() }
        assertTrue("an authenticated journal must not be downgraded to legacy JSON", reopened.isFailure)
    }

    @Test fun legacyUnsignedJournalDropsBearerBytesAndBecomesUnresolved() {
        val file = File(tmp.root, "tx-journal.json")
        file.writeText(
            """[{"sig":"legacy-signature","cluster":"MAINNET","wallet":"wallet-address","kind":"SEND_SOL","summary":"legacy transfer","cp":null,"amount":1,"dec":9,"mint":null,"tx":"AQIDBA==","lvbh":123,"fee":5000,"state":"SIGNED","created":10,"updated":10,"lastb":0,"nb":0,"detail":null,"proof":false}]"""
        )
        val migrated = TxJournal(file, TestAuthenticator()).all()

        assertEquals(1, migrated.size)
        assertEquals(TransactionState.UNKNOWN, migrated.single().state)
        assertNull(migrated.single().signedTxB64)
        assertFalse(migrated.single().statusVerified)
        assertTrue(migrated.single().unresolved)
        assertTrue("legacy journal must be rewritten in authenticated format", file.readText().contains("\"v\":1"))
    }
}
