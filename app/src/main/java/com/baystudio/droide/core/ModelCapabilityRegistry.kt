package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicReference

enum class ModelCapabilitySource { MODELS_DEV, RUNTIME_PROVIDER, PROTOCOL_FALLBACK }

data class ModelCapabilities(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val attachment: Boolean?,
    val reasoning: Boolean?,
    val toolCall: Boolean?,
    val structuredOutput: Boolean?,
    val temperature: Boolean?,
    val inputModalities: Set<String>,
    val outputModalities: Set<String>,
    val reasoningEfforts: Set<String>,
    val contextTokens: Int?,
    val outputTokens: Int?,
    val source: ModelCapabilitySource,
)

// Catalog metadata may disable a feature but never invents a wire implementation.


object ModelCapabilityRegistry {
    private val catalog = AtomicReference<Map<String, ModelCapabilities>>(emptyMap())
    private val runtime = AtomicReference<Map<String, ModelCapabilities>>(emptyMap())

    fun installCatalog(entries: Collection<ModelCapabilities>) {
        val next = LinkedHashMap<String, ModelCapabilities>(entries.size)
        entries.forEach { capability ->
            val provider = capability.providerId.trim().lowercase()
            val model = capability.modelId.trim()
            if (provider.isNotEmpty() && model.isNotEmpty()) {
                next[key(provider, model)] = capability.copy(providerId = provider, modelId = model)
                ModelContextRegistry.remember(provider, model, capability.contextTokens)
            }
        }
        catalog.set(next.toMap())
    }

    fun resolve(providerId: String, modelId: String): ModelCapabilities? =
        runtime.get()[key(providerId, modelId)] ?: catalog.get()[key(providerId, modelId)]

    fun installRuntime(capability: ModelCapabilities) {
        require(capability.source == ModelCapabilitySource.RUNTIME_PROVIDER) { "Runtime capability must identify its source" }
        val provider = capability.providerId.trim().lowercase()
        val model = capability.modelId.trim()
        require(provider.isNotEmpty() && model.isNotEmpty()) { "Runtime capability identity is invalid" }
        val normalized = capability.copy(providerId = provider, modelId = model)
        val k = key(provider, model)
        while (true) {
            val current = runtime.get()
            val next = current.toMutableMap().apply { put(k, normalized) }.toMap()
            if (runtime.compareAndSet(current, next)) break
        }
        ModelContextRegistry.remember(provider, model, normalized.contextTokens)
    }

    fun revokeRuntime(providerId: String, modelId: String? = null): Boolean {
        val provider = providerId.trim().lowercase()
        val model = modelId?.trim()
        while (true) {
            val current = runtime.get()
            val next = if (model == null) current.filterKeys { !it.startsWith(provider + "\u0000") }
            else current - key(provider, model)
            if (next.size == current.size) return false
            if (runtime.compareAndSet(current, next)) return true
        }
    }

    fun catalogModels(providerId: String): List<String> {
        val prefix = providerId.trim().lowercase() + "\u0000"
        return catalog.get().entries.asSequence()
            .filter { it.key.startsWith(prefix) }
            .map { it.value.modelId }
            .distinct()
            .sorted()
            .toList()
    }

    // Explicit false is authoritative.
    fun allowsToolUse(providerId: String, modelId: String): Boolean = resolve(providerId, modelId)?.toolCall != false

    fun allowsImageInput(providerId: String, modelId: String): Boolean? {
        val model = resolve(providerId, modelId) ?: return null
        if (model.inputModalities.isNotEmpty()) return "image" in model.inputModalities
        return model.attachment
    }

    fun allowsReasoning(providerId: String, modelId: String): Boolean? = resolve(providerId, modelId)?.reasoning

    fun advertisedReasoningEfforts(providerId: String, modelId: String): Set<String> =
        resolve(providerId, modelId)?.reasoningEfforts.orEmpty()

    private fun key(providerId: String, modelId: String): String =
        providerId.trim().lowercase() + "\u0000" + modelId.trim()
}
