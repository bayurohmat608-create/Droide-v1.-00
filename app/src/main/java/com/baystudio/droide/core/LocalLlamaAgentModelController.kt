package com.baystudio.droide.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext










class LocalLlamaAgentModelController(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val providerBackend: ProviderConnectionBackend,
    private val server: LocalLlamaServerManager,
    private val importer: AndroidLocalGgufImporter = AndroidLocalGgufImporter(context.applicationContext),
    private val certifier: LocalLlamaInferenceCertifier = LocalLlamaInferenceCertifier(context.applicationContext, bridge),
) {
    data class Model(
        val id: String,
        val displayName: String,
        val sizeBytes: Long,
        val target: String,
    )

    enum class Phase { IDLE, REFRESHING, STARTING, READY, STOPPING, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val models: List<Model> = emptyList(),
        val activeModelId: String? = null,
        val message: String? = null,
        val copiedBytes: Long = 0L,
        val totalBytes: Long? = null,
    ) {
        val busy: Boolean get() = phase == Phase.REFRESHING || phase == Phase.STARTING || phase == Phase.STOPPING
        val progress: Float?
            get() = totalBytes?.takeIf { it > 0L }?.let { total ->
                (copiedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
            }
    }

    private val appContext = context.applicationContext
    private val secrets = SecretStore(appContext)
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun refresh() = operationMutex.withLock {
        val previous = _state.value
        _state.value = previous.copy(phase = Phase.REFRESHING, message = null, copiedBytes = 0L, totalBytes = null)
        try {
            val models = certifiedInventory()
            val active = server.state.value.session?.modelId?.takeIf { id -> models.any { it.id == id } }
            _state.value = State(
                phase = if (active != null && server.state.value.phase == LocalLlamaServerManager.Phase.READY) Phase.READY else Phase.IDLE,
                models = models,
                activeModelId = active,
                message = if (models.isEmpty()) "No GGUF model is certified for the current Device Workstation target." else null,
            )
        } catch (cancelled: CancellationException) {
            _state.value = previous.copy(phase = Phase.IDLE)
            throw cancelled
        } catch (failure: Throwable) {
            _state.value = previous.copy(phase = Phase.FAILED, message = failure.userMessage("Could not refresh certified local models"))
        }
    }

     
    suspend fun selectLocal(modelId: String): AgentConfig = operationMutex.withLock {
        requireModelId(modelId)
        _state.value = _state.value.copy(
            phase = Phase.STARTING,
            activeModelId = modelId,
            message = "Preparing certified local model",
            copiedBytes = 0L,
            totalBytes = null,
        )
        try {
            val certified = certifiedInventory().firstOrNull { it.id == modelId }
                ?: error("Local GGUF is not certified for the current Device Workstation target")
            val session = server.start(modelId) { copied, total ->
                _state.value = _state.value.copy(
                    phase = Phase.STARTING,
                    activeModelId = modelId,
                    message = "Syncing local model to Device Workstation",
                    copiedBytes = copied,
                    totalBytes = total,
                )
            }
            require(session.providerId == LocalLlamaServerPolicy.PROVIDER_ID && session.modelId == modelId) {
                "Local llama.cpp lifecycle returned a different provider/model"
            }
            val provider = ProviderRuntimeGuard.requireCertifiedSelection(session.providerId, modelId)
            check(providerBackend.snapshot(provider.id).status == ProviderConnectionBackend.Status.CONNECTED) {
                "Local llama.cpp provider is not live-connected"
            }
            AgentPreferences.saveProvider(appContext, provider.id, modelId)
            AgentPreferences.saveReasoningEffort(appContext, null)
            val key = secrets.get(provider.id)
            check(key.isNotBlank()) { "Local llama.cpp session credential is unavailable" }
            _state.value = State(
                phase = Phase.READY,
                models = certifiedInventory(),
                activeModelId = modelId,
                message = "${certified.displayName} is ready for Agent chat",
            )
            AgentConfig(provider.baseUrl, key, modelId, provider.id, null)
        } catch (cancelled: CancellationException) {
            runCatching { server.stop() }
            _state.value = _state.value.copy(phase = Phase.IDLE, activeModelId = null, message = "Local model activation cancelled")
            throw cancelled
        } catch (failure: Throwable) {
            runCatching { server.stop() }
            _state.value = _state.value.copy(phase = Phase.FAILED, activeModelId = null, message = failure.userMessage("Local model activation failed"))
            throw failure
        }
    }

     
    suspend fun ensureReady(modelId: String, reasoningEffort: ReasoningEffort? = null): AgentConfig = operationMutex.withLock {
        requireModelId(modelId)
        _state.value = _state.value.copy(phase = Phase.STARTING, activeModelId = modelId, message = "Checking local llama.cpp server")
        try {
            val session = server.start(modelId) { copied, total ->
                _state.value = _state.value.copy(
                    phase = Phase.STARTING,
                    activeModelId = modelId,
                    message = "Syncing local model to Device Workstation",
                    copiedBytes = copied,
                    totalBytes = total,
                )
            }
            val provider = ProviderRuntimeGuard.requireCertifiedSelection(session.providerId, modelId)
            check(providerBackend.snapshot(provider.id).status == ProviderConnectionBackend.Status.CONNECTED) {
                "Local llama.cpp provider lost its live connection"
            }
            val key = secrets.get(provider.id)
            check(key.isNotBlank()) { "Local llama.cpp session credential is unavailable" }
            _state.value = _state.value.copy(phase = Phase.READY, activeModelId = modelId, message = null, copiedBytes = 0L, totalBytes = null)
            AgentConfig(provider.baseUrl, key, modelId, provider.id, reasoningEffort)
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(phase = Phase.IDLE, message = "Local llama.cpp restart cancelled")
            throw cancelled
        } catch (failure: Throwable) {
            _state.value = _state.value.copy(phase = Phase.FAILED, message = failure.userMessage("Local llama.cpp is unavailable"))
            throw failure
        }
    }

     
    suspend fun selectRemote(providerId: String, modelId: String) = operationMutex.withLock {
        require(!providerId.equals(LocalLlamaServerPolicy.PROVIDER_ID, true)) { "Use selectLocal for local llama.cpp" }
        val provider = ProviderModelCertificationRegistry.requireCertified(providerId, modelId)
        check(providerBackend.snapshot(provider.id).status == ProviderConnectionBackend.Status.CONNECTED) {
            "${provider.name} is not live-connected"
        }
        AgentPreferences.saveProvider(appContext, provider.id, modelId)
        AgentPreferences.saveReasoningEffort(appContext, ReasoningSupport.defaultFor(provider.id, modelId))
        _state.value = _state.value.copy(phase = Phase.STOPPING, message = "Stopping local llama.cpp server")
        server.stop()
        val models = runCatching { certifiedInventory() }.getOrDefault(_state.value.models)
        _state.value = State(phase = Phase.IDLE, models = models)
    }

    suspend fun stop() = operationMutex.withLock {
        _state.value = _state.value.copy(phase = Phase.STOPPING, message = "Stopping local llama.cpp server")
        try {
            server.stop()
            _state.value = _state.value.copy(phase = Phase.IDLE, activeModelId = null, message = null, copiedBytes = 0L, totalBytes = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _state.value = _state.value.copy(phase = Phase.FAILED, message = failure.userMessage("Could not stop local llama.cpp server"))
            throw failure
        }
    }

    suspend fun resolveAgentConfig(
        providerId: String,
        modelId: String,
        reasoningEffort: ReasoningEffort?,
    ): AgentConfig {
        if (providerId.equals(LocalLlamaServerPolicy.PROVIDER_ID, true)) {
            return ensureReady(modelId, reasoningEffort = null)
        }
        val provider = ProviderModelCertificationRegistry.requireCertified(providerId, modelId)
        check(providerBackend.snapshot(provider.id).status == ProviderConnectionBackend.Status.CONNECTED) {
            "${provider.name} is not live-connected"
        }
        val key = secrets.get(provider.id)
        if (provider.needsKey) check(key.isNotBlank()) { "Credential for ${provider.name} is unavailable" }
        return AgentConfig(provider.baseUrl, key, modelId, provider.id, reasoningEffort)
    }

    private suspend fun certifiedInventory(): List<Model> = withContext(Dispatchers.IO) {
        val stored = importer.listStored().take(MAX_MODELS)
        val certificates = certifier.certificationsFor(stored.map { it.id })
        stored.mapNotNull { storedModel ->
            val certificate = certificates[storedModel.id] ?: return@mapNotNull null
            Model(
                id = storedModel.id,
                displayName = storedModel.displayName,
                sizeBytes = storedModel.sizeBytes,
                target = "${certificate.targetAbi} / API ${certificate.targetApi}",
            )
        }.sortedWith(compareBy<Model> { it.displayName.lowercase() }.thenBy { it.id })
    }

    private fun requireModelId(modelId: String) {
        require(modelId.matches(MODEL_ID)) { "Invalid local GGUF model id" }
    }

    private fun Throwable.userMessage(prefix: String): String {
        val detail = message?.replace(Regex("\\s+"), " ")?.trim()?.take(360).orEmpty()
        return if (detail.isBlank()) prefix else "$prefix: $detail"
    }

    companion object {
        private val MODEL_ID = Regex("[0-9a-f]{64}")
        private const val MAX_MODELS = 128
    }
}
