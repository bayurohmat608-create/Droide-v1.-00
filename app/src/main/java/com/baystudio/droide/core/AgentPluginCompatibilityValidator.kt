package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

 
object AgentPluginCompatibilityValidator {
    private val json = Json { ignoreUnknownKeys = true }

    fun validate(root: File, format: AgentPluginFormat) {
        val contributes = listOf(
            File(root, "SKILL.md").isFile, File(root, "skills").isDirectory, File(root, "commands").isDirectory,
            File(root, "agents").isDirectory, File(root, "rules").isDirectory, File(root, ".mcp.json").isFile,
            File(root, "mcp_config.json").isFile, File(root, "mcp.json").isFile, File(root, "hooks.json").isFile,
            File(root, "hooks/hooks.json").isFile, File(root, "tools.json").isFile, File(root, "tools/tools.json").isFile,
        ).any { it }
        require(contributes) { "Agent plugin contains no supported contributions" }
        validateMcp(root)
        validateHooks(root)
        validateTools(root, format)
    }

    private fun validateMcp(root: File) {
        listOf(".mcp.json", "mcp_config.json", "mcp.json").map { File(root, it) }.filter(File::isFile).forEach { file ->
            require(file.length() <= 128L * 1024L) { "Agent plugin MCP config is too large" }
            val obj = json.parseToJsonElement(file.readText()) as? JsonObject ?: error("Agent plugin MCP config must be an object")
            val servers = (obj["servers"] as? JsonObject) ?: (obj["mcpServers"] as? JsonObject)
                ?: obj.takeIf { candidate -> candidate.values.all { it is JsonObject } } ?: error("Agent plugin MCP server map missing")
            require(servers.size <= 32) { "Agent plugin defines too many MCP servers" }
            servers.forEach { (name, value) ->
                require(name.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid Agent plugin MCP server name: $name" }
                val cfg = value as? JsonObject ?: error("Invalid Agent plugin MCP server config: $name")
                require(!cfg.containsKey("url") && !cfg.containsKey("httpUrl") && !cfg.containsKey("transport")) {
                    "Remote MCP is not supported for Agent plugins; server '$name' was not installed"
                }
                val env = cfg["env"] as? JsonObject
                require(env == null || env.isEmpty()) { "MCP env injection is not supported for Agent plugin server '$name'" }
                require((cfg["command"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) { "MCP stdio command missing for '$name'" }
                val args = cfg["args"] as? JsonArray
                require(args == null || args.size <= 64) { "Too many MCP args for '$name'" }
            }
        }
    }

    private fun validateHooks(root: File) {
        listOf("hooks/hooks.json", "hooks.json").map { File(root, it) }.filter(File::isFile).forEach { file ->
            require(file.length() <= 128L * 1024L) { "Agent plugin hooks config is too large" }
            val tree = json.parseToJsonElement(file.readText())
            walkHookObjects(tree) { obj ->
                val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()
                if (type != null) require(type == "command") {
                    "Agent plugin hook type '$type' is not supported; bundle was not installed partially"
                }
            }
        }
    }

    private fun walkHookObjects(element: kotlinx.serialization.json.JsonElement, visit: (JsonObject) -> Unit) {
        when (element) {
            is JsonObject -> {
                if (element.containsKey("command") || element.containsKey("type")) visit(element)
                element.values.forEach { walkHookObjects(it, visit) }
            }
            is JsonArray -> element.forEach { walkHookObjects(it, visit) }
            else -> Unit
        }
    }

    private fun validateTools(root: File, format: AgentPluginFormat) {
        listOf("tools/tools.json", "tools.json").map { File(root, it) }.filter(File::isFile).forEach { file ->
            require(format == AgentPluginFormat.DROIDE) { "Direct tools.json is a Droide Agent-plugin contribution; compatibility bundles should expose tools through MCP" }
            require(file.length() <= 128L * 1024L) { "Agent plugin tools config is too large" }
            val obj = json.parseToJsonElement(file.readText()) as? JsonObject ?: error("Agent plugin tools config must be an object")
            val tools = obj["tools"] as? JsonArray ?: error("Agent plugin tools array missing")
            require(tools.size <= 24) { "Agent plugin defines too many direct tools" }
            tools.forEach { value ->
                val tool = value as? JsonObject ?: error("Invalid Agent plugin tool")
                require((tool["name"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) { "Agent plugin tool name missing" }
                val command = tool["command"] as? JsonArray ?: error("Agent plugin tool command must be argv array")
                require(command.isNotEmpty() && command.size <= 64) { "Invalid Agent plugin tool argv" }
            }
        }
    }
}
