package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// Transactional multi-file text patcher with full preflight validation and rollback.
object PatchTool {
    sealed interface Op {
        data class Add(val path: String, val content: String) : Op
        data class Update(val path: String, val search: String, val replace: String, val moveTo: String?) : Op
        data class Delete(val path: String) : Op
    }

    fun parse(patchText: String): List<Op> {
        require(patchText.length <= 2_000_000) { "Patch too large" }
        val ops = mutableListOf<Op>()
        val lines = patchText.lines()
        var i = 0
        while (i < lines.size) {
            val h = lines[i].trim()
            when {
                h.startsWith("*** Add File:") -> {
                    val path = h.removePrefix("*** Add File:").trim()
                    require(path.isNotEmpty()) { "Add File tanpa path" }
                    val buf = StringBuilder(); i++
                    while (i < lines.size && !lines[i].startsWith("*** ")) { buf.appendLine(lines[i]); i++ }
                    ops += Op.Add(path, buf.toString().trimEnd('\n'))
                }
                h.startsWith("*** Update File:") -> {
                    val path = h.removePrefix("*** Update File:").trim(); require(path.isNotEmpty())
                    i++
                    var search: String? = null; var replace: String? = null; var moveTo: String? = null
                    while (i < lines.size && !lines[i].startsWith("*** Add File:") &&
                        !lines[i].startsWith("*** Update File:") && !lines[i].startsWith("*** Delete File:")) {
                        val l = lines[i]
                        when {
                            l.startsWith("*** Search:") -> {
                                val sb = StringBuilder(l.removePrefix("*** Search:").trimStart(' ')); i++
                                while (i < lines.size && !lines[i].startsWith("*** ")) { sb.appendLine(); sb.append(lines[i]); i++ }
                                search = sb.toString()
                            }
                            l.startsWith("*** Replace:") -> {
                                val sb = StringBuilder(l.removePrefix("*** Replace:").trimStart(' ')); i++
                                while (i < lines.size && !lines[i].startsWith("*** ")) { sb.appendLine(); sb.append(lines[i]); i++ }
                                replace = sb.toString()
                            }
                            l.startsWith("*** Move to:") -> { moveTo = l.removePrefix("*** Move to:").trim(); i++ }
                            l.isBlank() -> i++
                            else -> throw IllegalArgumentException("Baris tak dikenal di Update $path: $l")
                        }
                    }
                    require(!search.isNullOrEmpty() && replace != null) { "Update $path butuh Search + Replace" }
                    ops += Op.Update(path, search, replace, moveTo?.takeIf { it.isNotBlank() })
                }
                h.startsWith("*** Delete File:") -> {
                    val path = h.removePrefix("*** Delete File:").trim(); require(path.isNotEmpty())
                    ops += Op.Delete(path); i++
                }
                h.isEmpty() -> i++
                else -> throw IllegalArgumentException("Marker tak dikenal: $h")
            }
        }
        require(ops.isNotEmpty()) { "patchText kosong / tanpa operasi" }
        require(ops.size <= 100) { "Too many patch operations" }
        return ops
    }

    private suspend fun preflight(ops: List<Op>, files: FileRepository): Map<String, String?> {
        val touched = linkedSetOf<String>()
        val before = linkedMapOf<String, String?>()
        suspend fun capture(path: String): String? {
            files.resolveChecked(path) 
            if (path !in before) before[path] = if (files.exists(path)) files.readTextForEdit(path) else null
            return before[path]
        }
        for (op in ops) when (op) {
            is Op.Add -> {
                require(touched.add(op.path)) { "Path touched more than once: ${op.path}" }
                require(capture(op.path) == null) { "Add target already exists: ${op.path}" }
            }
            is Op.Update -> {
                require(touched.add(op.path)) { "Path touched more than once: ${op.path}" }
                val old = capture(op.path) ?: throw IllegalArgumentException("Update target missing: ${op.path}")
                val count = countOccurrences(old, op.search)
                require(count == 1) { "Search in ${op.path} must match exactly once (found $count)" }
                op.moveTo?.let { dst ->
                    require(dst != op.path) { "Move destination equals source: $dst" }
                    require(touched.add(dst)) { "Path touched more than once: $dst" }
                    require(capture(dst) == null) { "Move destination already exists: $dst" }
                }
            }
            is Op.Delete -> {
                require(touched.add(op.path)) { "Path touched more than once: ${op.path}" }
                require(capture(op.path) != null) { "Delete target missing: ${op.path}" }
            }
        }
        require(before.values.filterNotNull().sumOf { it.length.toLong() } <= 5_000_000L) { "Patch touches too much text for a safe mobile transaction" }
        return before
    }

    // Applying later must match this snapshot.
    suspend fun reviewSnapshot(ops: List<Op>, files: FileRepository): Map<String, String?> = preflight(ops, files)

    fun previewReviewed(ops: List<Op>, before: Map<String, String?>): String = buildString {
        for (op in ops) when (op) {
            is Op.Add -> { appendLine("+++ ADD ${op.path}"); appendLine(op.content.take(3_000)) }
            is Op.Update -> {
                val old = before[op.path]
                if (old == null) {
                    appendLine("--- ERROR: File ${op.path} not found or is empty")
                    continue
                }
                val updated = old.replaceFirst(op.search, op.replace)
                appendLine("--- UPDATE ${op.path}" + (op.moveTo?.let { " -> $it" } ?: ""))
                appendLine(DiffUtil.unified(old, updated))
            }
            is Op.Delete -> appendLine("--- DELETE ${op.path}")
        }
    }.take(12_000)

    suspend fun preview(ops: List<Op>, files: FileRepository): String =
        previewReviewed(ops, reviewSnapshot(ops, files))

    data class WorkspaceReview(
        val before: Map<String, String?>,
        val liveDocuments: Map<String, WorkspaceDocumentSnapshot>,
    )

    private suspend fun preflightWorkspace(
        ops: List<Op>,
        files: FileRepository,
        documents: WorkspaceDocumentAuthority?,
    ): WorkspaceReview {
        val touched = linkedSetOf<String>()
        val before = linkedMapOf<String, String?>()
        val live = linkedMapOf<String, WorkspaceDocumentSnapshot>()

        suspend fun capture(path: String): String? {
            files.resolveChecked(path)
            if (path in before) return before[path]
            documents?.snapshot(path)?.takeIf { it.loaded }?.let { snapshot ->
                live[path] = snapshot
                if (snapshot.kind == WorkspaceDocumentKind.TEXT) {
                    before[path] = snapshot.content
                    return snapshot.content
                }
            }
            val value = if (files.exists(path)) files.readTextForEdit(path) else null
            before[path] = value
            return value
        }

        for (op in ops) when (op) {
            is Op.Add -> {
                require(touched.add(op.path)) { "Path touched more than once: ${op.path}" }
                val old = capture(op.path)
                require(old == null && op.path !in live) { "Add target already exists: ${op.path}" }
            }
            is Op.Update -> {
                require(touched.add(op.path)) { "Path touched more than once: ${op.path}" }
                val old = capture(op.path) ?: throw IllegalArgumentException("Update target missing: ${op.path}")
                val count = countOccurrences(old, op.search)
                require(count == 1) { "Search in ${op.path} must match exactly once (found $count)" }
                op.moveTo?.let { dst ->
                    require(op.path !in live) {
                        "Move in apply_patch requires the source to be closed in the editor: ${op.path}. " +
                            "Rename/move the path separately so live editor identity cannot be lost."
                    }
                    require(dst != op.path) { "Move destination equals source: $dst" }
                    require(touched.add(dst)) { "Path touched more than once: $dst" }
                    val destination = capture(dst)
                    require(destination == null && dst !in live) { "Move destination already exists: $dst" }
                }
            }
            is Op.Delete -> {
                require(touched.add(op.path)) { "Path touched more than once: ${op.path}" }
                require(capture(op.path) != null || op.path in live) { "Delete target missing: ${op.path}" }
                require(live[op.path]?.dirty != true) {
                    "Delete conflicts with unsaved editor buffer: ${op.path}. Save/discard it before deleting."
                }
            }
        }
        require(before.values.filterNotNull().sumOf { it.length.toLong() } <= 5_000_000L) {
            "Patch touches too much text for a safe mobile transaction"
        }
        return WorkspaceReview(before, live)
    }

    suspend fun reviewWorkspaceSnapshot(
        ops: List<Op>,
        files: FileRepository,
        documents: WorkspaceDocumentAuthority?,
    ): WorkspaceReview = preflightWorkspace(ops, files, documents)

    fun previewWorkspaceReviewed(ops: List<Op>, review: WorkspaceReview): String =
        previewReviewed(ops, review.before)

    suspend fun applyWorkspaceReviewed(
        ops: List<Op>,
        files: FileRepository,
        history: EditHistory,
        documents: WorkspaceDocumentAuthority?,
        lsp: LspManager,
        reviewed: WorkspaceReview,
    ): String {
        val current = preflightWorkspace(ops, files, documents)
        require(current == reviewed) {
            "Patch target changed after review. Re-run the patch so the user can review the current editor/workspace state."
        }
        val pending = history.prepareBatch("patch ${ops.size} operation(s)", reviewed.before)
        data class AppliedLive(val path: String, val before: String, val after: WorkspaceDocumentVersion)
        val appliedLive = mutableListOf<AppliedLive>()
        try {
            for (op in ops) when (op) {
                is Op.Add -> files.writeText(op.path, op.content)
                is Op.Update -> {
                    val old = reviewed.before[op.path] ?: continue
                    val updated = old.replaceFirst(op.search, op.replace)
                    if (op.moveTo != null) {
                        check(files.move(op.path, op.moveTo)) { "Move failed: ${op.path} -> ${op.moveTo}" }
                        files.writeText(op.moveTo, updated)
                    } else {
                        val live = reviewed.liveDocuments[op.path]
                            ?.takeIf { it.kind == WorkspaceDocumentKind.TEXT && it.loaded }
                        if (live != null) {
                            val authority = documents ?: error("Live editor authority disappeared for ${op.path}")
                            when (val result = authority.replaceText(
                                op.path, live.version, updated, "Agent patch — review before saving",
                            )) {
                                is WorkspaceDocumentMutationResult.Applied -> {
                                    runSuspendCatching { lsp.didChange(op.path, result.after.content) }
                                    appliedLive += AppliedLive(op.path, old, result.after.version)
                                }
                                is WorkspaceDocumentMutationResult.Conflict -> error("Live document changed during patch: ${op.path}")
                                is WorkspaceDocumentMutationResult.ReadOnly -> error("Live document is read-only: ${op.path}")
                                WorkspaceDocumentMutationResult.NotLive -> error("Live document disappeared during patch: ${op.path}")
                            }
                        } else files.writeText(op.path, updated)
                    }
                }
                is Op.Delete -> check(files.delete(op.path)) { "Delete failed: ${op.path}" }
            }
            history.commitBatch(pending, appliedLive.associate { it.path to it.after })
            return "OK: ${ops.size} operasi patch diterapkan secara transaksional"
        } catch (t: Throwable) {
            val rollbackErrors = mutableListOf<String>()
            withContext(NonCancellable) {
                for (live in appliedLive.asReversed()) {
                    val authority = documents
                    if (authority == null) {
                        rollbackErrors += "${live.path}: editor authority unavailable"
                        continue
                    }
                    when (val result = authority.replaceText(live.path, live.after, live.before, "Rollback Agent patch")) {
                        is WorkspaceDocumentMutationResult.Applied -> runSuspendCatching { lsp.didChange(live.path, result.after.content) }
                        is WorkspaceDocumentMutationResult.Conflict -> rollbackErrors += "${live.path}: user edited buffer during rollback"
                        is WorkspaceDocumentMutationResult.ReadOnly -> rollbackErrors += "${live.path}: buffer became read-only"
                        WorkspaceDocumentMutationResult.NotLive -> rollbackErrors += "${live.path}: buffer disappeared"
                    }
                }
                val livePaths = appliedLive.mapTo(hashSetOf()) { it.path }
                for ((path, content) in reviewed.before.entries.toList().asReversed()) {
                    if (path in livePaths) continue
                    runSuspendCatching {
                        if (content == null) {
                            if (files.exists(path)) files.delete(path)
                        } else files.writeText(path, content)
                    }.exceptionOrNull()?.let { rollbackErrors += "$path: ${it.message ?: "rollback failed"}" }
                }
                history.discardBatch(pending)
            }
            if (t is CancellationException) throw t
            val suffix = if (rollbackErrors.isEmpty()) "" else
                "; rollback preserved newer editor state but had conflicts: ${rollbackErrors.joinToString(" | ").take(1_000)}"
            throw IllegalStateException("Patch rolled back: ${t.message}$suffix", t)
        }
    }

    suspend fun applyReviewed(
        ops: List<Op>,
        files: FileRepository,
        history: EditHistory,
        reviewed: Map<String, String?>,
    ): String {
        val current = preflight(ops, files)
        require(current == reviewed) {
            "Patch target changed after review. Re-run the patch so the user can review the current file state."
        }
        return applyPrepared(ops, files, history, reviewed)
    }

    suspend fun apply(ops: List<Op>, files: FileRepository, history: EditHistory): String =
        applyPrepared(ops, files, history, preflight(ops, files))

    private suspend fun applyPrepared(
        ops: List<Op>,
        files: FileRepository,
        history: EditHistory,
        before: Map<String, String?>,
    ): String {
        history.snapshotBatch("patch ${ops.size} operation(s)", before)
        try {
            for (op in ops) when (op) {
                is Op.Add -> files.writeText(op.path, op.content)
                is Op.Update -> {
                    val old = before[op.path] ?: continue
                    val updated = old.replaceFirst(op.search, op.replace)
                    if (op.moveTo != null) {
                        files.writeText(op.moveTo, updated)
                        files.delete(op.path)
                    } else files.writeText(op.path, updated)
                }
                is Op.Delete -> check(files.delete(op.path)) { "Delete failed: ${op.path}" }
            }
            return "OK: ${ops.size} operasi patch diterapkan secara transaksional"
        } catch (t: Throwable) {
            // Rollback must complete even when the original operation was cancelled.
            withContext(NonCancellable) {
                for ((path, content) in before.entries.toList().asReversed()) {
                    runSuspendCatching {
                        if (content == null) { if (files.exists(path)) files.delete(path) }
                        else files.writeText(path, content)
                    }
                }
                history.discardLast()
            }
            if (t is CancellationException) throw t
            throw IllegalStateException("Patch rolled back: ${t.message}", t)
        }
    }
    private fun countOccurrences(text: String, needle: String): Int {
        require(needle.isNotEmpty()) { "Search must not be empty" }
        var count = 0
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return count
            count++
            from = i + needle.length
        }
    }

}
