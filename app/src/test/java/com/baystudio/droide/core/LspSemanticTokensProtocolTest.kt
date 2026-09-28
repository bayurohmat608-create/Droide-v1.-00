package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class LspSemanticTokensProtocolTest {
    @Test fun negotiatesFullProviderAndLegend() {
        val provider = Json.parseToJsonElement("""{
          "legend":{"tokenTypes":["class","parameter"],"tokenModifiers":["definition","deprecated"]},
          "full":true,"range":false
        }""").jsonObject
        assertTrue(LspSemanticTokensProtocol.supportsFull(provider))
        val legend = LspSemanticTokensProtocol.legendFromProvider(provider)
        assertEquals(listOf("class", "parameter"), legend.tokenTypes)
        assertEquals(listOf("definition", "deprecated"), legend.tokenModifiers)
    }

    @Test fun fullResponseRejectsOversizeBeforeDecode() {
        val legend = LspSemanticTokenLegend(listOf("variable"), emptyList())
        val data = buildString {
            append("{\"data\":[")
            repeat(LspSemanticTokenDecoder.MAX_TOKENS + 1) { index ->
                if (index > 0) append(',')
                append("0,1,1,0,0")
            }
            append("]}")
        }
        assertTrue(LspSemanticTokensProtocol.parseFull(Json.parseToJsonElement(data), legend).isEmpty())
    }
}
