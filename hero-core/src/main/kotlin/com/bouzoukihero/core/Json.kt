package com.bouzoukihero.core

/**
 * A small JSON reader and writer, enough for Bouzouki Tabs results and saved songs.
 * Objects become [Map]s, arrays [List]s, and every number a [Double].
 */
object Json {
    class ParseError(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) throw ParseError("Unexpected text at ${p.i}")
        return v
    }

    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null")
                else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong())
                else sb.append(d)
            }
            is String -> {
                sb.append('"')
                for (c in v) when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append("\\u%04x".format(c.code))
                    else -> sb.append(c)
                }
                sb.append('"')
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            else -> write(sb, v.toString())
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) throw ParseError("Unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseError("Unexpected '$c' at $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseError("Bad literal at $i")
            i += word.length
            return v
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw ParseError("Bad number at $start")
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseError("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw ParseError("Bad escape")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseError("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw ParseError("Expected a key at $i")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') throw ParseError("Expected ':' at $i")
                i++
                m[k] = value()
                skipWs()
                if (i >= s.length) throw ParseError("Unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw ParseError("Expected ',' or '}' at ${i - 1}")
                }
            }
        }

        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                skipWs()
                if (i >= s.length) throw ParseError("Unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw ParseError("Expected ',' or ']' at ${i - 1}")
                }
            }
        }
    }
}

internal fun Map<*, *>.num(key: String): Double? = (this[key] as? Number)?.toDouble()
internal fun Map<*, *>.str(key: String): String? = this[key] as? String
