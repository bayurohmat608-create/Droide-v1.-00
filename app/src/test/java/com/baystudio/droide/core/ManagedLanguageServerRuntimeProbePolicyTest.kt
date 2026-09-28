package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLanguageServerRuntimeProbePolicyTest {
    private val json = Json

    @Test
    fun acceptsUsefulCodeIntelligenceCapabilities() {
        val result = json.parseToJsonElement(
            """{"capabilities":{"positionEncoding":"utf-16","completionProvider":{},"hoverProvider":true,"signatureHelpProvider":{}}}"""
        )
        assertEquals(
            linkedSetOf("completion", "signatureHelp", "hover"),
            ManagedLanguageServerProbePolicy.validateInitialize(result),
        )
    }

    @Test
    fun rejectsServerWithNoUsableCodeIntelligenceCapability() {
        val result = json.parseToJsonElement(
            """{"capabilities":{"positionEncoding":"utf-16","textDocumentSync":2}}"""
        )
        val failure = runCatching { ManagedLanguageServerProbePolicy.validateInitialize(result) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("no Droide-usable code-intelligence capability"))
    }

    @Test
    fun rejectsUnsupportedPositionEncoding() {
        val result = json.parseToJsonElement(
            """{"capabilities":{"positionEncoding":"utf-8","completionProvider":{}}}"""
        )
        val failure = runCatching { ManagedLanguageServerProbePolicy.validateInitialize(result) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("unsupported position encoding"))
    }
}
