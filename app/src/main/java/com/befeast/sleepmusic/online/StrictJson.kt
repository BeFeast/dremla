package com.befeast.sleepmusic.online

/** Same grammar on Android and the host: no platform tokenizer, duplicate keys or extensions. */
internal class StrictJson(private val text: String) {
    private var at = 0
    fun parse(): Any? = value(0).also { space(); require(at == text.length) }
    private fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(c: Char): Boolean { space(); return if (at < text.length && text[at] == c) { at++; true } else false }
    private fun value(depth: Int): Any? {
        require(depth < 64); space(); require(at < text.length)
        return when (text[at]) {
            '{' -> {
                at++; val result = linkedMapOf<String, Any?>()
                if (!take('}')) {
                    do { space(); val key = string(); require(key !in result); require(take(':')); result[key] = value(depth + 1) } while (take(','))
                    require(take('}'))
                }; result
            }
            '[' -> {
                at++; val result = mutableListOf<Any?>()
                if (!take(']')) { do { result += value(depth + 1) } while (take(',')); require(take(']')) }; result
            }
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> {
                val token = NUMBER.find(text, at)?.takeIf { it.range.first == at }?.value ?: error("JSON number")
                at += token.length
                if (token.any { it in ".eE" }) token.toDouble().also { require(it.isFinite()) } else token.toLong()
            }
        }
    }
    private fun literal(word: String, result: Any?): Any? { require(text.startsWith(word, at)); at += word.length; return result }
    private fun string(): String {
        require(at < text.length && text[at++] == '"')
        val out = StringBuilder()
        while (at < text.length) {
            val c = text[at++]
            if (c == '"') return out.toString()
            require(c >= ' ')
            if (c != '\\') out.append(c) else {
                require(at < text.length)
                out.append(when (val escape = text[at++]) {
                    '"', '\\', '/' -> escape
                    'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> { require(at + 4 <= text.length); val hex = text.substring(at, at + 4); require(hex.all { it in "0123456789abcdefABCDEF" }); at += 4; hex.toInt(16).toChar() }
                    else -> error("JSON escape")
                })
            }
        }
        error("Unterminated string")
    }
    companion object { private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") }
}
