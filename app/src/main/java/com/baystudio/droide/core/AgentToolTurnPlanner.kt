package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

internal data class PreparedToolCall(
    val index: Int,
    val id: String,
    val name: String,
    val rawArgs: String,
    val args: JsonObject,
    val partId: String,
    val repeatOrdinal: Int,
    val signature: String,
)

internal data class ToolOutcome(
    val call: PreparedToolCall,
    val result: String,
    val failed: Boolean = false,
)

 
internal object AgentToolTurnPlanner {
    fun prepare(
        toolCalls: JsonArray,
        json: Json,
        turn: Int,
        maxArgumentChars: Int,
        priorSignature: String,
        priorRepeat: Int,
    ): List<PreparedToolCall> {
        var lastSignature = priorSignature
        var repeat = priorRepeat
        val prepared = toolCalls.mapIndexed { index, raw ->
            val call = raw as? JsonObject ?: throw IllegalStateException("Malformed tool call")
            val id = (call["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Tool call is missing id")
            val fn = call["function"] as? JsonObject ?: throw IllegalStateException("Tool call is missing function")
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Tool call is missing function name")
            require(name.length <= 120) { "Tool name is too long" }
            val rawArgs = (fn["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}"
            require(rawArgs.length <= maxArgumentChars) { "Tool arguments are too large" }
            val args = try { json.parseToJsonElement(rawArgs) as? JsonObject ?: buildJsonObject {} }
            catch (_: Exception) { buildJsonObject {} }
            val signature = "$name:$rawArgs"
            val ordinal = if (signature == lastSignature) repeat + 1 else 1
            lastSignature = signature
            repeat = ordinal
            PreparedToolCall(index, id, name, rawArgs, args, "tool:$turn:$index", ordinal, signature)
        }
        require(prepared.map { it.id }.distinct().size == prepared.size) { "Duplicate tool call id in provider response" }
        return prepared
    }

    fun schedulingFacts(
        prepared: List<PreparedToolCall>,
        permissionAllows: (AgentToolReadAccess) -> Boolean,
        hasLifecycleHook: (String) -> Boolean,
    ): List<AgentToolScheduler.Facts> = prepared.map { call ->
        val readAccess = AgentTools.parallelReadAccess(call.name, call.args)
        AgentToolScheduler.Facts(
            index = call.index,
            readOnlyCandidate = readAccess != null,
            permissionAllowsWithoutPrompt = readAccess != null && permissionAllows(readAccess),
            lifecycleHookBoundary = hasLifecycleHook(call.name),
            doomLoopApprovalPossible = call.repeatOrdinal >= 3,
        )
    }
}
