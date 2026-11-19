package com.lockwallet.data.price

/**
 * Minimal strict JSON reader for small, untrusted price responses (RFC 8259 subset: objects, arrays,
 * strings, numbers kept as text, true/false/null). Bounded depth and size; numbers never become floating point.
 */
internal object MiniJson {
    class JsonException(msg: String) : Exception(msg)

    private const val MAX_DEPTH = 16

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
