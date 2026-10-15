package com.shike.timetable.data

/** Android 的 JSONTokener 容许非标准语法；先检查标准 JSON，保证导入行为一致。 */
internal object StrictJson {
    fun validate(text: String) = Reader(text).validate()

    private class Reader(private val text: String) {
        private var cursor = 0

        fun validate() {
            value(0)
            whitespace()
            if (cursor != text.length) fail("JSON 后存在多余内容")
        }

        private fun value(depth: Int) {
            if (depth > 64) fail("JSON 嵌套层级过深")
            whitespace()
            when (peek()) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> fail("JSON 值无效")
            }
        }

        private fun objectValue(depth: Int) {
            cursor++
            whitespace()
            if (take('}')) return
            val keys = mutableSetOf<String>()
            do {
                whitespace()
                if (peek() != '"') fail("JSON 字段名称必须使用双引号")
                val key = string()
                if (!keys.add(key)) fail("JSON 对象包含重复字段：$key")
                whitespace()
                if (!take(':')) fail("JSON 字段后缺少冒号")
                value(depth)
                whitespace()
                if (take('}')) return
                if (!take(',')) fail("JSON 对象字段之间缺少逗号")
            } while (true)
        }

        private fun arrayValue(depth: Int) {
            cursor++
            whitespace()
            if (take(']')) return
            do {
                value(depth)
                whitespace()
                if (take(']')) return
                if (!take(',')) fail("JSON 数组项之间缺少逗号")
            } while (true)
        }

        private fun string(): String {
            cursor++
            val result = StringBuilder()
            while (cursor < text.length) {
                val char = text[cursor++]
                when {
                    char == '"' -> return result.toString()
                    char == '\\' -> {
                        if (cursor >= text.length) fail("JSON 字符串转义不完整")
                        when (val escaped = text[cursor++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                if (cursor + 4 > text.length) fail("JSON Unicode 转义不完整")
                                val hex = text.substring(cursor, cursor + 4)
                                val code = hex.toIntOrNull(16) ?: fail("JSON Unicode 转义无效")
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                    fail("JSON Unicode 转义无效")
                                }
                                result.append(code.toChar())
                                cursor += 4
                            }
                            else -> fail("JSON 字符串包含无效转义")
                        }
                    }
                    char.code < 0x20 -> fail("JSON 字符串包含未转义的控制字符")
                    else -> result.append(char)
                }
            }
            fail("JSON 字符串缺少结束双引号")
        }

        private fun number() {
            take('-')
            if (!take('0')) {
                if (peek() !in '1'..'9') fail("JSON 数字无效")
                digits()
            }
            if (take('.')) {
                if (peek() !in '0'..'9') fail("JSON 小数部分不完整")
                digits()
            }
            if (peek() == 'e' || peek() == 'E') {
                cursor++
                if (peek() == '+' || peek() == '-') cursor++
                if (peek() !in '0'..'9') fail("JSON 指数部分不完整")
                digits()
            }
        }

        private fun digits() {
            while (peek() in '0'..'9') cursor++
        }

        private fun literal(expected: String) {
            if (!text.startsWith(expected, cursor)) fail("JSON 值无效")
            cursor += expected.length
        }

        private fun whitespace() {
            while (true) {
                when (peek()) {
                    ' ', '\t', '\r', '\n' -> cursor++
                    else -> return
                }
            }
        }

        private fun take(char: Char): Boolean = if (peek() == char) {
            cursor++
            true
        } else false

        private fun peek(): Char = text.getOrNull(cursor) ?: '\u0000'

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("$message（位置 ${cursor + 1}）")
    }
}
