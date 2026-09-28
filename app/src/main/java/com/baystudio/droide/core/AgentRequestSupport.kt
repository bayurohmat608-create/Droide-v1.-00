package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

 
internal object AgentRequestSupport {
    fun build(
        config: AgentConfig,
        systemText: String,
        history: List<JsonObject>,
        tools: JsonArray?,
    ): JsonObject {
        val modelId = ProviderModelIdentity.requireCanonical(config.model)
        return buildJsonObject {
        put("model", modelId)
        ReasoningSupport.apply(this, config.providerId, modelId, config.reasoningEffort)
        val system = PromptCacheSupport.systemMessage(config, systemText, tools)
        put("messages", JsonArray(listOf(system) + history))
        if (tools != null) {
            put("tools", tools)
            put("tool_choice", "auto")
        }
        }
    }

    suspend fun compact(
        sessions: SessionManager,
        tokens: TokenTracker,
        todos: TodoManager,
        mode: AgentMode,
        pendingInputs: List<AgentPendingInput>,
        messages: List<AgentMessage>,
        apiHistory: MutableList<JsonObject>,
        exactForkHistory: List<JsonObject>?,
        config: AgentConfig,
        sid: String,
        overflowRecovery: Boolean,
        ensureCurrent: () -> Unit,
    ): Boolean {
        ensureCurrent()
        val existing = sessions.get(sid) ?: DroideSession(id = sid, title = "Session")
        val complete = AgentHistory.completeGroups(apiHistory).flatten()
        val forkMessages = if (exactForkHistory != null) SessionForkHistory.annotate(messages, exactForkHistory)
        else messages.map { it.copy(forkHistoryEnd = null) }
        val snapshot = existing.copy(
            messages = forkMessages,
            apiHistory = complete,
            forkHistory = exactForkHistory ?: emptyList(),
            forkHistoryVersion = if (exactForkHistory != null) SessionForkHistory.SCHEMA_VERSION else 0,
            todos = todos.get(sid),
            mode = mode,
            pendingInputs = pendingInputs,
        )
        val keepTokens = if (overflowRecovery) {
            ContextBudget.overflowRecoveryKeepTokens(config.providerId, config.model)
        } else ContextBudget.recentKeepTokens(config.providerId, config.model)
        val result = sessions.compact(snapshot, config, keepRecentTokens = keepTokens)
        result.exchanges.forEach { tokens.add(config.model, it.requestBody, it.responseBody) }
        ensureCurrent()
        if (!result.changed) return false
        apiHistory.clear()
        apiHistory.addAll(result.session.apiHistory)
        return true
    }
}
