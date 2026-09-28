package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

@Serializable
data class HookHandlerConfig(
    val command: String = "",
    val matcher: String = "*",
    val timeoutMs: Long = HookLifecycleProtocol.DEFAULT_TIMEOUT_MS,
    val sourceLabel: String = "workspace",
)

// Legacy beforetool/aftertool remain accepted while typed lifecycle events become authoritative.
@Serializable
data class HooksConfig(
    val enabled: Boolean = false,
    val before_tool: List<String> = emptyList(),
    val after_tool: List<String> = emptyList(),
    val events: Map<String, List<HookHandlerConfig>> = emptyMap(),
)

data class HookLifecycleContext(
    val sessionId: String = "",
    val mode: String = "",
    val trigger: String = "",
    val prompt: String = "",
    val toolName: String = "",
    val toolInput: String = "",
    val toolOutput: String = "",
    val error: String = "",
)

data class HookDispatchResult(
    val blocked: Boolean = false,
    val reason: String = "",
    val additionalContext: String = "",
    val log: String = "",
)

// Project source never gains authority merely by defining a hook: every command still passes containment and permission/approval before execution.



class HookManager(
    private val workDir: File,
    private val terminal: ITerminalSession,
    private val perms: PermissionEngine,
    private val approvals: ApprovalManager,
    private val provenanceProvider: () -> AgentRequestProvenance = { AgentRequestProvenance.primary() },
    private val permissionScopeProvider: () -> String = { "build" },
    private val plugins: AgentPluginSource = AgentPluginSource.EMPTY,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun dispatch(event: HookLifecycleEvent, context: HookLifecycleContext = HookLifecycleContext()): HookDispatchResult {
        val cfg = withContext(Dispatchers.IO) { load() }
        val target = matcherTarget(event, context)
        val handlers = (handlers(cfg, event) + pluginHandlers(event))
            .filter { HookLifecycleProtocol.matches(it.matcher, target) }.take(MAX_HANDLERS_PER_EVENT)
        if (!cfg.enabled && handlers.none { it.sourceLabel != "workspace" }) return HookDispatchResult()
        if (handlers.isEmpty()) return HookDispatchResult()

        val logs = StringBuilder()
        val contexts = mutableListOf<String>()
        for (handler in handlers) {
            val rawCommand = handler.command.trim().take(MAX_COMMAND_CHARS)
            if (rawCommand.isBlank()) continue
            val eventFile = writeEventFile(event, context)
            try {
                val command = rawCommand
                    .replace("{tool}", shellLiteral(context.toolName))
                    .replace("{event_file}", shellLiteral(eventFile.absolutePath))
                if (!containmentAllowed(command, event, handler.sourceLabel)) {
                    logs.appendLine("[hook ${event.configKey} skipped: containment denied]")
                    continue
                }
                if (!hookPermissionAllowed(command, event, handler.sourceLabel)) {
                    logs.appendLine("[hook ${event.configKey} skipped: permission denied]")
                    continue
                }
                val timeout = HookLifecycleProtocol.timeoutMs(handler.timeoutMs)
                val result = runSuspendCatching { terminal.execOnce(command, timeoutMs = timeout) }
                    .getOrElse { ExecResult(-1, it.message ?: "error", false) }
                val output = result.output.take(MAX_OUTPUT_CHARS)
                logs.appendLine("[hook ${event.configKey} exit=${result.exitCode}${if (result.timedOut) " timeout" else ""}] ${output.take(1_000)}")
                if (result.timedOut) continue
                val decision = if (result.exitCode == 2) HookLifecycleProtocol.decision(event, "block", output, null)
                    else parseDecision(event, output)
                if (decision.additionalContext.isNotBlank()) contexts += decision.additionalContext
                if (decision.blocked) {
                    return HookDispatchResult(
                        blocked = true,
                        reason = decision.reason.ifBlank { "Blocked by ${event.configKey} hook" },
                        additionalContext = contexts.joinToString("\n").take(HookLifecycleProtocol.MAX_CONTEXT_CHARS),
                        log = logs.toString().take(MAX_LOG_CHARS),
                    )
                }
            } finally {
                runCatching { eventFile.delete() }
                runCatching { eventFile.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete() }
            }
        }
        return HookDispatchResult(
            additionalContext = contexts.joinToString("\n").take(HookLifecycleProtocol.MAX_CONTEXT_CHARS),
            log = logs.toString().take(MAX_LOG_CHARS),
        )
    }

     
    fun hasToolLifecycleHandlers(toolName: String): Boolean {
        val cfg = load()
        return listOf(
            HookLifecycleEvent.PRE_TOOL_USE,
            HookLifecycleEvent.POST_TOOL_USE,
            HookLifecycleEvent.POST_TOOL_USE_FAILURE,
        ).any { event ->
            val target = toolName
            (handlers(cfg, event) + pluginHandlers(event)).take(MAX_HANDLERS_PER_EVENT).any { handler ->
                HookLifecycleProtocol.matches(handler.matcher, target)
            }
        }
    }

    private fun handlers(cfg: HooksConfig, event: HookLifecycleEvent): List<HookHandlerConfig> {
        val typed = cfg.events.entries.firstOrNull { it.key.equals(event.configKey, ignoreCase = true) }?.value.orEmpty()
        val legacy = when (event) {
            HookLifecycleEvent.PRE_TOOL_USE -> cfg.before_tool.map { HookHandlerConfig(it, "*", sourceLabel = "workspace") }
            HookLifecycleEvent.POST_TOOL_USE -> cfg.after_tool.map { HookHandlerConfig(it, "*", sourceLabel = "workspace") }
            else -> emptyList()
        }
        return legacy + typed
    }

    private fun pluginHandlers(event: HookLifecycleEvent): List<HookHandlerConfig> =
        AgentPluginContributions.hookFiles(plugins, workDir).flatMap { (plugin, file) ->
            runCatching {
                val root = json.parseToJsonElement(file.readText()) as? JsonObject ?: return@runCatching emptyList()
                parsePluginHookObject(root, event, plugin)
            }.getOrDefault(emptyList())
        }.take(MAX_HANDLERS_PER_EVENT)

    private fun parsePluginHookObject(
        root: JsonObject,
        event: HookLifecycleEvent,
        plugin: AgentPluginContributionRoot,
    ): List<HookHandlerConfig> {
        val eventKey = event.configKey
        val candidates = mutableListOf<kotlinx.serialization.json.JsonElement>()
        (root["events"] as? JsonObject)?.entries?.firstOrNull { it.key.equals(eventKey, true) }?.value?.let(candidates::add)
        (root["hooks"] as? JsonObject)?.entries?.firstOrNull { it.key.equals(eventKey, true) }?.value?.let(candidates::add)
        root.entries.firstOrNull { it.key.equals(eventKey, true) }?.value?.let(candidates::add)
        
        root.values.filterIsInstance<JsonObject>().forEach { group ->
            group.entries.firstOrNull { it.key.equals(eventKey, true) }?.value?.let(candidates::add)
        }
        return candidates.flatMap outer@{ element ->
            val array = element as? kotlinx.serialization.json.JsonArray ?: return@outer emptyList()
            array.flatMap inner@{ entry ->
                val obj = entry as? JsonObject ?: return@inner emptyList()
                val matcher = (obj["matcher"] as? JsonPrimitive)?.contentOrNull ?: "*"
                val nested = obj["hooks"] as? kotlinx.serialization.json.JsonArray
                if (nested != null) nested.mapNotNull { hook -> pluginHookHandler(hook as? JsonObject, matcher, plugin) }
                else listOfNotNull(pluginHookHandler(obj, matcher, plugin))
            }
        }
    }

    private fun pluginHookHandler(
        obj: JsonObject?,
        matcher: String,
        plugin: AgentPluginContributionRoot,
    ): HookHandlerConfig? {
        obj ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "command"
        if (type != "command") return null
        val raw = (obj["command"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (raw.isBlank()) return null
        val command = AgentPluginContributions.expandPluginRoot(raw, plugin).take(MAX_COMMAND_CHARS)
        val timeoutMs = (obj["timeoutMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: (obj["timeout"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.times(1_000L)
            ?: HookLifecycleProtocol.DEFAULT_TIMEOUT_MS
        return HookHandlerConfig(
            command = command,
            matcher = matcher.take(300),
            timeoutMs = HookLifecycleProtocol.timeoutMs(timeoutMs),
            sourceLabel = "plugin:${plugin.id}@${plugin.contentSha256.take(12)}",
        )
    }

    private fun matcherTarget(event: HookLifecycleEvent, context: HookLifecycleContext): String = when (event) {
        HookLifecycleEvent.PRE_TOOL_USE, HookLifecycleEvent.POST_TOOL_USE, HookLifecycleEvent.POST_TOOL_USE_FAILURE -> context.toolName
        HookLifecycleEvent.PRE_COMPACT, HookLifecycleEvent.POST_COMPACT, HookLifecycleEvent.SESSION_START -> context.trigger
        else -> ""
    }

    private suspend fun containmentAllowed(command: String, event: HookLifecycleEvent, sourceLabel: String): Boolean {
        val containment = ShellContainmentPreflight.analyze(command, workDir, workDir)
        for (boundary in containment.externalDirectories) {
            if (!approve("external_directory", boundary, "Hook [$sourceLabel] ${event.configKey} external path: $boundary")) return false
        }
        if (containment.requiresShellEscape && !approve(
                "shell_escape",
                ShellContainmentPreflight.shellEscapeResource(command),
                "Hook [$sourceLabel] ${event.configKey} contains dynamic/opaque shell syntax",
            )) return false
        return true
    }

    private suspend fun hookPermissionAllowed(command: String, event: HookLifecycleEvent, sourceLabel: String): Boolean {
        val permissionScope = permissionScopeProvider()
        val resource = "$sourceLabel:${event.configKey}:$command"
        return when (perms.decide("hook", resource, permissionScope)) {
            PermEffect.ALLOW -> true
            PermEffect.DENY -> false
            PermEffect.ASK -> {
                val approved = approvals.requestApproval(
                    kind = "run_command",
                    summary = "Hook [$sourceLabel] (${event.configKey}): ${command.take(100)}",
                    detail = command,
                    action = "hook",
                    resource = resource,
                    permissionScope = permissionScope,
                    provenance = provenanceProvider(),
                ) is ApprovalDecision.Approved
                approved && ApprovalPolicyGuard.remainsPermitted(perms, "hook", resource, permissionScope)
            }
        }
    }

    private suspend fun approve(action: String, resource: String, summary: String): Boolean {
        val permissionScope = permissionScopeProvider()
        return when (perms.decide(action, resource, permissionScope)) {
            PermEffect.ALLOW -> true
            PermEffect.DENY -> false
            PermEffect.ASK -> {
                val approved = approvals.requestApproval(
                    kind = "run_command",
                    summary = summary.take(160),
                    detail = resource.take(2_000),
                    action = action,
                    resource = resource,
                    permissionScope = permissionScope,
                    provenance = provenanceProvider(),
                ) is ApprovalDecision.Approved
                approved && ApprovalPolicyGuard.remainsPermitted(perms, action, resource, permissionScope)
            }
        }
    }

    private fun writeEventFile(event: HookLifecycleEvent, context: HookLifecycleContext): File {
        val dir = PathSecurity.resolveWithin(workDir, ".droide/.runtime-hooks").apply { mkdirs() }
        val provenance = provenanceProvider()
        val file = File(dir, "event-${UUID.randomUUID()}.json")
        val payload = buildJsonObject {
            put("schemaVersion", HookLifecycleProtocol.SCHEMA_VERSION)
            put("event", event.configKey)
            put("timestampMs", System.currentTimeMillis())
            put("sessionId", context.sessionId.ifBlank { provenance.sessionId })
            put("mode", context.mode)
            put("trigger", context.trigger)
            put("prompt", context.prompt.take(MAX_EVENT_FIELD_CHARS))
            put("toolName", context.toolName)
            put("toolInput", context.toolInput.take(MAX_EVENT_FIELD_CHARS))
            put("toolOutput", context.toolOutput.take(MAX_EVENT_FIELD_CHARS))
            put("error", context.error.take(MAX_EVENT_FIELD_CHARS))
            put("agentType", provenance.agentType)
            put("requesterKind", provenance.kind.name.lowercase())
            put("taskId", provenance.taskId)
            put("parentSessionId", provenance.parentSessionId)
            put("depth", provenance.depth)
        }
        file.writeText(payload.toString())
        return file
    }

    private fun parseDecision(event: HookLifecycleEvent, output: String): HookLifecycleDecision {
        val trimmed = output.trim()
        val candidate = trimmed.lineSequence().filter { it.isNotBlank() }.lastOrNull().orEmpty()
        val objectValue = runCatching { json.parseToJsonElement(candidate) as? JsonObject }.getOrNull()
            ?: runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            ?: return HookLifecycleDecision()
        fun text(name: String): String? = (objectValue[name] as? JsonPrimitive)?.contentOrNull
        return HookLifecycleProtocol.decision(event, text("decision"), text("reason"), text("additionalContext"))
    }

    private fun load(): HooksConfig {
        val file = PathSecurity.resolveWithin(workDir, ".droide/hooks.json")
        if (!file.isFile || file.length() > MAX_CONFIG_BYTES) return HooksConfig()
        return runCatching { json.decodeFromString<HooksConfig>(file.readText()) }.getOrDefault(HooksConfig())
    }

    private fun shellLiteral(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val MAX_CONFIG_BYTES = 64_000L
        private const val MAX_HANDLERS_PER_EVENT = 8
        private const val MAX_COMMAND_CHARS = 4_000
        private const val MAX_OUTPUT_CHARS = 12_000
        private const val MAX_LOG_CHARS = 2_000
        private const val MAX_EVENT_FIELD_CHARS = 8_000
    }
}
