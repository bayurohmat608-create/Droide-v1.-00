package com.baystudio.droide.core

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject










object LocalLlamaToolCapabilityPolicy {
    const val POLICY_VERSION = 1
    const val PROBE_MAX_TOKENS = 96
    const val PROBE_TIMEOUT_MS = 2L * 60L * 1000L
    const val MAX_RESPONSE_CHARS = 256 * 1024
    const val TOOL_NAME = "droide_echo_probe"
    const val DECOY_TOOL_NAME = "droide_decoy_probe"

    private val json = Json { ignoreUnknownKeys = true }

    data class ServerFacts(
        val buildInfo: String,
        val chatTemplateSha256: String,
        val chatTemplateCapsSha256: String,
        val targetAbi: String,
        val targetApi: Int,
        val targetEnvironmentSha256: String,
    ) {
        init {
            require(buildInfo.isNotBlank() && buildInfo.length <= 256) { "Invalid llama.cpp build identity" }
            require(chatTemplateSha256.matches(SHA256)) { "Invalid chat-template digest" }
            require(chatTemplateCapsSha256.matches(SHA256)) { "Invalid chat-template capability digest" }
            require(targetAbi == "arm64-v8a") { "Tool certification requires the reviewed Android arm64 target" }
            require(targetApi in 28..99) { "Invalid Android target API" }
            require(targetEnvironmentSha256.matches(SHA256)) { "Invalid target-environment digest" }
        }
    }

    data class ParsedToolCall(
        val id: String,
        val name: String,
        val arguments: JsonObject,
    )

    fun serverFacts(
        props: JsonObject,
        targetAbi: String,
        targetApi: Int,
        targetEnvironmentSha256: String,
    ): ServerFacts {
        val buildInfo = props["build_info"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val template = props["chat_template"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(template.isNotBlank() && template.length <= MAX_TEMPLATE_CHARS) {
            "Local llama.cpp did not expose a bounded chat template"
        }
        val caps = props["chat_template_caps"] ?: JsonObject(emptyMap())
        return ServerFacts(
            buildInfo = buildInfo,
            chatTemplateSha256 = sha256(template.toByteArray(Charsets.UTF_8)),
            chatTemplateCapsSha256 = sha256(canonicalJson(caps).toByteArray(Charsets.UTF_8)),
            targetAbi = targetAbi,
            targetApi = targetApi,
            targetEnvironmentSha256 = targetEnvironmentSha256,
        )
    }

    fun requiredProbeBody(modelId: String, nonce: String): String = baseBody(modelId) {
        put("tool_choice", "required")
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                put("content", "Call $TOOL_NAME exactly once with nonce '$nonce'. Do not answer in text.")
            })
        }
        put("tools", probeTools())
    }.toString()

    fun autonomousProbeBody(modelId: String, nonce: String): String = baseBody(modelId) {
        put("tool_choice", "auto")
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                put(
                    "content",
                    "Use the tool named $TOOL_NAME exactly once and pass nonce '$nonce'. " +
                        "Do not use $DECOY_TOOL_NAME and do not answer in text.",
                )
            })
        }
        put("tools", probeTools())
    }.toString()

    fun continuationProbeBody(
        modelId: String,
        nonce: String,
        call: ParsedToolCall,
        resultCanary: String,
    ): String = baseBody(modelId) {
        put("tool_choice", "auto")
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                put("content", "Call $TOOL_NAME exactly once with nonce '$nonce'.")
            })
            add(buildJsonObject {
                put("role", "assistant")
                put("content", JsonNull)
                putJsonArray("tool_calls") {
                    add(buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", call.name)
                            put("arguments", call.arguments.toString())
                        }
                    })
                }
            })
            add(buildJsonObject {
                put("role", "tool")
                put("tool_call_id", call.id)
                put("name", call.name)
                put("content", buildJsonObject { put("result", resultCanary) }.toString())
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", "Reply with exactly the tool result value and nothing else.")
            })
        }
        put("tools", probeTools())
    }.toString()

    fun requireSingleToolCall(response: String, expectedNonce: String): ParsedToolCall {
        require(response.length <= MAX_RESPONSE_CHARS) { "Tool probe response is too large" }
        val root = json.parseToJsonElement(response) as? JsonObject ?: error("Tool probe returned invalid JSON")
        val choices = root["choices"] as? JsonArray ?: error("Tool probe omitted choices")
        require(choices.size == 1) { "Tool probe must return exactly one choice" }
        val choice = choices.single().jsonObject
        val message = choice["message"] as? JsonObject ?: error("Tool probe omitted assistant message")
        val calls = message["tool_calls"] as? JsonArray ?: error("Tool probe omitted tool_calls")
        require(calls.size == 1) { "Tool probe must return exactly one tool call" }
        val raw = calls.single().jsonObject
        val id = raw["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(id.matches(TOOL_CALL_ID)) { "Tool probe returned an invalid tool-call id" }
        require(raw["type"]?.jsonPrimitive?.contentOrNull == "function") { "Tool probe returned a non-function call" }
        val function = raw["function"] as? JsonObject ?: error("Tool probe omitted function payload")
        val name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(name == TOOL_NAME) { "Tool probe selected the wrong tool" }
        val argsText = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(argsText.length <= MAX_ARGUMENT_CHARS) { "Tool probe arguments are too large" }
        val args = json.parseToJsonElement(argsText) as? JsonObject ?: error("Tool probe arguments are not a JSON object")
        require(args.keys == setOf("nonce")) { "Tool probe returned unexpected arguments" }
        require(args["nonce"]?.jsonPrimitive?.contentOrNull == expectedNonce) { "Tool probe nonce mismatch" }
        return ParsedToolCall(id = id, name = name, arguments = args)
    }

    fun requireContinuation(response: String, expectedResult: String) {
        require(response.length <= MAX_RESPONSE_CHARS) { "Tool continuation response is too large" }
        val root = json.parseToJsonElement(response) as? JsonObject ?: error("Tool continuation returned invalid JSON")
        val choices = root["choices"] as? JsonArray ?: error("Tool continuation omitted choices")
        require(choices.size == 1) { "Tool continuation must return exactly one choice" }
        val message = choices.single().jsonObject["message"] as? JsonObject
            ?: error("Tool continuation omitted assistant message")
        val calls = message["tool_calls"] as? JsonArray
        require(calls == null || calls.isEmpty()) { "Tool continuation recursively requested another tool" }
        val content = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        require(content == expectedResult) { "Tool continuation did not consume the exact tool result" }
    }

    private fun baseBody(modelId: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject {
        require(modelId.matches(SHA256)) { "Invalid local GGUF model id" }
        return buildJsonObject {
            put("model", modelId)
            put("max_tokens", PROBE_MAX_TOKENS)
            put("temperature", 0)
            put("stream", false)
            put("parallel_tool_calls", false)
            put("reasoning_effort", "none")
            extra()
        }
    }

    private fun probeTools(): JsonArray = buildJsonArray {
        add(toolDefinition(TOOL_NAME, "Return the exact nonce supplied by the caller.", "nonce", "string"))
        add(toolDefinition(DECOY_TOOL_NAME, "Decoy tool that must not be selected.", "value", "integer"))
    }

    private fun toolDefinition(name: String, description: String, property: String, type: String): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject(property) { put("type", type) }
                }
                putJsonArray("required") { add(JsonPrimitive(property)) }
                put("additionalProperties", false)
            }
        }
    }

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            JsonPrimitive(key).toString() + ":" + canonicalJson(value)
        }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
        else -> element.toString()
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val TOOL_CALL_ID = Regex("[A-Za-z0-9._:-]{1,160}")
    private const val MAX_ARGUMENT_CHARS = 8_192
    private const val MAX_TEMPLATE_CHARS = 256 * 1024
}
