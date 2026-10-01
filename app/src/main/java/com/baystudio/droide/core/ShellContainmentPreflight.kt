package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest

enum class ShellContainmentClass {
    WORKSPACE_CONTAINED,
    EXTERNAL_PATH,
    DYNAMIC_OR_AMBIGUOUS,
}

data class ShellContainmentFinding(
    val kind: String,
    val value: String,
    val resolvedPath: String? = null,
)

data class ShellContainmentResult(
    val classification: ShellContainmentClass,
    val externalDirectories: List<String>,
    val findings: List<ShellContainmentFinding>,
) {
    val requiresShellEscape: Boolean
        get() = classification == ShellContainmentClass.DYNAMIC_OR_AMBIGUOUS ||
            findings.any { it.kind.startsWith("dynamic_") || it.kind == "parse_ambiguity" || it.kind == "wrapper" }
}

// The scanner exists to force a separate external-directory / shell-escape decision before normal shell approval whenever static workspace containment cannot be.







object ShellContainmentPreflight {
    private enum class TokenKind { WORD, OPERATOR }

    private data class Token(
        val kind: TokenKind,
        val text: String,
        val dynamic: Boolean = false,
        val unquotedExpansion: Boolean = false,
    )

    private data class LexResult(
        val tokens: List<Token>,
        val ambiguous: String? = null,
    )

    fun analyze(command: String, workspaceRoot: File, cwd: File = workspaceRoot): ShellContainmentResult {
        require(command.isNotBlank()) { "Shell command cannot be blank" }
        require(command.indexOf('\u0000') < 0) { "Shell command contains NUL" }

        val root = workspaceRoot.canonicalFile
        val working = cwd.canonicalFile
        val external = linkedSetOf<String>()
        val findings = mutableListOf<ShellContainmentFinding>()
        var dynamic = false

        if (!PathSecurity.contains(root, working)) {
            external += directoryBoundary(working, treatAsDirectory = true)
            findings += ShellContainmentFinding("cwd_external", cwd.path, working.path)
        }

        val lexed = lex(command)
        if (lexed.ambiguous != null) {
            dynamic = true
            findings += ShellContainmentFinding("parse_ambiguity", lexed.ambiguous)
        }

        val words = lexed.tokens.filter { it.kind == TokenKind.WORD }
        words.forEach { token ->
            if (token.dynamic) {
                dynamic = true
                findings += ShellContainmentFinding("dynamic_expansion", token.text.take(512))
            }
            if (token.unquotedExpansion && isPathLike(token.text)) {
                dynamic = true
                findings += ShellContainmentFinding("dynamic_glob_or_brace", token.text.take(512))
            }
        }

        inspectCommandSemantics(lexed.tokens, findings).also { if (it) dynamic = true }

        

        lexed.tokens.forEachIndexed { index, token ->
            if (token.kind != TokenKind.WORD || token.dynamic) return@forEachIndexed
            val previous = lexed.tokens.getOrNull(index - 1)
            val forcedPath = previous?.kind == TokenKind.OPERATOR && previous.text in REDIRECT_OPERATORS
            val operand = staticPathOperand(token.text, forcedPath) ?: return@forEachIndexed

            val candidate = resolveStaticPath(operand, working) ?: return@forEachIndexed
            if (!PathSecurity.contains(root, candidate)) {
                external += directoryBoundary(candidate, treatAsDirectory = operand.endsWith('/'))
                findings += ShellContainmentFinding("external_path", operand.take(512), candidate.path)
            }
        }

        val classification = when {
            dynamic -> ShellContainmentClass.DYNAMIC_OR_AMBIGUOUS
            external.isNotEmpty() -> ShellContainmentClass.EXTERNAL_PATH
            else -> ShellContainmentClass.WORKSPACE_CONTAINED
        }
        return ShellContainmentResult(classification, external.toList().sorted(), findings.distinct())
    }

    // Exact-command session scope without placing a potentially 128 KiB command in permission state.
    fun shellEscapeResource(command: String): String = "command-sha256:${sha256(command)}"

    fun customToolEscapeResource(name: String, fingerprint: String): String =
        "custom:$name@sha256:${fingerprint.lowercase().take(64)}"

    private fun inspectCommandSemantics(tokens: List<Token>, findings: MutableList<ShellContainmentFinding>): Boolean {
        var dynamic = false
        val segments = mutableListOf<MutableList<Token>>()
        var current = mutableListOf<Token>()
        fun flush() {
            if (current.isNotEmpty()) segments += current
            current = mutableListOf()
        }
        tokens.forEach { token ->
            if (token.kind == TokenKind.OPERATOR && token.text in COMMAND_SEPARATORS) flush()
            else current += token
        }
        flush()

        segments.forEach { segment ->
            val words = segment.filter { it.kind == TokenKind.WORD }
            if (words.isEmpty()) return@forEach
            var commandIndex = 0
            while (commandIndex < words.size && ENV_ASSIGNMENT.matches(words[commandIndex].text)) commandIndex++
            val command = words.getOrNull(commandIndex) ?: return@forEach
            val base = command.text.substringAfterLast('/')
            val args = words.drop(commandIndex + 1)

            if (base in setOf("eval", "source") || command.text == ".") {
                dynamic = true
                findings += ShellContainmentFinding("wrapper", command.text)
            }
            // Clustered flags such as -lc/-xec still carry -c semantics and must not bypass shellescape.


            words.windowed(size = 2, step = 1, partialWindows = false).forEach { pair ->
                val wrapper = pair[0].text.substringAfterLast('/')
                if (wrapper in SHELL_WRAPPERS && isShellCommandFlag(pair[1].text)) {
                    dynamic = true
                    findings += ShellContainmentFinding("wrapper", "$wrapper ${pair[1].text}")
                }
            }
            if (base == "cd" || base == "pushd") {
                val target = args.firstOrNull()
                if (target == null || target.text == "-" || target.dynamic) {
                    dynamic = true
                    findings += ShellContainmentFinding("dynamic_cwd", target?.text ?: "<default-home>")
                }
            }
            if (base == "popd") {
                dynamic = true
                findings += ShellContainmentFinding("dynamic_cwd", "popd")
            }
        }
        return dynamic
    }


    private fun staticPathOperand(value: String, forcedPath: Boolean): String? {
        if (value.isBlank() || value == "-") return null
        if (forcedPath) return value
        

        if (value.startsWith("--") && '=' in value) {
            val optionValue = value.substringAfter('=')
            return optionValue.takeIf(::isPathLike)
        }
        if (value.startsWith("--")) return null
        return value.takeIf(::isPathLike)
    }

    private fun isShellCommandFlag(value: String): Boolean =
        value.length >= 2 && value[0] == '-' && !value.startsWith("--") && 'c' in value.substring(1)

    private fun resolveStaticPath(value: String, cwd: File): File? {
        if (value.isEmpty()) return null
        
        if (URI_SCHEME.matches(value)) return null
        if (value.startsWith("-") && !value.startsWith("./") && !value.startsWith("../")) return null
        return runCatching {
            val file = File(value)
            if (file.isAbsolute) file.canonicalFile else File(cwd, value).canonicalFile
        }.getOrNull()
    }

    private fun isPathLike(value: String): Boolean {
        if (value.isBlank()) return false
        if (URI_SCHEME.matches(value)) return false
        if (value == "." || value == "..") return true
        if (value.startsWith('/') || value.startsWith("./") || value.startsWith("../") || value.startsWith("~/")) return true
        if ('/' in value) return true
        return false
    }

    private fun directoryBoundary(path: File, treatAsDirectory: Boolean): String {
        val canonical = path.canonicalFile
        val dir = when {
            treatAsDirectory -> canonical
            canonical.isDirectory -> canonical
            else -> canonical.parentFile ?: canonical
        }
        val normalized = dir.path.replace('\\', '/').trimEnd('/')
        return if (normalized.isEmpty()) "/*" else "$normalized/*"
    }

    




    private fun lex(command: String): LexResult {
        val out = mutableListOf<Token>()
        val word = StringBuilder()
        var wordDynamic = false
        var wordExpansion = false
        var state = 0 
        var escaped = false
        var i = 0

        fun flushWord() {
            if (word.isNotEmpty() || wordDynamic || wordExpansion) {
                out += Token(TokenKind.WORD, word.toString(), wordDynamic, wordExpansion)
                word.setLength(0)
                wordDynamic = false
                wordExpansion = false
            }
        }

        while (i < command.length) {
            val ch = command[i]
            if (escaped) {
                word.append(ch)
                escaped = false
                i++
                continue
            }
            when (state) {
                1 -> { 
                    if (ch == '\'') state = 0 else word.append(ch)
                    i++
                }
                2 -> {
                    when (ch) {
                        '"' -> { state = 0; i++ }
                        '\\' -> {
                            val next = command.getOrNull(i + 1)
                            if (next != null && next in setOf('$', '`', '"', '\\', '\n')) {
                                escaped = true
                                i++
                            } else {
                                word.append(ch)
                                i++
                            }
                        }
                        '$', '`' -> {
                            wordDynamic = true
                            word.append(ch)
                            i++
                        }
                        else -> { word.append(ch); i++ }
                    }
                }
                else -> {
                    when {
                        ch == '\'' -> { state = 1; i++ }
                        ch == '"' -> { state = 2; i++ }
                        ch == '\\' -> { escaped = true; i++ }
                        ch == '$' || ch == '`' -> {
                            wordDynamic = true
                            word.append(ch)
                            i++
                        }
                        (ch == '<' || ch == '>') && command.getOrNull(i + 1) == '(' -> {
                            flushWord()
                            return LexResult(out, "process substitution ${command.substring(i, minOf(command.length, i + 16))}")
                        }
                        ch.isWhitespace() -> {
                            flushWord()
                            if (ch == '\n') out += Token(TokenKind.OPERATOR, "\n")
                            i++
                        }
                        ch in OPERATOR_STARTS -> {
                            flushWord()
                            val op = longestOperatorAt(command, i)
                            out += Token(TokenKind.OPERATOR, op)
                            if (op in setOf("<<", "<<<")) {
                                return LexResult(out, "heredoc/here-string requires shell-escape approval")
                            }
                            i += op.length
                        }
                        ch == '~' && word.isEmpty() -> {
                            wordDynamic = true
                            word.append(ch)
                            i++
                        }
                        ch == '*' || ch == '?' || ch == '[' || ch == '{' -> {
                            wordExpansion = true
                            word.append(ch)
                            i++
                        }
                        ch == '#' && word.isEmpty() && (out.isEmpty() || out.last().kind == TokenKind.OPERATOR) -> {
                            while (i < command.length && command[i] != '\n') i++
                        }
                        else -> { word.append(ch); i++ }
                    }
                }
            }
        }
        if (escaped) return LexResult(out, "trailing escape")
        if (state != 0) return LexResult(out, "unclosed shell quote")
        flushWord()
        return LexResult(out)
    }

    private fun longestOperatorAt(command: String, offset: Int): String {
        val candidates = listOf("<<<", ">>", "<<", "&&", "||", ";;", ">&", "<&", ">", "<", ";", "|", "&", "(", ")")
        return candidates.firstOrNull { command.startsWith(it, offset) } ?: command[offset].toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private val OPERATOR_STARTS = setOf('<', '>', ';', '|', '&', '(', ')')
    private val COMMAND_SEPARATORS = setOf(";", "&&", "||", "|", "&", "\n", "(", ")")
    private val REDIRECT_OPERATORS = setOf(">", ">>", "<", ">&", "<&")
    private val SHELL_WRAPPERS = setOf("sh", "bash", "zsh", "dash", "ksh", "mksh", "ash")
    private val ENV_ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
    private val URI_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*://.*")
}
