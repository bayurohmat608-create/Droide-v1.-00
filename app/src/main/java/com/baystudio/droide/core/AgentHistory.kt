package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal object AgentHistory {
    data class InterruptedClosure(
        val apiEntries: List<JsonObject>,
        val messages: List<AgentMessage>,
    )

    fun closeInterruptedTools(
        unresolved: Map<String, String>,
        reason: String,
        terminalState: AgentStreamPartState,
        parts: List<AgentStreamPart>,
        activeStep: Int,
    ): InterruptedClosure {
        val api = mutableListOf<JsonObject>()
        val messages = mutableListOf<AgentMessage>()
        unresolved.forEach { (callId, toolName) ->
            api += buildJsonObject {
                put("role", "tool")
                put("tool_call_id", callId)
                put("content", reason.take(12_000))
            }
            val source = parts.firstOrNull { it.toolCallId == callId }
            val durable = (source ?: AgentStreamPart(
                id = "interrupted:$callId",
                step = activeStep,
                kind = AgentStreamPartKind.TOOL,
                state = terminalState,
                title = toolName.ifBlank { "Tool" },
                toolName = toolName,
                toolCallId = callId,
            )).copy(
                state = terminalState,
                detail = reason.take(500),
                endedAtMs = System.currentTimeMillis(),
            )
            messages += AgentMessage(
                role = "assistant",
                content = "TOOL|${toolName.ifBlank { "tool" }}|${durable.input}|${reason.take(2_000)}",
                streamPart = durable,
            )
        }
        return InterruptedClosure(api, messages)
    }

    fun bounded(source: List<JsonObject>, maxEntries: Int, maxChars: Int): List<JsonObject> {
        val groups = completeGroups(source)
        val selected = ArrayDeque<List<JsonObject>>()
        var entries = 0
        var chars = 0
        for (group in groups.asReversed()) {
            val groupChars = group.sumOf { it.toString().length }
            if (entries + group.size > maxEntries || chars + groupChars > maxChars) break
            selected.addFirst(group)
            entries += group.size
            chars += groupChars
        }
        return selected.flatMap { it }
    }


    fun boundedUi(source: List<AgentMessage>, maxMessages: Int, maxChars: Int): List<AgentMessage> {
        val reversed = ArrayList<AgentMessage>()
        var chars = 0
        for (entry in source.asReversed()) {
            if (reversed.size >= maxMessages || chars + entry.content.length > maxChars) break
            reversed += entry
            chars += entry.content.length
        }
        return reversed.asReversed()
    }

    fun completeGroups(source: List<JsonObject>): List<List<JsonObject>> {
        val groups = mutableListOf<List<JsonObject>>()
        var i = 0
        while (i < source.size) {
            val entry = source[i]
            val role = (entry["role"] as? JsonPrimitive)?.contentOrNull
            if (role == "tool") {
                i++
                continue
            }
            val calls = if (role == "assistant") entry["tool_calls"] as? JsonArray else null
            if (calls != null && calls.isNotEmpty()) {
                val required = calls.mapNotNull { call ->
                    ((call as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                }.toSet()
                if (required.size != calls.size) {
                    i++
                    continue
                }
                val group = mutableListOf(entry)
                val found = mutableSetOf<String>()
                var j = i + 1
                while (j < source.size) {
                    val tool = source[j]
                    if ((tool["role"] as? JsonPrimitive)?.contentOrNull != "tool") break
                    val id = (tool["tool_call_id"] as? JsonPrimitive)?.contentOrNull
                    if (id != null && id in required && id !in found) {
                        group += tool
                        found += id
                    }
                    j++
                }
                if (found == required) groups += group
                i = j
            } else {
                if (role == "user" || role == "assistant" || role == "system") groups += listOf(entry)
                i++
            }
        }
        return groups
    }
}
