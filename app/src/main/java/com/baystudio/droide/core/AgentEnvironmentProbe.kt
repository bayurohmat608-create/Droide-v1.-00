package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// It proves presence, never workload success.
internal object AgentEnvironmentProbe {
    private const val MAX_ITEMS = 32
    private const val MAX_NAME_CHARS = 80
    private val commandName = Regex("[A-Za-z0-9_+.-]{1,$MAX_NAME_CHARS}")

    suspend fun run(args: JsonObject, c: AgentTools.Ctx): String {
        val requestedTools = strings(args, "agent_tools")
        val requestedCommands = strings(args, "commands")
        val includePath = args["include_path"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        val resource = (requestedTools + requestedCommands).joinToString(",").take(500).ifBlank { "summary" }
        AgentTools.permissionGateForProbe("read", "environment:$resource", c)?.let { return it }

        return buildString {
            append("[DROIDE_ENVIRONMENT_PROBE proof=presence_only workload_executed=false]\n")
            append("workdir=").append(c.workDir.absolutePath).append('\n')
            append("active_agent_tool_count=").append(c.activeToolNames.size).append('\n')
            requestedTools.forEach { name ->
                val advertised = name in c.activeToolNames
                append("agent_tool=").append(name.take(MAX_NAME_CHARS))
                    .append(" advertised=").append(advertised)
                    .append(" callable=").append(advertised)
                    .append('\n')
            }
            requestedCommands.forEach { name ->
                if (!commandName.matches(name)) {
                    append("command=").append(name.take(MAX_NAME_CHARS)).append(" available=false reason=invalid_probe_name\n")
                    return@forEach
                }
                val result = c.terminal.execOnce("command -v $name 2>/dev/null", timeoutMs = 5_000)
                val resolved = result.output.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() }.orEmpty().take(500)
                val available = !result.timedOut && result.exitCode == 0 && resolved.isNotBlank()
                append("command=").append(name)
                    .append(" available=").append(available)
                    .append(" probe_exit=").append(result.exitCode)
                if (resolved.isNotBlank()) append(" path=").append(resolved.replace('\n', ' '))
                append('\n')
            }
            if (includePath) {
                val result = c.terminal.execOnce("printf '%s\\n' \"\$PATH\"", timeoutMs = 5_000)
                append("PATH_probe_exit=").append(result.exitCode).append('\n')
                if (result.exitCode == 0) append("PATH=").append(result.output.trim().take(4_000)).append('\n')
            }
            append("NOTE: presence/callability is not proof that a later command or workload succeeded.")
        }.take(12_000)
    }

    private fun strings(args: JsonObject, key: String): List<String> =
        runCatching { args[key]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) } }
            .getOrDefault(emptyList())
            .distinct()
            .take(MAX_ITEMS)
}
