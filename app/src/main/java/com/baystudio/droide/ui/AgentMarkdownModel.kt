package com.baystudio.droide.ui

// Keep untrusted input and output bounded.


internal sealed interface AgentMarkdownBlock {
    data class Paragraph(val text: String) : AgentMarkdownBlock
    data class Heading(val level: Int, val text: String) : AgentMarkdownBlock
    data class Quote(val text: String) : AgentMarkdownBlock
    data class ListBlock(val items: List<AgentMarkdownListItem>) : AgentMarkdownBlock
    data class Table(
        val headers: List<String>,
        val aligns: List<AgentMarkdownTableAlign>,
        val rows: List<List<String>>,
    ) : AgentMarkdownBlock
    data class CodeBlock(val language: String, val code: String) : AgentMarkdownBlock
    data object Rule : AgentMarkdownBlock
}

internal enum class AgentMarkdownTableAlign { START, CENTER, END }

internal data class AgentStreamingMarkdownProjection(
    val stableBlocks: List<AgentMarkdownBlock>,
    val tail: String,
    val completed: Boolean,
)


internal class AgentStreamingMarkdownProjector {
    private var source = ""
    private var stableChars = 0
    private val stable = ArrayList<AgentMarkdownBlock>()

    fun project(markdown: String, completed: Boolean): AgentStreamingMarkdownProjection {
        if (!markdown.startsWith(source)) reset()
        source = markdown
        if (completed) {
            val all = AgentMarkdownParser.parse(markdown)
            stable.clear()
            stable.addAll(all)
            stableChars = markdown.length
            return AgentStreamingMarkdownProjection(stable.toList(), "", true)
        }

        val nextStable = AgentMarkdownParser.stableStreamingPrefixLength(markdown)
        if (nextStable < stableChars) reset()
        if (nextStable > stableChars) {
            val chunk = markdown.substring(stableChars, nextStable)
            stable += AgentMarkdownParser.parse(chunk)
            stableChars = nextStable
        }
        return AgentStreamingMarkdownProjection(
            stableBlocks = stable.toList(),
            tail = markdown.substring(stableChars),
            completed = false,
        )
    }

    private fun reset() {
        source = ""
        stableChars = 0
        stable.clear()
    }
}

internal data class AgentMarkdownListItem(
    val ordered: Boolean,
    val ordinal: Int?,
    val text: String,
    val task: Boolean? = null,
)

internal enum class AgentMarkdownInlineKind { TEXT, STRONG, EMPHASIS, STRIKE, CODE, LINK }

internal data class AgentMarkdownInline(
    val text: String,
    val kind: AgentMarkdownInlineKind = AgentMarkdownInlineKind.TEXT,
    val url: String? = null,
)

internal enum class AgentCodeTokenKind { PLAIN, KEYWORD, STRING, COMMENT, NUMBER }

internal data class AgentCodeToken(val text: String, val kind: AgentCodeTokenKind)

internal object AgentMarkdownParser {
    const val MAX_BLOCKS = 512
    const val MAX_INLINE_TOKENS = 768
    const val MAX_HIGHLIGHT_CHARS = 24_000

    private val heading = Regex("""^ {0,3}(#{1,6})[\t ]+(.+?)\s*#*\s*$""")
    private val unordered = Regex("""^\s{0,3}[-+*][\t ]+(.+)$""")
    private val ordered = Regex("""^\s{0,3}(\d{1,9})[.)][\t ]+(.+)$""")
    private val quote = Regex("""^\s{0,3}>[\t ]?(.*)$""")
    private val task = Regex("""^\[([ xX])][\t ]+(.+)$""")
    private val horizontalRule = Regex("""^\s{0,3}(?:(?:\*\s*){3,}|(?:-\s*){3,}|(?:_\s*){3,})$""")

    fun parse(markdown: String): List<AgentMarkdownBlock> {
        if (markdown.isBlank()) return emptyList()
        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val out = ArrayList<AgentMarkdownBlock>(minOf(lines.size, 32))
        var index = 0

        while (index < lines.size) {
            if (out.size >= MAX_BLOCKS) {
                val remainder = lines.subList(index, lines.size).joinToString("\n")
                if (remainder.isNotBlank()) out += AgentMarkdownBlock.Paragraph(remainder)
                break
            }
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }

            val fence = parseFence(line)
            if (fence != null) {
                val body = StringBuilder()
                index++
                while (index < lines.size) {
                    val candidate = lines[index]
                    if (isFenceClose(candidate, fence.marker, fence.length)) {
                        index++
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(candidate)
                    index++
                }
                

                out += AgentMarkdownBlock.CodeBlock(fence.language, body.toString())
                continue
            }

            val headingMatch = heading.matchEntire(line)
            if (headingMatch != null) {
                out += AgentMarkdownBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2])
                index++
                continue
            }
            if (horizontalRule.matches(line)) {
                out += AgentMarkdownBlock.Rule
                index++
                continue
            }

            val parsedTable = parseTable(lines, index)
            if (parsedTable != null) {
                out += parsedTable.block
                index = parsedTable.nextIndex
                continue
            }

            if (quote.matches(line)) {
                val body = StringBuilder()
                while (index < lines.size) {
                    val match = quote.matchEntire(lines[index]) ?: break
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(match.groupValues[1])
                    index++
                }
                out += AgentMarkdownBlock.Quote(body.toString())
                continue
            }

            val firstListItem = parseListItem(line)
            if (firstListItem != null) {
                val items = ArrayList<AgentMarkdownListItem>()
                var current: AgentMarkdownListItem? = firstListItem
                while (current != null && index < lines.size) {
                    items += current
                    index++
                    current = if (index < lines.size) parseListItem(lines[index]) else null
                }
                out += AgentMarkdownBlock.ListBlock(items)
                continue
            }

            val paragraph = StringBuilder(line)
            index++
            while (index < lines.size && lines[index].isNotBlank() && !startsBlock(lines[index])) {
                paragraph.append('\n').append(lines[index])
                index++
            }
            out += AgentMarkdownBlock.Paragraph(paragraph.toString())
        }
        return out
    }

    fun parseInline(source: String): List<AgentMarkdownInline> {
        if (source.isEmpty()) return emptyList()
        val out = ArrayList<AgentMarkdownInline>()
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out += AgentMarkdownInline(plain.toString())
                plain.setLength(0)
            }
        }
        fun add(text: String, kind: AgentMarkdownInlineKind, url: String? = null) {
            if (text.isEmpty()) return
            flushPlain()
            out += AgentMarkdownInline(text, kind, url)
        }

        var i = 0
        while (i < source.length) {
            if (out.size >= MAX_INLINE_TOKENS) {
                plain.append(source.substring(i))
                break
            }
            val c = source[i]
            if (c == '\\' && i + 1 < source.length && source[i + 1] in "\\`*_{}[]()#+-.!~>") {
                plain.append(source[i + 1])
                i += 2
                continue
            }

            if (c == '`') {
                val ticks = source.countRun(i, '`')
                val close = source.indexOf("`".repeat(ticks), i + ticks)
                if (close >= 0) {
                    add(source.substring(i + ticks, close).trimSinglePaddingSpace(), AgentMarkdownInlineKind.CODE)
                    i = close + ticks
                    continue
                }
            }

            val strongMarker = when {
                source.startsWith("**", i) -> "**"
                source.startsWith("__", i) -> "__"
                else -> null
            }
            if (strongMarker != null) {
                val close = source.indexOf(strongMarker, i + 2)
                if (close > i + 2) {
                    add(source.substring(i + 2, close), AgentMarkdownInlineKind.STRONG)
                    i = close + 2
                    continue
                }
            }

            if (source.startsWith("~~", i)) {
                val close = source.indexOf("~~", i + 2)
                if (close > i + 2) {
                    add(source.substring(i + 2, close), AgentMarkdownInlineKind.STRIKE)
                    i = close + 2
                    continue
                }
            }

            if ((c == '*' || c == '_') && (i == 0 || source[i - 1] != c)) {
                val close = source.indexOf(c, i + 1)
                if (close > i + 1) {
                    add(source.substring(i + 1, close), AgentMarkdownInlineKind.EMPHASIS)
                    i = close + 1
                    continue
                }
            }

            if (source.startsWith("![", i)) {
                val labelEnd = source.indexOf(']', i + 2)
                if (labelEnd > i + 2 && labelEnd + 1 < source.length && source[labelEnd + 1] == '(') {
                    val urlEnd = source.indexOf(')', labelEnd + 2)
                    if (urlEnd > labelEnd + 2) {
                        val alt = source.substring(i + 2, labelEnd)
                        plain.append("[image: ").append(alt).append(']')
                        i = urlEnd + 1
                        continue
                    }
                }
            }

            if (c == '[') {
                val labelEnd = source.indexOf(']', i + 1)
                if (labelEnd > i + 1 && labelEnd + 1 < source.length && source[labelEnd + 1] == '(') {
                    val urlEnd = findLinkTargetEnd(source, labelEnd + 2)
                    if (urlEnd > labelEnd + 2) {
                        val label = source.substring(i + 1, labelEnd)
                        val url = source.substring(labelEnd + 2, urlEnd).trim().substringBefore(' ').trim('<', '>')
                        if (isSafeHttpUrl(url)) add(label, AgentMarkdownInlineKind.LINK, url)
                        else { flushPlain(); out += AgentMarkdownInline(label) }
                        i = urlEnd + 1
                        continue
                    }
                }
            }

            if (c == '<') {
                val close = source.indexOf('>', i + 1)
                if (close > i + 1) {
                    val url = source.substring(i + 1, close)
                    if (isSafeHttpUrl(url)) {
                        add(url, AgentMarkdownInlineKind.LINK, url)
                        i = close + 1
                        continue
                    }
                }
            }

            if ((source.startsWith("https://", i) || source.startsWith("http://", i)) &&
                (i == 0 || !source[i - 1].isLetterOrDigit())
            ) {
                var end = i
                while (end < source.length && !source[end].isWhitespace()) end++
                while (end > i && source[end - 1] in ".,;:!?)]}") end--
                val url = source.substring(i, end)
                if (isSafeHttpUrl(url)) {
                    add(url, AgentMarkdownInlineKind.LINK, url)
                    i = end
                    continue
                }
            }

            plain.append(c)
            i++
        }
        flushPlain()
        return out
    }


    fun stableStreamingPrefixLength(markdown: String): Int {
        if (markdown.isEmpty()) return 0
        var offset = 0
        var stable = 0
        var fence: Fence? = null
        while (offset < markdown.length) {
            val newline = markdown.indexOf('\n', offset)
            if (newline < 0) break
            val line = markdown.substring(offset, newline)
            val next = newline + 1
            val active = fence
            if (active != null) {
                if (isFenceClose(line, active.marker, active.length)) {
                    fence = null
                    stable = next
                }
            } else {
                val opened = parseFence(line)
                if (opened != null) {
                    fence = opened
                } else if (line.isBlank()) {
                    stable = next
                }
            }
            offset = next
        }
        return stable.coerceIn(0, markdown.length)
    }

    fun isSafeHttpUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.length !in 8..2048) return false
        val lower = value.lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://")
    }

    fun highlightCode(code: String, language: String): List<AgentCodeToken> {
        if (code.isEmpty()) return emptyList()
        if (code.length > MAX_HIGHLIGHT_CHARS) return listOf(AgentCodeToken(code, AgentCodeTokenKind.PLAIN))
        val profile = CodeProfile.forLanguage(language) ?: return listOf(AgentCodeToken(code, AgentCodeTokenKind.PLAIN))
        val out = ArrayList<AgentCodeToken>()
        val plain = StringBuilder()

        fun emit(kind: AgentCodeTokenKind, text: String) {
            if (text.isEmpty()) return
            if (kind == AgentCodeTokenKind.PLAIN) {
                plain.append(text)
                return
            }
            if (plain.isNotEmpty()) {
                out += AgentCodeToken(plain.toString(), AgentCodeTokenKind.PLAIN)
                plain.setLength(0)
            }
            out += AgentCodeToken(text, kind)
        }

        var i = 0
        while (i < code.length) {
            val lineMarker = profile.lineComment?.takeIf { code.startsWith(it, i) }
            if (lineMarker != null) {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                emit(AgentCodeTokenKind.COMMENT, code.substring(i, end))
                i = end
                continue
            }
            if (profile.blockComments && code.startsWith("/*", i)) {
                val close = code.indexOf("*/", i + 2)
                val end = if (close < 0) code.length else close + 2
                emit(AgentCodeTokenKind.COMMENT, code.substring(i, end))
                i = end
                continue
            }
            val c = code[i]
            if (c == '"' || c == '\'' || (c == '`' && profile.backtickStrings)) {
                val quote = c
                var end = i + 1
                var escaped = false
                while (end < code.length) {
                    val ch = code[end]
                    if (!escaped && ch == quote) {
                        end++
                        break
                    }
                    escaped = !escaped && ch == '\\'
                    if (ch != '\\') escaped = false
                    end++
                }
                emit(AgentCodeTokenKind.STRING, code.substring(i, end))
                i = end
                continue
            }
            if (c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit())) {
                var end = i + 1
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] in "._xX+-")) end++
                emit(AgentCodeTokenKind.NUMBER, code.substring(i, end))
                i = end
                continue
            }
            if (c == '_' || c.isLetter()) {
                var end = i + 1
                while (end < code.length && (code[end] == '_' || code[end].isLetterOrDigit())) end++
                val word = code.substring(i, end)
                emit(if (word in profile.keywords) AgentCodeTokenKind.KEYWORD else AgentCodeTokenKind.PLAIN, word)
                i = end
                continue
            }
            plain.append(c)
            i++
        }
        if (plain.isNotEmpty()) out += AgentCodeToken(plain.toString(), AgentCodeTokenKind.PLAIN)
        return out
    }

    private data class Fence(val marker: Char, val length: Int, val language: String)

    private fun parseFence(line: String): Fence? {
        val trimmed = line.dropWhile { it == ' ' }.takeIf { line.length - it.length <= 3 } ?: return null
        if (trimmed.length < 3 || (trimmed[0] != '`' && trimmed[0] != '~')) return null
        val marker = trimmed[0]
        val length = trimmed.countRun(0, marker)
        if (length < 3) return null
        val info = trimmed.substring(length).trim()
        val language = info.substringBefore(' ').substringBefore('{').trim().take(40)
        return Fence(marker, length, language)
    }

    private fun isFenceClose(line: String, marker: Char, minimumLength: Int): Boolean {
        val trimmed = line.trimStart()
        if (line.length - trimmed.length > 3 || trimmed.isEmpty() || trimmed[0] != marker) return false
        val length = trimmed.countRun(0, marker)
        return length >= minimumLength && trimmed.substring(length).isBlank()
    }

    private data class ParsedTable(val block: AgentMarkdownBlock.Table, val nextIndex: Int)

    private fun parseTable(lines: List<String>, start: Int): ParsedTable? {
        if (start + 1 >= lines.size) return null
        val headers = splitTableRow(lines[start]) ?: return null
        if (headers.size !in 2..12) return null
        val separators = splitTableRow(lines[start + 1]) ?: return null
        if (separators.size != headers.size) return null
        val aligns = separators.map { raw ->
            val cell = raw.trim()
            if (!Regex("^:?-{3,}:?$").matches(cell)) return null
            when {
                cell.startsWith(':') && cell.endsWith(':') -> AgentMarkdownTableAlign.CENTER
                cell.endsWith(':') -> AgentMarkdownTableAlign.END
                else -> AgentMarkdownTableAlign.START
            }
        }
        val rows = ArrayList<List<String>>()
        var index = start + 2
        while (index < lines.size && rows.size < 64) {
            val line = lines[index]
            if (line.isBlank()) break
            val cells = splitTableRow(line) ?: break
            if (cells.size < 2) break
            rows += List(headers.size) { column -> cells.getOrNull(column).orEmpty().take(2_000) }
            index++
        }
        return ParsedTable(
            AgentMarkdownBlock.Table(
                headers = headers.map { it.take(2_000) },
                aligns = aligns,
                rows = rows,
            ),
            index,
        )
    }

    private fun splitTableRow(line: String): List<String>? {
        if ('|' !in line) return null
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var escaped = false
        var ticks = 0
        for (char in line.trim()) {
            if (escaped) {
                cell.append(char)
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                cell.append(char)
                continue
            }
            if (char == '`') {
                ticks = if (ticks == 0) 1 else 0
                cell.append(char)
                continue
            }
            if (char == '|' && ticks == 0) {
                cells += cell.toString().trim()
                cell.setLength(0)
            } else {
                cell.append(char)
            }
        }
        cells += cell.toString().trim()
        if (cells.firstOrNull().isNullOrEmpty()) cells.removeAt(0)
        if (cells.lastOrNull().isNullOrEmpty() && cells.isNotEmpty()) cells.removeAt(cells.lastIndex)
        return cells.takeIf { it.size >= 2 }
    }

    private fun parseListItem(line: String): AgentMarkdownListItem? {
        unordered.matchEntire(line)?.let { match ->
            val raw = match.groupValues[1]
            task.matchEntire(raw)?.let { taskMatch ->
                return AgentMarkdownListItem(false, null, taskMatch.groupValues[2], taskMatch.groupValues[1].isNotBlank())
            }
            return AgentMarkdownListItem(false, null, raw)
        }
        ordered.matchEntire(line)?.let { match ->
            return AgentMarkdownListItem(true, match.groupValues[1].toIntOrNull(), match.groupValues[2])
        }
        return null
    }

    private fun startsBlock(line: String): Boolean =
        parseFence(line) != null || heading.matches(line) || horizontalRule.matches(line) ||
            quote.matches(line) || parseListItem(line) != null

    private fun findLinkTargetEnd(source: String, start: Int): Int {
        var depth = 0
        var escaped = false
        for (i in start until source.length) {
            val c = source[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '(') depth++
            if (c == ')') {
                if (depth == 0) return i
                depth--
            }
        }
        return -1
    }

    private fun String.countRun(start: Int, char: Char): Int {
        var i = start
        while (i < length && this[i] == char) i++
        return i - start
    }

    private fun String.trimSinglePaddingSpace(): String =
        if (length >= 2 && first() == ' ' && last() == ' ' && any { it != ' ' }) substring(1, length - 1) else this

    private data class CodeProfile(
        val keywords: Set<String>,
        val lineComment: String?,
        val blockComments: Boolean,
        val backtickStrings: Boolean = false,
    ) {
        companion object {
            private val cLike = setOf(
                "abstract", "as", "break", "case", "catch", "class", "const", "continue", "default", "do", "else", "enum", "extends", "false", "final", "finally", "for", "fun", "function", "if", "implements", "import", "in", "interface", "is", "let", "new", "null", "object", "override", "package", "private", "protected", "public", "return", "static", "struct", "super", "switch", "this", "throw", "throws", "true", "try", "type", "typeof", "val", "var", "void", "when", "while"
            )
            private val python = setOf(
                "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while", "with", "yield"
            )
            private val shell = setOf("case", "do", "done", "elif", "else", "esac", "export", "fi", "for", "function", "if", "in", "local", "readonly", "then", "until", "while")
            private val rust = setOf("as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while")
            private val go = setOf("break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var")
            private val sql = setOf("SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING", "INSERT", "INTO", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "TABLE", "INDEX", "VALUES", "SET", "AND", "OR", "NOT", "NULL", "AS", "DISTINCT", "LIMIT", "OFFSET")

            fun forLanguage(raw: String): CodeProfile? = when (raw.trim().lowercase()) {
                "kt", "kts", "kotlin", "java", "c", "h", "cpp", "c++", "cc", "cxx", "cs", "csharp", "gradle" -> CodeProfile(cLike, "//", true)
                "js", "javascript", "jsx", "ts", "typescript", "tsx" -> CodeProfile(cLike, "//", true, backtickStrings = true)
                "py", "python" -> CodeProfile(python, "#", false)
                "sh", "bash", "zsh", "shell" -> CodeProfile(shell, "#", false)
                "rs", "rust" -> CodeProfile(rust, "//", true)
                "go", "golang" -> CodeProfile(go, "//", true, backtickStrings = true)
                "sql" -> CodeProfile(sql + sql.map { it.lowercase() }, "--", true)
                "json", "jsonc" -> CodeProfile(setOf("true", "false", "null"), if (raw.lowercase() == "jsonc") "//" else null, raw.lowercase() == "jsonc")
                else -> null
            }
        }
    }
}
