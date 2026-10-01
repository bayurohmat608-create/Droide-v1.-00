package com.baystudio.droide.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Runtime Models.dev reasoningoptions metadata is authoritative when present.









@Serializable
enum class ReasoningEffort(val label: String, val wireValue: String) {
    LOW("Low", "low"),
    HIGH("High", "high"),
    XTRA_HIGH("Xtra High", "xhigh"),
    ULTRA("Ultra", "max"),
}

 
sealed interface ReasoningWire {
    data class OpenAiCompatible(val value: String) : ReasoningWire
    data class OpenRouter(val value: String) : ReasoningWire
    data class AnthropicAdaptive(val effort: String) : ReasoningWire
    data class AnthropicManual(val budgetTokens: Int) : ReasoningWire
    data class GoogleNative(val value: String) : ReasoningWire
}

// This deliberately avoids provider-name allowlists as the primary source of truth so newly discovered reasoning models work without an APK update when.







object ReasoningSupport {
    fun wire(providerId: String, model: String, effort: ReasoningEffort): ReasoningWire? {
        val provider = ProviderRegistry.findById(providerId) ?: return null
        val capability = resolveCapability(providerId, model)
        if (capability?.reasoning == false) return null

        val advertised = normalizedAdvertisedEfforts(capability?.reasoningEfforts.orEmpty())
        if (advertised.isNotEmpty() && effort.wireValue !in advertised) return null

        val protocol = provider.wireProtocol

        // It is still constrained by the reviewed wire protocol so catalog metadata can never invent a serializer.

        if (advertised.isNotEmpty()) {
            return catalogWire(providerId, model, effort, protocol)
        }

        return fallbackWire(providerId, model, effort, protocol, provider, capability)
    }

    fun supported(providerId: String, model: String): List<ReasoningEffort> =
        ReasoningEffort.entries.filter { wire(providerId, model, it) != null }

    fun defaultFor(providerId: String, model: String): ReasoningEffort? {
        val supported = supported(providerId, model)
        return when {
            ReasoningEffort.HIGH in supported -> ReasoningEffort.HIGH
            ReasoningEffort.LOW in supported -> ReasoningEffort.LOW
            else -> null
        }
    }

     
    fun apply(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        providerId: String,
        model: String,
        effort: ReasoningEffort?,
    ) {
        val resolved = effort?.let { wire(providerId, model, it) } ?: return
        when (resolved) {
            is ReasoningWire.OpenAiCompatible -> builder.put("reasoning_effort", resolved.value)
            is ReasoningWire.OpenRouter -> builder.put("reasoning", buildJsonObject { put("effort", resolved.value) })
            is ReasoningWire.AnthropicAdaptive -> {
                builder.put("thinking", buildJsonObject { put("type", "adaptive") })
                builder.put("output_config", buildJsonObject { put("effort", resolved.effort) })
            }
            is ReasoningWire.AnthropicManual -> builder.put("thinking", buildJsonObject {
                put("type", "enabled")
                put("budget_tokens", resolved.budgetTokens)
            })
            // The raw field never reaches Google's native API.

            is ReasoningWire.GoogleNative -> builder.put("reasoning_effort", resolved.value)
        }
    }

    private fun catalogWire(
        providerId: String,
        model: String,
        effort: ReasoningEffort,
        protocol: ProviderWireProtocol,
    ): ReasoningWire? = when (protocol) {
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> anthropicWire(model, effort, catalogAdvertised = true)
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> googleNativeWire(model, effort)
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> {
            if (providerId.equals("openrouter", ignoreCase = true)) ReasoningWire.OpenRouter(effort.wireValue)
            else ReasoningWire.OpenAiCompatible(effort.wireValue)
        }
    }

    private fun fallbackWire(
        providerId: String,
        model: String,
        effort: ReasoningEffort,
        protocol: ProviderWireProtocol,
        provider: AiProvider?,
        capability: ModelCapabilities?,
    ): ReasoningWire? = when (protocol) {
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> anthropicWire(model, effort, catalogAdvertised = false)
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> googleNativeWire(model, effort)
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> {
            val allowed = openAiCompatibleFallbackEfforts(providerId, model, provider, capability)
            if (effort.wireValue !in allowed) null
            else if (providerId.equals("openrouter", ignoreCase = true)) ReasoningWire.OpenRouter(effort.wireValue)
            else ReasoningWire.OpenAiCompatible(effort.wireValue)
        }
    }

    




    private fun anthropicWire(model: String, effort: ReasoningEffort, catalogAdvertised: Boolean): ReasoningWire? {
        return when (anthropicThinkingMode(model)) {
            AnthropicThinkingMode.ADAPTIVE -> {
                val allowed = if (catalogAdvertised) setOf(effort.wireValue) else anthropicFallbackEfforts(model)
                if (effort.wireValue !in allowed) null else ReasoningWire.AnthropicAdaptive(effort.wireValue)
            }
            // Keep 's conservative Low/High presets and never pretend those named levels exist.

            AnthropicThinkingMode.MANUAL -> when (effort) {
                ReasoningEffort.LOW -> ReasoningWire.AnthropicManual(2_048)
                ReasoningEffort.HIGH -> ReasoningWire.AnthropicManual(8_192)
                ReasoningEffort.XTRA_HIGH, ReasoningEffort.ULTRA -> null
            }
            null -> null
        }
    }

    private enum class AnthropicThinkingMode { ADAPTIVE, MANUAL }

    private fun anthropicThinkingMode(model: String): AnthropicThinkingMode? {
        val m = normalizedModelId(model)
        if (!m.contains("claude-")) return null
        if (Regex("(?:^|/)claude-(?:opus|sonnet|haiku|fable|mythos)-5(?:[-.]|$)").containsMatchIn(m)) {
            return AnthropicThinkingMode.ADAPTIVE
        }
        val v4 = Regex("(?:^|/)claude-(?:opus|sonnet|haiku)-(4)-(\\d+)(?:[-.]|$)").find(m)
        if (v4 != null) {
            val minor = v4.groupValues[2].toIntOrNull() ?: return null
            return if (minor >= 6) AnthropicThinkingMode.ADAPTIVE else AnthropicThinkingMode.MANUAL
        }
        if (m.contains("claude-3-7-") || m.contains("claude-3.7-")) return AnthropicThinkingMode.MANUAL
        return null
    }

    private fun anthropicFallbackEfforts(model: String): Set<String> {
        val m = normalizedModelId(model)
        if (Regex("(?:^|/)claude-(?:opus|sonnet|haiku|fable|mythos)-5(?:[-.]|$)").containsMatchIn(m)) {
            return setOf("low", "high", "xhigh", "max")
        }
        val adaptive4 = Regex("claude-(?:opus|sonnet)-(4)[-.](\\d+)").find(m)
        if (adaptive4 != null) {
            val minor = adaptive4.groupValues[2].toIntOrNull() ?: return emptySet()
            return if (minor >= 7) setOf("low", "high", "xhigh", "max")
            else if (minor == 6) setOf("low", "high", "max")
            else emptySet()
        }
        return setOf("low", "high", "max")
    }

    private fun googleNativeWire(model: String, effort: ReasoningEffort): ReasoningWire? {
        val serializerSupported = googleNativeFallbackEfforts(model)
        if (effort.wireValue !in serializerSupported) return null
        // Catalog metadata may narrow support, never widen the reviewed native serializer.
        return ReasoningWire.GoogleNative(effort.wireValue)
    }

    private fun googleNativeFallbackEfforts(model: String): Set<String> {
        val m = normalizedModelId(model)
        return if ("gemini-" in m) setOf("low", "high") else emptySet()
    }

    




    private fun openAiCompatibleFallbackEfforts(
        providerId: String,
        model: String,
        provider: AiProvider?,
        capability: ModelCapabilities?,
    ): Set<String> {
        val m = normalizedModelId(model)

        openAiFamilyEfforts(m).takeIf(Set<String>::isNotEmpty)?.let { return it }
        grokFamilyEfforts(m).takeIf(Set<String>::isNotEmpty)?.let { return it }

        if ("gemini-" in m) return setOf("low", "high")
        if ("claude-" in m) return setOf("low", "high")
        if (isGlm52(m)) return setOf("high", "max")

        val id = providerId.lowercase()
        if (id == "groq" && groqReasoningModel(m)) return setOf("low", "high")
        if (id == "fireworks" && fireworksReasoningModel(m)) return setOf("low", "high")

        

        if (id == "openrouter" && capability?.reasoning != false) return setOf("low", "high")

        


        if (capability?.reasoning == true && provider?.catalogDiscovered == true &&
            provider.catalogPackage in OPENAI_SHAPED_REASONING_PACKAGES
        ) {
            return setOf("low", "high")
        }
        return emptySet()
    }

    private fun openAiFamilyEfforts(model: String): Set<String> {
        val m = normalizedModelId(model)
        val openAiFamily = Regex("(?:^|/)(?:gpt-5(?:[.-]|$)|gpt-6(?:[.-]|$)|o1(?:[-.]|$)|o3(?:[-.]|$)|o4(?:[-.]|$))")
        if (!openAiFamily.containsMatchIn(m)) return emptySet()
        if (Regex("(?:^|/)gpt-5(?:[.-]?pro)(?:[-.]|$)").containsMatchIn(m)) return setOf("high")

        val version = Regex("(?:^|/)gpt-5[.-](\\d+)(?:[-.]|$)").find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            m.contains("gpt-5.6") || m.contains("gpt-5-6") || m.contains("gpt-6") -> setOf("low", "high", "xhigh", "max")
            version != null && version >= 2 -> setOf("low", "high", "xhigh")
            else -> setOf("low", "high")
        }
    }

    private fun grokFamilyEfforts(model: String): Set<String> {
        val m = normalizedModelId(model)
        if (!m.contains("grok-")) return emptySet()
        return when {
            Regex("grok-4[.-](?:6|[7-9]|\\d{2,})").containsMatchIn(m) -> setOf("low", "high", "xhigh")
            m.contains("grok-4.5") || m.contains("grok-4-5") -> setOf("low", "high")
            m.contains("grok-4.3") || m.contains("grok-4-3") -> setOf("low", "high")
            m.contains("grok-3-mini") -> setOf("low", "high")
            else -> emptySet()
        }
    }

    private fun normalizedAdvertisedEfforts(raw: Set<String>): Set<String> = raw.mapNotNull { value ->
        when (value.trim().lowercase().replace('_', '-')) {
            "low" -> "low"
            "high" -> "high"
            "xhigh", "x-high", "extra-high", "extrahigh" -> "xhigh"
            "max", "maximum", "ultra" -> "max"
            else -> null 
        }
    }.toSet()

    private fun resolveCapability(providerId: String, model: String): ModelCapabilities? {
        ModelCapabilityRegistry.resolve(providerId, model)?.let { return it }
        

        if (providerId.equals("gemini", ignoreCase = true)) {
            return ModelCapabilityRegistry.resolve("google", model)
        }
        return null
    }

    private fun normalizedModelId(model: String): String = model.trim().lowercase()

    private fun isGlm52(model: String): Boolean =
        listOf("glm-5.2", "glm-5-2", "glm-5p2").any(model::contains)

    private fun groqReasoningModel(model: String): Boolean =
        "gpt-oss" in model || "qwen3.8" in model || "qwen-3.8" in model

    private fun fireworksReasoningModel(model: String): Boolean =
        "qwen3" in model || "qwen-3" in model || "deepseek" in model || "kimi" in model

    private val OPENAI_SHAPED_REASONING_PACKAGES = setOf(
        "@ai-sdk/openai-compatible",
        "@ai-sdk/xai",
        "@ai-sdk/cerebras",
        "@ai-sdk/cohere",
        "@ai-sdk/mistral",
    )
}
