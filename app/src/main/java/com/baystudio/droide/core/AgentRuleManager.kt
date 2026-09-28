package com.baystudio.droide.core

import java.io.File

// Rules are guidance, never authority.
class AgentRuleManager(
    private val workDir: File,
    private val plugins: AgentPluginSource = AgentPluginSource.EMPTY,
) {
    fun discover(): List<AgentPluginRule> = AgentPluginContributions.ruleFiles(plugins, workDir).mapNotNull { (plugin, file) ->
        parse(plugin, file)
    }.distinctBy { it.name }.take(96)

    fun load(name: String): AgentPluginRule? = discover().firstOrNull { it.name == name }
    fun alwaysOn(): List<AgentPluginRule> = discover().filter { it.activation == AgentPluginRuleActivation.ALWAYS }.take(24)
    fun modelVisible(): List<AgentPluginRule> = discover().filter { it.activation != AgentPluginRuleActivation.ALWAYS }.take(64)

    private fun parse(plugin: AgentPluginContributionRoot, file: File): AgentPluginRule? = runCatching {
        val raw = file.readText().replace("\r\n", "\n")
        val split = splitFrontmatter(raw)
        val fm = split.first
        val body = split.second.trim().take(12_000)
        require(body.isNotBlank()) { "Empty Agent rule" }
        val declared = scalar(fm, "name") ?: file.nameWithoutExtension
        val local = declared.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(64)
        require(local.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid Agent rule name" }
        val description = (scalar(fm, "description") ?: local).trim().take(500)
        val trigger = (scalar(fm, "trigger") ?: scalar(fm, "activation") ?: "model").trim().lowercase()
        val activation = when (trigger) {
            "always", "always_on", "always-on" -> AgentPluginRuleActivation.ALWAYS
            "manual" -> AgentPluginRuleActivation.MANUAL
            "glob", "globs" -> AgentPluginRuleActivation.GLOB
            else -> AgentPluginRuleActivation.MODEL
        }
        val globs = listValue(fm, "globs").take(32)
        AgentPluginRule(
            name = "${plugin.id}:$local",
            description = description,
            body = body,
            activation = activation,
            globs = globs,
            sourceLabel = "plugin:${plugin.id}@${plugin.contentSha256.take(12)}",
        )
    }.getOrNull()

    private fun splitFrontmatter(raw: String): Pair<String, String> {
        if (!raw.startsWith("---\n")) return "" to raw
        val end = raw.indexOf("\n---\n", 4)
        if (end < 0) return "" to raw
        return raw.substring(4, end) to raw.substring(end + 5)
    }

    private fun scalar(fm: String, key: String): String? = Regex("(?im)^\\s*${Regex.escape(key)}\\s*:\\s*([^#\\r\\n]+)")
        .find(fm)?.groupValues?.get(1)?.trim()?.trim('"', '\'')

    private fun listValue(fm: String, key: String): List<String> {
        val inline = scalar(fm, key)?.takeIf { it.startsWith("[") && it.endsWith("]") }
        if (inline != null) return inline.substring(1, inline.length - 1).split(',').map { it.trim().trim('"', '\'') }.filter(String::isNotBlank)
        val lines = fm.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("$key:") }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { it.trimStart().startsWith("-") }.map { it.trim().removePrefix("-").trim().trim('"', '\'') }.filter(String::isNotBlank)
    }
}
