package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest

// The language server may propose edits, but it never receives mutation authority.






class AgentLspEditActions(
    private val files: FileRepository,
    private val lsp: LspManager,
    private val history: EditHistory,
    private val documents: WorkspaceDocumentAuthority?,
) {
    data class Prepared(
        val title: String,
        val workspaceEdit: LspWorkspaceEdit,
        val before: Map<String, String>,
        val liveVersions: Map<String, WorkspaceDocumentVersion>,
        val after: Map<String, String>,
        val preview: String,
        val permissionResource: String,
    )

    suspend fun listActions(path: String, line: Int, col: Int): String {
        files.resolveChecked(path)
        val actions = lsp.codeActionsAt(path, line, col)
        if (actions.isEmpty()) return "No LSP code actions available at $path:$line:$col."
        return buildString {
            append("LSP_CODE_ACTIONS path=").append(path).append(" line=").append(line).append(" col=").append(col).append('\n')
            actions.take(60).forEachIndexed { index, action ->
                append(index + 1).append(". ").append(action.title.take(240))
                action.kind?.let { append(" · ").append(it.take(120)) }
                if (action.preferred) append(" · preferred")
                action.disabledReason?.let { append(" · disabled: ").append(it.take(240)) }
                if (action.edit != null) append(" · transparent edit")
                else if (!action.rawJson.isNullOrBlank()) append(" · resolve required")
                action.commandTitle?.let { append(" · command=").append(it.take(160)) }
                append('\n')
            }
        }.take(12_000)
    }

    suspend fun prepareCodeAction(path: String, line: Int, col: Int, title: String): Prepared {
        require(title.isNotBlank()) { "Code-action title is required" }
        files.resolveChecked(path)
        val candidates = lsp.codeActionsAt(path, line, col).filter { it.title == title }
        require(candidates.size == 1) {
            when {
                candidates.isEmpty() -> "Code action is no longer available: $title"
                else -> "Code-action title is ambiguous at the current location; list actions again and choose a unique title"
            }
        }
        val resolved = lsp.resolveCodeAction(path, candidates.single())
        require(resolved.disabledReason == null) { resolved.disabledReason ?: "Code action is disabled" }
        val edit = resolved.edit ?: error(
            if (resolved.commandTitle != null) {
                "Code action requires opaque server command '${resolved.commandTitle}'. Agent mutation is limited to transparent WorkspaceEdit payloads."
            } else "Language server returned no transparent workspace edit"
        )
        return prepare("Code action: ${resolved.title}", edit)
    }

    suspend fun prepareFormat(path: String): Prepared {
        files.resolveChecked(path)
        val edit = lsp.formatDocument(path) ?: error("Language server does not provide document formatting for $path")
        require(edit.edits.isNotEmpty()) { "Document is already formatted or formatter returned no edits" }
        return prepare("Format document: $path", edit)
    }

    suspend fun apply(prepared: Prepared): String {
        require(prepared.after.isNotEmpty()) { "Workspace edit has no effective text changes" }
        
        for ((path, expectedText) in prepared.before) {
            val live = documents?.snapshot(path)?.takeIf { it.loaded }
            if (live != null) {
                require(live.kind == WorkspaceDocumentKind.TEXT && live.editable) { "Live document is not Agent-editable: $path" }
                require(live.content == expectedText && live.version == prepared.liveVersions[path]) {
                    "Live document changed after LSP review: $path. Re-run the action."
                }
            } else {
                require(files.exists(path)) { "Workspace edit target disappeared after review: $path" }
                require(files.readTextForEdit(path) == expectedText) { "Workspace edit target changed after review: $path. Re-run the action." }
            }
        }

        val pending = history.prepareBatch(prepared.title, prepared.before)
        val appliedLive = mutableListOf<Triple<String, String, WorkspaceDocumentVersion>>()
        val appliedDisk = mutableListOf<String>()
        try {
            for ((path, updated) in prepared.after) {
                val expectedVersion = prepared.liveVersions[path]
                if (expectedVersion != null) {
                    val authority = documents ?: error("Editor authority unavailable for live LSP edit: $path")
                    when (val result = authority.replaceText(path, expectedVersion, updated, prepared.title)) {
                        is WorkspaceDocumentMutationResult.Applied -> {
                            appliedLive += Triple(path, result.before.content, result.after.version)
                            runSuspendCatching { lsp.didChange(path, result.after.content) }
                        }
                        is WorkspaceDocumentMutationResult.Conflict -> error("Live document changed during LSP edit: $path")
                        is WorkspaceDocumentMutationResult.ReadOnly -> error("Live document became read-only: $path")
                        WorkspaceDocumentMutationResult.NotLive -> error("Live document disappeared during LSP edit: $path")
                    }
                } else {
                    documents?.validatePersistedMutation(FileRepository.Mutation.Write(path, updated))
                    files.writeText(path, updated)
                    appliedDisk += path
                }
            }
            history.commitBatch(pending, appliedLive.associate { it.first to it.third })
            return buildString {
                append("LSP_EDIT_APPLIED title=").append(prepared.title.take(240)).append('\n')
                append("files=").append(prepared.after.size).append('\n')
                append("live_buffers=").append(appliedLive.size).append('\n')
                append("persisted_files=").append(appliedDisk.size).append('\n')
                if (appliedLive.isNotEmpty()) append("note=Live editor edits remain unsaved until the user/IDE saves them; disk-backed verification does not validate unsaved text.\n")
                append("status=applied_reviewed_workspace_edit")
            }
        } catch (t: Throwable) {
            val rollbackErrors = mutableListOf<String>()
            withContext(NonCancellable) {
                for ((path, oldText, afterVersion) in appliedLive.asReversed()) {
                    when (val rollback = documents?.replaceText(path, afterVersion, oldText, "Rollback ${prepared.title}")) {
                        is WorkspaceDocumentMutationResult.Applied -> runSuspendCatching { lsp.didChange(path, rollback.after.content) }
                        null, WorkspaceDocumentMutationResult.NotLive -> rollbackErrors += "$path: editor disappeared"
                        is WorkspaceDocumentMutationResult.Conflict -> rollbackErrors += "$path: newer editor change preserved"
                        is WorkspaceDocumentMutationResult.ReadOnly -> rollbackErrors += "$path: buffer became read-only"
                    }
                }
                for (path in appliedDisk.asReversed()) {
                    runSuspendCatching { files.writeText(path, prepared.before.getValue(path)) }
                        .exceptionOrNull()?.let { rollbackErrors += "$path: ${it.message ?: "rollback failed"}" }
                }
                history.discardBatch(pending)
            }
            if (t is CancellationException) throw t
            val suffix = if (rollbackErrors.isEmpty()) "" else "; rollback notes: ${rollbackErrors.joinToString(" | ").take(1_000)}"
            throw IllegalStateException("LSP workspace edit rolled back: ${t.message}$suffix", t)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private suspend fun prepare(title: String, edit: LspWorkspaceEdit): Prepared {
        require(edit.edits.isNotEmpty()) { "Language server returned no edits" }
        require(edit.fileCount in 1..32) { "Language server edit touches too many files (${edit.fileCount})" }
        val grouped = edit.edits.groupBy { it.path }
        val before = linkedMapOf<String, String>()
        val liveVersions = linkedMapOf<String, WorkspaceDocumentVersion>()
        val after = linkedMapOf<String, String>()
        var totalChars = 0L
        for ((path, edits) in grouped) {
            files.resolveChecked(path)
            val live = documents?.snapshot(path)?.takeIf { it.loaded }
            val current = if (live != null) {
                require(live.kind == WorkspaceDocumentKind.TEXT && live.editable) { "Workspace edit targets a non-editable live document: $path" }
                liveVersions[path] = live.version
                live.content
            } else {
                require(files.exists(path)) { "Workspace edit target does not exist: $path" }
                files.readTextForEdit(path)
            }
            edit.expectedContent[path]?.let { expected ->
                require(current == expected) { "Document changed since the LSP request: $path. Re-run the action." }
            }
            totalChars += current.length.toLong()
            require(totalChars <= 5_000_000L) { "Language server edit touches too much source text for one mobile transaction" }
            val updated = TextEditApplier.apply(current, edits.map { it.range })
            if (updated != current) {
                before[path] = current
                after[path] = updated
            }
        }
        require(after.isNotEmpty()) { "Language server edit has no effective text changes" }
        val preview = buildString {
            appendLine(title)
            after.forEach { (path, updated) ->
                appendLine("--- $path")
                appendLine(DiffUtil.unified(before.getValue(path), updated).take(5_000))
            }
        }.take(12_000)
        val resource = "lsp-edit:${after.keys.sorted().joinToString(",")}:${sha256(preview)}"
        return Prepared(title, edit, before, liveVersions, after, preview, resource)
    }
}
