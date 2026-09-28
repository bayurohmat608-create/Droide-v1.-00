package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean





class EditHistory(
    private val files: FileRepository,
    private val documents: WorkspaceDocumentAuthority? = null,
    private val lsp: LspManager? = null,
) {
    data class Snapshot(
        val path: String,
        val backup: File,
        val existed: Boolean,
        val liveExpected: WorkspaceDocumentVersion? = null,
    )
    data class Entry(val label: String, val snapshots: List<Snapshot>)
    data class PendingLive internal constructor(val path: String, val backup: File)
    data class PendingBatch internal constructor(val label: String, val snapshots: List<Snapshot>)

    private val stack = ArrayDeque<Entry>()
    private val dir = PathSecurity.resolveWithin(files.root, ".droide/backups")
    private val initialized = AtomicBoolean(false)

    private fun ensureDir(): File {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create edit-history directory: ${dir.path}" }
        if (initialized.compareAndSet(false, true)) {
            dir.listFiles()?.forEach { stale -> runCatching { PathSecurity.deleteTreeNoFollow(stale) } }
        }
        return dir
    }

    suspend fun snapshot(path: String, current: String?) = snapshotBatch("edit $path", mapOf(path to current))

    suspend fun prepareLive(path: String, before: String): PendingLive = withContext(Dispatchers.IO) {
        val backupDir = ensureDir()
        val safe = path.replace('/', '_').replace('\\', '_').take(100)
        val bak = File(backupDir, "${System.currentTimeMillis()}_live_$safe.bak")
        bak.writeText(before)
        PendingLive(path, bak)
    }

    fun commitLive(pending: PendingLive, expectedAfter: WorkspaceDocumentVersion) {
        stack.addLast(Entry("edit ${pending.path}", listOf(Snapshot(pending.path, pending.backup, true, expectedAfter))))
        trim()
    }

    suspend fun discardLive(pending: PendingLive) = withContext(Dispatchers.IO) {
        runCatching { pending.backup.delete() }
    }

    suspend fun snapshotBatch(label: String, states: Map<String, String?>) = withContext(Dispatchers.IO) {
        val pending = prepareBatch(label, states)
        stack.addLast(Entry(pending.label, pending.snapshots))
        trim()
    }

    suspend fun prepareBatch(label: String, states: Map<String, String?>): PendingBatch = withContext(Dispatchers.IO) {
        require(states.isNotEmpty()) { "No states to snapshot" }
        val backupDir = ensureDir()
        val stamp = System.currentTimeMillis()
        val snapshots = states.entries.mapIndexed { index, (path, content) ->
            val safe = path.replace('/', '_').replace('\\', '_').take(100)
            val bak = File(backupDir, "${stamp}_${index}_$safe.bak")
            bak.parentFile?.mkdirs()
            bak.writeText(content ?: "")
            Snapshot(path, bak, content != null)
        }
        PendingBatch(label, snapshots)
    }

    fun commitBatch(pending: PendingBatch, liveExpected: Map<String, WorkspaceDocumentVersion> = emptyMap()) {
        val snapshots = pending.snapshots.map { snapshot ->
            snapshot.copy(liveExpected = liveExpected[snapshot.path])
        }
        stack.addLast(Entry(pending.label, snapshots))
        trim()
    }

    suspend fun discardBatch(pending: PendingBatch) = withContext(Dispatchers.IO) {
        pending.snapshots.forEach { runCatching { it.backup.delete() } }
    }

    suspend fun discardLast() = withContext(Dispatchers.IO) {
        stack.removeLastOrNull()?.snapshots?.forEach { runCatching { it.backup.delete() } }
    }

    suspend fun undo(): String {
        val e = stack.removeLastOrNull() ?: return "Tidak ada yang bisa di-undo."
        return try {
            
            for (s in e.snapshots.asReversed()) {
                val backupText = withContext(Dispatchers.IO) { s.backup.readText() }
                if (s.liveExpected != null) {
                    val authority = documents ?: error("Live editor authority is unavailable for undo: ${s.path}")
                    when (val result = authority.replaceText(s.path, s.liveExpected, backupText, "Undo Agent edit")) {
                        is WorkspaceDocumentMutationResult.Applied -> runSuspendCatching { lsp?.didChange(s.path, backupText) }
                        is WorkspaceDocumentMutationResult.Conflict -> error("Live document changed after Agent edit: ${s.path}")
                        is WorkspaceDocumentMutationResult.ReadOnly -> error("Live document is read-only: ${s.path}")
                        WorkspaceDocumentMutationResult.NotLive -> error("Live document is no longer available: ${s.path}")
                    }
                } else if (s.existed) files.writeText(s.path, backupText)
                else if (files.exists(s.path)) files.delete(s.path)
            }
            withContext(Dispatchers.IO) { e.snapshots.forEach { runCatching { it.backup.delete() } } }
            "Undone: ${e.label} (${e.snapshots.size} file${if (e.snapshots.size == 1) "" else "s"})"
        } catch (cancelled: CancellationException) {
            stack.addLast(e)
            throw cancelled
        } catch (t: Throwable) {
            
            stack.addLast(e)
            "Undo failed: ${t.message}"
        }
    }

    fun depth(): Int = stack.size

    private fun trim() {
        while (stack.size > 30) {
            stack.removeFirst().snapshots.forEach { runCatching { it.backup.delete() } }
        }
    }
}
