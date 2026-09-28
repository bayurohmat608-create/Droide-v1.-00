package com.baystudio.droide.core

import java.io.File

 
object AgentPluginContributions {
    const val MAX_PLUGIN_COUNT = 32
    const val MAX_MARKDOWN_BYTES = 64L * 1024L

    fun skillFiles(source: AgentPluginSource, workDir: File): List<Pair<AgentPluginContributionRoot, File>> =
        source.contributions(workDir).flatMap { plugin ->
            val out = mutableListOf<Pair<AgentPluginContributionRoot, File>>()
            safeFile(plugin, "SKILL.md", MAX_MARKDOWN_BYTES)?.let { out += plugin to it }
            safeDir(plugin, "skills")?.listFiles().orEmpty()
                .asSequence()
                .filter { it.isDirectory && !PathSecurity.isSymbolicLink(it) }
                .sortedBy { it.name.lowercase() }
                .take(64)
                .mapNotNull { dir -> safeFile(plugin, "skills/${dir.name}/SKILL.md", MAX_MARKDOWN_BYTES)?.let { plugin to it } }
                .forEach(out::add)
            safeDir(plugin, "commands")?.listFiles().orEmpty().asSequence()
                .filter { it.isFile && it.extension.equals("md", true) && it.length() in 1..MAX_MARKDOWN_BYTES && !PathSecurity.isSymbolicLink(it) }
                .sortedBy { it.name.lowercase() }.take(48).map { plugin to it }.forEach(out::add)
            out
        }.take(128)

    fun agentFiles(source: AgentPluginSource, workDir: File): List<Pair<AgentPluginContributionRoot, File>> =
        source.contributions(workDir).flatMap { plugin ->
            safeDir(plugin, "agents")?.listFiles().orEmpty().asSequence()
                .filter { it.isFile && it.extension.equals("md", true) && it.length() in 1..MAX_MARKDOWN_BYTES && !PathSecurity.isSymbolicLink(it) }
                .sortedBy { it.name.lowercase() }
                .take(32)
                .map { plugin to it }
                .toList()
        }.take(96)

    fun ruleFiles(source: AgentPluginSource, workDir: File): List<Pair<AgentPluginContributionRoot, File>> =
        source.contributions(workDir).flatMap { plugin ->
            safeDir(plugin, "rules")?.listFiles().orEmpty().asSequence()
                .filter { it.isFile && it.extension.equals("md", true) && it.length() in 1..12_000L && !PathSecurity.isSymbolicLink(it) }
                .sortedBy { it.name.lowercase() }
                .take(32)
                .map { plugin to it }
                .toList()
        }.take(96)

    fun mcpFiles(source: AgentPluginSource, workDir: File): List<Pair<AgentPluginContributionRoot, File>> =
        source.contributions(workDir).flatMap { plugin ->
            listOf(".mcp.json", "mcp_config.json", "mcp.json").mapNotNull { rel ->
                safeFile(plugin, rel, 128L * 1024L)?.let { plugin to it }
            }
        }.take(32)

    fun hookFiles(source: AgentPluginSource, workDir: File): List<Pair<AgentPluginContributionRoot, File>> =
        source.contributions(workDir).flatMap { plugin ->
            listOf("hooks/hooks.json", "hooks.json").mapNotNull { rel ->
                safeFile(plugin, rel, 128L * 1024L)?.let { plugin to it }
            }
        }.take(32)

    fun toolFiles(source: AgentPluginSource, workDir: File): List<Pair<AgentPluginContributionRoot, File>> =
        source.contributions(workDir).mapNotNull { plugin ->
            safeFile(plugin, "tools/tools.json", 128L * 1024L)?.let { plugin to it }
                ?: safeFile(plugin, "tools.json", 128L * 1024L)?.let { plugin to it }
        }.take(32)

    fun expandPluginRoot(value: String, plugin: AgentPluginContributionRoot): String = value
        .replace("${'$'}{DROIDE_PLUGIN_ROOT}", plugin.root.absolutePath)
        .replace("${'$'}{CLAUDE_PLUGIN_ROOT}", plugin.root.absolutePath)
        .replace("${'$'}{PLUGIN_ROOT}", plugin.root.absolutePath)

    private fun safeDir(plugin: AgentPluginContributionRoot, rel: String): File? = runCatching {
        PathSecurity.resolveWithin(plugin.root, rel).takeIf { it.isDirectory && !PathSecurity.isSymbolicLink(it) }
    }.getOrNull()

    private fun safeFile(plugin: AgentPluginContributionRoot, rel: String, maxBytes: Long): File? = runCatching {
        PathSecurity.resolveWithin(plugin.root, rel).takeIf {
            it.isFile && !PathSecurity.isSymbolicLink(it) && it.length() in 1..maxBytes
        }
    }.getOrNull()
}
