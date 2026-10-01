package com.baystudio.droide.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.ConcurrentHashMap









object ModelContextRegistry {
    private val contextTokens = ConcurrentHashMap<String, Int>()

    fun remember(providerId: String, modelId: String, tokens: Int?) {
        val safe = tokens?.takeIf { it in MIN_CONTEXT_TOKENS..MAX_CONTEXT_TOKENS } ?: return
        contextTokens[key(providerId, modelId)] = safe
    }

    fun resolve(providerId: String, modelId: String): Int? = contextTokens[key(providerId, modelId)]

    internal fun contextLimitFromModel(model: JsonObject): Int? {
        val directKeys = listOf("context_length", "context_window", "max_context_length", "max_input_tokens")
        for (name in directKeys) {
            val value = (model[name] as? JsonPrimitive)?.intOrNull
            if (value != null && value in MIN_CONTEXT_TOKENS..MAX_CONTEXT_TOKENS) return value
        }
        val topProvider = model["top_provider"] as? JsonObject
        val nested = (topProvider?.get("context_length") as? JsonPrimitive)?.intOrNull
        return nested?.takeIf { it in MIN_CONTEXT_TOKENS..MAX_CONTEXT_TOKENS }
    }

    private fun key(providerId: String, modelId: String) = "${providerId.trim().lowercase()}\u0000${modelId.trim()}"

    private const val MIN_CONTEXT_TOKENS = 4_096
    private const val MAX_CONTEXT_TOKENS = 16_000_000
}

data class ContextEstimate(
    val approximateTokens: Int,
    val serializedChars: Int,
    val knownContextLimit: Int?,
    val triggerTokens: Int?,
) {
    val shouldCompact: Boolean
        get() = triggerTokens?.let { approximateTokens >= it }
            ?: (serializedChars >= ContextBudget.UNKNOWN_LIMIT_COMPACT_CHARS)
}

object ContextBudget {
     
    const val KEEP_RECENT_TOKENS = 15_000
     
    const val BUFFER_TOKENS = 20_000
    // Unknown catalogs never get a fabricated token limit.
    const val UNKNOWN_LIMIT_COMPACT_CHARS = 240_000
    const val UNKNOWN_OVERFLOW_KEEP_TOKENS = 4_000

    fun estimate(
        systemText: String,
        history: List<JsonObject>,
        tools: JsonArray?,
        providerId: String,
        modelId: String,
    ): ContextEstimate {
        val chars = systemText.length + history.sumOf { it.toString().length } + (tools?.toString()?.length ?: 0) + 1_024
        val tokens = estimateTokensFromChars(chars)
        val limit = ModelContextRegistry.resolve(providerId, modelId)
        val trigger = limit?.let { it - effectiveBufferTokens(it) }
        return ContextEstimate(tokens, chars, limit, trigger)
    }

    fun recentKeepTokens(providerId: String, modelId: String): Int {
        val limit = ModelContextRegistry.resolve(providerId, modelId) ?: return KEEP_RECENT_TOKENS
        val trigger = (limit - effectiveBufferTokens(limit)).coerceAtLeast(2_000)
        return KEEP_RECENT_TOKENS.coerceAtMost((trigger / 2).coerceAtLeast(2_000))
    }

    fun overflowRecoveryKeepTokens(providerId: String, modelId: String): Int =
        ModelContextRegistry.resolve(providerId, modelId)?.let { recentKeepTokens(providerId, modelId) }
            ?: UNKNOWN_OVERFLOW_KEEP_TOKENS

    private fun effectiveBufferTokens(contextLimit: Int): Int =
        BUFFER_TOKENS.coerceAtMost((contextLimit / 4).coerceAtLeast(2_000))

    fun estimateTokens(text: String): Int = estimateTokensFromChars(text.length)
    fun estimateTokensFromChars(chars: Int): Int = if (chars <= 0) 0 else (chars + 3) / 4
}
