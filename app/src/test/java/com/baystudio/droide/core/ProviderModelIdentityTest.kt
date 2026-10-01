package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelIdentityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun canonicalizesWhitespaceBeforeWireSerialization() {
        val request = ProviderModelRequestIdentity.canonicalize(
            """{"model":"  vendor/model-v1  ","messages":[]}""",
            json,
        )
        assertEquals("vendor/model-v1", request.modelId)
        assertEquals(
            "vendor/model-v1",
            json.parseToJsonElement(request.body).jsonObject["model"]!!.jsonPrimitive.content,
        )
    }

    @Test fun rejectsMissingNullNonStringAndControlModelIds() {
        listOf(
            """{"messages":[]}""",
            """{"model":null,"messages":[]}""",
            """{"model":123,"messages":[]}""",
            """{"model":"bad\\u000amodel","messages":[]}""",
        ).forEach { body ->
            assertTrue(runCatching { ProviderModelRequestIdentity.canonicalize(body, json) }.isFailure)
        }
    }

    @Test fun certificationRequiresExactCanonicalIdentity() {
        val provider = AiProvider(
            id = "custom.identity-test",
            name = "Identity test",
            baseUrl = "https://example.invalid/v1",
            model = "",
            free = true,
            needsKey = false,
            helpUrl = "",
            note = "test",
        )
        ProviderRegistry.installConfigured(listOf(provider))
        ProviderModelCertificationRegistry.certify(provider, listOf("  model-a  "))
        assertTrue(ProviderModelCertificationRegistry.isCertified(provider, "model-a"))
        assertFalse(ProviderModelCertificationRegistry.isCertified(provider, " model-a"))
        assertFalse(ProviderModelCertificationRegistry.isCertified(provider, "model-a "))
    }

    @Test fun canonicalLimitIsSharedAndNonTruncating() {
        val exact = "m".repeat(ProviderModelIdentity.MAX_MODEL_ID_CHARS)
        assertEquals(exact, ProviderModelIdentity.normalize(exact))
        assertTrue(runCatching { ProviderModelIdentity.normalize(exact + "x") }.isFailure)
    }
}
