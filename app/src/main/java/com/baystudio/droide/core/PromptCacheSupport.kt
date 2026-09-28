package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put














internal object PromptCacheSupport {
    fun systemMessage(config: AgentConfig, systemText: String, tools: JsonArray?): JsonObject {
        val explicit = tools != null && supportsExplicitCacheControl(config.providerId, config.model)
        return buildJsonObject {
            put("role", "system")
            if (explicit) {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", systemText)
                        put("cache_control", buildJsonObject { put("type", "ephemeral") })
                    })
                })
            } else {
                put("content", systemText)
            }
        }
    }

     
    internal fun supportsExplicitCacheControl(providerId: String, model: String): Boolean {
        if (providerId.equals("anthropic", ignoreCase = true)) return true
        if (!providerId.equals("openrouter", ignoreCase = true)) return false
        return model.trim().lowercase().startsWith("anthropic/")
    }
}
