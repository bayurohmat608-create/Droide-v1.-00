package com.baystudio.droide.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun AgentTools.Ctx.diskBacked(
    operation: String,
    reconcileAfter: Boolean = false,
    block: suspend () -> String,
): String {
    val state = AgentDiskExecutionState.capture(documentAuthority)
    return try {
        val output = block()
        val reconciliation = if (reconcileAfter) documentAuthority?.reconcilePersistedWorkspace() else null
        state.annotate(operation, output) + reconciliation.agentReconciliationNotice(operation)
    } catch (t: Throwable) {
        if (reconcileAfter) withContext(NonCancellable) {
            runSuspendCatching { documentAuthority?.reconcilePersistedWorkspace() }
        }
        throw t
    }
}

internal fun WorkspacePersistedReconciliation?.agentReconciliationNotice(operation: String): String {
    val result = this ?: return ""
    if (!result.changed && result.conflicts.isEmpty()) return ""
    return buildString {
        append("\n[DROIDE_EDITOR_RECONCILE operation=")
        append(operation)
        append(" refreshed=")
        append(result.refreshed.size)
        append(" removed=")
        append(result.removed.size)
        append(" remapped=")
        append(result.remapped.size)
        append(" conflicts=")
        append(result.conflicts.size)
        append(']')
        if (result.conflicts.isNotEmpty()) {
            append("\nWARNING: persisted workspace changed behind unsaved editor buffer(s): ")
            append(result.conflicts.take(12).joinToString(", "))
            if (result.conflicts.size > 12) append(" …")
            append(". Live editor text was preserved; review/reload before saving over disk.")
        }
    }
}

internal fun BackgroundCommandManager.Snapshot.agentSummary(includeDiskNotice: Boolean = false): String {
    val exit = exitCode?.let { " exit=$it" }.orEmpty()
    val timeout = timeoutMs?.let { " timeout_ms=$it" } ?: " timeout=none"
    val dirty = if (launchDiskState.hasDirtyBuffers) " launch_dirty_buffers=${launchDiskState.dirtyCount}" else ""
    val summary = "id=$id state=${state.name.lowercase()}$exit$timeout$dirty command=${command.take(500)}"
    return if (includeDiskNotice) launchDiskState.annotate("background_job:$id:launch", summary) else summary
}
