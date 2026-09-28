package com.baystudio.droide.core

data class SmartTypingProfile(
    val structuralPairs: Boolean = true,
    val doubleQuote: Boolean = true,
    val singleQuote: Boolean = true,
    val backtick: Boolean = false,
)

 
object ProfessionalSmartTyping {
    const val INDENT_SPACES = 4
    const val BACKWARD_BALANCE_SCAN = 4_096

    private val plainLike = setOf("plaintext", "markdown", "dockerfile", "makefile", "batch", "vim")
    private val doubleOnly = setOf("json")
    private val noSingleQuote = setOf("json", "haskell")
    private val backtickLanguages = setOf("javascript", "typescript", "react", "vue", "svelte", "kotlin", "shell")

    fun profile(languageId: String): SmartTypingProfile {
        val id = languageId.lowercase()
        if (id in plainLike) return SmartTypingProfile(structuralPairs = false, doubleQuote = false, singleQuote = false)
        return SmartTypingProfile(
            structuralPairs = true,
            doubleQuote = true,
            singleQuote = id !in noSingleQuote && id !in doubleOnly,
            backtick = id in backtickLanguages,
        )
    }

    fun openingForClosing(languageId: String, closing: Char): Char? {
        val profile = profile(languageId)
        return when (closing) {
            ')' -> '('.takeIf { profile.structuralPairs }
            ']' -> '['.takeIf { profile.structuralPairs }
            '}' -> '{'.takeIf { profile.structuralPairs }
            '"' -> '"'.takeIf { profile.doubleQuote }
            '\'' -> '\''.takeIf { profile.singleQuote }
            '`' -> '`'.takeIf { profile.backtick }
            else -> null
        }
    }

    fun isConfiguredEmptyPair(languageId: String, left: Char, right: Char): Boolean =
        openingForClosing(languageId, right) == left

    fun shouldOvertypeClosing(text: CharSequence, cursorIndex: Int, typed: Char, languageId: String): Boolean {
        if (cursorIndex !in 0 until text.length || text[cursorIndex] != typed) return false
        val opening = openingForClosing(languageId, typed) ?: return false
        if (opening == typed) return quoteIsOpen(text, cursorIndex, typed)
        var depth = 0
        var index = cursorIndex - 1
        val limit = (cursorIndex - BACKWARD_BALANCE_SCAN).coerceAtLeast(0)
        while (index >= limit) {
            when (text[index]) {
                typed -> depth++
                opening -> if (depth == 0) return true else depth--
            }
            index--
        }
        return false
    }

    fun shouldAutoPairQuote(
        line: CharSequence,
        cursorColumn: Int,
        quote: Char,
        languageId: String,
        hasSelection: Boolean,
    ): Boolean {
        val opening = openingForClosing(languageId, quote) ?: return false
        if (opening != quote) return false
        if (hasSelection) return true
        val previous = line.getOrNull(cursorColumn - 1)
        if (previous == '\\') return false
        
        if ((quote == '\'' || quote == '`') && previous != null && isIdentifierish(previous)) return false
        


        if (quoteIsOpen(line, cursorColumn.coerceIn(0, line.length), quote)) return false
        return true
    }

    fun indentAdvance(
        languageId: String,
        lineBeforeCursor: String,
        indentSize: Int = INDENT_SPACES,
        continuationIndent: Int = indentSize,
    ): Int {
        val id = languageId.lowercase()
        val trimmed = lineBeforeCursor.trimEnd()
        if (trimmed.isEmpty()) return 0
        val advance = indentSize.coerceIn(1, 8)
        val continuation = continuationIndent.coerceIn(1, 16)
        if (trimmed.last() == '{') return advance
        if (trimmed.last() in charArrayOf('[', '(')) return continuation
        if (id == "python" && trimmed.endsWith(':') && !trimmed.trimStart().startsWith('#')) return advance
        if (id == "yaml" && (trimmed.endsWith(':') || Regex("^\\s*-\\s+.*:\\s*$").matches(trimmed))) return advance
        if (id == "ruby" && Regex(".*\\b(def|class|module|if|unless|case|while|until|for|begin|do)\\b(?:.*)?$").matches(trimmed)) return advance
        if (id == "lua" && Regex(".*\\b(then|do|function|repeat)\\s*$").matches(trimmed)) return advance
        if (id == "elixir" && Regex(".*\\b(do|fn)\\s*$").matches(trimmed)) return advance
        return 0
    }

    fun betweenMatchingPair(languageId: String, beforeText: CharSequence, afterText: CharSequence): Boolean {
        val before = beforeText.lastNonWhitespace() ?: return false
        val after = afterText.firstNonWhitespace() ?: return false
        return openingForClosing(languageId, after) == before && before != after
    }

    fun pairedDeleteRange(text: CharSequence, cursorIndex: Int, languageId: String): IntRange? {
        if (cursorIndex <= 0 || cursorIndex >= text.length) return null
        val left = text[cursorIndex - 1]
        val right = text[cursorIndex]
        if (!isConfiguredEmptyPair(languageId, left, right)) return null
        return (cursorIndex - 1)..cursorIndex
    }

    private fun quoteIsOpen(text: CharSequence, cursorIndex: Int, quote: Char): Boolean {
        var unescapedCount = 0
        var i = cursorIndex - 1
        while (i >= 0 && text[i] != '\n' && text[i] != '\r') {
            if (text[i] == quote) {
                var slashes = 0
                var j = i - 1
                while (j >= 0 && text[j] == '\\') { slashes++; j-- }
                if (slashes % 2 == 0) unescapedCount++
            }
            i--
        }
        return unescapedCount % 2 == 1
    }

    private fun isIdentifierish(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun CharSequence.getOrNull(index: Int): Char? = if (index in indices) this[index] else null
    private fun CharSequence.lastNonWhitespace(): Char? {
        for (i in length - 1 downTo 0) if (!this[i].isWhitespace()) return this[i]
        return null
    }
    private fun CharSequence.firstNonWhitespace(): Char? {
        for (i in indices) if (!this[i].isWhitespace()) return this[i]
        return null
    }
}
