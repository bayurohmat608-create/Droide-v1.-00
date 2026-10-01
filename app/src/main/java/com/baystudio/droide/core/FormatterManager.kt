package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FormatterManager(
    private val workDir: File,
    private val terminals: TerminalManager,
) {
    private val cfgFile = PathSecurity.resolveWithin(workDir, ".droide/formatter.json")
    @Volatile private var enabled: Boolean = loadEnabled()

    suspend fun setEnabled(value: Boolean) = withContext(Dispatchers.IO) {
        check(cfgFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Cannot save formatter settings" }
        val pending = File.createTempFile(".formatter-", ".json", cfgFile.parentFile)
        try {
            pending.writeText("{\"enabled\":$value}")
            runCatching {
                Files.move(pending.toPath(), cfgFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(pending.toPath(), cfgFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            enabled = value
        } finally { pending.delete() }
    }

    fun isEnabled(): Boolean = enabled

    private fun loadEnabled(): Boolean = runCatching {
        cfgFile.isFile && cfgFile.length() <= 10_000 &&
            Json.parseToJsonElement(cfgFile.readText()).jsonObject["enabled"]?.jsonPrimitive?.booleanOrNull == true
    }.getOrDefault(false)

    suspend fun format(relPath: String, content: String, codeStyle: CodeStyleProfile? = null): FormattedDocument? {
        if (!enabled || GuestDocumentFormatter.definition(relPath) == null) return null
        val session = terminals.automatedExecutionSession(createIfMissing = true)
            ?: error(terminals.automatedExecutionUnavailableReason())
        return GuestDocumentFormatter.format(workDir, relPath, content, codeStyle) { argv ->
            session.execArgv(argv, timeoutMs = 10_000)
        }
    }
}
