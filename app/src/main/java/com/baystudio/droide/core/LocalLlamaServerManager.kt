package com.baystudio.droide.core

import android.content.Context
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit









class LocalLlamaServerManager(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val certifier: LocalLlamaInferenceCertifier = LocalLlamaInferenceCertifier(context.applicationContext, bridge),
    private val providerBackend: ProviderConnectionBackend = ProviderConnectionBackend(context.applicationContext),
    private val llm: LlmClient = LlmClient(),
    private val toolCertifier: LocalLlamaToolCapabilityCertifier = LocalLlamaToolCapabilityCertifier(context.applicationContext, llm),
) {
    enum class Phase { IDLE, PREPARING, STARTING, LOADING, VALIDATING, READY, STOPPING, FAILED }

    data class Session(
        val providerId: String,
        val modelId: String,
        val displayName: String,
        val baseUrl: String,
        val localPort: Int,
        val remotePort: Int,
        val contextSize: Int,
        val startedAtEpochMs: Long,
    )

    data class State(
        val phase: Phase = Phase.IDLE,
        val session: Session? = null,
        val message: String? = null,
    )

    private data class Running(
        val session: Session,
        val provider: AiProvider,
        val apiKey: String,
        val lease: RemoteProcessLease,
        val forward: Closeable,
        val logPath: String,
        val toolFacts: LocalLlamaToolCapabilityPolicy.ServerFacts,
    )

    private val appContext = context.applicationContext
    private val random = SecureRandom()
    private val operationMutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var running: Running? = null

    suspend fun start(
        modelId: String,
        onTransferProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Session = operationMutex.withLock {
        val current = running
        if (current != null && current.session.modelId == modelId && liveHealth(current.provider)) {
            return@withLock current.session
        }
        stopLocked()
        clearPublishedRuntimeState()
        _state.value = State(Phase.PREPARING, message = "Revalidating local model evidence")

        var lease: RemoteProcessLease? = null
        var forward: Closeable? = null
        var provider: AiProvider? = null
        var logPath: String? = null
        try {
            val prepared = certifier.prepareCertifiedForServing(modelId, onTransferProgress)
            LocalLlamaServerPolicy.requireServingHeadroom(prepared.memory, prepared.plan)
            val serverPath = prepared.runtime.serverPath
            DeviceBridgeManager.requireSafeRemotePath(serverPath)
            DeviceBridgeManager.requireSafeRemotePath(prepared.remoteModelPath)
            require(ProcessSecurityPolicy.isAllowedRemoteExecutable(serverPath, DeviceBridgeManager.remoteRoot())) {
                "Local llama.cpp server executable escaped Device Workstation"
            }

            val remotePort = 30_000 + random.nextInt(20_000)
            val localForward = openLocalForward(remotePort)
            forward = localForward.second
            val localPort = localForward.first
            val apiKey = sessionApiKey()
            val apiPrefix = sessionApiPrefix()
            val managedProvider = LocalLlamaServerPolicy.provider(localPort, modelId, apiPrefix)
            provider = managedProvider
            val processLease = RemoteProcessLease.create("llama-server")
            lease = processLease
            val remoteLog = "${DeviceBridgeManager.remoteRoot()}/run/llama-server-${modelId.take(12)}.log"
            logPath = remoteLog
            DeviceBridgeManager.requireSafeRemotePath(remoteLog)

            val threads = minOf(LocalLlamaServerPolicy.MAX_THREADS, prepared.memory.cpuCount).coerceAtLeast(1)
            val argv = LocalLlamaServerPolicy.commandArgv(
                serverPath = serverPath,
                remoteModelPath = prepared.remoteModelPath,
                remotePort = remotePort,
                modelId = modelId,
                threads = threads,
                apiPrefix = apiPrefix,
            )
            val command = argv.joinToString(" ") { DeviceBridgeManager.shellQuote(it) }
            val leasedCommand = "export LLAMA_API_KEY=${DeviceBridgeManager.shellQuote(apiKey)}; " +
                "exec /system/bin/toybox nice -n 10 $command"
            _state.value = State(Phase.STARTING, message = "Starting local llama.cpp server")
            val launch = bridge.shellBounded(processLease.detachedCommand(leasedCommand, remoteLog), maxOutputBytes = 16_384)
            check(launch.exitCode == 0 && !launch.truncated) { "Could not launch local llama.cpp server" }

            _state.value = State(Phase.LOADING, message = "Loading GGUF into local server")
            waitUntilHealthy(managedProvider, processLease, remoteLog)
            _state.value = State(Phase.VALIDATING, message = "Validating OpenAI-compatible local provider")
            val toolFacts = validateProps(
                managedProvider,
                apiKey,
                prepared.remoteModelPath,
                targetAbi = prepared.runtime.target.abi,
                targetApi = prepared.runtime.target.api,
                targetEnvironmentSha256 = prepared.targetEnvironmentSha256,
            )

            ProviderRegistry.installManagedRuntime(listOf(managedProvider))
            val connected = providerBackend.connect(LocalLlamaServerPolicy.PROVIDER_ID, "api-key", apiKey)
                .getOrElse { throw IllegalStateException("Local llama.cpp provider validation failed: ${it.message}", it) }
            check(connected.models.toSet() == setOf(modelId)) {
                "Local llama.cpp model inventory did not match the active certified GGUF"
            }
            validateChatRoute(managedProvider, apiKey, modelId)
            val toolCertified = toolCertifier.certificateFor(modelId, toolFacts) != null
            ModelCapabilityRegistry.installRuntime(
                LocalLlamaServerPolicy.runtimeCapability(modelId, prepared.model.displayName, toolCall = toolCertified),
            )

            val session = Session(
                providerId = managedProvider.id,
                modelId = modelId,
                displayName = prepared.model.displayName,
                baseUrl = managedProvider.baseUrl,
                localPort = localPort,
                remotePort = remotePort,
                contextSize = LocalLlamaServerPolicy.CONTEXT_SIZE,
                startedAtEpochMs = System.currentTimeMillis(),
            )
            running = Running(session, managedProvider, apiKey, processLease, forward, remoteLog, toolFacts)
            forward = null
            lease = null
            logPath = null
            _state.value = State(Phase.READY, session = session, message = "Local llama.cpp provider is live-certified")
            session
        } catch (cancelled: CancellationException) {
            cleanupPartial(lease, forward, logPath)
            clearPublishedRuntimeState()
            _state.value = State(Phase.IDLE, message = "Local llama.cpp server start cancelled")
            throw cancelled
        } catch (failure: Throwable) {
            cleanupPartial(lease, forward, logPath)
            clearPublishedRuntimeState()
            _state.value = State(Phase.FAILED, message = failure.userMessage())
            throw failure
        }
    }

    



    suspend fun certifyToolCalling(modelId: String): LocalLlamaToolCapabilityCertifier.Certificate = operationMutex.withLock {
        val active = running ?: error("Local llama.cpp server must be READY before tool certification")
        require(active.session.modelId == modelId) { "Tool certification model does not match the active local server" }
        check(liveHealth(active.provider)) { "Local llama.cpp server is not healthy" }
        _state.value = State(Phase.VALIDATING, session = active.session, message = "Certifying local model tool calling")
        try {
            val certificate = toolCertifier.certify(active.provider, active.apiKey, modelId, active.toolFacts)
            ModelCapabilityRegistry.installRuntime(
                LocalLlamaServerPolicy.runtimeCapability(modelId, active.session.displayName, toolCall = true),
            )
            _state.value = State(Phase.READY, session = active.session, message = "Local model tool calling is certified")
            certificate
        } catch (cancelled: CancellationException) {
            ModelCapabilityRegistry.installRuntime(
                LocalLlamaServerPolicy.runtimeCapability(modelId, active.session.displayName, toolCall = false),
            )
            _state.value = State(Phase.READY, session = active.session, message = "Tool certification cancelled; tools remain disabled")
            throw cancelled
        } catch (failure: Throwable) {
            ModelCapabilityRegistry.installRuntime(
                LocalLlamaServerPolicy.runtimeCapability(modelId, active.session.displayName, toolCall = false),
            )
            _state.value = State(Phase.READY, session = active.session, message = "Tool certification failed; tools remain disabled")
            throw failure
        }
    }

    suspend fun stop() = operationMutex.withLock { stopLocked() }

     
    suspend fun reconcileProcessState() = operationMutex.withLock {
        if (running == null) clearPublishedRuntimeState()
    }

    private suspend fun stopLocked() {
        val active = running
        if (active == null) {
            clearPublishedRuntimeState()
            _state.value = State()
            return
        }
        _state.value = State(Phase.STOPPING, session = active.session, message = "Stopping local llama.cpp server")
        running = null
        clearPublishedRuntimeState()
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { active.forward.close() }
            runSuspendCatching { bridge.shellBounded(active.lease.terminateCommand(), maxOutputBytes = 16_384) }
            runSuspendCatching { bridge.shellBounded(active.lease.cleanupCommand(), maxOutputBytes = 8_192) }
            runSuspendCatching { bridge.shellBounded("rm -f ${DeviceBridgeManager.shellQuote(active.logPath)}", maxOutputBytes = 8_192) }
        }
        _state.value = State()
    }

    private suspend fun clearPublishedRuntimeState() {
        runCatching { providerBackend.disconnect(LocalLlamaServerPolicy.PROVIDER_ID) }
        ProviderModelCertificationRegistry.revoke(LocalLlamaServerPolicy.PROVIDER_ID)
        ModelCapabilityRegistry.revokeRuntime(LocalLlamaServerPolicy.PROVIDER_ID)
        ProviderRegistry.removeManagedRuntime(LocalLlamaServerPolicy.PROVIDER_ID)
    }

    private fun openLocalForward(remotePort: Int): Pair<Int, Closeable> {
        repeat(8) {
            val candidate = ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1)
                socket.localPort
            }
            if (candidate !in 1024..65535) return@repeat
            val handle = runCatching { bridge.forward(candidate, remotePort) }.getOrNull()
            if (handle != null) return candidate to handle
        }
        error("Could not allocate a loopback KADB forward for local llama.cpp")
    }

    private suspend fun waitUntilHealthy(provider: AiProvider, lease: RemoteProcessLease, logPath: String) {
        try {
            withTimeout(LocalLlamaServerPolicy.STARTUP_TIMEOUT_MS) {
                while (true) {
                    when (healthCode(provider)) {
                        200 -> return@withTimeout
                        503, null -> Unit
                        else -> error("Local llama.cpp health endpoint returned an unexpected status")
                    }
                    val alive = bridge.shellBounded(lease.aliveCommand(), maxOutputBytes = 8_192)
                    if (alive.exitCode != 0) {
                        error("Local llama.cpp server exited while loading: ${readRemoteLog(logPath)}")
                    }
                    delay(750)
                }
            }
        } catch (_: TimeoutCancellationException) {
            error("Local llama.cpp server did not become healthy within ${LocalLlamaServerPolicy.STARTUP_TIMEOUT_MS / 1000}s")
        }
    }

    private suspend fun liveHealth(provider: AiProvider): Boolean = healthCode(provider) == 200

    private suspend fun healthCode(provider: AiProvider): Int? = withContext(Dispatchers.IO) { healthCodeBlocking(provider) }

    private fun healthCodeBlocking(provider: AiProvider): Int? {
        val target = NetworkSecurity.validateProviderTarget(provider, provider.baseUrl.trimEnd('/') + "/health")
        val request = Request.Builder().url(target.url).header("Accept", "application/json").get().build()
        return try {
            http.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(request).execute().use { it.code }
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun validateProps(
        provider: AiProvider,
        apiKey: String,
        remoteModelPath: String,
        targetAbi: String,
        targetApi: Int,
        targetEnvironmentSha256: String,
    ): LocalLlamaToolCapabilityPolicy.ServerFacts = withContext(Dispatchers.IO) {
        val target = NetworkSecurity.validateProviderTarget(provider, provider.baseUrl.removeSuffix("/v1") + "/props")
        val request = Request.Builder().url(target.url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .get().build()
        val body = http.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Local llama.cpp /props validation failed with HTTP ${response.code}" }
            boundedBody(response)
        }
        val root = json.parseToJsonElement(body) as? JsonObject ?: error("Local llama.cpp /props returned invalid JSON")
        val settings = root["default_generation_settings"] as? JsonObject ?: error("Local llama.cpp /props omitted generation settings")
        val nCtx = (settings["n_ctx"] as? JsonPrimitive)?.intOrNull ?: error("Local llama.cpp /props omitted n_ctx")
        val slots = (root["total_slots"] as? JsonPrimitive)?.intOrNull ?: error("Local llama.cpp /props omitted slot count")
        val modelPath = (root["model_path"] as? JsonPrimitive)?.contentOrNull ?: error("Local llama.cpp /props omitted model path")
        check(nCtx == LocalLlamaServerPolicy.CONTEXT_SIZE) { "Local llama.cpp server context does not match the reviewed profile" }
        check(slots == LocalLlamaServerPolicy.PARALLEL_SLOTS) { "Local llama.cpp server exposed an unexpected slot count" }
        check(modelPath == remoteModelPath) { "Local llama.cpp server loaded a different model path" }
        LocalLlamaToolCapabilityPolicy.serverFacts(
            root,
            targetAbi = targetAbi,
            targetApi = targetApi,
            targetEnvironmentSha256 = targetEnvironmentSha256,
        )
    }

    private suspend fun validateChatRoute(provider: AiProvider, apiKey: String, modelId: String) {
        val body = buildJsonObject {
            put("model", modelId)
            put("max_tokens", 1)
            put("temperature", 0)
            put("stream", false)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Reply with one short token.")
                })
            }
        }.toString()
        val response = withTimeout(120_000L) {
            llm.chatCompletions(provider.id, provider.baseUrl, apiKey, modelId, body)
        }
        val root = json.parseToJsonElement(response) as? JsonObject ?: error("Local llama.cpp chat route returned invalid JSON")
        val choices = root["choices"] as? JsonArray ?: error("Local llama.cpp chat route omitted choices")
        check(choices.isNotEmpty()) { "Local llama.cpp chat route returned no choices" }
    }

    private suspend fun readRemoteLog(path: String): String {
        DeviceBridgeManager.requireSafeRemotePath(path)
        val result = bridge.shellBounded(
            "if [ -f ${DeviceBridgeManager.shellQuote(path)} ]; then /system/bin/toybox tail -c 16000 ${DeviceBridgeManager.shellQuote(path)}; fi",
            maxOutputBytes = 16_384,
        )
        return result.combined.replace(Regex("\\s+"), " ").takeLast(4_000).ifBlank { "no startup log" }
    }

    private suspend fun cleanupPartial(lease: RemoteProcessLease?, forward: Closeable?, logPath: String?) =
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { forward?.close() }
            if (lease != null && bridge.state.value.connected != null) {
                runSuspendCatching { bridge.shellBounded(lease.terminateCommand(), maxOutputBytes = 16_384) }
                runSuspendCatching { bridge.shellBounded(lease.cleanupCommand(), maxOutputBytes = 8_192) }
            }
            if (logPath != null && bridge.state.value.connected != null) {
                runSuspendCatching { bridge.shellBounded("rm -f ${DeviceBridgeManager.shellQuote(logPath)}", maxOutputBytes = 8_192) }
            }
        }

    private fun boundedBody(response: okhttp3.Response): String {
        val source = response.body?.source() ?: return ""
        source.request(LocalLlamaServerPolicy.MAX_HTTP_BODY_BYTES + 1L)
        require(source.buffer.size <= LocalLlamaServerPolicy.MAX_HTTP_BODY_BYTES) { "Local llama.cpp response is too large" }
        return source.buffer.readUtf8()
    }

    private fun sessionApiKey(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sessionApiPrefix(): String {
        val bytes = ByteArray(12).also(random::nextBytes)
        return "/droide-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun Throwable.userMessage(): String {
        val detail = message?.replace(Regex("\\s+"), " ")?.trim()?.take(360).orEmpty()
        return if (detail.isBlank()) "Local llama.cpp server failed" else detail
    }
}
