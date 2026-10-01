package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Never present an estimate as provider truth.
@Serializable
enum class UsageAuthority { AUTHORITATIVE, ESTIMATED, MIXED, UNKNOWN }

// Token counts are AUTHORITATIVE only when returned by the provider.






data class TokenUsage(
    val prompt: Int = 0,
    val completion: Int = 0,
    val total: Int = 0,
    val reasoning: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    val costUsd: Double = 0.0,
    val tokenAuthority: UsageAuthority = UsageAuthority.UNKNOWN,
    val costAuthority: UsageAuthority = UsageAuthority.UNKNOWN,
)

class TokenTracker {
    private val _usage = MutableStateFlow(TokenUsage())
    val usage: StateFlow<TokenUsage> = _usage
    private val _history = MutableStateFlow<List<Pair<String, TokenUsage>>>(emptyList())
    val history: StateFlow<List<Pair<String, TokenUsage>>> = _history
    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning
    private val json = Json { ignoreUnknownKeys = true }

    // Records one model request and returns its exact accounting provenance for durable step data.
    fun add(model: String, promptText: String, completionResponse: String): TokenUsage {
        val parsed = parseProviderUsage(completionResponse)
        val sample = parsed ?: TokenUsage(
            prompt = estimateTokens(promptText),
            completion = estimateTokens(completionResponse),
            tokenAuthority = UsageAuthority.ESTIMATED,
            costAuthority = UsageAuthority.UNKNOWN,
        ).let { it.copy(total = it.prompt + it.completion) }
        val cur = _usage.value
        _usage.value = TokenUsage(
            prompt = cur.prompt + sample.prompt,
            completion = cur.completion + sample.completion,
            total = cur.total + sample.total,
            reasoning = cur.reasoning + sample.reasoning,
            cacheRead = cur.cacheRead + sample.cacheRead,
            cacheWrite = cur.cacheWrite + sample.cacheWrite,
            costUsd = cur.costUsd + sample.costUsd,
            tokenAuthority = mergeAuthority(cur.tokenAuthority, sample.tokenAuthority),
            costAuthority = mergeAuthority(cur.costAuthority, sample.costAuthority),
        )
        _history.value = (_history.value + ("$model: ${promptText.take(30)}" to sample)).takeLast(200)
        if (_usage.value.total > 400_000) {
            _warning.value = "Token usage is high (${_usage.value.total}). Consider compacting the session."
        }
        return sample
    }

    private fun parseProviderUsage(response: String): TokenUsage? = runCatching {
        val root = json.parseToJsonElement(response).jsonObject
        val usage = root["usage"]?.jsonObject ?: return null
        val prompt = usage.int("prompt_tokens") ?: usage.int("input_tokens") ?: return null
        val completion = usage.int("completion_tokens") ?: usage.int("output_tokens") ?: 0
        val total = usage.int("total_tokens") ?: (prompt + completion)
        val completionDetails = (usage["completion_tokens_details"] ?: usage["output_tokens_details"]) as? JsonObject
        val promptDetails = (usage["prompt_tokens_details"] ?: usage["input_tokens_details"]) as? JsonObject
        val reasoning = completionDetails?.int("reasoning_tokens") ?: 0
        val cacheRead = promptDetails?.int("cached_tokens")
            ?: usage.int("cache_read_input_tokens")
            ?: usage.int("cache_read_tokens")
            ?: 0
        val cacheWrite = promptDetails?.int("cache_write_tokens")
            ?: usage.int("cache_creation_input_tokens")
            ?: usage.int("cache_write_input_tokens")
            ?: 0
        // Do not collapse it into "missing".
        val providerCost = usage["cost"]?.jsonPrimitive?.doubleOrNull
            ?: usage["cost_usd"]?.jsonPrimitive?.doubleOrNull
        TokenUsage(
            prompt = prompt.coerceAtLeast(0),
            completion = completion.coerceAtLeast(0),
            total = total.coerceAtLeast(0),
            reasoning = reasoning.coerceAtLeast(0),
            cacheRead = cacheRead.coerceAtLeast(0),
            cacheWrite = cacheWrite.coerceAtLeast(0),
            costUsd = providerCost?.coerceAtLeast(0.0) ?: 0.0,
            tokenAuthority = UsageAuthority.AUTHORITATIVE,
            costAuthority = if (providerCost != null) UsageAuthority.AUTHORITATIVE else UsageAuthority.UNKNOWN,
        )
    }.getOrNull()

    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.double(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull

    private fun mergeAuthority(a: UsageAuthority, b: UsageAuthority): UsageAuthority = when {
        a == UsageAuthority.UNKNOWN -> b
        b == UsageAuthority.UNKNOWN -> if (a == UsageAuthority.AUTHORITATIVE) UsageAuthority.MIXED else a
        a == b -> a
        else -> UsageAuthority.MIXED
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(if (text.isEmpty()) 0 else 1)

    fun warnQuota(msg: String) { _warning.value = msg }
    fun clearWarning() { _warning.value = null }
    fun reset() { _usage.value = TokenUsage(); _history.value = emptyList(); _warning.value = null }
}
