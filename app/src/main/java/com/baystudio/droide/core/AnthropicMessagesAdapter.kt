package com.baystudio.droide.core

import kotlinx.serialization.json.*











internal object AnthropicMessagesAdapter {
    private const val DEFAULT_MAX_TOKENS = 8_192
    internal const val PRIVATE_CONTENT_FIELD = "_droide_anthropic_private_content"

    fun encodeRequest(body: String, streaming: Boolean, json: Json): String {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("LLM request body must be a JSON object")
        val sourceMessages = root["messages"] as? JsonArray ?: JsonArray(emptyList())
        val system = systemContent(sourceMessages)
        val messages = convertMessages(sourceMessages, json)
        val thinking = root["thinking"] as? JsonObject
        return buildJsonObject {
            put("model", root["model"] ?: throw IllegalArgumentException("Anthropic request requires model"))
            put("max_tokens", resolvedMaxTokens(root, thinking))
            if (system != null) put("system", system)
            put("messages", messages)
            convertTools(root["tools"] as? JsonArray)?.let { tools ->
                put("tools", tools)
                when (val choice = root["tool_choice"]) {
                    is JsonPrimitive -> if (choice.contentOrNull == "auto") {
                        put("tool_choice", buildJsonObject { put("type", "auto") })
                    }
                    is JsonObject -> put("tool_choice", choice)
                    else -> Unit
                }
            }
            thinking?.let { put("thinking", validateThinkingConfig(it)) }
            (root["output_config"] as? JsonObject)?.let { put("output_config", it) }
            

            if (thinking == null) {
                (root["temperature"] as? JsonPrimitive)?.let { put("temperature", it) }
                (root["top_p"] as? JsonPrimitive)?.let { put("top_p", it) }
            }
            put("stream", streaming)
        }.toString()
    }

    fun normalizeBuffered(body: String, json: Json): String {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalStateException("Malformed Anthropic response")
        root["error"]?.let { throw IllegalStateException("Provider response error: ${it.toString().take(500)}") }
        val content = root["content"] as? JsonArray ?: JsonArray(emptyList())
        val text = buildString {
            content.forEach { raw ->
                val block = raw as? JsonObject ?: return@forEach
                if (block["type"]?.jsonPrimitive?.contentOrNull == "text") {
                    append(block["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }
            }
        }
        val toolCalls = buildJsonArray {
            content.forEachIndexed { index, raw ->
                val block = raw as? JsonObject ?: return@forEachIndexed
                if (block["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return@forEachIndexed
                val id = block["id"]?.jsonPrimitive?.contentOrNull ?: "anthropic_tool_$index"
                val name = block["name"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                val input = block["input"] as? JsonObject ?: buildJsonObject {}
                add(openAiToolCall(index, id, name, input.toString()))
            }
        }
        return normalizedResponse(
            id = root["id"]?.jsonPrimitive?.contentOrNull,
            model = root["model"]?.jsonPrimitive?.contentOrNull,
            text = text,
            tools = toolCalls,
            stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull,
            usage = root["usage"] as? JsonObject,
            privateContent = privateContinuationContent(content, toolCalls, json),
        )
    }

    class StreamAccumulator(private val json: Json) {
        private data class ToolParts(
            var id: String = "",
            var name: String = "",
            val arguments: StringBuilder = StringBuilder(),
        )

        private data class RawBlock(
            var type: String = "",
            var id: String = "",
            var name: String = "",
            val text: StringBuilder = StringBuilder(),
            val arguments: StringBuilder = StringBuilder(),
            val thinking: StringBuilder = StringBuilder(),
            val signature: StringBuilder = StringBuilder(),
            var redactedData: String = "",
        )

        private val text = StringBuilder()
        private val rawBlocks = sortedMapOf<Int, RawBlock>()
        private val tools = sortedMapOf<Int, ToolParts>()
        private var responseId: String? = null
        private var model: String? = null
        private var stopReason: String? = null
        private var inputTokens = 0
        private var outputTokens = 0
        private var cacheReadTokens = 0
        private var cacheWriteTokens = 0
        private var usageSeen = false

        fun accept(payload: String): List<LlmStreamEvent> {
            val root = runCatching { json.parseToJsonElement(payload).jsonObject }
                .getOrElse { throw IllegalStateException("Invalid Anthropic SSE JSON") }
            if (root["type"]?.jsonPrimitive?.contentOrNull == "error") {
                throw IllegalStateException("Provider stream error: ${root["error"].toString().take(500)}")
            }
            when (root["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" -> {
                    val message = root["message"] as? JsonObject
                    responseId = message?.get("id")?.jsonPrimitive?.contentOrNull ?: responseId
                    model = message?.get("model")?.jsonPrimitive?.contentOrNull ?: model
                    captureUsage(message?.get("usage") as? JsonObject)
                }
                "content_block_start" -> {
                    val index = root["index"]?.jsonPrimitive?.intOrNull ?: rawBlocks.size
                    val block = root["content_block"] as? JsonObject ?: return emptyList()
                    val type = block["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val raw = rawBlocks.getOrPut(index) { RawBlock() }
                    raw.type = type
                    when (type) {
                        "text" -> raw.text.append(block["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        "thinking" -> {
                            raw.thinking.append(block["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty())
                            raw.signature.append(block["signature"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        }
                        "redacted_thinking" -> raw.redactedData = block["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        "tool_use" -> {
                            raw.id = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            raw.name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val initial = block["input"] as? JsonObject
                            if (initial != null && initial.isNotEmpty()) raw.arguments.append(initial.toString())
                            val part = tools.getOrPut(index) { ToolParts() }
                            part.id = raw.id
                            part.name = raw.name
                            if (initial != null && initial.isNotEmpty()) part.arguments.append(initial.toString())
                            return listOf(LlmStreamEvent.ToolCallDelta(index, part.id, part.name, null))
                        }
                    }
                }
                "content_block_delta" -> {
                    val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val delta = root["delta"] as? JsonObject ?: return emptyList()
                    return when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            val fragment = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (fragment.isEmpty()) emptyList() else {
                                text.append(fragment)
                                rawBlocks.getOrPut(index) { RawBlock(type = "text") }.text.append(fragment)
                                listOf(LlmStreamEvent.TextDelta(fragment))
                            }
                        }
                        "input_json_delta" -> {
                            val fragment = delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (fragment.isEmpty()) emptyList() else {
                                tools.getOrPut(index) { ToolParts() }.arguments.append(fragment)
                                rawBlocks.getOrPut(index) { RawBlock(type = "tool_use") }.arguments.append(fragment)
                                listOf(LlmStreamEvent.ToolCallDelta(index, null, null, fragment))
                            }
                        }
                        "thinking_delta" -> {
                            rawBlocks.getOrPut(index) { RawBlock(type = "thinking") }.thinking
                                .append(delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty())
                            emptyList()
                        }
                        "signature_delta" -> {
                            rawBlocks.getOrPut(index) { RawBlock(type = "thinking") }.signature
                                .append(delta["signature"]?.jsonPrimitive?.contentOrNull.orEmpty())
                            emptyList()
                        }
                        else -> emptyList() // private reasoning is retained for provider continuation, never rendered
                    }
                }
                "message_delta" -> {
                    val delta = root["delta"] as? JsonObject
                    stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull ?: stopReason
                    captureUsage(root["usage"] as? JsonObject)
                }
            }
            return emptyList()
        }

        fun completedResponse(): String {
            val calls = buildJsonArray {
                tools.forEach { (index, part) ->
                    add(openAiToolCall(index, part.id.ifBlank { "anthropic_tool_$index" }, part.name, part.arguments.toString().ifBlank { "{}" }))
                }
            }
            val usage = if (usageSeen) buildJsonObject {
                put("input_tokens", inputTokens)
                put("output_tokens", outputTokens)
                if (cacheReadTokens > 0) put("cache_read_input_tokens", cacheReadTokens)
                if (cacheWriteTokens > 0) put("cache_creation_input_tokens", cacheWriteTokens)
            } else null
            val rawContent = rawContinuationContent()
            return normalizedResponse(
                responseId, model, text.toString(), calls, stopReason, usage,
                privateContent = privateContinuationContent(rawContent, calls, json),
            )
        }

        private fun rawContinuationContent(): JsonArray = buildJsonArray {
            rawBlocks.forEach { (_, block) ->
                when (block.type) {
                    "text" -> add(buildJsonObject { put("type", "text"); put("text", block.text.toString()) })
                    "thinking" -> add(buildJsonObject {
                        put("type", "thinking")
                        put("thinking", block.thinking.toString())
                        put("signature", block.signature.toString())
                    })
                    "redacted_thinking" -> add(buildJsonObject {
                        put("type", "redacted_thinking"); put("data", block.redactedData)
                    })
                    "tool_use" -> {
                        val inputText = block.arguments.toString().ifBlank { "{}" }
                        val input = runCatching { json.parseToJsonElement(inputText) as? JsonObject }.getOrNull()
                            ?: throw IllegalStateException("Anthropic streamed tool input is not a JSON object")
                        add(buildJsonObject {
                            put("type", "tool_use"); put("id", block.id); put("name", block.name); put("input", input)
                        })
                    }
                }
            }
        }

        private fun captureUsage(usage: JsonObject?) {
            if (usage == null) return
            usageSeen = true
            usage["input_tokens"]?.jsonPrimitive?.intOrNull?.let { inputTokens = it }
            usage["output_tokens"]?.jsonPrimitive?.intOrNull?.let { outputTokens = it }
            usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull?.let { cacheReadTokens = it }
            usage["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull?.let { cacheWriteTokens = it }
        }
    }

    private fun systemContent(messages: JsonArray): JsonElement? {
        val blocks = buildJsonArray {
            messages.forEach { raw ->
                val msg = raw as? JsonObject ?: return@forEach
                if (msg["role"]?.jsonPrimitive?.contentOrNull != "system") return@forEach
                when (val content = msg["content"]) {
                    is JsonPrimitive -> add(buildJsonObject { put("type", "text"); put("text", content.content) })
                    is JsonArray -> content.forEach { part ->
                        val obj = part as? JsonObject ?: return@forEach
                        if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") add(obj)
                    }
                    else -> Unit
                }
            }
        }
        if (blocks.isEmpty()) return null
        if (blocks.size == 1) {
            val one = blocks.first() as? JsonObject
            if (one != null && "cache_control" !in one) return one["text"] ?: blocks
        }
        return blocks
    }

    private fun convertMessages(messages: JsonArray, json: Json): JsonArray {
        val out = mutableListOf<JsonObject>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i] as? JsonObject
            if (msg == null) { i++; continue }
            when (msg["role"]?.jsonPrimitive?.contentOrNull) {
                "system" -> i++
                "tool" -> {
                    val results = buildJsonArray {
                        while (i < messages.size) {
                            val tool = messages[i] as? JsonObject ?: break
                            if (tool["role"]?.jsonPrimitive?.contentOrNull != "tool") break
                            val callId = tool["tool_call_id"]?.jsonPrimitive?.contentOrNull
                                ?: throw IllegalArgumentException("Anthropic tool result is missing tool_call_id")
                            val content = tool["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", callId)
                                put("content", content)
                            })
                            i++
                        }
                    }
                    out += buildJsonObject { put("role", "user"); put("content", results) }
                }
                "assistant" -> {
                    val calls = msg["tool_calls"] as? JsonArray
                    val private = msg[PRIVATE_CONTENT_FIELD] as? JsonArray
                    val blocks = if (private != null) {
                        validatePrivateContinuation(private, calls, json)
                        private
                    } else buildJsonArray {
                        appendContentBlocks(this, msg["content"], allowImages = false)
                        calls?.forEachIndexed { index, raw ->
                            val call = raw as? JsonObject ?: return@forEachIndexed
                            val fn = call["function"] as? JsonObject ?: return@forEachIndexed
                            val id = call["id"]?.jsonPrimitive?.contentOrNull ?: "tool_$index"
                            val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                            val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                            val input = runCatching { json.parseToJsonElement(args) as? JsonObject }.getOrNull()
                                ?: throw IllegalArgumentException("Anthropic tool input must be a JSON object")
                            add(buildJsonObject {
                                put("type", "tool_use"); put("id", id); put("name", name); put("input", input)
                            })
                        }
                    }
                    out += buildJsonObject { put("role", "assistant"); put("content", blocks) }
                    i++
                }
                "user" -> {
                    val blocks = buildJsonArray { appendContentBlocks(this, msg["content"], allowImages = true) }
                    out += buildJsonObject { put("role", "user"); put("content", blocks) }
                    i++
                }
                else -> throw IllegalArgumentException("Unsupported message role for Anthropic")
            }
        }
        return JsonArray(out)
    }

    private fun appendContentBlocks(target: JsonArrayBuilder, content: JsonElement?, allowImages: Boolean) {
        when (content) {
            is JsonPrimitive -> if (content.content.isNotEmpty()) target.add(buildJsonObject { put("type", "text"); put("text", content.content) })
            is JsonArray -> content.forEach { raw ->
                val part = raw as? JsonObject ?: return@forEach
                when (part["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> target.add(buildJsonObject {
                        put("type", "text"); put("text", part["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        part["cache_control"]?.let { put("cache_control", it) }
                    })
                    "image_url" -> {
                        require(allowImages) { "Images are only supported in user messages" }
                        target.add(convertImage(part))
                    }
                    else -> throw IllegalArgumentException("Unsupported multimodal content block for Anthropic")
                }
            }
            null -> Unit
            else -> throw IllegalArgumentException("Unsupported message content for Anthropic")
        }
    }

    private fun convertImage(part: JsonObject): JsonObject {
        val url = (part["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Anthropic image block is missing URL")
        val match = DATA_IMAGE.matchEntire(url) ?: throw IllegalArgumentException("Anthropic image input requires a staged data image")
        val media = match.groupValues[1]
        val data = match.groupValues[2]
        return buildJsonObject {
            put("type", "image")
            put("source", buildJsonObject {
                put("type", "base64"); put("media_type", media); put("data", data)
            })
        }
    }

    private fun convertTools(tools: JsonArray?): JsonArray? {
        if (tools == null) return null
        return buildJsonArray {
            tools.forEach { raw ->
                val wrapper = raw as? JsonObject ?: return@forEach
                val fn = wrapper["function"] as? JsonObject ?: return@forEach
                val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                add(buildJsonObject {
                    put("name", name)
                    fn["description"]?.let { put("description", it) }
                    put("input_schema", fn["parameters"] ?: buildJsonObject { put("type", "object") })
                })
            }
        }
    }

    private fun resolvedMaxTokens(root: JsonObject, thinking: JsonObject?): JsonPrimitive {
        val requested = root["max_tokens"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_TOKENS
        val budget = thinking?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "enabled" }
            ?.get("budget_tokens")?.jsonPrimitive?.intOrNull
        return JsonPrimitive(if (budget == null) requested else maxOf(requested, budget + 4_096))
    }

    private fun validateThinkingConfig(thinking: JsonObject): JsonObject {
        return when (thinking["type"]?.jsonPrimitive?.contentOrNull) {
            "adaptive" -> thinking
            "enabled" -> {
                val budget = thinking["budget_tokens"]?.jsonPrimitive?.intOrNull
                    ?: throw IllegalArgumentException("Anthropic manual thinking requires budget_tokens")
                require(budget >= 1_024) { "Anthropic thinking budget_tokens must be >= 1024" }
                thinking
            }
            else -> throw IllegalArgumentException("Unsupported Anthropic thinking configuration")
        }
    }

    private fun privateContinuationContent(content: JsonArray, tools: JsonArray, json: Json): JsonArray? {
        if (tools.isEmpty()) return null
        val hasThinking = content.any { raw ->
            val type = (raw as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
            type == "thinking" || type == "redacted_thinking"
        }
        if (!hasThinking) return null
        validatePrivateContinuation(content, tools, json)
        return content
    }

    private fun validatePrivateContinuation(content: JsonArray, calls: JsonArray?, json: Json) {
        require(calls != null && calls.isNotEmpty()) { "Anthropic private continuation requires tool_calls" }
        val expected = linkedMapOf<String, Pair<String, JsonObject>>()
        calls.forEachIndexed { index, raw ->
            val call = raw as? JsonObject ?: throw IllegalArgumentException("Malformed normalized Anthropic tool call")
            val id = call["id"]?.jsonPrimitive?.contentOrNull ?: "tool_$index"
            val fn = call["function"] as? JsonObject ?: throw IllegalArgumentException("Malformed normalized Anthropic tool function")
            val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Anthropic tool call missing name")
            val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            val input = runCatching { json.parseToJsonElement(args) as? JsonObject }.getOrNull()
                ?: throw IllegalArgumentException("Anthropic tool input must be a JSON object")
            expected[id] = name to input
        }
        var thinkingSeen = false
        val actualIds = linkedSetOf<String>()
        content.forEach { raw ->
            val block = raw as? JsonObject ?: throw IllegalArgumentException("Malformed Anthropic private content block")
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "thinking" -> {
                    block["thinking"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Anthropic thinking block missing thinking")
                    block["signature"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Anthropic thinking block missing signature")
                    thinkingSeen = true
                }
                "redacted_thinking" -> {
                    block["data"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Anthropic redacted_thinking block missing data")
                    thinkingSeen = true
                }
                "text" -> block["text"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Anthropic text block missing text")
                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Anthropic private tool_use missing id")
                    val name = block["name"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Anthropic private tool_use missing name")
                    val input = block["input"] as? JsonObject
                        ?: throw IllegalArgumentException("Anthropic private tool_use input must be an object")
                    val wanted = expected[id] ?: throw IllegalArgumentException("Anthropic private tool_use id does not match normalized tool_calls")
                    require(wanted.first == name && wanted.second == input) { "Anthropic private tool_use payload mismatch" }
                    actualIds += id
                }
                else -> throw IllegalArgumentException("Unsupported Anthropic private continuation block")
            }
        }
        require(thinkingSeen) { "Anthropic private continuation must contain thinking or redacted_thinking" }
        require(actualIds == expected.keys) { "Anthropic private continuation tool set mismatch" }
    }

    private fun openAiToolCall(index: Int, id: String, name: String, arguments: String): JsonObject = buildJsonObject {
        put("index", index); put("id", id); put("type", "function")
        put("function", buildJsonObject { put("name", name); put("arguments", arguments) })
    }

    private fun normalizedResponse(
        id: String?, model: String?, text: String, tools: JsonArray, stopReason: String?, usage: JsonObject?,
        privateContent: JsonArray? = null,
    ): String = buildJsonObject {
        id?.let { put("id", it) }
        model?.let { put("model", it) }
        put("choices", buildJsonArray { add(buildJsonObject {
            put("index", 0)
            put("message", buildJsonObject {
                put("role", "assistant"); put("content", text)
                if (tools.isNotEmpty()) put("tool_calls", tools)
                privateContent?.let { put(PRIVATE_CONTENT_FIELD, it) }
            })
            val finishReason = when (stopReason) {
                "tool_use" -> JsonPrimitive("tool_calls")
                "end_turn", "stop_sequence" -> JsonPrimitive("stop")
                "max_tokens" -> JsonPrimitive("length")
                null -> JsonNull
                else -> JsonPrimitive(stopReason)
            }
            put("finish_reason", finishReason)
        }) })
        usage?.let { put("usage", it) }
    }.toString()

    private val DATA_IMAGE = Regex("^data:(image/(?:png|jpeg|gif|webp));base64,([A-Za-z0-9+/=]+)$")
}
