package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlamaToolCapabilityPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val modelId = "a".repeat(64)

    @Test fun serverFactsBindBuildTemplateAndCapabilities() {
        val props = json.parseToJsonElement(
            """{"build_info":"b10964-deadbeef","chat_template":"{{ messages }}","chat_template_caps":{"tools":true,"reasoning":false}}"""
        ) as JsonObject
        val facts = LocalLlamaToolCapabilityPolicy.serverFacts(
            props,
            targetAbi = "arm64-v8a",
            targetApi = 36,
            targetEnvironmentSha256 = "b".repeat(64),
        )
        assertEquals("b10964-deadbeef", facts.buildInfo)
        assertTrue(facts.chatTemplateSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(facts.chatTemplateCapsSha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals("arm64-v8a", facts.targetAbi)
        assertEquals(36, facts.targetApi)
        assertEquals("b".repeat(64), facts.targetEnvironmentSha256)
    }

    @Test fun requiredAndAutoProbesAreSingleToolAndNonParallel() {
        val required = LocalLlamaToolCapabilityPolicy.requiredProbeBody(modelId, "nonce-1")
        val auto = LocalLlamaToolCapabilityPolicy.autonomousProbeBody(modelId, "nonce-2")
        assertTrue(required.contains("\"tool_choice\":\"required\""))
        assertTrue(auto.contains("\"tool_choice\":\"auto\""))
        assertTrue(required.contains("\"parallel_tool_calls\":false"))
        assertTrue(required.contains(LocalLlamaToolCapabilityPolicy.TOOL_NAME))
        assertTrue(auto.contains(LocalLlamaToolCapabilityPolicy.DECOY_TOOL_NAME))
    }

    @Test fun exactToolCallIsAcceptedAndWrongNonceRejected() {
        val response = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_123","type":"function","function":{"name":"droide_echo_probe","arguments":"{\"nonce\":\"nonce-1\"}"}}]}}]}"""
        val call = LocalLlamaToolCapabilityPolicy.requireSingleToolCall(response, "nonce-1")
        assertEquals("call_123", call.id)
        assertEquals("droide_echo_probe", call.name)
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlamaToolCapabilityPolicy.requireSingleToolCall(response, "wrong")
        }
    }

    @Test fun continuationMustConsumeExactToolResultWithoutRecursiveCall() {
        val ok = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"droide_result_abc"}}]}"""
        LocalLlamaToolCapabilityPolicy.requireContinuation(ok, "droide_result_abc")
        val recursive = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"again","type":"function","function":{"name":"droide_echo_probe","arguments":"{}"}}]}}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlamaToolCapabilityPolicy.requireContinuation(recursive, "droide_result_abc")
        }
    }

    @Test fun continuationBodyCarriesMatchingToolCallIdAndDisablesParallelTools() {
        val call = LocalLlamaToolCapabilityPolicy.ParsedToolCall(
            id = "call_abc",
            name = LocalLlamaToolCapabilityPolicy.TOOL_NAME,
            arguments = json.parseToJsonElement("{\"nonce\":\"n\"}") as JsonObject,
        )
        val body = LocalLlamaToolCapabilityPolicy.continuationProbeBody(modelId, "n", call, "result-x")
        assertTrue(body.contains("\"tool_call_id\":\"call_abc\""))
        assertTrue(body.contains("\\\"result\\\":\\\"result-x\\\""))
        assertTrue(body.contains("\"parallel_tool_calls\":false"))
        assertFalse(body.contains("droide_result_abc"))
    }
}
