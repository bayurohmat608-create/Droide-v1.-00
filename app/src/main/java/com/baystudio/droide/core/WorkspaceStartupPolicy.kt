package com.baystudio.droide.core

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

// A mobile IDE must never leave the user behind an unbounded setup spinner.


enum class WorkspaceStartupPhase {
    IDLE,
    RUNTIME_REGISTRIES,
    PROJECT_CATALOG,
    ACTIVE_RUNTIME,
    FALLBACK_RUNTIME,
    PUBLISH_RUNTIME,
    READY,
    FAILED,
}

data class WorkspaceStartupStatus(
    val attempt: Long = 0L,
    val phase: WorkspaceStartupPhase = WorkspaceStartupPhase.IDLE,
    val projectId: String? = null,
    val detail: String? = null,
)

class WorkspaceStartupTimeoutException(
    val phase: WorkspaceStartupPhase,
    val timeoutMs: Long,
    val projectId: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(
    buildString {
        append("Workspace startup timed out during ")
        append(phase.name.lowercase())
        projectId?.let { append(" for project '").append(it).append('\'') }
        append(" after ").append(timeoutMs).append(" ms")
    },
    cause,
)

object WorkspaceStartupPolicy {
    

    const val RUNTIME_REGISTRIES_TIMEOUT_MS = 5_000L
    const val PROJECT_CATALOG_TIMEOUT_MS = 5_000L
    const val LOCAL_RUNTIME_TIMEOUT_MS = 15_000L
    const val SAF_RUNTIME_TIMEOUT_MS = 60_000L
    const val FALLBACK_RUNTIME_TIMEOUT_MS = 15_000L
    const val PUBLISH_RUNTIME_TIMEOUT_MS = 8_000L

    fun runtimeTimeoutMs(project: DroideProject): Long =
        if (project.treeUri.isNullOrBlank()) LOCAL_RUNTIME_TIMEOUT_MS else SAF_RUNTIME_TIMEOUT_MS

    suspend fun <T> bounded(
        phase: WorkspaceStartupPhase,
        timeoutMs: Long,
        projectId: String? = null,
        block: suspend () -> T,
    ): T {
        require(timeoutMs in 1_000L..60_000L) { "Invalid workspace startup timeout: $timeoutMs" }
        return try {
            withTimeout(timeoutMs) { block() }
        } catch (timeout: TimeoutCancellationException) {
            throw WorkspaceStartupTimeoutException(phase, timeoutMs, projectId, timeout)
        }
    }
}
