package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalReasoningEffortsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @After fun resetDynamicCatalog() {
        ModelCapabilityRegistry.installCatalog(emptyList())
        ProviderRegistry.installDiscovered(emptyList())
    }

    @Test fun userFacingTiersHaveCanonicalProviderNeutralWireNames() {
        assertEquals("low", ReasoningEffort.LOW.wireValue)
        assertEquals("high", ReasoningEffort.HIGH.wireValue)
        assertEquals("xhigh", ReasoningEffort.XTRA_HIGH.wireValue)
        assertEquals("max", ReasoningEffort.ULTRA.wireValue)
    }

    @Test fun gpt56AndGpt6ExposeAllFourDroideTiers() {
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.XTRA_HIGH, ReasoningEffort.ULTRA),
            ReasoningSupport.supported("openai", "gpt-5.6-sol"),
        )
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.XTRA_HIGH, ReasoningEffort.ULTRA),
            ReasoningSupport.supported("openai", "gpt-6-astra"),
        )
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), ReasoningSupport.supported("openai", "gpt-5"))
    }

    @Test fun anthropicNamedEffortsStayModelSpecific() {
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.XTRA_HIGH, ReasoningEffort.ULTRA),
            ReasoningSupport.supported("anthropic", "claude-opus-5"),
        )
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.XTRA_HIGH, ReasoningEffort.ULTRA),
            ReasoningSupport.supported("anthropic", "claude-opus-4-8"),
        )
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.ULTRA),
            ReasoningSupport.supported("anthropic", "claude-sonnet-4-6"),
        )
        
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            ReasoningSupport.supported("anthropic", "claude-sonnet-4-5-20250929"),
        )
    }

    @Test fun grokDoesNotSilentlyDowngradeXhighOn45() {
        ProviderRegistry.installDiscovered(listOf(openAiDiscoveredProvider("xai", "@ai-sdk/xai", "https://api.x.ai/v1")))
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.XTRA_HIGH),
            ReasoningSupport.supported("xai", "grok-4.6"),
        )
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            ReasoningSupport.supported("xai", "grok-4.5"),
        )
        assertNull(ReasoningSupport.wire("xai", "grok-4.5", ReasoningEffort.XTRA_HIGH))
        assertNull(ReasoningSupport.wire("xai", "grok-4.6", ReasoningEffort.ULTRA))
    }

    @Test fun modelsDevEffortListIsAuthorityForFutureOpenRouterModels() {
        ModelCapabilityRegistry.installCatalog(listOf(capability(
            provider = "openrouter",
            model = "future/reasoner-v9",
            efforts = setOf("low", "high", "xhigh", "max"),
        )))
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.XTRA_HIGH, ReasoningEffort.ULTRA),
            ReasoningSupport.supported("openrouter", "future/reasoner-v9"),
        )
        assertEquals(
            ReasoningWire.OpenRouter("max"),
            ReasoningSupport.wire("openrouter", "future/reasoner-v9", ReasoningEffort.ULTRA),
        )
    }

    @Test fun catalogNarrowsKnownFamiliesAndNeverCoercesUnsupportedTier() {
        ModelCapabilityRegistry.installCatalog(listOf(capability(
            provider = "openai",
            model = "gpt-5.6-sol",
            efforts = setOf("low", "max"),
        )))
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.ULTRA),
            ReasoningSupport.supported("openai", "gpt-5.6-sol"),
        )
        assertNull(ReasoningSupport.wire("openai", "gpt-5.6-sol", ReasoningEffort.HIGH))
    }

    @Test fun explicitCatalogReasoningFalseWinsOverFamilyFallback() {
        ModelCapabilityRegistry.installCatalog(listOf(capability(
            provider = "openai",
            model = "gpt-5.6-sol",
            efforts = setOf("low", "high", "xhigh", "max"),
            reasoning = false,
        )))
        assertTrue(ReasoningSupport.supported("openai", "gpt-5.6-sol").isEmpty())
    }

    @Test fun geminiExposesOnlyExactSupportedDroideTiersAndNativeAdapterTranslatesThem() {
        

        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            ReasoningSupport.supported("gemini", "gemini-3.1-pro-preview"),
        )
        assertNull(ReasoningSupport.wire("gemini", "gemini-3.1-pro-preview", ReasoningEffort.ULTRA))

        ProviderRegistry.installDiscovered(listOf(AiProvider(
            id = "google",
            name = "Google",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            model = "",
            free = false,
            needsKey = true,
            helpUrl = "",
            note = "test",
            wireProtocol = ProviderWireProtocol.GOOGLE_GENERATE_CONTENT,
            authScheme = ProviderAuthScheme.GOOGLE_API_KEY,
            catalogPackage = "@ai-sdk/google",
            catalogDiscovered = true,
        )))
        ModelCapabilityRegistry.installCatalog(listOf(capability(
            provider = "google",
            model = "gemini-3.1-pro-preview",
            efforts = setOf("low", "high", "xhigh", "max"),
        )))
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            ReasoningSupport.supported("google", "gemini-3.1-pro-preview"),
        )

        val encoded = json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(
            """{"model":"gemini-3.1-pro-preview","reasoning_effort":"high","messages":[{"role":"user","content":"hi"}]}""",
            json,
        )).jsonObject
        assertEquals(
            "high",
            encoded.getValue("generationConfig").jsonObject
                .getValue("thinkingConfig").jsonObject
                .getValue("thinkingLevel").jsonPrimitive.content,
        )
    }

    @Test fun gemini25HighUsesDocumentedNativeBudgetAndRejectsUltra() {
        val encoded = json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(
            """{"model":"gemini-2.5-pro","reasoning_effort":"high","messages":[{"role":"user","content":"hi"}]}""",
            json,
        )).jsonObject
        assertEquals(
            "24576",
            encoded.getValue("generationConfig").jsonObject
                .getValue("thinkingConfig").jsonObject
                .getValue("thinkingBudget").jsonPrimitive.content,
        )
        assertNull(ReasoningSupport.wire("gemini", "gemini-2.5-pro", ReasoningEffort.ULTRA))
    }

    private fun openAiDiscoveredProvider(id: String, pkg: String, url: String) = AiProvider(
        id = id,
        name = id,
        baseUrl = url,
        model = "",
        free = false,
        needsKey = true,
        helpUrl = "",
        note = "test",
        wireProtocol = ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
        authScheme = ProviderAuthScheme.BEARER,
        catalogPackage = pkg,
        catalogDiscovered = true,
    )

    private fun capability(
        provider: String,
        model: String,
        efforts: Set<String>,
        reasoning: Boolean = true,
    ) = ModelCapabilities(
        providerId = provider,
        modelId = model,
        displayName = model,
        attachment = null,
        reasoning = reasoning,
        toolCall = true,
        structuredOutput = null,
        temperature = null,
        inputModalities = setOf("text"),
        outputModalities = setOf("text"),
        reasoningEfforts = efforts,
        contextTokens = null,
        outputTokens = null,
        source = ModelCapabilitySource.MODELS_DEV,
    )
}
