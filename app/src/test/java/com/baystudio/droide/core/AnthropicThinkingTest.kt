package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicThinkingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun currentClaudeUsesAdaptiveThinkingWhileOlderClaudeUsesManualBudget() {
        assertEquals(
            ReasoningWire.AnthropicAdaptive("high"),
            ReasoningSupport.wire("anthropic", "claude-opus-4-8", ReasoningEffort.HIGH),
        )
        assertEquals(
            ReasoningWire.AnthropicManual(8_192),
            ReasoningSupport.wire("anthropic", "claude-sonnet-4-5-20250929", ReasoningEffort.HIGH),
        )
        assertNull(ReasoningSupport.wire("anthropic", "claude-future-unknown", ReasoningEffort.HIGH))
    }

    @Test fun anthropicEncoderSendsAdaptiveThinkingAndDropsSamplingControls() {
        val source = """{
          "model":"claude-opus-4-8",
          "thinking":{"type":"adaptive"},
          "output_config":{"effort":"high"},
          "temperature":0.2,
          "top_p":0.8,
          "messages":[{"role":"user","content":"hello"}]
        }"""
        val encoded = json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(source, false, json)).jsonObject
        assertEquals("adaptive", encoded.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("high", encoded.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
        assertFalse("temperature" in encoded)
        assertFalse("top_p" in encoded)
    }

    @Test fun manualThinkingRaisesMaxTokensAboveBudget() {
        val source = """{
          "model":"claude-sonnet-4-5-20250929",
          "thinking":{"type":"enabled","budget_tokens":8192},
          "messages":[{"role":"user","content":"hello"}]
        }"""
        val encoded = json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(source, false, json)).jsonObject
        assertTrue(encoded.getValue("max_tokens").jsonPrimitive.content.toInt() > 8192)
    }

    @Test fun bufferedToolTurnPreservesThinkingPrivatelyAndReplaysItExactly() {
        val providerResponse = """{
          "id":"msg_1","model":"claude-opus-4-8","stop_reason":"tool_use",
          "content":[
            {"type":"thinking","thinking":"private plan","signature":"sig-1"},
            {"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"README.md"}}
          ],
          "usage":{"input_tokens":10,"output_tokens":20}
        }"""
        val normalized = json.parseToJsonElement(AnthropicMessagesAdapter.normalizeBuffered(providerResponse, json)).jsonObject
        val message = normalized.getValue("choices").jsonArray[0].jsonObject.getValue("message").jsonObject
        assertEquals("", message.getValue("content").jsonPrimitive.content)
        val privateBlocks = message[AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD] as? JsonArray
        assertNotNull(privateBlocks)
        assertEquals("private plan", privateBlocks!![0].jsonObject.getValue("thinking").jsonPrimitive.content)

        val continuation = JsonObject(mapOf(
            "model" to json.parseToJsonElement("\"claude-opus-4-8\""),
            "messages" to JsonArray(listOf(
                JsonObject(mapOf(
                    "role" to json.parseToJsonElement("\"assistant\""),
                    "content" to message.getValue("content"),
                    "tool_calls" to message.getValue("tool_calls"),
                    AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD to privateBlocks,
                )),
                json.parseToJsonElement("""{"role":"tool","tool_call_id":"toolu_1","content":"ok"}""").jsonObject,
            )),
        )).toString()
        val replay = json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(continuation, false, json)).jsonObject
        val replayedAssistant = replay.getValue("messages").jsonArray[0].jsonObject
        assertEquals(privateBlocks, replayedAssistant.getValue("content"))
        assertFalse(AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD in replayedAssistant)
    }

    @Test fun tamperedPrivateToolPayloadFailsClosed() {
        val body = """{
          "model":"claude-opus-4-8",
          "messages":[{
            "role":"assistant","content":"",
            "tool_calls":[{"id":"toolu_1","type":"function","function":{"name":"read_file","arguments":"{\\\"path\\\":\\\"README.md\\\"}"}}],
            "${AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD}":[
              {"type":"thinking","thinking":"private","signature":"sig"},
              {"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"CHANGED.md"}}
            ]
          }]
        }"""
        assertThrows(IllegalArgumentException::class.java) {
            AnthropicMessagesAdapter.encodeRequest(body, false, json)
        }
    }

    @Test fun streamedThinkingNeverBecomesVisibleStreamTextButIsRetainedForToolContinuation() {
        val acc = AnthropicMessagesAdapter.StreamAccumulator(json)
        assertTrue(acc.accept("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""").isEmpty())
        assertTrue(acc.accept("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"secret"}}""").isEmpty())
        assertTrue(acc.accept("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-stream"}}""").isEmpty())
        acc.accept("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_2","name":"read_file","input":{}}}""")
        acc.accept("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"README.md\"}"}}""")
        acc.accept("""{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":30}}""")
        val normalized = json.parseToJsonElement(acc.completedResponse()).jsonObject
        val message = normalized.getValue("choices").jsonArray[0].jsonObject.getValue("message").jsonObject
        assertEquals("", message.getValue("content").jsonPrimitive.content)
        val privateBlocks = message.getValue(AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD).jsonArray
        assertEquals("secret", privateBlocks[0].jsonObject.getValue("thinking").jsonPrimitive.content)
        assertEquals("sig-stream", privateBlocks[0].jsonObject.getValue("signature").jsonPrimitive.content)
    }
}
