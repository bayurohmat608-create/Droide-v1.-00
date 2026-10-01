package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FormatterDef(val name: String, val command: List<String>, val extensions: List<String>)
data class FormattedDocument(val text: String, val formatter: String)

object GuestDocumentFormatter {
    private const val MAX_BYTES = 2_000_000
    private val builtins = listOf(
        FormatterDef("ruff", listOf("ruff", "format", "\$FILE"), listOf("py", "pyi")),
        FormatterDef("prettier", listOf("prettier", "--write", "\$FILE"), listOf("js", "ts", "jsx", "tsx", "mjs", "cjs", "mts", "cts", "md", "json", "html", "css", "scss", "yaml", "yml")),
        FormatterDef("ktlint", listOf("ktlint", "-F", "\$FILE"), listOf("kt", "kts")),
        FormatterDef("gofmt", listOf("gofmt", "-w", "\$FILE"), listOf("go")),
        FormatterDef("shfmt", listOf("shfmt", "-w", "\$FILE"), listOf("sh", "bash")),
    )

    fun definition(path: String): FormatterDef? =
        builtins.firstOrNull { path.substringAfterLast('.', "").lowercase() in it.extensions }

    suspend fun format(
        root: File,
        relativePath: String,
        content: String,
        codeStyle: CodeStyleProfile? = null,
        execute: suspend (List<String>) -> ExecResult,
    ): FormattedDocument? = withContext(Dispatchers.IO) {
        val definition = definition(relativePath) ?: return@withContext null
        val target = PathSecurity.resolveWithin(root, relativePath)
        require(target.isFile) { "Formatter source is missing: $relativePath" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Formatter skipped: file exceeds 2 MB safety limit" }
        // An adjacent snapshot preserves project config discovery without overwriting the saved file.
        val snapshot = File.createTempFile(".droide-format-", ".${target.extension.lowercase()}", target.parentFile)
        try {
            snapshot.writeBytes(bytes)
            val argv = definition.command.map { if (it == "\$FILE") snapshot.absolutePath else it }.toMutableList()
            when (definition.name) {
                "prettier" -> {
                    argv.addAll(1, listOf("--tab-width", (codeStyle?.tabWidth ?: 4).coerceIn(1, 8).toString()))
                    if (codeStyle?.useTabs == true) argv.add(3, "--use-tabs")
                }
                "shfmt" -> argv.addAll(1, listOf("-i", if (codeStyle?.useTabs == true) "0" else (codeStyle?.indentSize ?: 4).coerceIn(1, 8).toString()))
            }
            val result = execute(argv)
            check(!result.timedOut && result.exitCode == 0) {
                if (!result.timedOut && result.exitCode in listOf(126, 127))
                    "Install ${definition.name} in the active Ubuntu terminal to format this file"
                else "Formatter ${definition.name} ${if (result.timedOut) "timed out" else "failed"}: ${result.output.takeLast(500)}"
            }
            check(snapshot.isFile && !PathSecurity.isSymbolicLink(snapshot) && snapshot.length() <= MAX_BYTES) {
                "Formatter produced an invalid file or output larger than 2 MB"
            }
            val decoded = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(snapshot.readBytes())).toString()
            FormattedDocument(decoded, definition.name)
        } finally { snapshot.delete() }
    }
}
