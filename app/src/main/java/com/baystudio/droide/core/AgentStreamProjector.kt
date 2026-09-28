package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow







internal class AgentStreamProjector(
    private val state: MutableStateFlow<AgentStreamState>,
    private val maxAssistantChars: Int,
    private val maxToolArgumentChars: Int,
) {
    @Synchronized fun part(id: String): AgentStreamPart? = state.value.parts.firstOrNull { it.id == id }

    @Synchronized fun upsert(part: AgentStreamPart) {
        val current = state.value
        val index = current.parts.indexOfFirst { it.id == part.id }
        val next = if (index >= 0) current.parts.toMutableList().also { it[index] = part } else current.parts + part
        state.value = current.copy(parts = next)
    }

    @Synchronized fun update(id: String, transform: (AgentStreamPart) -> AgentStreamPart) {
        val current = state.value
        val index = current.parts.indexOfFirst { it.id == id }
        if (index < 0) return
        val next = current.parts.toMutableList()
        next[index] = transform(next[index])
        state.value = current.copy(parts = next)
    }

    @Synchronized fun remove(id: String) {
        val current = state.value
        if (current.parts.none { it.id == id }) return
        state.value = current.copy(parts = current.parts.filterNot { it.id == id })
    }

    @Synchronized fun terminateOpen(terminalState: AgentStreamPartState, detail: String) {
        val now = System.currentTimeMillis()
        val current = state.value
        state.value = current.copy(parts = current.parts.map { part ->
            if (part.state == AgentStreamPartState.PENDING || part.state == AgentStreamPartState.RUNNING) {
                part.copy(state = terminalState, detail = detail, endedAtMs = now)
            } else part
        })
    }

    @Synchronized fun appendText(id: String, step: Int, delta: String, buffered: Boolean) {
        if (delta.isEmpty()) return
        val existing = part(id)
        val merged = ((existing?.text ?: "") + delta).take(maxAssistantChars)
        upsert(
            (existing ?: AgentStreamPart(
                id = id,
                step = step,
                kind = AgentStreamPartKind.TEXT,
                state = AgentStreamPartState.RUNNING,
                title = "Droide",
            )).copy(
                state = AgentStreamPartState.RUNNING,
                text = merged,
                detail = if (buffered) "Buffered provider response" else "Streaming",
            )
        )
    }

    @Synchronized fun applyToolCallDelta(step: Int, event: LlmStreamEvent.ToolCallDelta) {
        val partId = "tool:$step:${event.index}"
        val current = part(partId)
        val oldName = current?.toolName.orEmpty()
        val name = event.nameFragment?.let { fragment ->
            when {
                oldName.isBlank() -> fragment
                fragment == oldName -> oldName
                else -> oldName + fragment
            }
        } ?: oldName
        val input = (current?.input.orEmpty() + event.argumentsFragment.orEmpty()).take(maxToolArgumentChars)
        upsert(
            (current ?: AgentStreamPart(
                id = partId,
                step = step,
                kind = AgentStreamPartKind.TOOL,
                state = AgentStreamPartState.PENDING,
                title = "Preparing tool",
            )).copy(
                state = AgentStreamPartState.PENDING,
                title = name.ifBlank { "Preparing tool" }.replace('_', ' '),
                toolName = name,
                toolCallId = event.callId ?: current?.toolCallId.orEmpty(),
                input = input,
                detail = if (name.isBlank()) "Receiving tool call" else "Preparing",
            )
        )
    }

    @Synchronized fun finishStep(step: Int, reason: String): AgentStreamPart {
        update("step:$step") {
            it.copy(
                state = AgentStreamPartState.COMPLETED,
                detail = reason,
                endedAtMs = System.currentTimeMillis(),
            )
        }
        return part("step:$step") ?: AgentStreamPart(
            id = "step:$step",
            step = step,
            kind = AgentStreamPartKind.STEP,
            state = AgentStreamPartState.COMPLETED,
            title = "Step $step",
            detail = reason,
            endedAtMs = System.currentTimeMillis(),
        )
    }
}
