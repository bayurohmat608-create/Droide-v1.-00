package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

 
class AgentWorkspaceMutation(
    private val files: FileRepository,
    private val documents: WorkspaceDocumentAuthority? = null,
) {
    sealed interface Target {
        val path: String
        val content: String

        data class Live(
            override val path: String,
            val snapshot: WorkspaceDocumentSnapshot,
        ) : Target {
            override val content: String get() = snapshot.content
        }

        data class Disk(
            override val path: String,
            val existed: Boolean,
            override val content: String,
        ) : Target
    }

    suspend fun inspect(path: String): Target {
        val normalized = canonicalRelativePath(path)
        val live = documents?.snapshot(normalized)
            ?.takeIf { it.loaded && it.kind == WorkspaceDocumentKind.TEXT }
        if (live != null) return Target.Live(normalized, live)
        val existed = files.exists(normalized)
        val current = if (existed) files.readTextForEdit(normalized) else ""
        return Target.Disk(normalized, existed, current)
    }

    suspend fun replaceLive(
        target: Target.Live,
        expected: WorkspaceDocumentVersion,
        content: String,
        reason: String,
    ): WorkspaceDocumentMutationResult = documents?.replaceText(target.path, expected, content, reason)
        ?: WorkspaceDocumentMutationResult.NotLive

    fun expectedVersion(args: JsonObject): WorkspaceDocumentVersion? {
        val revision = args["expected_revision"]?.jsonPrimitive?.intOrNull
        val changeVersion = args["expected_change_version"]?.jsonPrimitive?.intOrNull
        if (revision == null && changeVersion == null) return null
        require(revision != null && changeVersion != null) {
            "expected_revision and expected_change_version must be supplied together"
        }
        return WorkspaceDocumentVersion(revision, changeVersion)
    }

    fun versionRequired(target: Target.Live): String =
        "ERROR: LIVE_DOCUMENT_VERSION_REQUIRED path=${target.path} revision=${target.snapshot.revision} " +
            "change_version=${target.snapshot.changeVersion}. Run read_file and retry with expected_revision + " +
            "expected_change_version so unsaved user edits cannot be overwritten."

    fun versionConflict(path: String, current: WorkspaceDocumentSnapshot): String =
        "ERROR: DOCUMENT_CHANGED path=$path current_revision=${current.revision} " +
            "current_change_version=${current.changeVersion}. The live editor buffer changed; re-run read_file " +
            "and recompute the edit."

    suspend fun finalizeLive(
        history: EditHistory,
        lsp: LspManager,
        undo: EditHistory.PendingLive,
        apply: suspend () -> WorkspaceDocumentMutationResult,
        path: String,
        missingVerb: String,
        success: (WorkspaceDocumentMutationResult.Applied) -> String,
    ): String {
        val result = try { apply() } catch (t: Throwable) { history.discardLive(undo); throw t }
        return when (result) {
            is WorkspaceDocumentMutationResult.Applied -> {
                history.commitLive(undo, result.after.version)
                val sync = runSuspendCatching { lsp.didChange(path, result.after.content) }
                success(result) + if (sync.isFailure)
                    "\nWARNING: LSP sync deferred: ${sync.exceptionOrNull()?.message ?: "unknown error"}" else ""
            }
            is WorkspaceDocumentMutationResult.Conflict -> { history.discardLive(undo); versionConflict(path, result.current) }
            is WorkspaceDocumentMutationResult.ReadOnly -> { history.discardLive(undo); "ERROR: live editor document is read-only: $path" }
            WorkspaceDocumentMutationResult.NotLive -> { history.discardLive(undo); "ERROR: live editor document disappeared before $missingVerb: $path; re-run read_file." }
        }
    }

    private fun canonicalRelativePath(path: String): String {
        val root = files.root.canonicalFile
        val target = PathSecurity.resolveWithin(root, path).canonicalFile
        return root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
    }
}
