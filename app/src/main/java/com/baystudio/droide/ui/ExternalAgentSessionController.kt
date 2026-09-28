package com.baystudio.droide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.baystudio.droide.core.AgentMessage
import com.baystudio.droide.core.WorkspaceAgentEvent
import com.baystudio.droide.core.WorkspaceAgentMode
import com.baystudio.droide.core.WorkspaceAgentPluginHost
import com.baystudio.droide.core.WorkspaceAgentPluginSpec
import com.baystudio.droide.core.WorkspaceAgentUsageSnapshot
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class ExternalAgentConversationState(
    val sessionId: String? = null,
    val messages: List<AgentMessage> = emptyList(),
    val liveText: String = "",
    val activity: List<String> = emptyList(),
    val usage: WorkspaceAgentUsageSnapshot? = null,
)

internal data class ExternalAgentActiveRun(
    val runId: Long,
    val agentId: String,
    val sessionId: String?,
)

 
internal class ExternalAgentSessionController(private val scope: CoroutineScope) {
    private val states = mutableStateMapOf<String, ExternalAgentConversationState>()
    private val runIds = AtomicLong(1)

    var activeRun by mutableStateOf<ExternalAgentActiveRun?>(null)
        private set
    var lifecycleBusyAgentId by mutableStateOf<String?>(null)
        private set

    val busy: Boolean get() = activeRun != null || lifecycleBusyAgentId != null

    operator fun get(agentId: String): ExternalAgentConversationState =
        states[agentId] ?: ExternalAgentConversationState()

    fun submit(
        host: WorkspaceAgentPluginHost,
        spec: WorkspaceAgentPluginSpec,
        prompt: String,
        mode: WorkspaceAgentMode,
    ) {
        if (busy) return
        val agentId = spec.id
        val initial = this[agentId]
        val runId = runIds.getAndIncrement()
        states[agentId] = initial.copy(
            messages = initial.messages + AgentMessage("user", prompt),
            liveText = "",
            activity = emptyList(),
        )
        activeRun = ExternalAgentActiveRun(runId, agentId, initial.sessionId)
        scope.launch {
            runCatching {
                host.runTurn(agentId, prompt, mode, resumeSessionId = initial.sessionId) { event ->
                    scope.launch { applyEvent(runId, agentId, event) }
                }
            }.onSuccess { result ->
                if (activeRun?.runId != runId) return@onSuccess
                val current = this@ExternalAgentSessionController[agentId]
                val reply = result.text.ifBlank { current.liveText }
                    .ifBlank { "${spec.displayName} completed the turn." }
                var messages = current.messages + AgentMessage("assistant", reply)
                if (result.changedFiles.isNotEmpty()) {
                    messages += AgentMessage("assistant", "Workspace changes applied: ${result.changedFiles.joinToString(limit = 8)}")
                }
                if (result.conflicts.isNotEmpty()) {
                    messages += AgentMessage("assistant", "Workspace conflicts left untouched: ${result.conflicts.joinToString(limit = 8)}")
                }
                states[agentId] = current.copy(sessionId = result.sessionId, messages = messages, liveText = "")
            }.onFailure { error ->
                if (activeRun?.runId != runId) return@onFailure
                val current = this@ExternalAgentSessionController[agentId]
                states[agentId] = current.copy(
                    messages = current.messages + AgentMessage("assistant", "${spec.displayName} error: ${error.message ?: "unknown error"}"),
                    liveText = "",
                )
            }
            if (activeRun?.runId == runId) activeRun = null
        }
    }

    fun newSession(host: WorkspaceAgentPluginHost, spec: WorkspaceAgentPluginSpec) {
        if (busy) return
        val agentId = spec.id
        lifecycleBusyAgentId = agentId
        scope.launch {
            runCatching { host.newSession(agentId) }
                .onSuccess { sessionId -> states[agentId] = ExternalAgentConversationState(sessionId = sessionId) }
                .onFailure { error ->
                    val current = this@ExternalAgentSessionController[agentId]
                    states[agentId] = current.copy(
                        messages = current.messages + AgentMessage("assistant", "${spec.displayName} new session failed: ${error.message ?: "unknown error"}"),
                    )
                }
            if (lifecycleBusyAgentId == agentId) lifecycleBusyAgentId = null
        }
    }

    fun cancel(host: WorkspaceAgentPluginHost?) {
        val run = activeRun ?: return
        if (host == null) return
        scope.launch { host.cancel(run.agentId, run.sessionId) }
    }

    private fun applyEvent(runId: Long, agentId: String, event: WorkspaceAgentEvent) {
        val run = activeRun?.takeIf { it.runId == runId && it.agentId == agentId } ?: return
        val current = this[agentId]
        when (event) {
            is WorkspaceAgentEvent.SessionBound -> {
                activeRun = run.copy(sessionId = event.sessionId)
                states[agentId] = current.copy(sessionId = event.sessionId)
            }
            is WorkspaceAgentEvent.Text -> states[agentId] = current.copy(liveText = current.liveText + event.text)
            is WorkspaceAgentEvent.Status -> states[agentId] = current.copy(activity = (current.activity + event.message).takeLast(6))
            is WorkspaceAgentEvent.Tool -> states[agentId] = current.copy(activity = (current.activity + event.title).takeLast(6))
            is WorkspaceAgentEvent.Usage -> states[agentId] = current.copy(usage = event.snapshot)
            is WorkspaceAgentEvent.Thought -> Unit
        }
    }
}
