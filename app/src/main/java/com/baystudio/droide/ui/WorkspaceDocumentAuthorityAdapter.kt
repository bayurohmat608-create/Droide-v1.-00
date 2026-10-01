package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.LspManager
import com.baystudio.droide.core.WorkspaceDocumentAuthority
import com.baystudio.droide.core.WorkspaceDocumentConflictException
import com.baystudio.droide.core.WorkspaceDocumentKind
import com.baystudio.droide.core.WorkspaceDocumentMutationResult
import com.baystudio.droide.core.WorkspaceDocumentSnapshot
import com.baystudio.droide.core.WorkspaceDocumentVersion
import com.baystudio.droide.core.WorkspacePersistedReconciliation
import com.baystudio.droide.core.runSuspendCatching
import com.baystudio.droide.core.version
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext








internal class EditorWorkspaceDocumentAuthority(
    private val editorState: EditorWorkspaceState,
    private val editorDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val files: FileRepository? = null,
    private val lsp: LspManager? = null,
) : WorkspaceDocumentAuthority {

    constructor(
        editorState: EditorWorkspaceState,
        files: FileRepository,
        lsp: LspManager,
        editorDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    ) : this(editorState, editorDispatcher, files, lsp)

    override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = withContext(editorDispatcher) {
        editorState.peek(path)?.toAuthoritySnapshot()
    }

    override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = withContext(editorDispatcher) {
        editorState.allDocuments().map(EditorDocument::toAuthoritySnapshot)
    }

    override suspend fun dirtySnapshots(): List<WorkspaceDocumentSnapshot> = withContext(editorDispatcher) {
        editorState.dirtyDocuments().map(EditorDocument::toAuthoritySnapshot)
    }

    override suspend fun replaceText(
        path: String,
        expected: WorkspaceDocumentVersion,
        content: String,
        reason: String,
    ): WorkspaceDocumentMutationResult = withContext(editorDispatcher) {
        val document = editorState.peek(path) ?: return@withContext WorkspaceDocumentMutationResult.NotLive
        val before = document.toAuthoritySnapshot()
        if (!before.loaded || before.kind != WorkspaceDocumentKind.TEXT) {
            return@withContext WorkspaceDocumentMutationResult.NotLive
        }
        if (!before.editable) return@withContext WorkspaceDocumentMutationResult.ReadOnly(before)
        if (before.version != expected) return@withContext WorkspaceDocumentMutationResult.Conflict(before)
        document.applyWorkspaceText(content, reason)
        WorkspaceDocumentMutationResult.Applied(before, document.toAuthoritySnapshot())
    }

    override suspend fun validatePersistedMutation(mutation: FileRepository.Mutation) {
        val conflicts = withContext(editorDispatcher) {
            when (mutation) {
                is FileRepository.Mutation.Write -> editorState.peek(mutation.path)
                    ?.takeIf { it.loaded && it.dirty && (mutation.text == null || it.content != mutation.text) }
                    ?.let { listOf(it.path) }.orEmpty()
                is FileRepository.Mutation.Delete -> editorState.documentsAtOrUnder(mutation.path)
                    .filter { it.dirty }.map { it.path }
                is FileRepository.Mutation.Move -> (
                    editorState.documentsAtOrUnder(mutation.from).filter { it.dirty }.map { it.path } +
                        editorState.documentsAtOrUnder(mutation.to).map { it.path }
                    ).distinct()
                is FileRepository.Mutation.CreateDirectory, is FileRepository.Mutation.Sync -> emptyList()
            }
        }
        if (conflicts.isNotEmpty()) throw WorkspaceDocumentConflictException(conflicts)
    }

    override suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation {
        val result = when (mutation) {
            is FileRepository.Mutation.Write -> reconcileWrite(mutation.path, mutation.text)
            is FileRepository.Mutation.CreateDirectory -> WorkspacePersistedReconciliation()
            is FileRepository.Mutation.Delete -> reconcileDelete(mutation.path)
            is FileRepository.Mutation.Move -> reconcileMove(mutation.from, mutation.to)
            is FileRepository.Mutation.Sync -> reconcilePersisted(mutation.path, failOnConflict = true)
        }
        if (result.conflicts.isNotEmpty()) throw WorkspaceDocumentConflictException(result.conflicts)
        return result
    }

    override suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation =
        reconcilePersisted(path = null, failOnConflict = false)

    private suspend fun reconcileWrite(path: String, text: String?): WorkspacePersistedReconciliation {
        val actions = mutableListOf<LspAction>()
        val result = withContext(editorDispatcher) {
            val document = editorState.peek(path) ?: return@withContext WorkspacePersistedReconciliation()
            if (!document.loaded) return@withContext WorkspacePersistedReconciliation()
            if (text != null && document.kind == EditorDocumentKind.TEXT) {
                if (document.dirty && document.content != text) {
                    return@withContext WorkspacePersistedReconciliation(conflicts = listOf(path))
                }
                if (document.content == text) {
                    val baselineChanged = document.dirty
                    document.acceptPersistedBaseline(text, if (baselineChanged) "Saved" else document.status)
                    return@withContext WorkspacePersistedReconciliation(
                        refreshed = if (baselineChanged) listOf(path) else emptyList(),
                    )
                }
                document.acceptPersistedText(text, "Reloaded after persisted workspace write")
                if (document.fullIntelligence) actions += LspAction.Change(path, text)
                return@withContext WorkspacePersistedReconciliation(refreshed = listOf(path))
            }
            if (document.dirty) {
                return@withContext WorkspacePersistedReconciliation(conflicts = listOf(path))
            }
            val repository = files ?: return@withContext WorkspacePersistedReconciliation()
            val wasLspDocument = document.fullIntelligence
            document.reload(repository)
            when {
                wasLspDocument && document.fullIntelligence -> actions += LspAction.Change(path, document.content)
                wasLspDocument -> actions += LspAction.Close(path)
                document.fullIntelligence -> actions += LspAction.Open(path, document.content)
            }
            WorkspacePersistedReconciliation(refreshed = listOf(path))
        }
        runLspActions(actions)
        return result
    }

    private suspend fun reconcileDelete(path: String): WorkspacePersistedReconciliation {
        val actions = mutableListOf<LspAction>()
        val result = withContext(editorDispatcher) {
            val affected = editorState.documentsAtOrUnder(path)
            val conflicts = affected.filter { it.dirty }.map { it.path }
            if (conflicts.isNotEmpty()) return@withContext WorkspacePersistedReconciliation(conflicts = conflicts)
            val removed = editorState.removePersistedPath(path)
            removed.filter { it.fullIntelligence }
                .forEach { actions += LspAction.Close(it.path) }
            WorkspacePersistedReconciliation(removed = removed.map { it.path })
        }
        runLspActions(actions)
        return result
    }

    private suspend fun reconcileMove(from: String, to: String): WorkspacePersistedReconciliation {
        val actions = mutableListOf<LspAction>()
        val result = withContext(editorDispatcher) {
            val source = editorState.documentsAtOrUnder(from)
            val destination = editorState.documentsAtOrUnder(to)
            val conflicts = (source.filter { it.dirty }.map { it.path } + destination.map { it.path }).distinct()
            if (conflicts.isNotEmpty()) return@withContext WorkspacePersistedReconciliation(conflicts = conflicts)
            val textByOldPath = source.filter { it.fullIntelligence }.associate { it.path to it.content }
            val remapped = editorState.remapPersistedPath(from, to)
            remapped.forEach { (oldPath, newPath) ->
                textByOldPath[oldPath]?.let { content ->
                    actions += LspAction.Close(oldPath)
                    actions += LspAction.Open(newPath, content)
                }
            }
            WorkspacePersistedReconciliation(remapped = remapped)
        }
        runLspActions(actions)
        return result
    }

    private data class ReconcileBaseline(
        val path: String,
        val version: WorkspaceDocumentVersion,
        val dirty: Boolean,
        val savedContent: String,
        val kind: EditorDocumentKind,
        val loaded: Boolean,
    )

    private sealed interface DiskState {
        data object Missing : DiskState
        data class Text(val content: String) : DiskState
        data object Other : DiskState
    }

    private suspend fun reconcilePersisted(path: String?, failOnConflict: Boolean): WorkspacePersistedReconciliation {
        val repository = files ?: return WorkspacePersistedReconciliation()
        val baselines = withContext(editorDispatcher) {
            val docs = if (path == null) editorState.allDocuments() else editorState.documentsAtOrUnder(path)
            docs.map {
                ReconcileBaseline(it.path, it.toAuthoritySnapshot().version, it.dirty, it.savedContent, it.kind, it.loaded)
            }
        }
        if (baselines.isEmpty()) return WorkspacePersistedReconciliation()

        val disk = linkedMapOf<String, DiskState>()
        for (baseline in baselines) {
            disk[baseline.path] = when {
                !repository.exists(baseline.path) -> DiskState.Missing
                else -> runCatching { DiskState.Text(repository.readTextForEdit(baseline.path)) as DiskState }
                    .getOrDefault(DiskState.Other)
            }
        }

        val actions = mutableListOf<LspAction>()
        val result = withContext(editorDispatcher) {
            val conflicts = mutableListOf<String>()
            for (baseline in baselines) {
                val current = editorState.peek(baseline.path) ?: continue
                if (current.toAuthoritySnapshot().version != baseline.version) {
                    conflicts += baseline.path
                    continue
                }
                if (baseline.dirty) {
                    val persisted = disk.getValue(baseline.path)
                    if (persisted !is DiskState.Text || persisted.content != baseline.savedContent) conflicts += baseline.path
                }
            }
            if (conflicts.isNotEmpty() && failOnConflict) {
                return@withContext WorkspacePersistedReconciliation(conflicts = conflicts.distinct())
            }

            val refreshed = mutableListOf<String>()
            val removed = mutableListOf<String>()
            for (baseline in baselines) {
                if (baseline.path in conflicts || baseline.dirty) continue
                val current = editorState.peek(baseline.path) ?: continue
                when (val persisted = disk.getValue(baseline.path)) {
                    DiskState.Missing -> {
                        val removedDocs = editorState.removePersistedPath(baseline.path)
                        removedDocs.filter { it.fullIntelligence }
                            .forEach { actions += LspAction.Close(it.path) }
                        removed += removedDocs.map { it.path }
                    }
                    is DiskState.Text -> {
                        if (current.loaded && current.kind == EditorDocumentKind.TEXT) {
                            if (current.content != persisted.content || current.savedContent != persisted.content) {
                                val wasLspDocument = current.fullIntelligence
                                current.acceptPersistedText(persisted.content, "Reloaded after external workspace change")
                                when {
                                    wasLspDocument && current.fullIntelligence -> actions += LspAction.Change(current.path, current.content)
                                    wasLspDocument -> actions += LspAction.Close(current.path)
                                    current.fullIntelligence -> actions += LspAction.Open(current.path, current.content)
                                }
                                refreshed += current.path
                            }
                        } else {
                            val wasLspDocument = current.fullIntelligence
                            current.reload(repository)
                            if (wasLspDocument && !current.fullIntelligence) actions += LspAction.Close(current.path)
                            else if (!wasLspDocument && current.fullIntelligence) actions += LspAction.Open(current.path, current.content)
                            refreshed += current.path
                        }
                    }
                    DiskState.Other -> {
                        val wasLspDocument = current.fullIntelligence
                        current.reload(repository)
                        when {
                            wasLspDocument && current.fullIntelligence -> actions += LspAction.Change(current.path, current.content)
                            wasLspDocument -> actions += LspAction.Close(current.path)
                            current.fullIntelligence -> actions += LspAction.Open(current.path, current.content)
                        }
                        refreshed += current.path
                    }
                }
            }
            WorkspacePersistedReconciliation(
                refreshed = refreshed.distinct(),
                removed = removed.distinct(),
                conflicts = conflicts.distinct(),
            )
        }
        runLspActions(actions)
        return result
    }

    private sealed interface LspAction {
        data class Open(val path: String, val content: String) : LspAction
        data class Change(val path: String, val content: String) : LspAction
        data class Close(val path: String) : LspAction
    }

    private suspend fun runLspActions(actions: List<LspAction>) {
        val manager = lsp ?: return
        actions.forEach { action ->
            runSuspendCatching {
                when (action) {
                    is LspAction.Open -> manager.didOpen(action.path, action.content)
                    is LspAction.Change -> manager.didChange(action.path, action.content)
                    is LspAction.Close -> manager.didClose(action.path)
                }
            }
        }
    }
}

private fun EditorDocument.toAuthoritySnapshot(): WorkspaceDocumentSnapshot = WorkspaceDocumentSnapshot(
    path = path,
    content = content,
    loaded = loaded,
    kind = when (kind) {
        EditorDocumentKind.TEXT -> if (largeFileOptimized) WorkspaceDocumentKind.LARGE else WorkspaceDocumentKind.TEXT
        EditorDocumentKind.IMAGE -> WorkspaceDocumentKind.IMAGE
        EditorDocumentKind.BINARY -> WorkspaceDocumentKind.BINARY
        EditorDocumentKind.LARGE -> WorkspaceDocumentKind.LARGE
    },
    dirty = dirty,
    editable = agentEditable,
    revision = revision,
    changeVersion = changeVersion,
    selectionStart = selectionStart,
    selectionEnd = selectionEnd,
    savedContentLength = savedContent.length,
)
