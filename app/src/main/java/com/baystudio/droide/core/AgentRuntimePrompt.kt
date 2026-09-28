package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

 
internal object AgentRuntimePrompt {
    fun compose(
        mode: AgentMode,
        modeInstruction: String,
        projectBrief: String,
        systemOverlay: String,
        finalStep: Boolean,
        tools: JsonArray?,
    ): String {
        val overlay = systemOverlay.takeIf { it.isNotBlank() }?.let { "\n\nSUBAGENT PROFILE:\n$it" }.orEmpty()
        val lifecycle = if (finalStep) "\n\n" + AgentService.FINAL_STEP_INSTRUCTION else ""
        val capability = if (tools == null) {
            "\n\nACTIVE TOOL SURFACE: none. This is a text-only final step; do not request tools."
        } else {
            val names = toolNames(tools)
            "\n\nACTIVE TOOL SURFACE (${names.size}): ${names.joinToString(", ")}. " +
                "Only these tools are callable in this step. This proves Agent-tool advertisement/callability only; external binaries and runtime dependencies may still be absent. Mode-hidden tools must not be requested."
        }
        return AgentSystemPrompt.TEXT + overlay + "\n\nCurrent mode: $mode. " + modeInstruction + projectBrief + capability + lifecycle
    }

    internal fun toolNames(tools: JsonArray): List<String> = tools.mapNotNull { element ->
        val function = (element as? JsonObject)?.get("function") as? JsonObject ?: return@mapNotNull null
        (function["name"] as? JsonPrimitive)?.contentOrNull
    }.distinct()
}
