package com.lockwallet.custody.local

import com.lockwallet.custody.local.storage.AtomicFiles
import com.lockwallet.custody.local.storage.WriteStep
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Crash : RuntimeException()

    @Test fun crashAtEveryStepLeavesOldOrNewNeverPartial() {
        for (step in WriteStep.entries) {
            val f = File(tmp.root, "x-$step")
            f.writeBytes(byteArrayOf(1, 2, 3))
            val r = runCatching { AtomicFiles { if (it == step) throw Crash() }.write(f, byteArrayOf(9, 9, 9, 9)) {} }
            assertTrue(r.exceptionOrNull() is Crash)
            val now = f.readBytes()
            if (step == WriteStep.RENAMED) assertArrayEquals(byteArrayOf(9, 9, 9, 9), now) else assertArrayEquals(byteArrayOf(1, 2, 3), now)
            AtomicFiles().cleanup(tmp.root)
            assertFalse(File(tmp.root, f.name + AtomicFiles.TMP_SUFFIX).exists())
        }
    }

    @Test fun validationFailureKeepsOld() {
        val f = File(tmp.root, "y")
        f.writeBytes(byteArrayOf(1))
        assertTrue(runCatching { AtomicFiles().write(f, byteArrayOf(2)) { error("bad") } }.isFailure)
        assertArrayEquals(byteArrayOf(1), f.readBytes())
    }
}
