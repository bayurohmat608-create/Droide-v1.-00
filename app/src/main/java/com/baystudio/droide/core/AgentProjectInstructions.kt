package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext










object AgentProjectInstructions {
    private const val MAX_BRIEF_FILE_BYTES = 50_000L
    private const val MAX_RULE_FILE_BYTES = 12_000L
    private const val MAX_RULES = 24
    private const val MAX_TOTAL_CHARS = 28_000

    suspend fun load(files: FileRepository, workDir: File, plugins: AgentPluginSource = AgentPluginSource.EMPTY): String = withContext(Dispatchers.IO) {
        buildString {
            listOf("AGENTS.md", ".droide/AGENTS.md", "GEMINI.md").forEach { rel ->
                readBounded(files, workDir, rel, MAX_BRIEF_FILE_BYTES)?.let { text ->
                    appendLine("\n--- Project instructions ($rel) ---")
                    appendLine(text.take(8_000))
                }
            }

            discoverAlwaysOnRules(workDir).forEach { rule ->
                val rel = rule.relativeTo(workDir).invariantSeparatorsPath
                val text = runCatching { rule.readText() }.getOrNull()?.take(MAX_RULE_FILE_BYTES.toInt()) ?: return@forEach
                appendLine("\n--- Always-on workspace rule ($rel) ---")
                appendLine(stripFrontmatter(text).take(MAX_RULE_FILE_BYTES.toInt()))
            }

            AgentRuleManager(workDir, plugins).alwaysOn().forEach { rule ->
                appendLine("\n--- Always-on Agent plugin rule (${rule.name}; ${rule.sourceLabel}) ---")
                appendLine(rule.body.take(MAX_RULE_FILE_BYTES.toInt()))
            }
        }.take(MAX_TOTAL_CHARS)
    }

    fun discoverAlwaysOnRules(workDir: File): List<File> {
        val canonicalRoot = runCatching { workDir.canonicalFile }.getOrNull() ?: return emptyList()
        return listOf(".agents/rules", ".agent/rules")
            .asSequence()
            .mapNotNull { rel -> runCatching { PathSecurity.resolveWithin(canonicalRoot, rel) }.getOrNull() }
            .filter { it.isDirectory && !PathSecurity.isSymbolicLink(it) }
            .flatMap { dir ->
                dir.listFiles().orEmpty().asSequence()
                    .filter { file ->
                        file.isFile && !PathSecurity.isSymbolicLink(file) &&
                            file.extension.equals("md", ignoreCase = true) &&
                            file.length() in 1..MAX_RULE_FILE_BYTES &&
                            PathSecurity.contains(canonicalRoot, file)
                    }
            }
            .sortedBy { it.absolutePath }
            .filter(::isAlwaysOn)
            .take(MAX_RULES)
            .toList()
    }

    private fun isAlwaysOn(file: File): Boolean {
        val prefix = runCatching {
            file.bufferedReader().use { reader ->
                buildString {
                    repeat(32) {
                        val line = reader.readLine() ?: return@repeat
                        appendLine(line)
                        if (length > 4_096) return@repeat
                    }
                }
            }
        }.getOrNull() ?: return false
        if (!prefix.startsWith("---")) return false
        val end = prefix.indexOf("\n---", startIndex = 3)
        if (end < 0) return false
        val frontmatter = prefix.substring(3, end)
        val trigger = Regex("(?im)^\\s*trigger\\s*:\\s*([^#\\r\\n]+)")
            .find(frontmatter)?.groupValues?.get(1)?.trim()?.lowercase() ?: return false
        return trigger == "always_on" || trigger == "always-on" || trigger == "always"
    }

    private fun stripFrontmatter(text: String): String {
        if (!text.startsWith("---")) return text.trim()
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return text.trim()
        return text.substring(end + 4).trim()
    }

    private suspend fun readBounded(files: FileRepository, workDir: File, rel: String, maxBytes: Long): String? {
        val file = runCatching { PathSecurity.resolveWithin(workDir, rel) }.getOrNull() ?: return null
        if (!file.isFile || PathSecurity.isSymbolicLink(file) || file.length() !in 1..maxBytes) return null
        return runSuspendCatching { files.readText(rel, maxBytes = maxBytes) }.getOrNull()
    }
}
