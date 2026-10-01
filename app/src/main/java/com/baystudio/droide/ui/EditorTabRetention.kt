package com.baystudio.droide.ui

import com.baystudio.droide.core.EditorRecoverySnapshot
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.runSuspendCatching

 
internal object EditorTabRetention {
    const val MAX_TABS = 32

     
    fun open(
        tabs: List<String>,
        path: String,
        activePath: String,
        allowExistingDirtyOverflow: Boolean = false,
        isDirty: (String) -> Boolean,
    ): List<String>? {
        if (path in tabs) return tabs
        if (tabs.size < MAX_TABS) return tabs + path
        val evicted = tabs.firstOrNull { it != activePath && !isDirty(it) }
            ?: tabs.firstOrNull { !isDirty(it) }
            ?: return if (allowExistingDirtyOverflow && isDirty(path)) tabs + path else null
        return tabs.filterNot { it == evicted } + path
    }

    fun planWorkspaceEdit(
        tabs: List<String>, targets: Set<String>, activePath: String, isDirty: (String) -> Boolean,
    ): List<String> {
        var planned = tabs
        for (path in targets) {
            planned = open(planned, path, activePath) { it in targets || isDirty(it) }
                ?: error("Unsaved editor tabs are full. Save or close a tab before applying this workspace edit.")
        }
        return planned
    }

    fun closeEvicted(tabs: List<String>, next: List<String>, state: EditorWorkspaceState, onClose: (String) -> Unit) {
        tabs.filterNot { it in next }.forEach { path ->
            onClose(path)
            state.forgetAtOrUnder(path)
        }
    }

    suspend fun restoreDocuments(snapshot: EditorRecoverySnapshot, files: FileRepository, state: EditorWorkspaceState): List<String> {
        val recovered = mutableListOf<String>()
        for (path in (snapshot.openFiles + snapshot.dirtyPaths).distinct()) {
            val exists = runSuspendCatching { files.exists(path) }.getOrDefault(false)
            if (!exists && path !in snapshot.dirtyPaths) continue
            recovered += path
        }
        val visible = restore(snapshot.openFiles, recovered, snapshot.dirtyPaths, snapshot.activeFile)
        for (path in visible) {
            val doc = state.document(path)
            val deferred = snapshot.deferredBuffers[path]
            if (deferred != null) doc.restoreDeferred(deferred, snapshot.selections[path], path in snapshot.reviewFiles)
            else if (path in snapshot.dirtyBuffers) {
                doc.ensureLoaded(files)
                doc.restoreUnsaved(snapshot.dirtyBuffers.getValue(path))
                snapshot.selections[path]?.let { doc.captureSelection(it.start, it.end) }
                if (path in snapshot.reviewFiles) doc.setReviewMode(true)
            }
        }
        return visible
    }

    fun restore(
        openTabs: List<String>, recoveredPaths: List<String>, dirtyPaths: Set<String>, activePath: String = "",
    ): List<String> {
        val available = recoveredPaths.toSet()
        val dirty = recoveredPaths.filter { it in dirtyPaths }.distinct()
        val cleanSlots = (MAX_TABS - dirty.size).coerceAtLeast(0)
        val visible = (openTabs + dirty).filter { it in available }.distinct()
        val clean = visible.filterNot { it in dirtyPaths }
        val keptClean = (listOf(activePath).filter { it in clean } + clean).distinct().take(cleanSlots).toSet()
        // Exceptional legacy recovery can exceed 32 tabs; every unsaved buffer must stay visible.
        return visible.filter { it in dirtyPaths || it in keptClean }
    }
}
