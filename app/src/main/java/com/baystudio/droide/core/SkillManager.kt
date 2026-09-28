package com.baystudio.droide.core

import java.io.File

// Progressive-disclosure Agent Skills with project + integrity-verified plugin sources.
data class Skill(val name: String, val description: String, val body: String, val sourceLabel: String = "workspace")

class SkillManager(
    private val workDir: File,
    private val plugins: AgentPluginSource = AgentPluginSource.EMPTY,
) {
    fun discover(): List<Skill> {
        val out = mutableListOf<Skill>()
        listOf(".droide/skills", ".agents/skills", ".agent/skills", ".claude/skills", ".opencode/skills").forEach { rel ->
            val base = runCatching { PathSecurity.resolveWithin(workDir, rel) }.getOrNull() ?: return@forEach
            if (!base.isDirectory || PathSecurity.isSymbolicLink(base)) return@forEach
            base.listFiles()?.asSequence()?.filter { it.isDirectory && !PathSecurity.isSymbolicLink(it) }?.take(100)?.forEach skillDir@ { dir ->
                if (!PathSecurity.contains(workDir, dir)) return@skillDir
                val f = File(dir, "SKILL.md")
                if (f.isFile && f.length() <= 200_000 && PathSecurity.contains(workDir, f)) parse(f, null)?.let { out += it }
            }
        }
        AgentPluginContributions.skillFiles(plugins, workDir).forEach { (plugin, file) ->
            parse(file, plugin)?.let(out::add)
        }
        return out.distinctBy { it.name }.take(160)
    }

    fun load(name: String): Skill? = discover().firstOrNull { it.name == name }

    private fun parse(f: File, plugin: AgentPluginContributionRoot?): Skill? = runCatching {
        val raw = f.readText()
        if (!raw.startsWith("---")) return null
        val end = raw.indexOf("\n---", 3)
        if (end == -1) return null
        val fm = raw.substring(3, end)
        val body = raw.substring(end + 4).trim().take(12_000)
        val declared = Regex("(?im)^\\s*name\\s*:\\s*(.+)$").find(fm)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
            ?: if (f.parentFile.name == "commands") f.nameWithoutExtension else f.parentFile.name
        val localName = normalizeSkillName(declared) ?: return null
        val desc = Regex("(?im)^\\s*description\\s*:\\s*(.+)$").find(fm)?.groupValues?.get(1)?.trim()?.trim('"', '\'') ?: localName
        val finalName = if (plugin == null) localName else "${plugin.id}:$localName"
        Skill(finalName, desc.take(1_024), body, plugin?.let { "plugin:${it.id}@${it.contentSha256.take(12)}" } ?: "workspace")
    }.getOrNull()

    private fun normalizeSkillName(raw: String): String? = raw.trim().lowercase()
        .takeIf { it.matches(Regex("^[a-z0-9]+(?:[-_][a-z0-9]+)*$")) }
}
