package com.lockwallet.core.crypto.security

import com.lockwallet.core.crypto.Fixtures
import com.lockwallet.core.crypto.lwrp.LwrpCipher
import com.lockwallet.core.crypto.lwrp.LwrpFormatException
import com.lockwallet.core.crypto.lwrp.LwrpHeader
import com.lockwallet.core.crypto.lwrp.LwrpOpenException
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private val CASE = Fixtures.json("lwrp-v1-encrypted.json").getAsJsonArray("cases").first().asJsonObject
private val FILE: ByteArray = CASE["file_hex"].asString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private val PASSWORD: String = CASE["password"].asString

private fun opens(file: ByteArray, password: String): Boolean = try {
    LwrpCipher.open(file, password.toCharArray()).close(); true
} catch (_: LwrpOpenException) { false } catch (_: LwrpFormatException) { false } catch (_: com.lockwallet.core.crypto.lwrp.LwrpPasswordException) { false }

/**
 * Recovery Package tampering: a change to any header byte (magic, version, KDF parameters, salt, nonce) or to any
 * ciphertext/tag byte means the file does not open, so it cannot be downgraded to weaker KDF settings or edited.
 */
@RunWith(Parameterized::class)
class BackupTamperTest(private val position: Int, private val mask: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "byte {0} xor {1}")
        fun data(): List<Array<Any>> {
            val header = (0 until LwrpHeader.HEADER_LEN).flatMap { p -> listOf(0x01, 0x80).map { arrayOf<Any>(p, it) } }
            val body = (LwrpHeader.HEADER_LEN until FILE.size).let { r -> r.filter { (it - r.first) % maxOf(1, r.count() / 40) == 0 } }
                .plus(FILE.size - 1).distinct().map { arrayOf<Any>(it, 0x10) }
            return header + body
        }
    }

    @Test fun neverOpens() {
        val x = FILE.copyOf(); x[position] = (x[position].toInt() xor mask).toByte()
        assertFalse("byte $position xor $mask opened", opens(x, PASSWORD))
    }
}

/** Truncated, extended or spliced files never open and fail with a format error, never a crash. */
@RunWith(Parameterized::class)
class BackupShapeTest(private val case: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "shape {0}") fun data(): List<Array<Any>> = (0 until 24).map { arrayOf<Any>(it) }
    }

    @Test fun refused() {
        val x = when (case) {
            in 0..9 -> FILE.copyOf(FILE.size * case / 10)
            10 -> FILE + 0; 11 -> FILE + ByteArray(1024); 12 -> ByteArray(0); 13 -> FILE.copyOfRange(1, FILE.size)
            14 -> FILE.copyOf(LwrpHeader.HEADER_LEN); 15 -> FILE.copyOf(LwrpHeader.HEADER_LEN + LwrpHeader.TAG_LEN)
            16 -> FILE.copyOfRange(0, LwrpHeader.HEADER_LEN) + FILE.copyOfRange(LwrpHeader.HEADER_LEN, FILE.size).reversedArray()
            17 -> FILE.copyOf().also { it.fill(0, LwrpHeader.HEADER_LEN) }
            18 -> FILE.copyOf().also { it.fill(0xFF.toByte(), FILE.size - LwrpHeader.TAG_LEN) }
            19 -> ByteArray(LwrpHeader.MAX_FILE_SIZE + 1)
            20 -> FILE.copyOf().also { System.arraycopy(FILE, LwrpHeader.HEADER_LEN, it, LwrpHeader.HEADER_LEN + 1, FILE.size - LwrpHeader.HEADER_LEN - 1) }
            21 -> FILE + FILE
            22 -> FILE.copyOf(FILE.size - 1)
            else -> FILE.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        }
        assertFalse(opens(x, PASSWORD))
    }
}

/** Near-miss passwords (case, whitespace, lookalike characters, prefix/suffix) never open the file. */
@RunWith(Parameterized::class)
class BackupPasswordNearMissTest(private val variant: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "variant {0}") fun data(): List<Array<Any>> = (0 until 16).map { arrayOf<Any>(it) }
    }

    @Test fun refused() {
        val p = PASSWORD
        val v = when (variant) {
            0 -> p.uppercase(); 1 -> p.lowercase(); 2 -> " $p"; 3 -> "$p "; 4 -> p.dropLast(1); 5 -> p.drop(1); 6 -> p + p.last()
            7 -> p.replaceFirst(p.first(), if (p.first() == 'a') 'b' else 'a'); 8 -> p.reversed(); 9 -> p + "\u200B"
            10 -> p.replace('o', '0').replace('l', '1').let { if (it == p) p + "1" else it }; 11 -> p + "\n"; 12 -> "\uFEFF$p"
            13 -> p.substring(0, p.length / 2); 14 -> p.map { if (it.isLetter()) (it + 1) else it }.joinToString(""); else -> ""
        }
        if (v == p) return
        assertFalse("'$v' opened", opens(FILE, v))
    }
}
