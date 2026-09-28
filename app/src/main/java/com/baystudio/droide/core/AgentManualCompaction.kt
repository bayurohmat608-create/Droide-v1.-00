package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject

 
object AgentManualCompaction {
    suspend fun run(
        sessionId: String?, sessions: SessionManager, messages: List<AgentMessage>, apiHistory: List<JsonObject>,
        exactFork: List<JsonObject>?, todos: List<Todo>, mode: AgentMode, pendingInputs: List<AgentPendingInput>,
        config: AgentConfig, tokens: TokenTracker, hooks: AgentHookLifecycleBridge, load: (DroideSession) -> Unit,
    ): String {
        val sid = sessionId ?: return "No active session."
        val current = sessions.get(sid) ?: return "Session not found."
        var result: CompactionResult? = null
        val hook = hooks.compact("manual") {
            result = sessions.compact(
                current.copy(
                    messages = messages, apiHistory = apiHistory, forkHistory = exactFork ?: emptyList(),
                    forkHistoryVersion = if (exactFork != null) SessionForkHistory.SCHEMA_VERSION else 0,
                    todos = todos, mode = mode, pendingInputs = pendingInputs,
                ), config,
            )
            result!!.changed
        }
        if (hook.blockedReason.isNotBlank()) return "Compaction blocked by project hook: ${hook.blockedReason}"
        val compacted = result ?: return "Session is already compact enough."
        compacted.exchanges.forEach { tokens.add(config.model, it.requestBody, it.responseBody) }
        if (!compacted.changed) return "Session is already compact enough."
        load(compacted.session)
        return "Session compacted."
    }
}
