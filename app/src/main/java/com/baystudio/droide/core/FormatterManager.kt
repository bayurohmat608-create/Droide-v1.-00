package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class FormatterDef(val name: String, val command: List<String>, val extensions: List<String>)
data class FormattedDocument(val text: String, val formatter: String)

 
class FormatterManager(
    private val workDir: File,
    private val terminals: TerminalManager,
    private val bridge: DeviceBridgeManager,
    private val android: AndroidDevelopmentManager,
    private val capabilities: UniversalCapabilityRegistry,
) {
    private val cfgFile = PathSecurity.resolveWithin(workDir, ".droide/formatter.json")
    private var enabled: Boolean = loadEnabled()
    private val builtins = listOf(
        FormatterDef("ruff", listOf("ruff", "format", "\$FILE"), listOf(".py", ".pyi")),
        FormatterDef("prettier", listOf("prettier", "--write", "\$FILE"), listOf(".js", ".ts", ".jsx", ".tsx", ".md", ".json")),
        FormatterDef("ktlint", listOf("ktlint", "-F", "\$FILE"), listOf(".kt", ".kts")),
        FormatterDef("gofmt", listOf("gofmt", "-w", "\$FILE"), listOf(".go")),
        FormatterDef("shfmt", listOf("shfmt", "-w", "\$FILE"), listOf(".sh")),
    )

    fun setEnabled(v: Boolean) {
        enabled = v
        runCatching {
            cfgFile.parentFile?.mkdirs()
            atomicWrite(cfgFile, "{\"enabled\":$v}")
        }
    }

    fun isEnabled() = enabled

    private fun loadEnabled(): Boolean = runCatching {
        cfgFile.isFile && cfgFile.length() <= 10_000 && cfgFile.readText().contains("\"enabled\":true")
    }.getOrDefault(false)

    suspend fun format(relPath: String, content: String, codeStyle: CodeStyleProfile? = null): FormattedDocument? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val target = PathSecurity.resolveWithin(workDir, relPath)
        require(target.isFile) { "Formatter source is missing: $relPath" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2_000_000) { "Formatter skipped: file exceeds 2 MB safety limit" }
        val ext = "." + target.name.substringAfterLast('.', "")
        val def = builtins.firstOrNull { ext in it.extensions } ?: return@withContext null
        val executable = capabilities.resolveExecutable(def.command.first())?.resolvedPath
            ?: error("Formatter ${def.name} is not installed in the selected Device Workstation environment")
        val session = terminals.automatedExecutionSession(createIfMissing = true) as? DevicePtySessionHandle
            ?: error("Connect Device Workstation to run ${def.name}")
        session.refreshWorkspace()
        val config = android.prepareInteractiveShell(syncProject = false)
        val mapper = WorkspacePathMapper(workDir, config.remoteWorkspace)
        val parent = mapper.localToRemote(target).substringBeforeLast('/')
        val remoteFile = "$parent/.droide-format-${UUID.randomUUID()}$ext"
        DeviceBridgeManager.requireSafeRemotePath(remoteFile)
        val snapshot = File.createTempFile("droide-format-", ext)
        val output = File.createTempFile("droide-formatted-", ext)
        try {
            snapshot.writeBytes(bytes)
            val prepared = bridge.shellBounded("mkdir -p -- ${DeviceBridgeManager.shellQuote(parent)}", maxOutputBytes = 4_096)
            check(prepared.exitCode == 0) { "Formatter workspace preparation failed: ${prepared.combined.takeLast(300)}" }
            bridge.push(snapshot, remoteFile)
            val argv = def.command.map { if (it == "\$FILE") remoteFile else it }.toMutableList()
            argv[0] = executable
            when (def.name) {
                "prettier" -> {
                    argv.add(1, "--tab-width"); argv.add(2, (codeStyle?.tabWidth ?: 4).toString())
                    if (codeStyle?.useTabs == true) argv.add(3, "--use-tabs")
                }
                "shfmt" -> {
                    argv.add(1, "-i"); argv.add(2, if (codeStyle?.useTabs == true) "0" else (codeStyle?.indentSize ?: 4).toString())
                }
            }
            val outcome = session.execArgv(argv, timeoutMs = 10_000)
            check(!outcome.timedOut && outcome.exitCode == 0) {
                "Formatter ${def.name} ${if (outcome.timedOut) "timed out" else "failed"}: ${outcome.output.takeLast(500)}"
            }
            bridge.pull(remoteFile, output)
            check(output.length() <= 2_000_000) { "Formatter output exceeds 2 MB safety limit" }
            val decoded = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.readBytes())).toString()
            FormattedDocument(decoded, def.name)
        } finally {
            withContext(NonCancellable) {
                runSuspendCatching { bridge.shellBounded("rm -f -- ${DeviceBridgeManager.shellQuote(remoteFile)}", maxOutputBytes = 4_096) }
            }
            snapshot.delete()
            output.delete()
        }
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(text)
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
