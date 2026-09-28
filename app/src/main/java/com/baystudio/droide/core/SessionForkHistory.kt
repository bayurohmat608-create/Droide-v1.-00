package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put








internal object SessionForkHistory {
    const val SCHEMA_VERSION = 1

    fun isExact(history: List<JsonObject>): Boolean {
        val complete = AgentHistory.completeGroups(history).flatten()
        return complete.size == history.size && complete.indices.all { complete[it] == history[it] }
    }

    







    fun migrateLegacy(messages: List<AgentMessage>, activeHistory: List<JsonObject>): List<JsonObject>? {
        val hasUiToolEvidence = messages.any {
            it.content.startsWith("TOOL|") || it.streamPart?.kind == AgentStreamPartKind.TOOL
        }
        val hasStructuredToolEvidence = activeHistory.any { entry ->
            roleOf(entry) == "tool" ||
                ((entry["tool_calls"] as? JsonArray)?.isNotEmpty() == true)
        }
        if (hasUiToolEvidence || hasStructuredToolEvidence) return null

        val candidate = if (activeHistory.isEmpty() && messages.isNotEmpty()) {
            messages.asSequence()
                .filter { it.streamPart == null && (it.role == "user" || it.role == "assistant") }
                .filterNot { it.content.startsWith("TOOL|") }
                .map { message -> buildJsonObject { put("role", message.role); put("content", message.content) } }
                .toList()
        } else activeHistory
        if (candidate.isEmpty()) return emptyList()
        if (!isExact(candidate)) return null
        if (candidate.any { isCompactionCheckpoint(it) }) return null
        val annotated = annotate(messages, candidate)
        if (annotated.none { it.forkHistoryEnd != null }) return null
        return candidate
    }

    fun annotate(messages: List<AgentMessage>, history: List<JsonObject>): List<AgentMessage> {
        if (messages.isEmpty()) return messages
        if (!isExact(history)) {
            return messages.map { it.copy(forkHistoryEnd = null) }
        }

        val boundaries = arrayOfNulls<Int>(messages.size)
        val groups = AgentHistory.completeGroups(history)
        val ends = IntArray(groups.size)
        var total = 0
        groups.forEachIndexed { index, group ->
            total += group.size
            ends[index] = total
        }

        var cursor = messages.lastIndex
        for (groupIndex in groups.indices.reversed()) {
            val semantic = previousSemantic(messages, cursor)
            if (semantic < 0) break 
            val group = groups[groupIndex]
            val head = group.firstOrNull() ?: continue
            if (isInvisibleStructuredEntry(head)) continue

            val match = matchGroupBackward(messages, cursor, group) ?: break
            boundaries[match.endUi] = ends[groupIndex]
            cursor = match.startUi - 1
        }

        return messages.mapIndexed { index, message -> message.copy(forkHistoryEnd = boundaries[index]) }
    }

    fun slice(messages: List<AgentMessage>, history: List<JsonObject>, atIndex: Int): List<JsonObject>? {
        if (atIndex !in messages.indices) return null
        if (!isExact(history)) return null
        val annotated = if (messages[atIndex].forkHistoryEnd != null) messages else annotate(messages, history)
        val end = annotated[atIndex].forkHistoryEnd ?: return null
        if (end !in 0..history.size) return null
        val prefix = history.take(end)
        val verified = AgentHistory.completeGroups(prefix).flatten()
        return prefix.takeIf { verified.size == prefix.size && verified.indices.all { verified[it] == prefix[it] } }
    }

    private data class Match(val startUi: Int, val endUi: Int)

    private fun matchGroupBackward(messages: List<AgentMessage>, cursor: Int, group: List<JsonObject>): Match? {
        val assistant = group.firstOrNull() ?: return null
        val role = roleOf(assistant)
        if (role == "user") {
            val index = findPreviousSemantic(messages, cursor) { message ->
                message.streamPart == null && message.role == "user" && textMatches(message.content, contentOf(assistant))
            } ?: return null
            return Match(index, index)
        }
        if (role != "assistant") return null

        val calls = assistant["tool_calls"] as? JsonArray
        if (calls == null || calls.isEmpty()) {
            val index = findPreviousSemantic(messages, cursor) { message ->
                message.streamPart == null && message.role == "assistant" && textMatches(message.content, contentOf(assistant))
            } ?: return null
            return Match(index, index)
        }

        var at = cursor
        var start = Int.MAX_VALUE
        var end = -1
        val tools = group.drop(1)
        for (tool in tools.asReversed()) {
            val callId = (tool["tool_call_id"] as? JsonPrimitive)?.contentOrNull ?: return null
            val index = findPreviousSemantic(messages, at) { message ->
                message.streamPart?.kind == AgentStreamPartKind.TOOL && message.streamPart.toolCallId == callId
            } ?: return null
            start = minOf(start, index)
            end = maxOf(end, index)
            at = index - 1
        }

        val assistantText = contentOf(assistant)
        if (assistantText.isNotBlank()) {
            val index = findPreviousSemantic(messages, at) { message ->
                message.streamPart == null && message.role == "assistant" && textMatches(message.content, assistantText)
            } ?: return null
            start = minOf(start, index)
            end = maxOf(end, index)
        }
        if (end < 0) return null
        return Match(start, end)
    }

    private fun findPreviousSemantic(
        messages: List<AgentMessage>,
        cursor: Int,
        predicate: (AgentMessage) -> Boolean,
    ): Int? {
        var index = cursor.coerceAtMost(messages.lastIndex)
        while (index >= 0) {
            val message = messages[index]
            if (!isPresentationOnly(message)) return index.takeIf { predicate(message) }
            index--
        }
        return null
    }

    private fun previousSemantic(messages: List<AgentMessage>, cursor: Int): Int {
        var index = cursor.coerceAtMost(messages.lastIndex)
        while (index >= 0 && isPresentationOnly(messages[index])) index--
        return index
    }

    private fun isPresentationOnly(message: AgentMessage): Boolean =
        message.streamPart != null && message.streamPart.kind != AgentStreamPartKind.TOOL

    private fun isInvisibleStructuredEntry(entry: JsonObject): Boolean {
        val content = contentOf(entry)
        return roleOf(entry) == "system" || isCompactionCheckpoint(entry) ||
            content.startsWith("[Background subagent completion;")
    }

    private fun isCompactionCheckpoint(entry: JsonObject): Boolean =
        contentOf(entry).startsWith("[Historical conversation checkpoint;")

    private fun roleOf(entry: JsonObject): String? = (entry["role"] as? JsonPrimitive)?.contentOrNull
    private fun contentOf(entry: JsonObject): String = (entry["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun textMatches(ui: String, api: String): Boolean =
        ui == api || (api.isNotEmpty() && ui.length >= api.length && ui.startsWith(api))
}
