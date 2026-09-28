package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Execution is argv-only and permission-gated by AgentTools.
data class CustomToolDef(val name: String, val description: String, val script: File)

class CustomToolManager(private val workDir: File) {
    fun discover(): List<CustomToolDef> {
        val base = runCatching { PathSecurity.resolveWithin(workDir, ".droide/tools") }.getOrElse { return emptyList() }
        if (!base.isDirectory) return emptyList()
        return base.listFiles()
            ?.asSequence()
            ?.filter {
                it.extension in setOf("sh", "py", "js") && it.isFile && it.length() in 1..MAX_SCRIPT_BYTES &&
                    !PathSecurity.isSymbolicLink(it) && PathSecurity.contains(workDir, it)
            }
            ?.take(50)
            ?.map { f ->
                val first = runCatching { f.bufferedReader().use { it.readLine().orEmpty() } }.getOrDefault("")
                val desc = if (first.startsWith("# desc:")) first.removePrefix("# desc:").trim() else "Custom tool ${f.nameWithoutExtension}"
                val safeName = f.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_-]"), "_").take(56).ifBlank { "tool" }
                CustomToolDef(safeName, desc.take(300), f)
            }
            ?.distinctBy { it.name }
            ?.toList() ?: emptyList()
    }

    fun argv(name: String, input: String): List<String>? {
        val def = discover().firstOrNull { it.name == name } ?: return null
        val relative = def.script.canonicalFile.relativeTo(workDir.canonicalFile).invariantSeparatorsPath
        return when (def.script.extension) {
            "py" -> listOf("python3", relative, input.take(8_000))
            "js" -> listOf("node", relative, input.take(8_000))
            else -> listOf("sh", relative, input.take(8_000))
        }
    }

    fun permissionFingerprint(name: String): String? = discover().firstOrNull { it.name == name }?.let(::fingerprint)

    suspend fun execute(
        name: String,
        input: String,
        terminal: ITerminalSession? = null,
        expectedFingerprint: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val def = discover().firstOrNull { it.name == name } ?: return@withContext "ERROR: custom tool not found: $name"
        expectedFingerprint?.let { expected ->
            require(fingerprint(def) == expected) {
                "Custom tool changed after approval: $name. Review the current script before running it."
            }
        }
        val argv = argv(name, input) ?: return@withContext "ERROR: custom tool not found: $name"
        val execution = terminal
            ?: return@withContext "ERROR: custom tool execution requires the approved workspace execution boundary"
        val result = execution.execArgv(argv, timeoutMs = 10_000)
        if (result.timedOut) "(timeout)" else "exit=${result.exitCode}\n${result.output}"
    }
    private fun fingerprint(def: CustomToolDef): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(def.script.canonicalPath.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        def.script.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_SCRIPT_BYTES = 2L * 1024L * 1024L
    }

}
