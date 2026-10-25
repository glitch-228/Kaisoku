package org.koitharu.kotatsu.core.util

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Validation only. Numbers/unknown fields and the original UTF-8 byte sequence are never reserialized. */
object BoundedJson {
    fun validateObject(bytes: ByteArray, maximum: Int = 32 * 1024 * 1024): String {
        if (bytes.isEmpty() || bytes.size > maximum) throw IllegalArgumentException("payload_too_large")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("invalid_utf8")
        }
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > MAX_DEPTH) throw IllegalArgumentException("json_too_deep")
                '}', ']' -> if (--depth < 0) throw IllegalArgumentException("invalid_json")
            }
        }
        if (depth != 0 || quoted) throw IllegalArgumentException("invalid_json")
        Parser(text).validate()
        return text
    }

    private class Parser(private val input: String) {
        private var index = 0
        private var values = 0

        fun validate() {
            whitespace()
            if (peek() != '{') invalid()
            value()
            whitespace()
            if (index != input.length) invalid()
        }

        private fun value() {
            if (++values > 4_000_000) throw IllegalArgumentException("too_many_json_values")
            whitespace()
            when (peek()) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> string(false)
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> invalid()
            }
        }

        private fun objectValue() {
            index++
            whitespace()
            if (consume('}')) return
            val keys = hashSetOf<String>()
            while (true) {
                whitespace()
                if (peek() != '"') invalid()
                val key = checkNotNull(string(true))
                if (!keys.add(key)) throw IllegalArgumentException("duplicate_json_key")
                if (keys.size > 100_000) throw IllegalArgumentException("too_many_json_members")
                whitespace()
                if (!consume(':')) invalid()
                value()
                whitespace()
                if (consume('}')) return
                if (!consume(',')) invalid()
            }
        }

        private fun arrayValue() {
            index++
            whitespace()
            if (consume(']')) return
            var count = 0
            while (true) {
                if (++count > 500_000) throw IllegalArgumentException("too_many_json_elements")
                value()
                whitespace()
                if (consume(']')) return
                if (!consume(',')) invalid()
            }
        }

        private fun string(collect: Boolean): String? {
            if (!consume('"')) invalid()
            val result = if (collect) StringBuilder() else null
            while (index < input.length) {
                var character = input[index++]
                if (character == '"') return result?.toString()
                if (character < ' ') invalid()
                if (character == '\\') {
                    if (index >= input.length) invalid()
                    character = when (val escape = input[index++]) {
                        '"', '\\', '/' -> escape
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> unicodeEscape()
                        else -> invalid()
                    }
                    if (character.isHighSurrogate()) {
                        if (index + 2 > input.length || input[index++] != '\\' || input[index++] != 'u') invalid()
                        val low = unicodeEscape()
                        if (!low.isLowSurrogate()) invalid()
                        result?.append(character)?.append(low)
                    } else {
                        if (character.isLowSurrogate()) invalid()
                        result?.append(character)
                    }
                } else if (character.isHighSurrogate()) {
                    if (index >= input.length || !input[index].isLowSurrogate()) invalid()
                    result?.append(character)?.append(input[index])
                    index++
                } else {
                    if (character.isLowSurrogate()) invalid()
                    result?.append(character)
                }
                if (result != null && result.length > 16_384) throw IllegalArgumentException("json_key_too_long")
            }
            invalid()
        }

        private fun unicodeEscape(): Char {
            if (index + 4 > input.length) invalid()
            var code = 0
            repeat(4) { code = code * 16 + (input[index++].digitToIntOrNull(16) ?: invalid()) }
            return code.toChar()
        }

        private fun number() {
            consume('-')
            if (consume('0')) {
                if (peek() in '0'..'9') invalid()
            } else {
                if (peek() !in '1'..'9') invalid()
                while (peek() in '0'..'9') index++
            }
            if (consume('.')) {
                if (peek() !in '0'..'9') invalid()
                while (peek() in '0'..'9') index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                if (peek() !in '0'..'9') invalid()
                while (peek() in '0'..'9') index++
            }
        }

        private fun literal(value: String) {
            if (!input.startsWith(value, index)) invalid()
            index += value.length
        }

        private fun whitespace() {
            while (index < input.length && input[index] in " \t\r\n") index++
        }

        private fun peek(): Char = input.getOrNull(index) ?: '\u0000'
        private fun consume(value: Char): Boolean = if (peek() == value) { index++; true } else false
        private fun invalid(): Nothing = throw IllegalArgumentException("invalid_json")
    }

    private const val MAX_DEPTH = 64
}
