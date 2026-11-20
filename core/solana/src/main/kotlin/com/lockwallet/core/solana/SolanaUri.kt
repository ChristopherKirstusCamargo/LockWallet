package com.lockwallet.core.solana

/**
 * Recipient read from a QR code (Phase H): a bare Base58 address or a Solana Pay *transfer* request
 * `solana:<address>[?query]`. Only the address is taken (amount/label/memo are ignored and never prefill a
 * transaction). Solana Pay *transaction* requests (`solana:https://…`) are refused — they fetch a
 * transaction from a server, which this wallet never signs blindly.
 */
object SolanaUri {
    const val MAX_LEN = 512
    const val MAX_QUERY_LEN = 256

    sealed interface Result {
        data class Address(val key: SolanaPublicKey) : Result
        data object TransactionRequest : Result
        data object Invalid : Result
    }

    fun parse(text: String): Result {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LEN) return Result.Invalid
        val body = if (t.regionMatches(0, "solana:", 0, 7, ignoreCase = true)) t.substring(7) else t
        if (listOf("http:", "https:", "http%3a", "https%3a").any { body.startsWith(it, ignoreCase = true) }) return Result.TransactionRequest
        val queryAt = body.indexOf('?')
        if (queryAt >= 0 && body.length - queryAt - 1 > MAX_QUERY_LEN) return Result.Invalid
        val addr = body.substringBefore('?').substringBefore('#')
        if (addr.length !in 32..44) return Result.Invalid
        return SolanaPublicKey.fromBase58OrNull(addr)?.let { Result.Address(it) } ?: Result.Invalid
    }
}
