package com.baystudio.droide.core

enum class DroideHighlightKind {
    NORMAL, KEYWORD, COMMENT, STRING, NUMBER, OPERATOR, FUNCTION, TYPE, VARIABLE,
    ANNOTATION, TAG, ATTRIBUTE, PROPERTY, REGEXP,
}

data class DroideHighlightSpan(
    val line: Int,
    val start: Int,
    val end: Int,
    val kind: DroideHighlightKind,
    val semantic: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
)

data class DroideHighlightResult(
    val lineCount: Int,
    val spans: List<DroideHighlightSpan>,
    val skippedForSize: Boolean = false,
)

// This is deliberately conservative: malformed or oversized input becomes plain text, never a hot loop.



object DroideSyntaxSemanticEngine {
    const val MAX_HIGHLIGHT_CHARS = 1_048_576
    const val MAX_HIGHLIGHT_SPANS = 20_000

    private enum class Family { C, PYTHON, HASH, SQL, LUA, HTML, CSS, JSON, YAML, MARKDOWN, LISP, PLAIN }
    private data class State(var blockComment: String? = null, var tripleQuote: String? = null, var htmlComment: Boolean = false)

    private val commonKeywords = setOf(
        "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return", "throw", "try", "catch", "finally",
        "class", "interface", "enum", "struct", "trait", "object", "extends", "implements", "new", "this", "super", "public", "private",
        "protected", "internal", "static", "final", "const", "let", "var", "val", "fun", "function", "def", "fn", "func", "void", "async",
        "await", "yield", "import", "from", "export", "package", "module", "namespace", "using", "in", "is", "as", "with", "where", "when",
        "match", "type", "typeof", "sizeof", "true", "false", "null", "nil", "none", "self", "operator", "override", "abstract", "sealed",
        "data", "record", "readonly", "mutable", "unsafe", "extern", "inline", "virtual", "suspend", "synchronized", "volatile", "transient",
    )
    private val perLanguageKeywords = mapOf(
        "python" to setOf("and", "or", "not", "elif", "lambda", "nonlocal", "global", "pass", "raise", "assert", "del", "None", "True", "False"),
        "kotlin" to setOf("companion", "reified", "crossinline", "noinline", "lateinit", "tailrec", "infix", "actual", "expect"),
        "java" to setOf("instanceof", "native", "strictfp", "assert"),
        "rust" to setOf("crate", "impl", "dyn", "move", "mut", "pub", "ref", "loop", "use", "mod", "macro_rules"),
        "go" to setOf("chan", "defer", "fallthrough", "go", "map", "range", "select", "go", "interface"),
        "swift" to setOf("guard", "defer", "protocol", "extension", "associatedtype", "some", "any"),
        "ruby" to setOf("begin", "rescue", "ensure", "unless", "until", "elsif", "end", "require", "include"),
        "lua" to setOf("then", "elseif", "repeat", "until", "local", "end"),
        "php" to setOf("echo", "foreach", "elseif", "endif", "endforeach", "require", "include", "namespace", "use"),
        "sql" to setOf("select", "from", "where", "join", "left", "right", "inner", "outer", "on", "group", "order", "by", "having", "insert", "into", "update", "delete", "create", "alter", "drop", "table", "index", "values", "set", "distinct", "limit", "offset", "union", "all", "and", "or", "not", "null", "as", "case", "when", "then", "end"),
        "dart" to setOf("mixin", "extension", "required", "late", "factory"),
        "scala" to setOf("given", "implicit", "lazy", "opaque", "extension"),
        "elixir" to setOf("defmodule", "defp", "defmacro", "cond", "unless", "end"),
    )

    fun highlight(text: String, languageId: String, semanticTokens: List<LspSemanticToken> = emptyList()): DroideHighlightResult {
        val lineCount = text.count { it == '\n' } + 1
        if (text.length > MAX_HIGHLIGHT_CHARS) return DroideHighlightResult(lineCount, emptyList(), skippedForSize = true)
        val lines = text.split('\n')
        val declarativeSpans = DeclarativeTextMateRegistry.highlight(text, languageId, MAX_HIGHLIGHT_SPANS)
        val spans = if (declarativeSpans != null) {
            ArrayList<DroideHighlightSpan>(declarativeSpans)
        } else {
            val family = family(languageId)
            val state = State()
            ArrayList<DroideHighlightSpan>(minOf(text.length / 6, MAX_HIGHLIGHT_SPANS)).also { lexical ->
                for ((lineIndex, line) in lines.withIndex()) {
                    if (lexical.size >= MAX_HIGHLIGHT_SPANS) break
                    scanLine(line, lineIndex, languageId, family, state, lexical)
                }
            }
        }
        if (semanticTokens.isNotEmpty() && spans.size < MAX_HIGHLIGHT_SPANS) {
            for (token in semanticTokens) {
                if (spans.size >= MAX_HIGHLIGHT_SPANS) break
                val line = lines.getOrNull(token.line) ?: continue
                val start = token.startCharacter.coerceIn(0, line.length)
                val end = (start + token.length).coerceIn(start, line.length)
                if (end <= start) continue
                spans += semanticSpan(token, start, end)
            }
        }
        return DroideHighlightResult(lineCount, spans)
    }

    private fun scanLine(
        line: String,
        lineIndex: Int,
        languageId: String,
        family: Family,
        state: State,
        out: MutableList<DroideHighlightSpan>,
    ) {
        when (family) {
            Family.HTML -> scanHtml(line, lineIndex, state, out)
            Family.MARKDOWN -> scanMarkdown(line, lineIndex, out)
            Family.JSON -> scanJson(line, lineIndex, out)
            Family.YAML -> scanYaml(line, lineIndex, out)
            else -> scanCode(line, lineIndex, languageId, family, state, out)
        }
    }

    private fun scanCode(line: String, lineIndex: Int, languageId: String, family: Family, state: State, out: MutableList<DroideHighlightSpan>) {
        val keywords = commonKeywords + perLanguageKeywords[languageId].orEmpty()
        var i = 0
        fun add(start: Int, end: Int, kind: DroideHighlightKind) {
            if (end > start && out.size < MAX_HIGHLIGHT_SPANS) out += DroideHighlightSpan(lineIndex, start, end, kind)
        }
        state.blockComment?.let { endMarker ->
            val end = line.indexOf(endMarker)
            if (end < 0) { add(0, line.length, DroideHighlightKind.COMMENT); return }
            add(0, end + endMarker.length, DroideHighlightKind.COMMENT)
            i = end + endMarker.length
            state.blockComment = null
        }
        state.tripleQuote?.let { marker ->
            val end = line.indexOf(marker)
            if (end < 0) { add(0, line.length, DroideHighlightKind.STRING); return }
            add(0, end + marker.length, DroideHighlightKind.STRING)
            i = end + marker.length
            state.tripleQuote = null
        }
        while (i < line.length && out.size < MAX_HIGHLIGHT_SPANS) {
            if (isLineCommentStart(line, i, family)) { add(i, line.length, DroideHighlightKind.COMMENT); break }
            val block = blockCommentAt(line, i, family)
            if (block != null) {
                val (open, close) = block
                val end = line.indexOf(close, i + open.length)
                if (end < 0) { add(i, line.length, DroideHighlightKind.COMMENT); state.blockComment = close; break }
                add(i, end + close.length, DroideHighlightKind.COMMENT); i = end + close.length; continue
            }
            val triple = tripleQuoteAt(line, i, languageId)
            if (triple != null) {
                val end = line.indexOf(triple, i + triple.length)
                if (end < 0) { add(i, line.length, DroideHighlightKind.STRING); state.tripleQuote = triple; break }
                add(i, end + triple.length, DroideHighlightKind.STRING); i = end + triple.length; continue
            }
            val ch = line[i]
            if (ch == '\'' || ch == '"' || (ch == '`' && languageId in setOf("javascript", "typescript", "react", "svelte", "vue"))) {
                val end = scanQuoted(line, i, ch)
                add(i, end, DroideHighlightKind.STRING); i = end; continue
            }
            if (ch.isDigit()) {
                var j = i + 1
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "._xXbBoO+-")) j++
                add(i, j, DroideHighlightKind.NUMBER); i = j; continue
            }
            if (ch == '@' && i + 1 < line.length && isIdentifierStart(line[i + 1])) {
                var j = i + 2; while (j < line.length && isIdentifierPart(line[j])) j++
                add(i, j, DroideHighlightKind.ANNOTATION); i = j; continue
            }
            if (isIdentifierStart(ch) || (ch == '$' && languageId in setOf("php", "javascript", "typescript", "react"))) {
                var j = i + 1; while (j < line.length && isIdentifierPart(line[j])) j++
                val word = line.substring(i, j)
                val keywordHit = word in keywords || (languageId == "sql" && word.lowercase() in perLanguageKeywords["sql"].orEmpty())
                if (keywordHit) add(i, j, DroideHighlightKind.KEYWORD)
                else {
                    var k = j; while (k < line.length && line[k].isWhitespace()) k++
                    when {
                        k < line.length && line[k] == '(' -> add(i, j, DroideHighlightKind.FUNCTION)
                        word.firstOrNull()?.isUpperCase() == true && family == Family.C -> add(i, j, DroideHighlightKind.TYPE)
                    }
                }
                i = j; continue
            }
            if (!ch.isWhitespace() && ch in "+-*/%=!<>:&|^~?.;,()[]{}") add(i, i + 1, DroideHighlightKind.OPERATOR)
            i++
        }
    }

    private fun scanHtml(line: String, lineIndex: Int, state: State, out: MutableList<DroideHighlightSpan>) {
        var i = 0
        fun add(a: Int, b: Int, k: DroideHighlightKind) { if (b > a && out.size < MAX_HIGHLIGHT_SPANS) out += DroideHighlightSpan(lineIndex, a, b, k) }
        if (state.htmlComment) {
            val e = line.indexOf("-->")
            if (e < 0) { add(0, line.length, DroideHighlightKind.COMMENT); return }
            add(0, e + 3, DroideHighlightKind.COMMENT); state.htmlComment = false; i = e + 3
        }
        while (i < line.length && out.size < MAX_HIGHLIGHT_SPANS) {
            if (line.startsWith("<!--", i)) {
                val e = line.indexOf("-->", i + 4)
                if (e < 0) { add(i, line.length, DroideHighlightKind.COMMENT); state.htmlComment = true; break }
                add(i, e + 3, DroideHighlightKind.COMMENT); i = e + 3; continue
            }
            if (line[i] == '<') {
                val end = line.indexOf('>', i + 1).let { if (it < 0) line.length else it + 1 }
                var j = i + 1
                if (j < end && line[j] == '/') j++
                while (j < end && line[j].isWhitespace()) j++
                while (j < end && (line[j].isLetterOrDigit() || line[j] in "-:_")) j++
                add(i, minOf(j, end), DroideHighlightKind.TAG)
                while (j < end) {
                    while (j < end && (line[j].isWhitespace() || line[j] == '/')) j++
                    val a = j
                    while (j < end && (line[j].isLetterOrDigit() || line[j] in "-:_@.")) j++
                    if (j > a) add(a, j, DroideHighlightKind.ATTRIBUTE)
                    while (j < end && line[j].isWhitespace()) j++
                    if (j < end && line[j] == '=') {
                        add(j, j + 1, DroideHighlightKind.OPERATOR); j++
                        while (j < end && line[j].isWhitespace()) j++
                        if (j < end && (line[j] == '"' || line[j] == '\'')) {
                            val q = line[j]; val e = scanQuoted(line.substring(0, end), j, q)
                            add(j, e, DroideHighlightKind.STRING); j = e
                        }
                    }
                    if (j == a) j++
                }
                i = end; continue
            }
            i++
        }
    }

    private fun scanJson(line: String, lineIndex: Int, out: MutableList<DroideHighlightSpan>) {
        var i = 0
        fun add(a: Int, b: Int, k: DroideHighlightKind) { if (b > a && out.size < MAX_HIGHLIGHT_SPANS) out += DroideHighlightSpan(lineIndex, a, b, k) }
        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') {
                val e = scanQuoted(line, i, ch)
                var k = e; while (k < line.length && line[k].isWhitespace()) k++
                add(i, e, if (k < line.length && line[k] == ':') DroideHighlightKind.PROPERTY else DroideHighlightKind.STRING)
                i = e
            } else if (ch.isDigit() || (ch == '-' && i + 1 < line.length && line[i + 1].isDigit())) {
                var j = i + 1; while (j < line.length && (line[j].isDigit() || line[j] in ".eE+-")) j++
                add(i, j, DroideHighlightKind.NUMBER); i = j
            } else if (line.startsWith("true", i) || line.startsWith("false", i) || line.startsWith("null", i)) {
                val len = when { line.startsWith("false", i) -> 5; line.startsWith("true", i) -> 4; else -> 4 }
                add(i, i + len, DroideHighlightKind.KEYWORD); i += len
            } else { if (!ch.isWhitespace() && ch in "{}[],:" ) add(i, i + 1, DroideHighlightKind.OPERATOR); i++ }
        }
    }

    private fun scanYaml(line: String, lineIndex: Int, out: MutableList<DroideHighlightSpan>) {
        val comment = line.indexOf('#').takeIf { it >= 0 && (it == 0 || line[it - 1] != '\\') }
        val codeEnd = comment ?: line.length
        val colon = line.indexOf(':').takeIf { it in 1 until codeEnd }
        if (colon != null) {
            val start = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            if (start < colon) out += DroideHighlightSpan(lineIndex, start, colon, DroideHighlightKind.PROPERTY)
        }
        var i = 0
        while (i < codeEnd && out.size < MAX_HIGHLIGHT_SPANS) {
            val ch = line[i]
            if (ch == '\'' || ch == '"') { val e = minOf(scanQuoted(line, i, ch), codeEnd); out += DroideHighlightSpan(lineIndex, i, e, DroideHighlightKind.STRING); i = e }
            else if (ch.isDigit()) { var j=i+1; while(j<codeEnd && (line[j].isDigit()||line[j] in ".-+")) j++; out += DroideHighlightSpan(lineIndex,i,j,DroideHighlightKind.NUMBER); i=j }
            else i++
        }
        if (comment != null) out += DroideHighlightSpan(lineIndex, comment, line.length, DroideHighlightKind.COMMENT)
    }

    private fun scanMarkdown(line: String, lineIndex: Int, out: MutableList<DroideHighlightSpan>) {
        val trim = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }
        var hashes = trim
        while (hashes < line.length && line[hashes] == '#') hashes++
        if (hashes > trim && hashes - trim <= 6 && hashes < line.length && line[hashes].isWhitespace()) {
            out += DroideHighlightSpan(lineIndex, trim, line.length, DroideHighlightKind.KEYWORD, bold = true)
            return
        }
        var i = 0
        while (i < line.length && out.size < MAX_HIGHLIGHT_SPANS) {
            if (line[i] == '`') {
                val fence = if (line.startsWith("```", i)) "```" else "`"
                val end = line.indexOf(fence, i + fence.length).let { if (it < 0) line.length else it + fence.length }
                out += DroideHighlightSpan(lineIndex, i, end, DroideHighlightKind.STRING); i = end
            } else if (line[i] == '[') {
                val close = line.indexOf(']', i + 1); if (close > i) { out += DroideHighlightSpan(lineIndex, i, close + 1, DroideHighlightKind.ATTRIBUTE); i = close + 1 } else i++
            } else i++
        }
    }

    private fun semanticSpan(token: LspSemanticToken, start: Int, end: Int): DroideHighlightSpan {
        val kind = when (token.type) {
            "keyword", "modifier" -> DroideHighlightKind.KEYWORD
            "comment" -> DroideHighlightKind.COMMENT
            "string" -> DroideHighlightKind.STRING
            "number" -> DroideHighlightKind.NUMBER
            "regexp" -> DroideHighlightKind.REGEXP
            "operator" -> DroideHighlightKind.OPERATOR
            "function", "method", "macro" -> DroideHighlightKind.FUNCTION
            "type", "class", "enum", "interface", "struct", "typeParameter", "namespace" -> DroideHighlightKind.TYPE
            "property", "enumMember", "event" -> DroideHighlightKind.PROPERTY
            "decorator" -> DroideHighlightKind.ANNOTATION
            "parameter", "variable" -> DroideHighlightKind.VARIABLE
            else -> DroideHighlightKind.NORMAL
        }
        val mods = token.modifiers
        return DroideHighlightSpan(
            line = token.line, start = start, end = end, kind = kind, semantic = true,
            bold = "declaration" in mods || "definition" in mods,
            italic = "readonly" in mods || "static" in mods || "abstract" in mods,
            strike = "deprecated" in mods,
        )
    }

    private fun isLineCommentStart(line: String, i: Int, family: Family): Boolean = when (family) {
        Family.C -> line.startsWith("//", i)
        Family.PYTHON, Family.HASH -> line[i] == '#'
        Family.SQL -> line.startsWith("--", i)
        Family.LUA -> line.startsWith("--", i)
        Family.LISP -> line[i] == ';'
        else -> false
    }

    private fun blockCommentAt(line: String, i: Int, family: Family): Pair<String, String>? = when {
        family in setOf(Family.C, Family.SQL, Family.CSS) && line.startsWith("/*", i) -> "/*" to "*/"
        else -> null
    }

    private fun tripleQuoteAt(line: String, i: Int, languageId: String): String? = when {
        languageId == "python" && line.startsWith("\"\"\"", i) -> "\"\"\""
        languageId == "python" && line.startsWith("'''", i) -> "'''"
        languageId == "kotlin" && line.startsWith("\"\"\"", i) -> "\"\"\""
        else -> null
    }

    private fun scanQuoted(line: String, start: Int, quote: Char): Int {
        var i = start + 1
        var escaped = false
        while (i < line.length) {
            val c = line[i]
            if (!escaped && c == quote) return i + 1
            if (!escaped && c == '\\') escaped = true else escaped = false
            i++
        }
        return line.length
    }

    private fun isIdentifierStart(c: Char) = c == '_' || c.isLetter()
    private fun isIdentifierPart(c: Char) = c == '_' || c == '$' || c.isLetterOrDigit()

    private fun family(id: String): Family = when (id) {
        "python" -> Family.PYTHON
        "ruby", "shell", "r", "perl", "powershell", "makefile", "dockerfile", "properties", "ini", "toml" -> Family.HASH
        "sql" -> Family.SQL
        "lua" -> Family.LUA
        "html", "vue", "svelte" -> Family.HTML
        "css" -> Family.CSS
        "json" -> Family.JSON
        "yaml" -> Family.YAML
        "markdown" -> Family.MARKDOWN
        "clojure" -> Family.LISP
        "plaintext" -> Family.PLAIN
        else -> Family.C
    }
}
