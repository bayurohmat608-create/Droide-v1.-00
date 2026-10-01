package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The coordinator never registers imported files as Agent models by itself.






class LocalLlamaModelCoordinator(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val importer: AndroidLocalGgufImporter = AndroidLocalGgufImporter(context.applicationContext),
    private val certifier: LocalLlamaInferenceCertifier = LocalLlamaInferenceCertifier(context.applicationContext, bridge),
) {
    enum class Availability { IMPORTED, CERTIFIED, REJECTED }
    enum class OperationKind { IMPORT, CERTIFY }

    data class Model(
        val id: String,
        val displayName: String,
        val sizeBytes: Long,
        val ggufVersion: Int,
        val availability: Availability,
        val certifiedAtEpochMs: Long? = null,
        val certifiedTarget: String? = null,
    )

    data class Operation(
        val kind: OperationKind,
        val modelId: String? = null,
        val phase: String,
        val completedBytes: Long = 0L,
        val totalBytes: Long? = null,
    ) {
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0L }?.let { total ->
                (completedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
            }
    }

    data class State(
        val loading: Boolean = true,
        val models: List<Model> = emptyList(),
        val operation: Operation? = null,
        val message: String? = null,
        val failedModelId: String? = null,
    )

    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun refresh() = operationMutex.withLock {
        try {
            publishInventory(message = _state.value.message, failedModelId = _state.value.failedModelId)
        } catch (cancelled: CancellationException) {
            _state.update { it.copy(loading = false, operation = null) }
            throw cancelled
        } catch (failure: Throwable) {
            _state.update {
                it.copy(
                    loading = false,
                    operation = null,
                    message = failure.userMessage("Could not refresh local model evidence"),
                )
            }
        }
    }

    suspend fun import(uri: Uri) = operationMutex.withLock {
        _state.update {
            it.copy(
                loading = false,
                operation = Operation(OperationKind.IMPORT, phase = "Importing GGUF"),
                message = null,
                failedModelId = null,
            )
        }
        try {
            val job = currentCoroutineContext()[Job]
            val imported = importer.import(
                uri = uri,
                checkCancelled = { job?.ensureActive() },
                onProgress = { copied, total ->
                    _state.update { current ->
                        current.copy(
                            operation = Operation(
                                kind = OperationKind.IMPORT,
                                phase = "Importing GGUF",
                                completedBytes = copied,
                                totalBytes = total,
                            ),
                        )
                    }
                },
            )
            publishInventory(message = "Imported ${imported.displayName}. Certification is required before Agent use.")
        } catch (cancelled: CancellationException) {
            _state.update { it.copy(operation = null, message = "Local model import cancelled.") }
            throw cancelled
        } catch (failure: Throwable) {
            _state.update {
                it.copy(operation = null, message = failure.userMessage("Local model import failed"), failedModelId = null)
            }
        }
    }

    suspend fun certify(modelId: String) = operationMutex.withLock {
        require(modelId.matches(MODEL_ID)) { "Invalid local GGUF model id" }
        _state.update {
            it.copy(
                loading = false,
                operation = Operation(OperationKind.CERTIFY, modelId, "Verifying model"),
                message = null,
                failedModelId = null,
            )
        }
        try {
            val certificate = certifier.certify(
                modelId = modelId,
                onPhase = { phase ->
                    _state.update { current ->
                        current.copy(
                            operation = current.operation?.copy(phase = phase.label()),
                        )
                    }
                },
                onTransferProgress = { copied, total ->
                    _state.update { current ->
                        current.copy(
                            operation = current.operation?.copy(
                                phase = "Syncing model to Device Workstation",
                                completedBytes = copied,
                                totalBytes = total,
                            ),
                        )
                    }
                },
            )
            publishInventory(
                message = "Certified ${modelId.take(12)} on ${certificate.targetAbi} / API ${certificate.targetApi}.",
                failedModelId = null,
            )
        } catch (cancelled: CancellationException) {
            _state.update { it.copy(operation = null, message = "Local model certification cancelled.") }
            throw cancelled
        } catch (failure: Throwable) {
            val message = failure.userMessage("Local model certification failed")
            try {
                publishInventory(message = message, failedModelId = modelId)
            } catch (refreshFailure: Throwable) {
                if (refreshFailure is CancellationException) throw refreshFailure
                _state.update {
                    it.copy(
                        loading = false,
                        operation = null,
                        message = "$message · inventory refresh also failed",
                        failedModelId = modelId,
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun publishInventory(message: String? = null, failedModelId: String? = null) {
        _state.update { it.copy(loading = true, operation = null) }
        val stored = importer.listStored().take(MAX_VISIBLE_MODELS)
        val certifications = certifier.certificationsFor(stored.map { it.id })
        val models = stored.map { model ->
            val certificate = certifications[model.id]
            val availability = when {
                certificate != null -> Availability.CERTIFIED
                failedModelId == model.id -> Availability.REJECTED
                else -> Availability.IMPORTED
            }
            Model(
                id = model.id,
                displayName = model.displayName,
                sizeBytes = model.sizeBytes,
                ggufVersion = model.header.version,
                availability = availability,
                certifiedAtEpochMs = certificate?.certifiedAtEpochMs,
                certifiedTarget = certificate?.let { "${it.targetAbi} / API ${it.targetApi}" },
            )
        }
        _state.value = State(
            loading = false,
            models = models,
            operation = null,
            message = message,
            failedModelId = failedModelId?.takeIf { id -> models.any { it.id == id } },
        )
    }

    private fun LocalLlamaInferenceCertifier.Phase.label(): String = when (this) {
        LocalLlamaInferenceCertifier.Phase.VERIFYING_MODEL -> "Verifying model"
        LocalLlamaInferenceCertifier.Phase.PREPARING_RUNTIME -> "Preparing llama.cpp runtime"
        LocalLlamaInferenceCertifier.Phase.CHECKING_MEMORY -> "Checking device memory"
        LocalLlamaInferenceCertifier.Phase.SYNCING_MODEL -> "Syncing model to Device Workstation"
        LocalLlamaInferenceCertifier.Phase.DECODING -> "Running bounded decode"
        LocalLlamaInferenceCertifier.Phase.CERTIFIED -> "Certified"
    }

    private fun Throwable.userMessage(prefix: String): String {
        val detail = message?.trim().orEmpty().replace(Regex("\\s+"), " ").take(360)
        return if (detail.isBlank()) prefix else "$prefix: $detail"
    }

    companion object {
        private val MODEL_ID = Regex("[0-9a-f]{64}")
        private const val MAX_VISIBLE_MODELS = 128

        fun formatBytes(bytes: Long): String {
            val mib = bytes / (1024.0 * 1024.0)
            return if (mib < 1024.0) String.format(Locale.US, "%.0f MiB", mib)
            else String.format(Locale.US, "%.1f GiB", mib / 1024.0)
        }
    }
}
