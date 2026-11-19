package com.lockwallet.data.rpc

/**
 * Minimal strict JSON reader for untrusted RPC responses (RFC 8259 subset: objects, arrays,
 * strings, numbers kept as text, true/false/null). Bounded depth and size; numbers never become floating point.
 */
internal object MiniJson {
    class JsonException(msg: String) : Exception(msg)

    private const val MAX_DEPTH = 32

    fun parse(text: String): Any? {
        val p = P(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw JsonException("trailing data")
        return v
    }

    /** Numbers are returned as [Num] wrapping their exact text. */
    data class Num(val text: String)

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }
        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("too deep")
            if (i >= s.length) throw JsonException("eof")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw JsonException("unexpected")
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw JsonException("literal"); i += w.length; return v }
        fun obj(d: Int): Map<String, Any?> {
            i++; ws()
            val m = LinkedHashMap<String, Any?>()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); if (i >= s.length || s[i] != '"') throw JsonException("key")
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') throw JsonException("colon"); i++; ws()
                if (k in m) throw JsonException("duplicate key")
                m[k] = value(d + 1); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; return m }
                throw JsonException("object")
            }
        }
        fun arr(d: Int): List<Any?> {
            i++; ws()
            val l = ArrayList<Any?>()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws(); l += value(d + 1); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; return l }
                throw JsonException("array")
            }
        }
        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("string eof")
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw JsonException("escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c'); 'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> { if (i + 4 > s.length) throw JsonException("unicode"); b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw JsonException("escape")
                        }
                    }
                    c < ' ' -> throw JsonException("control char")
                    else -> b.append(c)
                }
            }
        }
        fun num(): Num {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return Num(s.substring(st, i))
        }
    }
}

/** Typed accessors over [MiniJson] trees; any shape mismatch is an [RpcException] (MALFORMED). */
@Suppress("UNCHECKED_CAST")
internal object J {
    fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: bad("object")
    fun arr(v: Any?): List<Any?> = v as? List<Any?> ?: bad("array")
    fun str(v: Any?): String = v as? String ?: bad("string")
    fun bool(v: Any?): Boolean = v as? Boolean ?: bad("bool")
    /** Exact unsigned integer ≤ 2^63-1 from JSON number text. */
    fun long(v: Any?): Long {
        val t = (v as? MiniJson.Num)?.text ?: bad("number")
        if (t.isEmpty() || t.length > 19 || !t.all { it in '0'..'9' }) bad("integer")
        return t.toLongOrNull() ?: bad("integer range")
    }
    /** Like [long] but accepts u64 values above 2^63-1 as saturating Long.MAX_VALUE (e.g. activatedStake sums). */
    fun longSat(v: Any?): Long {
        val t = (v as? MiniJson.Num)?.text ?: bad("number")
        if (t.isEmpty() || t.length > 20 || !t.all { it in '0'..'9' }) bad("integer")
        return t.toLongOrNull() ?: Long.MAX_VALUE
    }
    fun longOrNull(v: Any?): Long? = if (v == null) null else long(v)
    fun bad(what: String): Nothing = throw RpcException(RpcException.Kind.MALFORMED, "malformed response: $what")
}

/** Tiny JSON writer for request params (strings are escaped; only ASCII-safe values are produced by callers). */
internal object JsonOut {
    fun str(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\""); c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
