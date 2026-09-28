package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

 
internal object ProviderModelRequestIdentity {
    data class CanonicalRequest(
        val modelId: String,
        val body: String,
    )

    fun canonicalize(body: String, json: Json): CanonicalRequest {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("LLM request body must be a JSON object")
        val raw = root["model"] as? JsonPrimitive
            ?: throw IllegalArgumentException("Provider request is missing model")
        require(raw.isString) { "Provider request model must be a string" }
        val canonical = ProviderModelIdentity.normalize(raw.content)
        if (raw.content == canonical) return CanonicalRequest(canonical, body)
        val next = root.toMutableMap().apply { put("model", JsonPrimitive(canonical)) }
        return CanonicalRequest(canonical, JsonObject(next).toString())
    }
}
