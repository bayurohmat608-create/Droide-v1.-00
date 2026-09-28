package com.baystudio.droide.core

 
object LocalLlamaServerPolicy {
    const val PROVIDER_ID = "local.llama"
    const val CONTEXT_SIZE = 4_096
    const val BATCH_SIZE = 128
    const val UBATCH_SIZE = 64
    const val MAX_THREADS = 2
    const val PARALLEL_SLOTS = 1
    const val SERVER_TIMEOUT_SECONDS = 120
    const val STARTUP_TIMEOUT_MS = 4L * 60L * 1000L
    const val MAX_HTTP_BODY_BYTES = 512 * 1024L
    const val EXTRA_AVAILABLE_HEADROOM_BYTES = 512L * 1024L * 1024L
    const val PROFILE_VERSION = 2

    fun provider(localPort: Int, modelId: String, apiPrefix: String): AiProvider {
        require(localPort in 1024..65535) { "Invalid local llama.cpp forward port" }
        requireModelId(modelId)
        require(apiPrefix.matches(Regex("/droide-[A-Za-z0-9_-]{12,48}"))) { "Invalid local llama.cpp API prefix" }
        return AiProvider(
            id = PROVIDER_ID,
            name = "Droide Local llama.cpp",
            baseUrl = "http://127.0.0.1:$localPort$apiPrefix/v1",
            model = modelId,
            free = true,
            needsKey = true,
            helpUrl = "https://github.com/ggml-org/llama.cpp/tree/master/tools/server",
            note = "Droide-managed llama.cpp server. Loopback-forwarded, session-key protected, and evidence-gated.",
            wireProtocol = ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
            authScheme = ProviderAuthScheme.BEARER,
            networkScope = ProviderNetworkScope.LOOPBACK_ONLY,
        )
    }

    fun requireServingHeadroom(
        memory: LocalLlamaInferencePolicy.MemorySnapshot,
        certificationPlan: LocalLlamaInferencePolicy.Plan,
    ): Long {
        val required = Math.addExact(certificationPlan.requiredAvailableBytes, EXTRA_AVAILABLE_HEADROOM_BYTES)
        require(memory.availableBytes >= required) {
            "Insufficient available memory for local llama.cpp server: need at least " +
                "${LocalLlamaInferencePolicy.formatBytes(required)}, have ${LocalLlamaInferencePolicy.formatBytes(memory.availableBytes)}"
        }
        return required
    }

    fun commandArgv(
        serverPath: String,
        remoteModelPath: String,
        remotePort: Int,
        modelId: String,
        threads: Int,
        apiPrefix: String,
    ): List<String> {
        require(serverPath.isNotBlank() && remoteModelPath.isNotBlank()) { "Local llama.cpp server paths are missing" }
        require(remotePort in 1024..65535) { "Invalid remote llama.cpp port" }
        requireModelId(modelId)
        require(threads in 1..MAX_THREADS) { "Invalid local llama.cpp thread count" }
        require(apiPrefix.matches(Regex("/droide-[A-Za-z0-9_-]{12,48}"))) { "Invalid local llama.cpp API prefix" }
        return listOf(
            serverPath,
            "-m", remoteModelPath,
            "--alias", modelId,
            "--host", "127.0.0.1",
            "--port", remotePort.toString(),
            "-c", CONTEXT_SIZE.toString(),
            "-b", BATCH_SIZE.toString(),
            "-ub", UBATCH_SIZE.toString(),
            "-t", threads.toString(),
            "-np", PARALLEL_SLOTS.toString(),
            "-ngl", "0",
            "--cache-ram", "0",
            "--no-warmup",
            "--no-ui",
            "--no-slots",
            "--jinja",
            "--cors-origins", "localhost",
            "--api-prefix", apiPrefix,
            "--timeout", SERVER_TIMEOUT_SECONDS.toString(),
            "--verbosity", "1",
        )
    }

    fun runtimeCapability(modelId: String, displayName: String, toolCall: Boolean = false): ModelCapabilities {
        requireModelId(modelId)
        return ModelCapabilities(
            providerId = PROVIDER_ID,
            modelId = modelId,
            displayName = displayName.take(200),
            attachment = false,
            reasoning = null,
            toolCall = toolCall,
            structuredOutput = null,
            temperature = null,
            inputModalities = setOf("text"),
            outputModalities = setOf("text"),
            reasoningEfforts = emptySet(),
            contextTokens = CONTEXT_SIZE,
            outputTokens = null,
            source = ModelCapabilitySource.RUNTIME_PROVIDER,
        )
    }

    private fun requireModelId(modelId: String) {
        require(modelId.matches(Regex("[0-9a-f]{64}"))) { "Invalid local GGUF model id" }
    }
}
