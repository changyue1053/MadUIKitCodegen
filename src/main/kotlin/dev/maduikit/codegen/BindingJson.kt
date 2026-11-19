package dev.maduikit.codegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Strict, duplicate-aware lexical gate for opt-in component inputs only. */
internal fun validateBindingJson(raw: String) {
    class Scanner {
        var offset = 0
        fun demand(ok: Boolean) { require(ok) { "Invalid or ambiguous component JSON" } }
        fun space() { while (offset < raw.length && raw[offset] in " \t\r\n") offset++ }
        fun take(c: Char): Boolean {
            space()
            if (offset < raw.length && raw[offset] == c) { offset++; return true }
            return false
        }
        fun string(): String {
            space(); val start = offset
            demand(offset < raw.length && raw[offset++] == '"')
            var closed = false
            while (offset < raw.length) {
                val c = raw[offset++]
                if (c == '"') { closed = true; break }
                demand(c.code >= 32)
                if (c == '\\') {
                    demand(offset < raw.length)
                    val escape = raw[offset++]
                    demand(escape in "\"\\/bfnrtu")
                    if (escape == 'u') {
                        demand(offset + 4 <= raw.length && raw.substring(offset, offset + 4).all { it in "0123456789abcdefABCDEF" })
                        offset += 4
                    }
                }
            }
            demand(closed)
            val value = (Json.parseToJsonElement(raw.substring(start, offset)) as JsonPrimitive).content
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (c.isHighSurrogate()) { demand(i < value.length && value[i].isLowSurrogate()); i++ }
                else demand(!c.isLowSurrogate())
            }
            return value
        }
        fun value(depth: Int) {
            demand(depth <= 128); space(); demand(offset < raw.length)
            when (raw[offset]) {
                '{' -> {
                    offset++; val keys = mutableSetOf<String>()
                    if (!take('}')) do {
                        demand(keys.add(string())); demand(take(':')); value(depth + 1)
                        if (take('}')) return
                    } while (take(','))
                    else return
                    demand(false)
                }
                '[' -> {
                    offset++
                    if (!take(']')) do {
                        value(depth + 1)
                        if (take(']')) return
                    } while (take(','))
                    else return
                    demand(false)
                }
                '"' -> string()
                else -> {
                    val start = offset
                    while (offset < raw.length && raw[offset] !in " \t\r\n,]}") offset++
                    val token = raw.substring(start, offset)
                    if (token !in setOf("true", "false", "null")) {
                        demand(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token))
                        demand(token.toDoubleOrNull()?.isFinite() == true)
                    }
                }
            }
        }
    }
    val scanner = Scanner()
    scanner.value(0); scanner.space()
    require(scanner.offset == raw.length) { "Trailing component JSON" }
}
