package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

// Compatibility bundles use MCP for tool contributions.
class AgentPluginToolManager(
    private val workDir: File,
    private val plugins: AgentPluginSource = AgentPluginSource.EMPTY,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun discover(): List<AgentPluginToolDef> = AgentPluginContributions.toolFiles(plugins, workDir).flatMap { (plugin, file) ->
        runCatching {
            val root = json.parseToJsonElement(file.readText()) as? JsonObject ?: return@runCatching emptyList()
            val array = root["tools"] as? JsonArray ?: return@runCatching emptyList()
            array.take(MAX_TOOLS_PER_PLUGIN).mapNotNull { element -> parseTool(plugin, element as? JsonObject ?: return@mapNotNull null) }
        }.getOrDefault(emptyList())
    }.distinctBy { it.functionName }.take(MAX_TOOLS_TOTAL)

    suspend fun execute(def: AgentPluginToolDef, input: JsonObject, terminal: ITerminalSession): String {
        require(def.fingerprint == discover().firstOrNull { it.functionName == def.functionName }?.fingerprint) {
            "Agent plugin tool changed after approval: ${def.functionName}"
        }
        AgentPluginJsonSchema.requireValid(def.inputSchema, input)
        val payload = input.toString()
        require(payload.length <= MAX_INPUT_CHARS) { "Agent plugin tool input is too large" }
        var replaced = false
        val argv = def.argv.map { arg ->
            if (arg.contains(INPUT_PLACEHOLDER)) {
                replaced = true
                arg.replace(INPUT_PLACEHOLDER, payload)
            } else arg
        }.let { if (replaced) it else it + payload }
        val result = terminal.execArgv(argv, timeoutMs = def.timeoutMs)
        return if (result.timedOut) "(timeout)" else "exit=${result.exitCode}\n${result.output.take(MAX_OUTPUT_CHARS)}"
    }


    fun permissionResource(def: AgentPluginToolDef, input: JsonObject): String {
        AgentPluginJsonSchema.requireValid(def.inputSchema, input)
        val invocation = AgentPluginJsonSchema.invocationHash(input).take(24)
        return "agent-plugin:${def.pluginId}:tool:${def.name}@sha:${def.fingerprint.take(24)}:input:$invocation"
    }

    private fun parseTool(plugin: AgentPluginContributionRoot, obj: JsonObject): AgentPluginToolDef? {
        val rawName = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return null
        val local = rawName.replace(Regex("[^a-z0-9_-]+"), "_").trim('_').take(40)
        if (!local.matches(Regex("[a-z0-9][a-z0-9_-]{0,39}"))) return null
        val pluginKey = plugin.id.replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val function = ("plugin_${pluginKey.take(20)}_${local.take(26)}_${plugin.contentSha256.take(8)}").take(64)
        val description = ((obj["description"] as? JsonPrimitive)?.contentOrNull ?: "${plugin.displayName}: $local").take(600)
        val command = obj["command"] as? JsonArray ?: return null
        if (command.isEmpty() || command.size > MAX_ARG_COUNT) return null
        val argv = command.map { item ->
            val raw = (item as? JsonPrimitive)?.contentOrNull ?: return null
            val expanded = AgentPluginContributions.expandPluginRoot(raw, plugin)
            if (expanded.length > MAX_ARG_CHARS || expanded.any { it == '\u0000' || it == '\n' || it == '\r' }) return null
            expanded
        }
        val schema = (obj["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", JsonPrimitive("object")) }
        if (schema.toString().length > MAX_SCHEMA_CHARS || !AgentPluginJsonSchema.schemaSupported(schema)) return null
        val timeout = (obj["timeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.coerceIn(500L, 30_000L) ?: 10_000L
        val fingerprint = sha256(listOf(plugin.id, plugin.contentSha256, local, description, argv.joinToString("\u0000"), schema.toString(), timeout.toString()))
        return AgentPluginToolDef(plugin.id, local, function, description, argv, schema, timeout, fingerprint)
    }

    private fun sha256(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val INPUT_PLACEHOLDER = "${'$'}{DROIDE_TOOL_INPUT_JSON}"
        private const val MAX_TOOLS_PER_PLUGIN = 24
        private const val MAX_TOOLS_TOTAL = 64
        private const val MAX_ARG_COUNT = 64
        private const val MAX_ARG_CHARS = 8_000
        private const val MAX_SCHEMA_CHARS = 24_000
        private const val MAX_INPUT_CHARS = 32_000
        private const val MAX_OUTPUT_CHARS = 12_000
    }
}
