package com.lockwallet.core.crypto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

internal object Fixtures {
    private val dir = File(checkNotNull(System.getProperty("lockwallet.fixtures")) { "fixtures dir not set" })
    fun json(name: String): JsonObject = JsonParser.parseString(File(dir, name).readText()).asJsonObject
}

internal fun String.hex(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
