package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LspCompletionContractTest {
    @Test fun initializeNegotiatesCompletionTriggerCharacters() {
        val result = Json.parseToJsonElement(
            """{"capabilities":{"positionEncoding":"utf-16","completionProvider":{"triggerCharacters":[".",":","->"]}}}"""
        )
        val capabilities = LspNegotiatedCapabilities.fromInitialize(result)
        assertTrue(capabilities.completion)
        assertEquals(setOf(".", ":", "->"), capabilities.completionTriggerCharacters)
    }

    @Test fun snippetSupportIsAdvertisedAfterRealTabStopEngineLands() {
        val completion = LspProtocolContract.clientCapabilities()["textDocument"]
            ?.let { it as kotlinx.serialization.json.JsonObject }?.get("completion")
            ?.let { it as kotlinx.serialization.json.JsonObject }?.get("completionItem")
            ?.let { it as kotlinx.serialization.json.JsonObject }
        assertTrue(completion?.get("snippetSupport")?.toString()?.toBoolean() == true)
    }
}
