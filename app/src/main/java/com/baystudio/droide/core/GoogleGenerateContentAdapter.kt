package com.baystudio.droide.core

import kotlinx.serialization.json.*

 
internal object GoogleGenerateContentAdapter {
    fun modelFromBody(body: String, json: Json): String {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("LLM request body must be a JSON object")
        return normalizeModel(root["model"]?.jsonPrimitive?.contentOrNull)
    }

    fun requestUrl(baseUrl: String, model: String, streaming: Boolean): String {
        val safe = normalizeModel(model)
        val action = if (streaming) "streamGenerateContent?alt=sse" else "generateContent"
        return "${baseUrl.trimEnd('/')}/models/$safe:$action"
    }

    fun encodeRequest(body: String, json: Json): String {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("LLM request body must be a JSON object")
        val messages = root["messages"] as? JsonArray ?: JsonArray(emptyList())
        val callNames = toolCallNames(messages)
        return buildJsonObject {
            systemInstruction(messages)?.let { put("systemInstruction", it) }
            put("contents", convertMessages(messages, callNames, json))
            convertTools(root["tools"] as? JsonArray)?.let { put("tools", it) }
            val generation = buildJsonObject {
                root["max_tokens"]?.let { put("maxOutputTokens", it) }
                root["temperature"]?.let { put("temperature", it) }
                root["top_p"]?.let { put("topP", it) }
                root["reasoning_effort"]?.jsonPrimitive?.contentOrNull?.let { effort ->
                    put("thinkingConfig", googleThinkingConfig(modelFromBody(body, json), effort))
                }
            }
            if (generation.isNotEmpty()) put("generationConfig", generation)
        }.toString()
    }


    private fun googleThinkingConfig(model: String, rawEffort: String): JsonObject {
        val effort = rawEffort.trim().lowercase()
        val id = model.lowercase()
        return if ("gemini-2.5" in id) {
            val budget = when (effort) {
                "low" -> 1_024
                "high" -> 24_576
                else -> throw IllegalArgumentException("Unsupported Gemini 2.5 reasoning effort: $rawEffort")
            }
            buildJsonObject { put("includeThoughts", false); put("thinkingBudget", budget) }
        } else {
            require(effort == "low" || effort == "high") {
                "Native Gemini thinkingLevel does not support reasoning effort: $rawEffort"
            }
            buildJsonObject { put("includeThoughts", false); put("thinkingLevel", effort) }
        }
    }

    fun normalizeBuffered(body: String, json: Json): String {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalStateException("Malformed Google GenerateContent response")
        root["error"]?.let { throw IllegalStateException("Provider response error: ${it.toString().take(500)}") }
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        return normalized(candidate, root["usageMetadata"] as? JsonObject)
    }

    class StreamAccumulator(private val json: Json) {
        private val text = StringBuilder()
        private val calls = mutableListOf<JsonObject>()
        private val seenCalls = hashSetOf<String>()
        private var finishReason: String? = null
        private var usage: JsonObject? = null

        fun accept(payload: String): List<LlmStreamEvent> {
            val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
                ?: throw IllegalStateException("Invalid Gemini SSE JSON")
            root["error"]?.let { throw IllegalStateException("Provider stream error: ${it.toString().take(500)}") }
            (root["usageMetadata"] as? JsonObject)?.let { usage = it }
            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
            candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
            val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
            val events = mutableListOf<LlmStreamEvent>()
            parts.forEach { raw ->
                val part = raw as? JsonObject ?: return@forEach
                part["text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let { delta ->
                    text.append(delta); events += LlmStreamEvent.TextDelta(delta)
                }
                val fc = part["functionCall"] as? JsonObject ?: return@forEach
                val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val id = fc["id"]?.jsonPrimitive?.contentOrNull ?: "gemini_tool_${calls.size}"
                val args = (fc["args"] as? JsonObject ?: buildJsonObject {}).toString()
                val key = "$id\u0000$name\u0000$args"
                if (seenCalls.add(key)) {
                    val index = calls.size
                    calls += openAiToolCall(index, id, name, args)
                    events += LlmStreamEvent.ToolCallDelta(index, id, name, args)
                }
            }
            return events
        }

        fun completedResponse(): String = normalizedFromParts(text.toString(), JsonArray(calls), finishReason, usage)
    }

    private fun normalizeModel(raw: String?): String {
        val value = raw?.removePrefix("models/")?.trim().orEmpty()
        require(value.matches(Regex("[A-Za-z0-9._:-]{1,200}"))) { "Invalid Gemini model ID" }
        return value
    }

    private fun systemInstruction(messages: JsonArray): JsonObject? {
        val texts = messages.mapNotNull { raw ->
            val msg = raw as? JsonObject ?: return@mapNotNull null
            if (msg["role"]?.jsonPrimitive?.contentOrNull != "system") return@mapNotNull null
            extractText(msg["content"])
        }.filter(String::isNotBlank)
        if (texts.isEmpty()) return null
        return buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", texts.joinToString("\n\n")) }) }) }
    }

    private fun toolCallNames(messages: JsonArray): Map<String, String> = buildMap {
        messages.forEach { raw ->
            val msg = raw as? JsonObject ?: return@forEach
            (msg["tool_calls"] as? JsonArray)?.forEach { callRaw ->
                val call = callRaw as? JsonObject ?: return@forEach
                val id = call["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = ((call["function"] as? JsonObject)?.get("name"))?.jsonPrimitive?.contentOrNull ?: return@forEach
                put(id, name)
            }
        }
    }

    private fun convertMessages(messages: JsonArray, callNames: Map<String, String>, json: Json): JsonArray = buildJsonArray {
        messages.forEach { raw ->
            val msg = raw as? JsonObject ?: return@forEach
            when (msg["role"]?.jsonPrimitive?.contentOrNull) {
                "system" -> Unit
                "user" -> add(buildJsonObject { put("role", "user"); put("parts", contentParts(msg["content"], true)) })
                "assistant" -> add(buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray {
                        contentParts(msg["content"], false).forEach(::add)
                        (msg["tool_calls"] as? JsonArray)?.forEachIndexed { index, callRaw ->
                            val call = callRaw as? JsonObject ?: return@forEachIndexed
                            val fn = call["function"] as? JsonObject ?: return@forEachIndexed
                            val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                            val argsRaw = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                            val args = runCatching { json.parseToJsonElement(argsRaw) as? JsonObject }.getOrNull()
                                ?: throw IllegalArgumentException("Gemini tool input must be a JSON object")
                            add(buildJsonObject { put("functionCall", buildJsonObject {
                                put("id", call["id"]?.jsonPrimitive?.contentOrNull ?: "gemini_tool_$index")
                                put("name", name); put("args", args)
                            }) })
                        }
                    })
                })
                "tool" -> {
                    val id = msg["tool_call_id"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Gemini tool result is missing tool_call_id")
                    val name = callNames[id] ?: throw IllegalArgumentException("Gemini tool result has unknown tool_call_id")
                    val output = msg["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    add(buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject {
                        put("functionResponse", buildJsonObject {
                            put("id", id); put("name", name); put("response", buildJsonObject { put("output", output) })
                        })
                    }) }) })
                }
                else -> throw IllegalArgumentException("Unsupported message role for Gemini")
            }
        }
    }

    private fun contentParts(content: JsonElement?, allowImages: Boolean): JsonArray = buildJsonArray {
        when (content) {
            is JsonPrimitive -> if (content.content.isNotEmpty()) add(buildJsonObject { put("text", content.content) })
            is JsonArray -> content.forEach { raw ->
                val part = raw as? JsonObject ?: return@forEach
                when (part["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> add(buildJsonObject { put("text", part["text"]?.jsonPrimitive?.contentOrNull.orEmpty()) })
                    "image_url" -> {
                        require(allowImages) { "Images are only supported in user messages" }
                        add(convertImage(part))
                    }
                    else -> throw IllegalArgumentException("Unsupported multimodal content block for Gemini")
                }
            }
            null -> Unit
            else -> throw IllegalArgumentException("Unsupported message content for Gemini")
        }
    }

    private fun convertImage(part: JsonObject): JsonObject {
        val url = (part["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Gemini image block is missing URL")
        val match = DATA_IMAGE.matchEntire(url) ?: throw IllegalArgumentException("Gemini image input requires a staged data image")
        return buildJsonObject { put("inlineData", buildJsonObject {
            put("mimeType", match.groupValues[1]); put("data", match.groupValues[2])
        }) }
    }

    private fun convertTools(tools: JsonArray?): JsonArray? {
        if (tools == null || tools.isEmpty()) return null
        val declarations = buildJsonArray {
            tools.forEach { raw ->
                val fn = ((raw as? JsonObject)?.get("function") as? JsonObject) ?: return@forEach
                val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                add(buildJsonObject {
                    put("name", name); fn["description"]?.let { put("description", it) }
                    put("parameters", fn["parameters"] ?: buildJsonObject { put("type", "object") })
                })
            }
        }
        return if (declarations.isEmpty()) null else buildJsonArray { add(buildJsonObject { put("functionDeclarations", declarations) }) }
    }

    private fun extractText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.mapNotNull { raw ->
            val obj = raw as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") obj["text"]?.jsonPrimitive?.contentOrNull else null
        }.joinToString("\n")
        else -> ""
    }

    private fun normalized(candidate: JsonObject?, usage: JsonObject?): String {
        val parts = (((candidate?.get("content") as? JsonObject)?.get("parts")) as? JsonArray).orEmpty()
        val text = buildString { parts.forEach { raw -> (raw as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull?.let(::append) } }
        val tools = buildJsonArray {
            parts.forEachIndexed { index, raw ->
                val fc = (raw as? JsonObject)?.get("functionCall") as? JsonObject ?: return@forEachIndexed
                val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                val id = fc["id"]?.jsonPrimitive?.contentOrNull ?: "gemini_tool_$index"
                add(openAiToolCall(index, id, name, (fc["args"] as? JsonObject ?: buildJsonObject {}).toString()))
            }
        }
        return normalizedFromParts(text, tools, candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull, usage)
    }

    private fun normalizedFromParts(text: String, tools: JsonArray, stop: String?, usage: JsonObject?): String = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("index", 0); put("message", buildJsonObject {
                put("role", "assistant"); put("content", text); if (tools.isNotEmpty()) put("tool_calls", tools)
            })
            put("finish_reason", when {
                tools.isNotEmpty() -> JsonPrimitive("tool_calls")
                stop == "MAX_TOKENS" -> JsonPrimitive("length")
                stop == null -> JsonNull
                else -> JsonPrimitive("stop")
            })
        }) })
        usage?.let { put("usage", normalizeUsage(it)) }
    }.toString()

    private fun normalizeUsage(usage: JsonObject): JsonObject = buildJsonObject {
        usage["promptTokenCount"]?.let { put("prompt_tokens", it) }
        usage["candidatesTokenCount"]?.let { put("completion_tokens", it) }
        usage["totalTokenCount"]?.let { put("total_tokens", it) }
        usage["cachedContentTokenCount"]?.let { cached -> put("prompt_tokens_details", buildJsonObject { put("cached_tokens", cached) }) }
    }

    private fun openAiToolCall(index: Int, id: String, name: String, arguments: String): JsonObject = buildJsonObject {
        put("index", index); put("id", id); put("type", "function")
        put("function", buildJsonObject { put("name", name); put("arguments", arguments) })
    }

    private val DATA_IMAGE = Regex("^data:(image/(?:png|jpeg|gif|webp));base64,([A-Za-z0-9+/=]+)$")
}
