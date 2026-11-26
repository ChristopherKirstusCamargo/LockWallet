package com.lockwallet.data.rpc

import java.io.File

/** Scripted JSON-RPC endpoint(s). Handlers return the raw JSON of `result` or throw [RpcException]. */
class FakeRpc : RpcTransport {
    val handlers = HashMap<String, HashMap<String, (String) -> String>>() // url -> method -> handler
    val calls = mutableListOf<Triple<String, String, String>>()          // url, method, body

    fun on(url: String, method: String, h: (String) -> String) { handlers.getOrPut(url) { HashMap() }[method] = h }

    override fun post(url: String, body: String, maxBytes: Int): String {
        val m = Regex("\"method\":\"(\\w+)\"").find(body)!!.groupValues[1]
        calls += Triple(url, m, body)
        val h = handlers[url]?.get(m) ?: throw RpcException(RpcException.Kind.TRANSPORT, "down")
        val rid = Regex("\"id\":(\\d+)").find(body)!!.groupValues[1]
        return """{"jsonrpc":"2.0","id":$rid,"result":${h(body)}}"""
    }

    fun count(method: String) = calls.count { it.second == method }

    companion object {
        const val A = "https://a.example"
        const val B = "https://b.example"
        fun fixtures(): File = File(checkNotNull(System.getProperty("lockwallet.fixtures")))
    }
}

internal fun String.hexBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
