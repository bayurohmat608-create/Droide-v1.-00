package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest

 
enum class AgentProfileSource { BUILTIN, PROJECT, PLUGIN }

data class AgentProfile(
    val name: String,
    val description: String,
    val mode: AgentMode,
    val systemInstructions: String,
    val modelPreference: String? = null,
     
    val tools: Set<String>? = null,
    val mainAgent: Boolean = false,
    val subagent: Boolean = true,
    val source: AgentProfileSource = AgentProfileSource.PROJECT,
    val revision: String,
) {
    fun applyModel(parent: AgentConfig): AgentConfig =
        modelPreference?.takeUnless { it.equals("inherit", ignoreCase = true) }?.let { parent.copy(model = it) } ?: parent

    fun acceptsStoredRevision(storedRevision: String): Boolean =
        if (storedRevision.isBlank()) source == AgentProfileSource.BUILTIN else storedRevision == revision
}

data class AgentProfileSummary(
    val name: String,
    val description: String,
    val mode: AgentMode,
    val modelPreference: String? = null,
    val mainAgent: Boolean = false,
    val subagent: Boolean = true,
)

// PermissionEngine remains authoritative and profile tool sets are restrictive-only.





class AgentProfileManager(private val workDir: File, private val plugins: AgentPluginSource = AgentPluginSource.EMPTY) {
    fun discover(): List<AgentProfile> {
        val builtins = BUILTINS
        val builtinNames = builtins.mapTo(mutableSetOf()) { it.name }
        val custom = discoverProject().filterNot { it.name in builtinNames }
        val pluginProfiles = discoverPlugins().filterNot { it.name in builtinNames || custom.any { local -> local.name == it.name } }
        return (builtins + custom + pluginProfiles).take(MAX_TOTAL_PROFILES)
    }

    fun get(name: String): AgentProfile? {
        val normalized = normalizeNameOrNull(name) ?: return null
        return discover().firstOrNull { it.name == normalized }
    }

    fun subagents(): List<AgentProfile> = discover().filter { it.subagent }
    fun primaryAgents(): List<AgentProfile> = discover().filter { it.mainAgent }
    fun subagentSummaries(): List<AgentProfileSummary> = subagents().map(AgentProfile::summary)

    private fun discoverProject(): List<AgentProfile> {
        val base = runCatching { PathSecurity.resolveWithin(workDir, ".droide/agents") }.getOrElse { return emptyList() }
        if (!base.isDirectory || PathSecurity.isSymbolicLink(base)) return emptyList()
        return base.listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile && file.extension.equals("md", ignoreCase = true) && file.length() in 1..MAX_PROFILE_BYTES &&
                    !PathSecurity.isSymbolicLink(file) && PathSecurity.contains(workDir, file)
            }
            ?.sortedBy { it.name.lowercase() }
            ?.take(MAX_PROJECT_PROFILES)
            ?.mapNotNull(::parseProjectProfile)
            ?.distinctBy { it.name }
            ?.toList()
            .orEmpty()
    }


    private fun discoverPlugins(): List<AgentProfile> = AgentPluginContributions.agentFiles(plugins, workDir)
        .mapNotNull { (plugin, file) -> parsePluginProfile(plugin, file) }
        .distinctBy { it.name }
        .take(MAX_PLUGIN_PROFILES)

    private fun parsePluginProfile(plugin: AgentPluginContributionRoot, file: File): AgentProfile? = runCatching {
        val raw = file.readText(Charsets.UTF_8).replace("\r\n", "\n")
        require(!raw.contains('\u0000')) { "Plugin Agent profile contains NUL" }
        val end = if (raw.startsWith("---\n")) raw.indexOf("\n---\n", 4) else -1
        val header = if (end >= 0) raw.substring(4, end) else ""
        val body = if (end >= 0) raw.substring(end + 5).trim() else raw.trim()
        require(body.length in 1..MAX_INSTRUCTION_CHARS) { "Plugin Agent instructions are required" }
        fun scalar(key: String): String? = Regex("(?im)^\\s*${Regex.escape(key)}\\s*:\\s*([^#\\r\\n]+)")
            .find(header)?.groupValues?.get(1)?.trim()?.let(::unquote)
        val localRaw = scalar("name") ?: file.nameWithoutExtension
        val local = localRaw.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(40)
        require(local.matches(Regex("[a-z0-9][a-z0-9._-]{0,39}"))) { "Invalid plugin Agent profile name" }
        val rawName = "${plugin.id}.$local"
        val name = normalizeName(if (rawName.length <= 64) rawName else "${plugin.id.take(32)}.${local.take(22)}.${plugin.contentSha256.take(7)}")
        val description = (scalar("description") ?: "${plugin.displayName}: $local").take(MAX_DESCRIPTION_CHARS)
        val mode = when (scalar("mode")?.lowercase()) {
            "plan" -> AgentMode.PLAN; "review" -> AgentMode.REVIEW; "explore" -> AgentMode.EXPLORE; else -> AgentMode.BUILD
        }
        val model = scalar("model")?.takeIf { it.isNotBlank() && !it.equals("inherit", true) }?.take(MAX_MODEL_CHARS)
        val toolsRaw = scalar("tools")
        val tools = toolsRaw?.removePrefix("[")?.removeSuffix("]")?.split(',')
            ?.map { it.trim().trim('"', '\'') }?.filter { it.isNotBlank() }?.take(MAX_TOOLS)
            ?.mapNotNull { runCatching { normalizePluginToolPattern(it) }.getOrNull() }?.toSet()
        AgentProfile(
            name = name, description = description, mode = mode, systemInstructions = body, modelPreference = model, tools = tools,
            mainAgent = false, subagent = true, source = AgentProfileSource.PLUGIN,
            revision = revision(name, description, mode, body, model, tools, false, true),
        )
    }.getOrNull()

    private fun parseProjectProfile(file: File): AgentProfile? = runCatching {
        val raw = file.readText(Charsets.UTF_8)
        require(!raw.contains('\u0000')) { "Agent profile contains NUL" }
        val parsed = parseFrontmatter(raw)
        val declared = parsed.scalar("name") ?: file.nameWithoutExtension
        val name = normalizeName(declared)
        require(normalizeName(file.nameWithoutExtension) == name) { "Agent profile filename must match name" }
        require(name !in BUILTIN_NAMES) { "Built-in Agent profiles cannot be shadowed" }
        val description = parsed.scalar("description")?.trim().orEmpty()
        require(description.length in 1..MAX_DESCRIPTION_CHARS) { "Agent profile description is required" }
        val mode = when (parsed.scalar("mode")?.trim()?.lowercase() ?: "build") {
            "build" -> AgentMode.BUILD
            "plan" -> AgentMode.PLAN
            "review" -> AgentMode.REVIEW
            "explore" -> AgentMode.EXPLORE
            else -> error("Unsupported Agent profile mode")
        }
        val model = parsed.scalar("model")?.trim()?.takeIf { it.isNotBlank() && !it.equals("inherit", true) }
        model?.let {
            require(it.length <= MAX_MODEL_CHARS && '\n' !in it && '\r' !in it && '\u0000' !in it) { "Invalid Agent profile model" }
        }
        val tools = parsed.list("tools")?.let { values ->
            require(values.size <= MAX_TOOLS) { "Too many Agent profile tools" }
            values.mapTo(linkedSetOf<String>()) { normalizeToolPattern(it) }
        }
        val mainAgent = parsed.boolean("mainagent") ?: false
        val subagent = parsed.boolean("subagent") ?: true
        require(mainAgent || subagent) { "Agent profile must be primary, subagent, or both" }
        val instructions = parsed.body.trim()
        require(instructions.length in 1..MAX_INSTRUCTION_CHARS) { "Agent profile instructions are required" }
        AgentProfile(
            name = name,
            description = description,
            mode = mode,
            systemInstructions = instructions,
            modelPreference = model,
            tools = tools,
            mainAgent = mainAgent,
            subagent = subagent,
            source = AgentProfileSource.PROJECT,
            revision = revision(name, description, mode, instructions, model, tools, mainAgent, subagent),
        )
    }.getOrNull()

    private data class Frontmatter(val values: Map<String, Any>, val body: String) {
        fun scalar(key: String): String? = values[key.lowercase()] as? String
        @Suppress("UNCHECKED_CAST")
        fun list(key: String): List<String>? = values[key.lowercase()] as? List<String>
        fun boolean(key: String): Boolean? = scalar(key)?.let {
            when (it.trim().lowercase()) { "true" -> true; "false" -> false; else -> null }
        }
    }

    private fun parseFrontmatter(raw: String): Frontmatter {
        val normalized = raw.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) error("Agent profile requires YAML frontmatter")
        val end = normalized.indexOf("\n---\n", startIndex = 4)
        if (end < 0) error("Agent profile frontmatter is not closed")
        val header = normalized.substring(4, end)
        val body = normalized.substring(end + 5)
        val values = linkedMapOf<String, Any>()
        var listKey: String? = null
        header.lineSequence().forEach { sourceLine ->
            val line = sourceLine.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
            if (line.trimStart().startsWith("- ")) {
                val key = listKey ?: error("List item without a key")
                val item = unquote(line.trimStart().removePrefix("- ").trim())
                @Suppress("UNCHECKED_CAST")
                (values[key] as MutableList<String>).add(item)
                return@forEach
            }
            val separator = line.indexOf(':')
            require(separator > 0) { "Invalid Agent profile frontmatter line" }
            val key = line.substring(0, separator).trim().lowercase()
            require(key in ALLOWED_KEYS) { "Unsupported Agent profile key: $key" }
            val rawValue = line.substring(separator + 1).trim()
            if (rawValue.isEmpty()) {
                require(key == "tools") { "Only tools may use a block list" }
                values[key] = mutableListOf<String>()
                listKey = key
            } else {
                listKey = null
                values[key] = if (key == "tools" && rawValue.startsWith("[") && rawValue.endsWith("]")) {
                    rawValue.substring(1, rawValue.length - 1).split(',').map { unquote(it.trim()) }.filter { it.isNotBlank() }.toMutableList()
                } else unquote(rawValue)
            }
        }
        return Frontmatter(values, body)
    }

    companion object {
        private const val MAX_PROFILE_BYTES = 64L * 1024L
        private const val MAX_PROJECT_PROFILES = 32
        private const val MAX_TOTAL_PROFILES = 96
        private const val MAX_PLUGIN_PROFILES = 56
        private const val MAX_DESCRIPTION_CHARS = 500
        private const val MAX_INSTRUCTION_CHARS = 32_000
        private const val MAX_MODEL_CHARS = 200
        private const val MAX_TOOLS = 96
        private val ALLOWED_KEYS = setOf("name", "description", "mode", "model", "tools", "mainagent", "subagent")
        private val NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val TOOL = Regex("[A-Za-z0-9_.*?-]{1,120}")

        private fun builtin(
            name: String,
            description: String,
            mode: AgentMode,
            instructions: String,
            tools: Set<String>? = null,
        ): AgentProfile = AgentProfile(
            name = name,
            description = description,
            mode = mode,
            systemInstructions = instructions.trimIndent(),
            tools = tools,
            mainAgent = false,
            subagent = true,
            source = AgentProfileSource.BUILTIN,
            revision = revision(name, description, mode, instructions.trimIndent(), null, tools, false, true),
        )

        val BUILTINS: List<AgentProfile> = listOf(
            builtin(
                "general",
                "General autonomous coding worker for implementation, verification, and multi-step engineering tasks.",
                AgentMode.BUILD,
                """
                    You are a Droide general subagent. You are a child worker, not the user-facing parent agent.
                    Work only on the delegated task. Start with fresh context, inspect before changing, verify your own work,
                    and return one concise final report containing result, evidence, changed files, and blockers.
                    Do not duplicate unrelated parent work. Do not ask the user unless a permission/question tool is required.
                """,
            ),
            builtin(
                "explore",
                "Read-only codebase research worker for broad searches, architecture tracing, and evidence gathering.",
                AgentMode.EXPLORE,
                """
                    You are a Droide explore subagent. You are strictly read-only and start with fresh context.
                    Investigate the delegated question using available read/search/LSP/web tools, never invent file contents,
                    and return concise numbered findings with relevant paths and concrete evidence.
                """,
            ),
            builtin(
                "reviewer",
                "Read-only reviewer for correctness, regressions, security, and missing verification.",
                AgentMode.REVIEW,
                """
                    You are a Droide reviewer subagent. Review only the delegated scope. Do not modify files.
                    Prioritize concrete defects, regressions, security issues, and missing tests. Return findings in severity
                    order with file references and evidence; say explicitly when no actionable finding is supported.
                """,
            ),
        )
        private val BUILTIN_NAMES = BUILTINS.mapTo(mutableSetOf()) { it.name }

        fun builtinSubagentSummaries(): List<AgentProfileSummary> = BUILTINS.filter { it.subagent }.map(AgentProfile::summary)
        fun normalizeName(value: String): String = value.trim().lowercase().also { require(NAME.matches(it)) { "Invalid Agent profile name" } }
        fun normalizeNameOrNull(value: String): String? = runCatching { normalizeName(value) }.getOrNull()
        fun normalizeToolPattern(value: String): String {
            val trimmed = value.trim()
            require(TOOL.matches(trimmed)) { "Invalid Agent profile tool pattern" }
            return AgentModePolicy.canonicalTool(trimmed)
        }
        private fun normalizePluginToolPattern(value: String): String {
            val trimmed = value.trim()
            require(TOOL.matches(trimmed)) { "Invalid plugin Agent profile tool pattern" }
            return when (trimmed.lowercase()) {
                "read" -> "read_file"; "grep" -> "search_files"; "glob" -> "glob"; "bash" -> "run_command"
                "edit" -> "edit_file"; "write" -> "write_file"; "task" -> "task"; "webfetch" -> "webfetch"
                "websearch" -> "websearch"; else -> AgentModePolicy.canonicalTool(trimmed)
            }
        }

        private fun unquote(value: String): String {
            val trimmed = value.trim()
            if (trimmed.length >= 2 && ((trimmed.first() == '"' && trimmed.last() == '"') || (trimmed.first() == '\'' && trimmed.last() == '\''))) {
                return trimmed.substring(1, trimmed.length - 1)
            }
            return trimmed
        }

        private fun revision(
            name: String,
            description: String,
            mode: AgentMode,
            instructions: String,
            model: String?,
            tools: Set<String>?,
            mainAgent: Boolean,
            subagent: Boolean,
        ): String {
            val canonical = listOf(
                "name=$name", "description=$description", "mode=${mode.name}", "model=${model.orEmpty()}",
                "tools=${tools?.toList()?.sorted()?.joinToString(",") ?: "<inherit>"}",
                "mainAgent=$mainAgent", "subagent=$subagent", "instructions=$instructions",
            ).joinToString("\n")
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

private fun AgentProfile.summary() = AgentProfileSummary(name, description, mode, modelPreference, mainAgent, subagent)
