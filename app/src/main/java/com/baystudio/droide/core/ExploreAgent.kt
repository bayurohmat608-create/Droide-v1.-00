package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive


class ExploreAgent(
    private val files: FileRepository,
    private val git: GitManager,
    private val llm: LlmClient,
    private val perms: PermissionEngine,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val ALLOWED = setOf("list_files", "read_file", "glob", "search_files")

         
        fun definitions(): JsonArray = JsonArray(
            AgentTools.definitions.filter { element ->
                val tool = element as? JsonObject ?: return@filter false
                val function = tool["function"] as? JsonObject ?: return@filter false
                val name = (function["name"] as? JsonPrimitive)?.contentOrNull ?: return@filter false
                name in ALLOWED
            }
        )
    }

     
    private object MuteTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        override fun start() {}
        override fun send(cmd: String) {}
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(-1, "disabled in explore sandbox", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(-1, "disabled in explore sandbox", false)
        override fun interrupt() {}
        override fun clear() {}
        override fun destroy() {}
    }

    suspend fun run(query: String, config: AgentConfig, maxTurns: Int = 5): String {
        val selectedProvider = ProviderRegistry.findById(config.providerId)
            ?: throw IllegalArgumentException("Unknown provider: ${config.providerId}")
        require(!selectedProvider.needsKey || config.apiKey.isNotBlank()) { "API key empty" }
        val apiMessages = mutableListOf<JsonObject>()
        apiMessages += buildJsonObject {
            put("role", "system")
            put("content", "You are a read-only explore subagent in Droide IDE. " +
                "Answer '$query' by reading code via tools. " +
                "Do not guess file contents. End with numbered findings + relevant file paths.")
        }
        apiMessages += buildJsonObject { put("role", "user"); put("content", query.take(2_000)) }

        
        val todosNoop = TodoManager()
        val ctx = AgentTools.Ctx(files, MuteTerminal, git, ApprovalManager(), QuestionManager(),
            EditHistory(files), perms, AgentMode.EXPLORE, llm, config, files.root, todosNoop, null, CliLspManager(MuteTerminal, files))

        val modelId = ProviderModelIdentity.requireCanonical(config.model)
        ProviderRuntimeGuard.requireCertifiedSelection(config.providerId, config.baseUrl, modelId)
        var turns = 0
        var pending: List<JsonObject> = emptyList()
        var lastText = "(explore no results)"
        while (turns++ < maxTurns) {
            coroutineContext.ensureActive()
            val reqJson = buildJsonObject {
                put("model", modelId)
                put("messages", JsonArray(apiMessages + pending))
                put("tools", definitions())
                put("tool_choice", "auto")
            }
            pending = emptyList()
            val resp = llm.chatCompletions(config.providerId, config.baseUrl, config.apiKey, modelId, reqJson.toString())
            val root = runCatching { json.parseToJsonElement(resp) as? JsonObject }.getOrNull()
                ?: return "Explore failed: model returned malformed JSON"
            val choices = root["choices"] as? JsonArray
                ?: return "Explore failed: model response has no choices array"
            val choice = choices.firstOrNull() as? JsonObject
                ?: return "Explore failed: model response has no assistant choice"
            val msg = choice["message"] as? JsonObject
                ?: return "Explore failed: assistant choice has no message object"
            val content = (msg["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val toolCalls = msg["tool_calls"] as? JsonArray ?: JsonArray(emptyList())

            if (toolCalls.isEmpty()) {
                if (content.isNotBlank()) lastText = content
                break
            }
            if (content.isNotBlank()) lastText = content
            apiMessages += buildJsonObject {
                put("role", "assistant"); put("content", content); put("tool_calls", toolCalls)
            }
            val results = mutableListOf<JsonObject>()
            for (tc in toolCalls) {
                coroutineContext.ensureActive()
                val tco = tc as? JsonObject ?: continue
                val id = (tco["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val fn = tco["function"] as? JsonObject
                val fname = (fn?.get("name") as? JsonPrimitive)?.contentOrNull
                val rawArgs = (fn?.get("arguments") as? JsonPrimitive)?.contentOrNull ?: "{}"
                val fargs = runCatching { json.parseToJsonElement(rawArgs) as? JsonObject }.getOrNull()
                // Sandbox gate: malformed calls are never repaired into permissive default arguments.
                val result = when {
                    fname == null -> "SKIPPED (explore sandbox): malformed tool call"
                    fargs == null -> "SKIPPED (explore sandbox): tool arguments must be a JSON object"
                    fname !in ALLOWED -> "SKIPPED (explore sandbox): tool outside read-only allowlist"
                    else -> {
                        val action = AgentTools.actionOf(fname)
                        val resource = (fargs.entries.firstOrNull()?.value as? JsonPrimitive)?.contentOrNull ?: ""
                        if (AgentModePolicy.check(AgentMode.EXPLORE, fname) != null ||
                            perms.decide(action, resource) != PermEffect.ALLOW
                        ) {
                            "SKIPPED (explore sandbox): requires approval — subagent must not prompt"
                        } else AgentTools.execute(fname, fargs, ctx)
                    }
                }
                results += buildJsonObject {
                    put("role", "tool"); put("tool_call_id", id); put("content", result.take(8_000))
                }
            }
            apiMessages += results
        }
        return "Explore results ($query):\n$lastText".take(8_000)
    }
}
