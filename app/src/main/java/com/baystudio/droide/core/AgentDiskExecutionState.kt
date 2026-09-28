package com.baystudio.droide.core








data class AgentDiskExecutionState(
    val dirtyDocuments: List<DirtyDocument>,
    val omittedCount: Int = 0,
) {
    data class DirtyDocument(
        val path: String,
        val revision: Int,
        val changeVersion: Int,
    )

    val dirtyCount: Int get() = dirtyDocuments.size + omittedCount
    val hasDirtyBuffers: Boolean get() = dirtyCount > 0

    fun annotate(operation: String, output: String): String {
        if (!hasDirtyBuffers) return output
        val safeOperation = operation.replace(Regex("[\r\n\t]+"), " ").take(80)
        val detail = buildString {
            append("[DROIDE_DISK_STATE persisted_only=true operation=")
            append(safeOperation)
            append(" dirty_buffers=")
            append(dirtyCount)
            append("]\n")
            append("Unsaved editor buffers are NOT included in this filesystem/process result; treat it as persisted-disk evidence only.\n")
            dirtyDocuments.forEach { doc ->
                append("- ")
                append(displayPath(doc.path))
                append(" revision=")
                append(doc.revision)
                append(" change_version=")
                append(doc.changeVersion)
                append('\n')
            }
            if (omittedCount > 0) append("- ... +").append(omittedCount).append(" more dirty buffer(s)\n")
            append("[/DROIDE_DISK_STATE]\n")
        }
        return detail + output
    }

    companion object {
        private const val MAX_LISTED_DIRTY_DOCUMENTS = 16
        val CLEAN = AgentDiskExecutionState(emptyList())

        suspend fun capture(authority: WorkspaceDocumentAuthority?): AgentDiskExecutionState {
            val dirty = authority?.dirtySnapshots().orEmpty()
                .filter { it.dirty }
                .sortedBy { it.path }
            if (dirty.isEmpty()) return CLEAN
            val listed = dirty.take(MAX_LISTED_DIRTY_DOCUMENTS).map { snap ->
                DirtyDocument(snap.path, snap.revision, snap.changeVersion)
            }
            return AgentDiskExecutionState(listed, (dirty.size - listed.size).coerceAtLeast(0))
        }

        private fun displayPath(path: String): String {
            if (SensitivePathPolicy.isSensitive(path)) return "<sensitive-path>"
            return path.replace(Regex("[\r\n\t]+"), " ").take(240)
        }
    }
}
