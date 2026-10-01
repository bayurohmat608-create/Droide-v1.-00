package com.baystudio.droide.ui

import kotlin.jvm.JvmName

import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.baystudio.droide.core.EditorFilePerformanceMode
import com.baystudio.droide.core.EditorLargeFilePolicy
import com.baystudio.droide.core.EditorReviewModePolicy
import com.baystudio.droide.core.FileIconRegistry
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.LargeFileException
import com.baystudio.droide.core.runSuspendCatching








@Stable
enum class EditorDocumentKind { TEXT, IMAGE, BINARY, LARGE }

@Stable
class EditorDocument internal constructor(initialPath: String) {
    var path by mutableStateOf(initialPath)
        private set
    var content by mutableStateOf("")
        private set
    var savedContent by mutableStateOf("")
        private set
    var loaded by mutableStateOf(false)
        private set
    var kind by mutableStateOf(EditorDocumentKind.TEXT)
        private set
    
    var loadError by mutableStateOf<String?>(null)
        private set
    private var loadGeneration = 0L
    private var saveInProgress = false
    var status by mutableStateOf("Loading…")
    var revision by mutableIntStateOf(0)
        private set
    var changeVersion by mutableIntStateOf(0)
        private set
    var fileBytes by mutableLongStateOf(0L)
        private set
    var lineCountHint by mutableIntStateOf(1)
        private set
    var performanceMode by mutableStateOf(EditorFilePerformanceMode.FULL_INTELLIGENCE)
        private set
    private var optimizedBufferDirty by mutableStateOf(false)
    var selectionStart: Int = 0
        private set
    var selectionEnd: Int = 0
        private set
    @set:JvmName("setReviewModeState")
    var reviewMode by mutableStateOf(false)
        private set

    val dirty: Boolean get() = kind == EditorDocumentKind.TEXT && loaded && (optimizedBufferDirty || content != savedContent)
     
    val editable: Boolean get() = kind == EditorDocumentKind.TEXT && loaded
    val canEdit: Boolean get() = EditorReviewModePolicy.canEditText(kind == EditorDocumentKind.TEXT, loaded, reviewMode)
    val fullIntelligence: Boolean get() = editable && performanceMode == EditorFilePerformanceMode.FULL_INTELLIGENCE
    val largeFileOptimized: Boolean get() = editable && performanceMode == EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED
     
    val agentEditable: Boolean get() = EditorReviewModePolicy.canAgentEdit(canEdit, fullIntelligence)

    fun setReviewMode(enabled: Boolean) {
        if (!editable || reviewMode == enabled) return
        reviewMode = enabled
        status = if (enabled) "Review Mode · read-only" else if (dirty) "Review Mode off · unsaved changes" else "Review Mode off"
    }

    private fun profileCapturedText(text: String) {
        fileBytes = EditorLargeFilePolicy.utf8Bytes(text)
        lineCountHint = EditorLargeFilePolicy.lineCount(text, EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES + 1)
        val classified = EditorLargeFilePolicy.classify(fileBytes, lineCountHint).mode
        


        performanceMode = if (kind == EditorDocumentKind.TEXT && classified == EditorFilePerformanceMode.PREVIEW_ONLY) {
            EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED
        } else classified
    }

    fun capture(text: String) {
        if (kind != EditorDocumentKind.TEXT || !loaded) return
        if (reviewMode && content != text) return
        if (content != text) {
            content = text
            changeVersion++
        }
        profileCapturedText(text)
        optimizedBufferDirty = text != savedContent
        captureSelection(selectionStart, selectionEnd)
    }

    




    fun captureInsert(start: Int, inserted: String): Boolean {
        if (!canEdit) return false
        if (inserted.isEmpty()) return true
        if (largeFileOptimized) {
            val wasOverCeiling = fileBytes > EditorLargeFilePolicy.MAX_EDITABLE_BYTES
            fileBytes += EditorLargeFilePolicy.utf8Bytes(inserted)
            lineCountHint = (lineCountHint + inserted.count { it == '\n' }).coerceAtLeast(1)
            markOptimizedBufferChanged()
            if (!wasOverCeiling && fileBytes > EditorLargeFilePolicy.MAX_EDITABLE_BYTES) {
                status = "Buffer exceeds 20 MiB · keep editing to reduce it before Save"
            }
            return true
        }
        if (start !in 0..content.length) return false
        content = buildString(content.length + inserted.length) {
            append(this@EditorDocument.content, 0, start)
            append(inserted)
            append(this@EditorDocument.content, start, this@EditorDocument.content.length)
        }
        fileBytes += EditorLargeFilePolicy.utf8Bytes(inserted)
        lineCountHint = (lineCountHint + inserted.count { it == '\n' }).coerceAtLeast(1)
        changeVersion++
        if (fileBytes > EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_BYTES ||
            lineCountHint > EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES
        ) {
            performanceMode = EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED
            optimizedBufferDirty = true
            status = "Large File Performance Mode · editing optimized"
        }
        captureSelection(selectionStart, selectionEnd)
        return true
    }

    fun captureDelete(start: Int, deleted: String): Boolean {
        if (!canEdit) return false
        if (deleted.isEmpty()) return true
        if (largeFileOptimized) {
            val wasOverCeiling = fileBytes > EditorLargeFilePolicy.MAX_EDITABLE_BYTES
            fileBytes = (fileBytes - EditorLargeFilePolicy.utf8Bytes(deleted)).coerceAtLeast(0L)
            lineCountHint = (lineCountHint - deleted.count { it == '\n' }).coerceAtLeast(1)
            markOptimizedBufferChanged()
            if (wasOverCeiling && fileBytes <= EditorLargeFilePolicy.MAX_EDITABLE_BYTES) {
                status = "Large File Performance Mode · editing optimized"
            }
            return true
        }
        val end = start + deleted.length
        if (start < 0 || end > content.length) return false
        if (!content.regionMatches(start, deleted, 0, deleted.length)) return false
        content = buildString(content.length - deleted.length) {
            append(this@EditorDocument.content, 0, start)
            append(this@EditorDocument.content, end, this@EditorDocument.content.length)
        }
        fileBytes = (fileBytes - EditorLargeFilePolicy.utf8Bytes(deleted)).coerceAtLeast(0L)
        lineCountHint = (lineCountHint - deleted.count { it == '\n' }).coerceAtLeast(1)
        changeVersion++
        captureSelection(selectionStart, selectionEnd)
        return true
    }

    private fun markOptimizedBufferChanged() {
        optimizedBufferDirty = true
        changeVersion++
    }

    fun captureSelection(start: Int, end: Int = start) {
        
        val upper = if (largeFileOptimized) maxOf(content.length, start, end).coerceAtLeast(0) else content.length
        selectionStart = start.coerceIn(0, upper)
        selectionEnd = end.coerceIn(0, upper)
    }

    suspend fun ensureLoaded(files: FileRepository) {
        if (loaded) return
        reload(files)
    }

    suspend fun reload(files: FileRepository, discardUnsaved: Boolean = false) {
        if (dirty && !discardUnsaved) {
            status = "Reload skipped · unsaved edits preserved. Use Discard & reload to replace them."
            return
        }
        if (saveInProgress) {
            status = "Save in progress · reload after it finishes"
            return
        }
        val request = ++loadGeneration
        val path = this.path
        val startRevision = revision
        val startVersion = changeVersion
        fun stillCurrent() = request == loadGeneration && this.path == path &&
            revision == startRevision && changeVersion == startVersion
        // Publish a complete read atomically on Main; never clear the buffer before I/O.
        try {
            var nextKind = EditorDocumentKind.TEXT
            var nextMode = EditorFilePerformanceMode.FULL_INTELLIGENCE
            var nextStatus = ""
            var nextBytes = 0L
            var nextLines = 1
            val text = if (FileIconRegistry.isImageFile(path)) {
                nextKind = EditorDocumentKind.IMAGE
                nextStatus = "Image file — preview below"
                ""
            } else if (!files.exists(path)) {
                nextStatus = "New file: $path"
                ""
            } else {
                try {
                    files.readEditorText(path).also {
                        nextBytes = EditorLargeFilePolicy.utf8Bytes(it)
                        nextLines = EditorLargeFilePolicy.lineCount(it, EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES + 1)
                        nextMode = EditorLargeFilePolicy.classify(nextBytes, nextLines).mode
                        if (nextMode == EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED) {
                            nextStatus = "Large File Performance Mode · ${EditorLargeFilePolicy.formatBytes(nextBytes)} · editing enabled"
                        }
                    }
                } catch (e: LargeFileException) {
                    nextKind = EditorDocumentKind.LARGE
                    nextMode = EditorFilePerformanceMode.PREVIEW_ONLY
                    nextBytes = e.bytes
                    nextLines = EditorLargeFilePolicy.PREVIEW_LINES
                    nextStatus = "${EditorLargeFilePolicy.formatBytes(e.bytes)} · read-only preview (20 MiB limit)"
                    files.readRange(path, 1, EditorLargeFilePolicy.PREVIEW_LINES)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: com.baystudio.droide.core.UnsupportedTextEncodingException) {
                    nextKind = EditorDocumentKind.BINARY
                    nextStatus = "Unsupported encoding · original bytes preserved · convert a copy to UTF-8 before editing"
                    ""
                } catch (e: Exception) {
                    if (e.message?.contains("Binary file", ignoreCase = true) == true && !loaded) {
                        nextKind = EditorDocumentKind.BINARY
                        nextStatus = e.message ?: "Binary file"
                        ""
                    } else throw e
                }
            }
            if (!stillCurrent()) return
            content = text
            savedContent = text
            kind = nextKind
            performanceMode = nextMode
            fileBytes = nextBytes
            lineCountHint = nextLines
            optimizedBufferDirty = false
            loadError = null
            status = nextStatus
            loaded = true
            revision++
            captureSelection(selectionStart, selectionEnd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!stillCurrent()) return
            val message = "Cannot open $path: ${e.message ?: "unknown error"}"
            if (!loaded) loadError = message
            status = message
        }
    }

    suspend fun save(files: FileRepository): Result<Unit> {
        if (!editable) return Result.failure(IllegalStateException("This preview is read-only"))
        if (saveInProgress) return Result.failure(IllegalStateException("Save already in progress"))
        saveInProgress = true
        loadGeneration++
        val path = this.path
        val content = this.content
        val baseline = savedContent
        val startRevision = revision
        val startVersion = changeVersion
        try {
            return runSuspendCatching {
                check(EditorLargeFilePolicy.utf8Bytes(content) <= EditorLargeFilePolicy.MAX_EDITABLE_BYTES) {
                    "Buffer exceeds the 20 MiB mobile editing/save ceiling. Reduce the file before saving."
                }
                if (files.exists(path)) {
                    val diskNow = files.readEditorText(path)
                    check(diskNow == baseline) {
                        "File changed on disk since this editor buffer was loaded. Reload or review before saving."
                    }
                }
                check(this.path == path && revision == startRevision && changeVersion == startVersion) {
                    "Document changed while preparing Save. Review and save again."
                }
                files.writeEditorText(path, content)
                check(this.path == path) { "Document moved during Save. Review before saving again." }
                savedContent = content
                val changedDuringWrite = revision != startRevision || changeVersion != startVersion
                optimizedBufferDirty = if (largeFileOptimized && changedDuringWrite) true else this.content != content
                if (!changedDuringWrite) profileCapturedText(content)
                check(!dirty) { "Saved snapshot; newer edits remain unsaved. Save again before continuing." }
                status = "Saved — ${EditorLargeFilePolicy.lineCount(content)} lines"
            }
        } finally {
            saveInProgress = false
        }
    }

    fun discardUnsaved() {
        if (!editable) return
        content = savedContent
        optimizedBufferDirty = false
        profileCapturedText(content)
        status = "Changes discarded"
        revision++
        changeVersion++
    }

    fun restoreUnsaved(recoveredContent: String) {
        val originalUnavailable = !editable
        if (originalUnavailable) {
            // A missing, unreadable, or newly binary original must not make a valid recovery blob disappear.

            kind = EditorDocumentKind.TEXT
            loaded = true
            savedContent = ""
            loadError = null
        }
        content = recoveredContent
        profileCapturedText(content)
        optimizedBufferDirty = originalUnavailable || content != savedContent
        status = when {
            originalUnavailable -> "Recovered unsaved changes · original file unavailable; Save will check for conflicts"
            dirty -> "Recovered unsaved changes"
            else -> ""
        }
        revision++
        changeVersion++
    }

    fun applyWorkspaceText(updatedContent: String, reason: String = "Workspace edit") {
        check(agentEditable) { "Document is not Agent/LSP editable in its current performance mode: $path" }
        if (content == updatedContent) return
        content = updatedContent
        profileCapturedText(content)
        status = reason
        revision++
        changeVersion++
    }

    internal fun acceptPersistedText(text: String, reason: String): Boolean {
        check(kind == EditorDocumentKind.TEXT && loaded) { "Document is not editable text: $path" }
        val contentChanged = content != text
        content = text
        savedContent = text
        optimizedBufferDirty = false
        profileCapturedText(text)
        status = reason
        if (contentChanged) {
            revision++
            changeVersion++
            captureSelection(selectionStart, selectionEnd)
        }
        return contentChanged
    }

    internal fun acceptPersistedBaseline(text: String, reason: String) {
        check(kind == EditorDocumentKind.TEXT && loaded) { "Document is not editable text: $path" }
        check(content == text) { "Persisted baseline does not match live content: $path" }
        savedContent = text
        optimizedBufferDirty = false
        profileCapturedText(text)
        status = reason
    }

    internal fun remapPath(newPath: String) {
        check(newPath.isNotBlank()) { "Document path is blank" }
        if (path == newPath) return
        path = newPath
        revision++
    }
}


data class EditorWorkspacePathMutation(
    val sequence: Long,
    val from: String,
    val to: String? = null,
)

@Stable
class EditorWorkspaceState {
    private val documents = mutableStateMapOf<String, EditorDocument>()
    private val pathMutationLog = ArrayDeque<EditorWorkspacePathMutation>()
    private var pathMutationListener: ((EditorWorkspacePathMutation) -> Unit)? = null
    var pathMutationSequence by mutableLongStateOf(0L)
        private set

    fun document(path: String): EditorDocument = documents.getOrPut(path) { EditorDocument(path) }
    fun peek(path: String): EditorDocument? = documents[path]
    fun allDocuments(): List<EditorDocument> = documents.values.toList()
    fun dirtyDocuments(): List<EditorDocument> = documents.values.filter { it.dirty }
    fun hasDirtyAtOrUnder(path: String): Boolean {
        val prefix = path.trimEnd('/') + "/"
        return documents.values.any { it.dirty && (it.path == path || it.path.startsWith(prefix)) }
    }

    fun forgetAtOrUnder(path: String) {
        val prefix = path.trimEnd('/') + "/"
        documents.keys.filter { it == path || it.startsWith(prefix) }.toList().forEach(documents::remove)
    }

    internal fun documentsAtOrUnder(path: String): List<EditorDocument> {
        val prefix = path.trimEnd('/') + "/"
        return documents.values.filter { it.path == path || it.path.startsWith(prefix) }
    }

    internal fun remapPersistedPath(oldPath: String, newPath: String): List<Pair<String, String>> {
        val affected = documentsAtOrUnder(oldPath)
        if (affected.isEmpty()) return emptyList()
        val oldPrefix = oldPath.trimEnd('/') + "/"
        fun remap(path: String): String = if (path == oldPath) newPath else newPath.trimEnd('/') + "/" + path.removePrefix(oldPrefix)
        val pairs = affected.map { it.path to remap(it.path) }
        val affectedKeys = pairs.mapTo(hashSetOf()) { it.first }
        pairs.forEach { (_, target) ->
            check(target !in documents || target in affectedKeys) { "Live editor destination already exists: $target" }
        }
        affected.forEach { documents.remove(it.path) }
        pairs.zip(affected).forEach { (pair, doc) ->
            doc.remapPath(pair.second)
            documents[pair.second] = doc
        }
        recordPathMutation(oldPath, newPath)
        return pairs
    }

    internal fun removePersistedPath(path: String): List<EditorDocument> {
        val affected = documentsAtOrUnder(path)
        if (affected.isEmpty()) return emptyList()
        affected.forEach { documents.remove(it.path) }
        recordPathMutation(path, null)
        return affected
    }

    fun pathMutationsAfter(sequence: Long): List<EditorWorkspacePathMutation> =
        pathMutationLog.filter { it.sequence > sequence }

    fun bindPathMutationListener(listener: (EditorWorkspacePathMutation) -> Unit): AutoCloseable {
        pathMutationListener = listener
        return AutoCloseable { if (pathMutationListener === listener) pathMutationListener = null }
    }

    private fun recordPathMutation(from: String, to: String?) {
        pathMutationSequence += 1L
        val event = EditorWorkspacePathMutation(pathMutationSequence, from, to)
        pathMutationLog.addLast(event)
        while (pathMutationLog.size > 64) pathMutationLog.removeFirst()
        pathMutationListener?.invoke(event)
    }

    fun selectionSnapshots(): Map<String, com.baystudio.droide.core.EditorSelectionSnapshot> =
        documents.values.associate { it.path to com.baystudio.droide.core.EditorSelectionSnapshot(it.selectionStart, it.selectionEnd) }

    fun reviewModePaths(): Set<String> = documents.values.filter { it.reviewMode }.mapTo(linkedSetOf()) { it.path }

    fun clear() = documents.clear()
}

 
class ActiveEditorBridge {
    var snapshot: (() -> Unit)? = null
    var find: (() -> Unit)? = null
    var quickFix: (() -> Unit)? = null
    var renameSymbol: (() -> Unit)? = null
    var documentSymbols: (() -> Unit)? = null
    var goToDefinition: (() -> Unit)? = null
    var findReferences: (() -> Unit)? = null
    var formatDocument: (() -> Unit)? = null
    var completion: (() -> Unit)? = null
    var signatureHelp: (() -> Unit)? = null
    var cursorLocation: (() -> Pair<Int, Int>)? = null

    



    fun capture(): Boolean = safeInvoke(snapshot)
    fun requestFind(): Boolean = safeInvoke(find)
    fun requestQuickFix(): Boolean = safeInvoke(quickFix)
    fun requestRenameSymbol(): Boolean = safeInvoke(renameSymbol)
    fun requestDocumentSymbols(): Boolean = safeInvoke(documentSymbols)
    fun requestGoToDefinition(): Boolean = safeInvoke(goToDefinition)
    fun requestFindReferences(): Boolean = safeInvoke(findReferences)
    fun requestFormatDocument(): Boolean = safeInvoke(formatDocument)
    fun requestCompletion(): Boolean = safeInvoke(completion)
    fun requestSignatureHelp(): Boolean = safeInvoke(signatureHelp)
    fun currentCursorLocation(): Pair<Int, Int>? = try { cursorLocation?.invoke() } catch (_: Exception) { null }

    private fun safeInvoke(action: (() -> Unit)?): Boolean {
        val current = action ?: return false
        return try {
            current()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clear() {
        snapshot = null
        find = null
        quickFix = null
        renameSymbol = null
        documentSymbols = null
        goToDefinition = null
        findReferences = null
        formatDocument = null
        completion = null
        signatureHelp = null
        cursorLocation = null
    }
}
