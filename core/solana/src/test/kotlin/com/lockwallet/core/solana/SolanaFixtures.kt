package com.lockwallet.core.solana

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

internal object SolanaFixtures {
    private val dir = File(checkNotNull(System.getProperty("lockwallet.fixtures")))
    fun json(name: String): JsonObject = JsonParser.parseString(File(dir, name).readText()).asJsonObject
}

internal fun String.hex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
