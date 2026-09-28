package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlamaServerPolicyTest {
    private val modelId = "a".repeat(64)

    @Test fun managedProviderIsLoopbackBearerAndExactModel() {
        val provider = LocalLlamaServerPolicy.provider(49123, modelId, "/droide-abcdefghijkl")
        assertEquals(LocalLlamaServerPolicy.PROVIDER_ID, provider.id)
        assertEquals("http://127.0.0.1:49123/droide-abcdefghijkl/v1", provider.baseUrl)
        assertEquals(modelId, provider.model)
        assertEquals(ProviderNetworkScope.LOOPBACK_ONLY, provider.networkScope)
        assertEquals(ProviderAuthScheme.BEARER, provider.authScheme)
        assertTrue(provider.needsKey)
        assertTrue(provider.configuredModels.isEmpty())
    }

    @Test fun serverCommandIsSingleSlotLoopbackAndContainsNoSecret() {
        val argv = LocalLlamaServerPolicy.commandArgv(
            serverPath = "/data/local/tmp/droide/runtime/llama-server",
            remoteModelPath = "/data/local/tmp/droide/models/$modelId.gguf",
            remotePort = 41234,
            modelId = modelId,
            threads = 2,
            apiPrefix = "/droide-abcdefghijkl",
        )
        assertTrue(argv.windowed(2).any { it == listOf("--host", "127.0.0.1") })
        assertTrue(argv.windowed(2).any { it == listOf("--port", "41234") })
        assertTrue(argv.windowed(2).any { it == listOf("--alias", modelId) })
        assertTrue(argv.windowed(2).any { it == listOf("-c", "4096") })
        assertTrue(argv.windowed(2).any { it == listOf("-np", "1") })
        assertTrue(argv.windowed(2).any { it == listOf("-ngl", "0") })
        assertTrue("--no-ui" in argv)
        assertTrue("--no-slots" in argv)
        assertTrue("--jinja" in argv)
        assertTrue(argv.windowed(2).any { it == listOf("--api-prefix", "/droide-abcdefghijkl") })
        assertFalse(argv.any { it.contains("api-key", ignoreCase = true) })
    }

    @Test fun servingHeadroomAddsReserveBeyondCertification() {
        val memory = LocalLlamaInferencePolicy.MemorySnapshot(
            totalBytes = 8L * 1024 * 1024 * 1024,
            availableBytes = 5L * 1024 * 1024 * 1024,
            cpuCount = 8,
        )
        val plan = LocalLlamaInferencePolicy.plan(2L * 1024 * 1024 * 1024, memory)
        val required = LocalLlamaServerPolicy.requireServingHeadroom(memory, plan)
        assertEquals(plan.requiredAvailableBytes + 512L * 1024 * 1024, required)
    }

    @Test fun insufficientServingHeadroomFailsClosed() {
        val memory = LocalLlamaInferencePolicy.MemorySnapshot(
            totalBytes = 8L * 1024 * 1024 * 1024,
            availableBytes = 4L * 1024 * 1024 * 1024,
            cpuCount = 8,
        )
        val plan = LocalLlamaInferencePolicy.plan(2L * 1024 * 1024 * 1024, memory)
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlamaServerPolicy.requireServingHeadroom(
                memory.copy(availableBytes = plan.requiredAvailableBytes),
                plan,
            )
        }
    }

    @Test fun runtimeCapabilityDisablesToolsUntilSeparatelyCertified() {
        val capability = LocalLlamaServerPolicy.runtimeCapability(modelId, "Local model")
        assertEquals(ModelCapabilitySource.RUNTIME_PROVIDER, capability.source)
        assertEquals(4096, capability.contextTokens)
        assertEquals(false, capability.toolCall)
        assertEquals(setOf("text"), capability.inputModalities)
        assertEquals(setOf("text"), capability.outputModalities)

        val certified = LocalLlamaServerPolicy.runtimeCapability(modelId, "Local model", toolCall = true)
        assertEquals(true, certified.toolCall)
    }
}
