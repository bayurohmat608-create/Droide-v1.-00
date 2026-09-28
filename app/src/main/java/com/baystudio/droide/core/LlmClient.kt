package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LlmRequestTooLargeException(val requestChars: Int) : IllegalStateException(
    "LLM request is too large ($requestChars chars); compact the session"
)

class LlmHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException("HTTP $statusCode: ${responseBody.take(500)}") {
    fun isContextOverflow(): Boolean {
        val text = responseBody.lowercase()
        if (statusCode == 413) return true
        if (statusCode !in setOf(400, 422)) return false
        return CONTEXT_OVERFLOW_MARKERS.any(text::contains)
    }

    companion object {
        private val CONTEXT_OVERFLOW_MARKERS = listOf(
            "context_length_exceeded",
            "maximum context length",
            "max context length",
            "context window",
            "context length",
            "prompt is too long",
            "prompt too long",
            "input is too long",
            "input too long",
            "too many tokens",
            "token limit",
            "tokens exceed",
            "exceeds the model's context",
            "exceeds model context",
        )
    }
}










class LlmClient {
    private val client = OkHttpClient.Builder()
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

     
    suspend fun chatCompletions(baseUrl: String, apiKey: String, body: String): String =
        withContext(Dispatchers.IO) {
            val request = ProviderModelRequestIdentity.canonicalize(body, json)
            val requestBody = request.body
            val target = NetworkSecurity.validatePublicHttpsTarget("${baseUrl.trimEnd('/')}/chat/completions")
            if (requestBody.length > requestCharLimit(requestBody)) throw LlmRequestTooLargeException(requestBody.length)
            val builder = Request.Builder().url(target.url)
                .addHeader("Content-Type", "application/json")
            if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
            val req = builder.post(requestBody.toRequestBody("application/json".toMediaType())).build()
            client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = readBoundedBody(response.body?.source())
                    throw LlmHttpException(response.code, errorBody)
                }
                readBoundedBody(response.body?.source())
            }
        }

     
    suspend fun chatCompletions(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        expectedModelId: String,
        body: String,
    ): String = withContext(Dispatchers.IO) {
            val expectedModel = ProviderModelIdentity.requireCanonical(expectedModelId)
            val request = ProviderModelRequestIdentity.canonicalize(body, json)
            require(request.modelId == expectedModel) { "Provider request model identity drifted from the selected model" }
            val provider = requireRuntimeProvider(providerId, baseUrl, expectedModel)
            val spec = ProviderTransportV2.spec(provider)
            val requestBody = when (spec.protocol) {
                ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> request.body
                ProviderWireProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesAdapter.encodeRequest(request.body, streaming = false, json)
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> GoogleGenerateContentAdapter.encodeRequest(request.body, json)
            }
            val limit = requestCharLimit(request.body)
            if (requestBody.length > limit) throw LlmRequestTooLargeException(requestBody.length)
            val requestUrl = when (spec.protocol) {
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> GoogleGenerateContentAdapter.requestUrl(
                    baseUrl, request.modelId, streaming = false
                )
                else -> "${baseUrl.trimEnd('/')}${spec.chatPath}"
            }
            val target = NetworkSecurity.validateProviderTarget(provider, requestUrl)
            val builder = Request.Builder().url(target.url).addHeader("Content-Type", "application/json")
            ProviderTransportV2.applyAuth(builder, provider, apiKey)
            val unsigned = builder.post(requestBody.toRequestBody("application/json".toMediaType())).build()
            val req = ProviderRequestAuthorizer.authorize(unsigned, provider, apiKey)
            client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = readBoundedBody(response.body?.source())
                    throw LlmHttpException(response.code, errorBody)
                }
                val raw = readBoundedBody(response.body?.source())
                when (spec.protocol) {
                    ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> raw
                    ProviderWireProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesAdapter.normalizeBuffered(raw, json)
                    ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> GoogleGenerateContentAdapter.normalizeBuffered(raw, json)
                }
            }
        }

    suspend fun chatCompletionsStream(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        expectedModelId: String,
        body: String,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): LlmStreamResult = withContext(Dispatchers.IO) {
        val expectedModel = ProviderModelIdentity.requireCanonical(expectedModelId)
        val request = ProviderModelRequestIdentity.canonicalize(body, json)
        require(request.modelId == expectedModel) { "Provider request model identity drifted from the selected model" }
        val provider = requireRuntimeProvider(providerId, baseUrl, expectedModel)
        when (provider.wireProtocol) {
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> streamOpenAi(provider, baseUrl, apiKey, request.body, onEvent)
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> streamAnthropic(provider, baseUrl, apiKey, request.body, onEvent)
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> streamGoogle(provider, baseUrl, apiKey, request.body, onEvent)
        }
    }

    private suspend fun streamOpenAi(
        provider: AiProvider,
        baseUrl: String,
        apiKey: String,
        body: String,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): LlmStreamResult {
        val requestBody = enableStreaming(body)
        val spec = ProviderTransportV2.spec(provider)
        val target = NetworkSecurity.validateProviderTarget(provider, "${baseUrl.trimEnd('/')}${spec.chatPath}")
        if (requestBody.length > requestCharLimit(body)) throw LlmRequestTooLargeException(requestBody.length)
        val builder = Request.Builder().url(target.url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream, application/json")
        ProviderTransportV2.applyAuth(builder, provider, apiKey)
        val unsigned = builder.post(requestBody.toRequestBody("application/json".toMediaType())).build()
        val req = ProviderRequestAuthorizer.authorize(unsigned, provider, apiKey)

        return client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                val errorBody = readBoundedBody(response.body?.source())
                throw LlmHttpException(response.code, errorBody)
            }
            val source = response.body?.source() ?: return@use LlmStreamResult("", LlmStreamMode.BUFFERED)

            

            val firstLine = source.readUtf8Line() ?: return@use LlmStreamResult("", LlmStreamMode.BUFFERED)
            var streamedChars = firstLine.length
            if (!firstLine.startsWith("data:")) {
                val remainder = StringBuilder(firstLine)
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    streamedChars += line.length + 1
                    if (streamedChars > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM response is too large")
                    remainder.append('\n').append(line)
                }
                val completed = remainder.toString()
                val content = extractBufferedAssistantContent(completed)
                if (content.isNotEmpty()) onEvent(LlmStreamEvent.BufferedResponse(content))
                return@use LlmStreamResult(completed, LlmStreamMode.BUFFERED)
            }

            val accumulator = StreamAccumulator(json)
            suspend fun consume(line: String): Boolean {
                if (!line.startsWith("data:")) return false
                val payload = line.removePrefix("data:").trimStart()
                if (payload == "[DONE]") return true
                if (payload.isBlank()) return false
                accumulator.accept(payload).forEach { onEvent(it) }
                return false
            }

            var done = consume(firstLine)
            while (!done) {
                val line = source.readUtf8Line() ?: break
                streamedChars += line.length
                if (streamedChars > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM streamed response is too large")
                done = consume(line)
            }
            LlmStreamResult(accumulator.completedResponse(), LlmStreamMode.NATIVE)
        }
    }

    private suspend fun streamAnthropic(
        provider: AiProvider,
        baseUrl: String,
        apiKey: String,
        body: String,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): LlmStreamResult {
        val requestBody = AnthropicMessagesAdapter.encodeRequest(body, streaming = true, json)
        val spec = ProviderTransportV2.spec(provider)
        val target = NetworkSecurity.validateProviderTarget(provider, "${baseUrl.trimEnd('/')}${spec.chatPath}")
        if (requestBody.length > requestCharLimit(body)) throw LlmRequestTooLargeException(requestBody.length)
        val builder = Request.Builder().url(target.url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream, application/json")
        ProviderTransportV2.applyAuth(builder, provider, apiKey)
        val unsigned = builder.post(requestBody.toRequestBody("application/json".toMediaType())).build()
        val req = ProviderRequestAuthorizer.authorize(unsigned, provider, apiKey)

        return client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                val errorBody = readBoundedBody(response.body?.source())
                throw LlmHttpException(response.code, errorBody)
            }
            val source = response.body?.source() ?: return@use LlmStreamResult("", LlmStreamMode.BUFFERED)
            val firstLine = source.readUtf8Line() ?: return@use LlmStreamResult("", LlmStreamMode.BUFFERED)
            var streamedChars = firstLine.length
            val eventStream = firstLine.startsWith("event:") || firstLine.startsWith("data:") ||
                response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)
            if (!eventStream) {
                val remainder = StringBuilder(firstLine)
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    streamedChars += line.length + 1
                    if (streamedChars > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM response is too large")
                    remainder.append('\n').append(line)
                }
                val completed = AnthropicMessagesAdapter.normalizeBuffered(remainder.toString(), json)
                val content = extractBufferedAssistantContent(completed)
                if (content.isNotEmpty()) onEvent(LlmStreamEvent.BufferedResponse(content))
                return@use LlmStreamResult(completed, LlmStreamMode.BUFFERED)
            }

            val accumulator = AnthropicMessagesAdapter.StreamAccumulator(json)
            suspend fun consume(line: String): Boolean {
                if (!line.startsWith("data:")) return false
                val payload = line.removePrefix("data:").trimStart()
                if (payload.isBlank()) return false
                accumulator.accept(payload).forEach { onEvent(it) }
                return runCatching {
                    json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.contentOrNull == "message_stop"
                }.getOrDefault(false)
            }
            var done = consume(firstLine)
            while (!done) {
                val line = source.readUtf8Line() ?: break
                streamedChars += line.length
                if (streamedChars > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM streamed response is too large")
                done = consume(line)
            }
            LlmStreamResult(accumulator.completedResponse(), LlmStreamMode.NATIVE)
        }
    }

    private suspend fun streamGoogle(
        provider: AiProvider,
        baseUrl: String,
        apiKey: String,
        body: String,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): LlmStreamResult {
        val requestBody = GoogleGenerateContentAdapter.encodeRequest(body, json)
        if (requestBody.length > requestCharLimit(body)) throw LlmRequestTooLargeException(requestBody.length)
        val model = GoogleGenerateContentAdapter.modelFromBody(body, json)
        val target = NetworkSecurity.validateProviderTarget(
            provider, GoogleGenerateContentAdapter.requestUrl(baseUrl, model, streaming = true)
        )
        val builder = Request.Builder().url(target.url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream, application/json")
        ProviderTransportV2.applyAuth(builder, provider, apiKey)
        val unsigned = builder.post(requestBody.toRequestBody("application/json".toMediaType())).build()
        val req = ProviderRequestAuthorizer.authorize(unsigned, provider, apiKey)
        return client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                val errorBody = readBoundedBody(response.body?.source())
                throw LlmHttpException(response.code, errorBody)
            }
            val source = response.body?.source() ?: return@use LlmStreamResult("", LlmStreamMode.BUFFERED)
            val firstLine = source.readUtf8Line() ?: return@use LlmStreamResult("", LlmStreamMode.BUFFERED)
            var streamedChars = firstLine.length
            val eventStream = firstLine.startsWith("data:") ||
                response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)
            if (!eventStream) {
                val remainder = StringBuilder(firstLine)
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    streamedChars += line.length + 1
                    if (streamedChars > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM response is too large")
                    remainder.append('\n').append(line)
                }
                val completed = GoogleGenerateContentAdapter.normalizeBuffered(remainder.toString(), json)
                val content = extractBufferedAssistantContent(completed)
                if (content.isNotEmpty()) onEvent(LlmStreamEvent.BufferedResponse(content))
                return@use LlmStreamResult(completed, LlmStreamMode.BUFFERED)
            }
            val accumulator = GoogleGenerateContentAdapter.StreamAccumulator(json)
            suspend fun consume(line: String) {
                if (!line.startsWith("data:")) return
                val payload = line.removePrefix("data:").trimStart()
                if (payload.isBlank()) return
                accumulator.accept(payload).forEach { onEvent(it) }
            }
            consume(firstLine)
            while (true) {
                val line = source.readUtf8Line() ?: break
                streamedChars += line.length
                if (streamedChars > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM streamed response is too large")
                consume(line)
            }
            LlmStreamResult(accumulator.completedResponse(), LlmStreamMode.NATIVE)
        }
    }

    private fun enableStreaming(body: String): String {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("LLM request body must be a JSON object")
        val existingStreamOptions = root["stream_options"] as? JsonObject
        return buildJsonObject {
            root.forEach { (key, value) ->
                if (key != "stream_options") put(key, value)
            }
            put("stream", true)
            put("stream_options", buildJsonObject {
                existingStreamOptions?.forEach { (key, value) -> put(key, value) }
                


                put("include_usage", true)
            })
        }.toString()
    }

    private fun extractBufferedAssistantContent(body: String): String = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("")

    private fun readBoundedBody(source: okio.BufferedSource?): String {
        if (source == null) return ""
        source.request(MAX_RESPONSE_CHARS.toLong() + 1L)
        if (source.buffer.size > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM response is too large")
        return source.buffer.readUtf8()
    }

    private class StreamAccumulator(private val json: Json) {
        private data class ToolParts(
            var id: String = "",
            var type: String = "function",
            var name: String = "",
            val arguments: StringBuilder = StringBuilder(),
        )

        private val content = StringBuilder()
        private val tools = sortedMapOf<Int, ToolParts>()
        private var finishReason: JsonElement = JsonNull
        private var usage: JsonObject? = null
        private var responseId: String? = null
        private var model: String? = null

        fun accept(payload: String): List<LlmStreamEvent> {
            val root = runCatching { json.parseToJsonElement(payload).jsonObject }
                .getOrElse { throw IllegalStateException("Invalid SSE JSON from provider") }
            root["error"]?.let { throw IllegalStateException("Provider stream error: ${it.toString().take(500)}") }
            responseId = root["id"]?.jsonPrimitive?.contentOrNull ?: responseId
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: model
            (root["usage"] as? JsonObject)?.let { usage = it }

            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
            choice["finish_reason"]?.let { if (it !is JsonNull) finishReason = it }
            val delta = choice["delta"] as? JsonObject ?: return emptyList()
            val events = mutableListOf<LlmStreamEvent>()
            val textDelta = (delta["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (textDelta.isNotEmpty()) {
                content.append(textDelta)
                events += LlmStreamEvent.TextDelta(textDelta)
            }

            val toolDeltas = delta["tool_calls"] as? JsonArray
            toolDeltas?.forEach { raw ->
                val item = raw as? JsonObject ?: return@forEach
                val index = (item["index"] as? JsonPrimitive)?.intOrNull ?: tools.size
                val part = tools.getOrPut(index) { ToolParts() }
                val idFragment = (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                if (idFragment != null) part.id = idFragment
                (item["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { part.type = it }
                val fn = item["function"] as? JsonObject
                val nameFragment = (fn?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                if (nameFragment != null) {
                    part.name = when {
                        part.name.isBlank() -> nameFragment
                        nameFragment == part.name -> part.name
                        else -> part.name + nameFragment
                    }
                }
                val argsFragment = (fn?.get("arguments") as? JsonPrimitive)?.contentOrNull
                if (argsFragment != null) part.arguments.append(argsFragment)
                if (idFragment != null || nameFragment != null || argsFragment != null) {
                    events += LlmStreamEvent.ToolCallDelta(index, idFragment, nameFragment, argsFragment)
                }
            }
            return events
        }

        fun completedResponse(): String = buildJsonObject {
            responseId?.let { put("id", it) }
            model?.let { put("model", it) }
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", content.toString())
                        if (tools.isNotEmpty()) {
                            put("tool_calls", buildJsonArray {
                                tools.forEach { (index, part) ->
                                    add(buildJsonObject {
                                        put("index", index)
                                        put("id", part.id.ifBlank { "stream_tool_$index" })
                                        put("type", part.type)
                                        put("function", buildJsonObject {
                                            put("name", part.name)
                                            put("arguments", part.arguments.toString())
                                        })
                                    })
                                }
                            })
                        }
                    })
                    put("finish_reason", finishReason)
                })
            })
            usage?.let { put("usage", it) }
        }.toString()
    }

    private fun requireRuntimeProvider(providerId: String, baseUrl: String, modelId: String): AiProvider =
        ProviderRuntimeGuard.requireCertifiedSelection(
            providerId,
            baseUrl,
            ProviderModelIdentity.requireCanonical(modelId),
        )

    private fun requestCharLimit(body: String): Int =
        if (body.contains("\"type\":\"image_url\"")) MAX_MULTIMODAL_REQUEST_CHARS else MAX_REQUEST_CHARS

    companion object {
        const val MAX_REQUEST_CHARS = 1_500_000
        const val MAX_MULTIMODAL_REQUEST_CHARS = 13_000_000
        const val MAX_RESPONSE_CHARS = 2_000_000
    }
}
