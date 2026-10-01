package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class LargeFileException(val relativePath: String, val bytes: Long) :
    IllegalStateException("File exceeds this bounded read ($bytes bytes): $relativePath. Use the editor large-file path or a bounded read range.")

class UnsupportedTextEncodingException(val relativePath: String) :
    IllegalStateException("Unsupported or invalid UTF-8 encoding: $relativePath. Original bytes are unchanged; convert a copy to UTF-8 explicitly before editing.")

enum class StreamWritePhase {
    PREPARING,
    COPYING,
    COMMITTING,
    SYNCHRONIZING,
}

data class StreamWriteProgress(
    val phase: StreamWritePhase,
    val completedBytes: Long = 0L,
    val totalBytes: Long? = null,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let { total ->
            (completedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }

     
    val cancellable: Boolean
        get() = phase == StreamWritePhase.PREPARING || phase == StreamWritePhase.COPYING
}

 
class FileRepository(
    val root: File,
    private val beforeMutation: (suspend (Mutation) -> Unit)? = null,
    private val onMutation: (suspend (Mutation) -> Unit)? = null,
) {
    sealed interface Mutation {
        data class Write(val path: String, val text: String? = null) : Mutation
        data class CreateDirectory(val path: String) : Mutation
        data class Delete(val path: String) : Mutation
        data class Move(val from: String, val to: String) : Mutation
         
        data class Sync(val path: String) : Mutation
    }

    private data class IgnoreRule(val regex: Regex, val negated: Boolean)

    init { check(root.isDirectory || root.mkdirs()) { "Cannot create project root: ${root.path}" } }

    suspend fun listFiles(rel: String = "", maxEntries: Int = MAX_DIRECTORY_ENTRIES): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = resolve(rel)
        require(dir.isDirectory) { "Not a directory: $rel" }
        val limit = maxEntries.coerceIn(1, MAX_DIRECTORY_ENTRIES)
        val entries = ArrayList<FileEntry>(minOf(limit, 256))
        Files.newDirectoryStream(dir.toPath()).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                currentCoroutineContext().ensureActive()
                val child = iterator.next().toFile()
                if (PathSecurity.isSymbolicLink(child) || !PathSecurity.contains(root, child)) continue
                if (entries.size >= limit) throw IllegalStateException("Directory has more than $limit visible entries; narrow the project layout")
                entries += FileEntry(relPath(child), child.isDirectory, child.length(), child.lastModified())
            }
        }
        entries.sortedWith(compareBy({ !it.isDir }, { it.path.substringAfterLast('/').lowercase() }))
    }

     
    suspend fun listFilesPage(
        rel: String = "",
        after: DirectoryPageCursor? = null,
        pageSize: Int = 250,
    ): DirectoryPage = withContext(Dispatchers.IO) {
        val normalized = normalizeRel(rel)
        val dir = resolve(normalized)
        require(dir.isDirectory) { "Not a directory: $rel" }
        require(pageSize in 1..MAX_DIRECTORY_ENTRIES) { "Invalid directory page size" }
        require(after == null || after.path.substringBeforeLast('/', "") == normalized) { "Directory cursor belongs to another folder" }
        val order = compareBy<FileEntry>(
            { !it.isDir },
            { it.path.substringAfterLast('/').lowercase(Locale.ROOT) },
            { it.path.substringAfterLast('/') },
            { it.path },
        )
        val cursor = after?.let { FileEntry(it.path, it.isDir, 0L, 0L) }
        val best = PriorityQueue<FileEntry>(pageSize + 2, order.reversed())
        Files.newDirectoryStream(dir.toPath()).use { stream ->
            for (candidate in stream) {
                currentCoroutineContext().ensureActive()
                val child = candidate.toFile()
                if (PathSecurity.isSymbolicLink(child) || !PathSecurity.contains(root, child)) continue
                val entry = FileEntry(relPath(child), child.isDirectory, child.length(), child.lastModified())
                if (cursor != null && order.compare(entry, cursor) <= 0) continue
                best += entry
                if (best.size > pageSize + 1) best.poll()
            }
        }
        val sorted = best.toList().sortedWith(order)
        val visible = sorted.take(pageSize)
        DirectoryPage(visible, if (sorted.size > pageSize) visible.last().let { DirectoryPageCursor(it.path, it.isDir) } else null)
    }

    suspend fun exists(rel: String): Boolean = withContext(Dispatchers.IO) { resolve(rel).exists() }
    suspend fun isDirectory(rel: String): Boolean = withContext(Dispatchers.IO) { resolve(rel).isDirectory }

    // Large files are never represented by a destructive preview buffer.
    suspend fun readText(rel: String, maxBytes: Long = 500_000): String = withContext(Dispatchers.IO) {
        val f = resolve(rel)
        require(f.isFile) { "Not a file: $rel" }
        if (f.length() > maxBytes) throw LargeFileException(rel, f.length())
        rejectBinary(f, rel)
        readUtf8Bounded(f, maxBytes, rel, largeFileException = true)
    }

     
    suspend fun readEditorText(rel: String, maxBytes: Long = EditorLargeFilePolicy.MAX_EDITABLE_BYTES): String = withContext(Dispatchers.IO) {
        val f = resolve(rel)
        require(f.isFile) { "Not a file: $rel" }
        if (f.length() > maxBytes) throw LargeFileException(rel, f.length())
        rejectBinary(f, rel)
        readUtf8Bounded(f, maxBytes, rel, largeFileException = true)
    }

    suspend fun fileSize(rel: String): Long = withContext(Dispatchers.IO) {
        val f = resolve(rel)
        require(f.isFile) { "Not a file: $rel" }
        f.length()
    }

     
    suspend fun readTextForEdit(rel: String, maxBytes: Long = 2_000_000): String = withContext(Dispatchers.IO) {
        val f = resolve(rel)
        require(f.isFile) { "Not a file: $rel" }
        require(f.length() <= maxBytes) { "File too large to edit safely: $rel (${f.length()} bytes)" }
        rejectBinary(f, rel)
        readUtf8Bounded(f, maxBytes, rel, largeFileException = false)
    }


    suspend fun readRange(rel: String, start: Int = 1, end: Int = 200): String = withContext(Dispatchers.IO) {
        val f = resolve(rel)
        require(f.isFile) { "Not a file: $rel" }
        rejectBinary(f, rel)
        val s = start.coerceAtLeast(1)
        val e = end.coerceIn(s, s + 500)
        val selected = ArrayList<Pair<Int, String>>()
        var lineNo = 1
        var sawAnyByte = false
        var truncatedLine = false
        var hasMore = false
        var lineBuffer = ByteArrayOutputStream(512)
        val maxLineBytes = 2_000

        fun finishLine() {
            if (lineNo in s..e) {
                var bytes = lineBuffer.toByteArray()
                if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes = bytes.copyOf(bytes.size - 1)
                val text = bytes.toString(Charsets.UTF_8) + if (truncatedLine) "…" else ""
                selected += lineNo to text
            }
            lineNo++
            lineBuffer = ByteArrayOutputStream(512)
            truncatedLine = false
        }

        f.inputStream().buffered(64 * 1024).use { input ->
            val buf = ByteArray(64 * 1024)
            loop@ while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n <= 0) break
                sawAnyByte = true
                for (i in 0 until n) {
                    val b = buf[i]
                    if (b == '\n'.code.toByte()) {
                        finishLine()
                        if (lineNo > e) {
                            hasMore = i < n - 1 || input.available() > 0
                            break@loop
                        }
                    } else if (lineNo in s..e) {
                        if (lineBuffer.size() < maxLineBytes) lineBuffer.write(b.toInt()) else truncatedLine = true
                    }
                }
            }
        }
        if (sawAnyByte && lineBuffer.size() > 0 && lineNo <= e) finishLine()

        if (selected.isEmpty()) return@withContext "(range $s–$e is beyond end of file)"
        buildString {
            appendLine("… showing lines $s–${selected.last().first}${if (hasMore) "; more lines follow" else ""} …")
            selected.forEach { (i, line) -> appendLine(String.format("%4d│ %s", i, line)) }
        }
    }

    // Never returns internal or sensitive paths.
    suspend fun findFiles(query: String, maxResults: Int = 100): List<String> = findFilesPage(query, maxResults).paths

    suspend fun findFilesPage(query: String, maxResults: Int = 100): FileQueryPage = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        require(q.length <= 256) { "query too long" }
        val limit = maxResults.coerceIn(1, 300)
        val ignore = loadIgnoreRules()
        val ranked = ArrayList<Pair<Int, String>>(minOf(limit * 4, 1_200))
        var scanned = 0
        val iterator = root.walkTopDown().onEnter { PathSecurity.canDescend(root, it) }.maxDepth(20).iterator()
        while (iterator.hasNext() && scanned < MAX_SCAN_ENTRIES) {
            if ((scanned and 0x7f) == 0) currentCoroutineContext().ensureActive()
            val f = iterator.next()
            scanned++
            if (PathSecurity.isSymbolicLink(f) || !f.isFile) continue
            val rel = relPath(f)
            if (isInternalPath(rel) || SensitivePathPolicy.isSensitive(rel) || ignored(rel, ignore)) continue
            val score = QuickOpenMatcher.score(q, rel) ?: continue
            ranked += score to rel
            if (ranked.size > limit * 8) {
                ranked.sortWith(compareBy<Pair<Int, String>>({ it.first }, { it.second.length }, { it.second }))
                ranked.subList(limit * 4, ranked.size).clear()
            }
        }
        val paths = ranked.sortedWith(compareBy<Pair<Int, String>>({ it.first }, { it.second.length }, { it.second }))
            .take(limit).map { it.second }
        FileQueryPage(paths, scanned, scanned >= MAX_SCAN_ENTRIES && iterator.hasNext(), ranked.size > limit)
    }

    suspend fun glob(pattern: String, max: Int = 200): String = try {
        globInternal(pattern, max)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        "(glob error: ${error.message?.take(320) ?: error::class.java.simpleName})"
    }

    private suspend fun globInternal(pattern: String, max: Int = 200): String {
        val page = globPage(pattern, max)
        return buildString {
            page.paths.forEach { appendLine(it) }
            if (page.scanLimitReached) appendLine("… scan stopped after $MAX_SCAN_ENTRIES entries …")
            else if (page.resultLimitReached) appendLine("… results limited to ${max.coerceIn(1, MAX_GLOB_RESULTS)} …")
        }.ifBlank { "(no results for $pattern)" }
    }

    suspend fun globPage(pattern: String, max: Int = 200): FileQueryPage = withContext(Dispatchers.IO) {
        val trimmed = pattern.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 256) { "Invalid glob pattern" }
        val resultLimit = max.coerceIn(1, MAX_GLOB_RESULTS)
        val ignore = loadIgnoreRules()
        val re = globToRegex(trimmed)
        val paths = ArrayList<String>(minOf(resultLimit, 128))
        var scanned = 0
        val iterator = root.walkTopDown().onEnter { PathSecurity.canDescend(root, it) }.maxDepth(20).iterator()
        while (iterator.hasNext() && paths.size < resultLimit && scanned < MAX_SCAN_ENTRIES) {
            if ((scanned and 0x7f) == 0) currentCoroutineContext().ensureActive()
            val f = iterator.next()
            scanned++
            if (PathSecurity.isSymbolicLink(f) || !f.isFile) continue
            val rel = relPath(f)
            if (isInternalPath(rel) || SensitivePathPolicy.isSensitive(rel) || ignored(rel, ignore)) continue
            if (re.matches(rel) || re.matches(f.name)) {
                paths += rel
            }
        }
        FileQueryPage(paths, scanned, scanned >= MAX_SCAN_ENTRIES && iterator.hasNext(), paths.size >= resultLimit)
    }

    suspend fun writeText(rel: String, content: String) = writeTextBounded(rel, content, MAX_TEXT_WRITE_BYTES)

     
    suspend fun writeEditorText(rel: String, content: String) =
        writeTextBounded(rel, content, EditorLargeFilePolicy.MAX_EDITABLE_BYTES)

    private suspend fun writeTextBounded(rel: String, content: String, maxBytes: Long) = withContext(Dispatchers.IO) {
        require(maxBytes in 1..EditorLargeFilePolicy.MAX_EDITABLE_BYTES) { "Invalid text write limit" }
        val encodedBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
        require(encodedBytes <= maxBytes) { "Text write exceeds ${maxBytes / (1024 * 1024)} MiB safety limit" }
        val normalized = normalizeRel(rel)
        require(normalized.isNotBlank()) { "File path is empty" }
        val f = resolve(normalized)
        require(!f.isDirectory) { "Cannot overwrite directory: $rel" }
        beforeMutation?.invoke(Mutation.Write(normalized, content))
        f.parentFile?.mkdirs()
        val backup = backupFile(f)
        try {
            atomicWrite(f, content)
            try {
                onMutation?.invoke(Mutation.Write(normalized, content))
            } catch (t: Throwable) {
                restoreFile(f, backup)
                reconcileBestEffort(Mutation.Sync(normalized))
                throw t
            }
        } finally {
            backup?.let(PathSecurity::deleteTreeNoFollow)
        }
    }

    suspend fun writeStream(
        rel: String,
        input: java.io.InputStream,
        expectedBytes: Long? = null,
        onProgress: (suspend (StreamWriteProgress) -> Unit)? = null,
    ): Long = withContext(Dispatchers.IO) {
        // A picked DocumentsProvider stream may hold a Binder/pipe/file descriptor.


        input.use { source ->
            val normalized = normalizeRel(rel)
            require(normalized.isNotBlank()) { "File path is empty" }
            val f = resolve(normalized)
            require(!f.isDirectory) { "Cannot overwrite directory: $rel" }
            val expected = expectedBytes?.takeIf { it >= 0L }
            reportStreamProgress(onProgress, StreamWriteProgress(StreamWritePhase.PREPARING, totalBytes = expected))
            if (expected != null) {
                require(expected <= MAX_STREAM_WRITE_BYTES) {
                    "Import exceeds ${MAX_STREAM_WRITE_BYTES / (1024 * 1024)} MiB mobile safety limit"
                }
                ensureImportFreeSpace(root, expected)
            }
            beforeMutation?.invoke(Mutation.Write(normalized))
            f.parentFile?.mkdirs()
            val backup = backupFile(f)
            val tmp = tempSibling(f)
            try {
                reportStreamProgress(onProgress, StreamWriteProgress(StreamWritePhase.COPYING, totalBytes = expected))
                val count = tmp.outputStream().buffered(IMPORT_COPY_BUFFER_BYTES).use { out ->
                    copyBounded(source, out, MAX_STREAM_WRITE_BYTES, root, expected, onProgress)
                }

                // Recheck state around cancellation-sensitive boundaries.


                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    reportStreamProgress(
                        onProgress,
                        StreamWriteProgress(StreamWritePhase.COMMITTING, count, expected),
                        propagateCancellation = false,
                    )
                    replaceFile(tmp, f)
                    try {
                        reportStreamProgress(
                            onProgress,
                            StreamWriteProgress(StreamWritePhase.SYNCHRONIZING, count, expected),
                            propagateCancellation = false,
                        )
                        onMutation?.invoke(Mutation.Write(normalized))
                    } catch (t: Throwable) {
                        restoreFile(f, backup)
                        reconcileBestEffort(Mutation.Sync(normalized))
                        throw t
                    }
                }
                count
            } finally {
                if (tmp.exists()) PathSecurity.deleteTreeNoFollow(tmp)
                backup?.let(PathSecurity::deleteTreeNoFollow)
            }
        }
    }

    suspend fun createDirectory(rel: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeRel(rel).trimEnd('/')
        require(normalized.isNotBlank()) { "Directory path is empty" }
        val dir = resolve(normalized)
        require(!dir.isFile) { "A file already exists at $rel" }
        val existed = dir.isDirectory
        beforeMutation?.invoke(Mutation.CreateDirectory(normalized))
        val created = existed || dir.mkdirs()
        if (!created) return@withContext false
        try {
            onMutation?.invoke(Mutation.CreateDirectory(normalized))
        } catch (t: Throwable) {
            if (!existed) PathSecurity.deleteTreeNoFollow(dir)
            reconcileBestEffort(Mutation.Sync(normalized))
            throw t
        }
        true
    }

    suspend fun delete(rel: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeRel(rel)
        require(normalized.isNotBlank()) { "Refusing to delete project root" }
        val f = resolve(normalized)
        if (!f.exists()) return@withContext false
        beforeMutation?.invoke(Mutation.Delete(normalized))
        if (onMutation == null) return@withContext PathSecurity.deleteTreeNoFollow(f)

        val backup = tempSibling(f)
        require(moveLocal(f, backup)) { "Cannot stage deletion: $rel" }
        try {
            try {
                onMutation.invoke(Mutation.Delete(normalized))
            } catch (t: Throwable) {
                check(moveLocal(backup, f)) { "Storage rollback failed for $rel" }
                reconcileBestEffort(Mutation.Sync(normalized))
                throw t
            }
            PathSecurity.deleteTreeNoFollow(backup)
            true
        } finally {
            
            if (backup.exists() && !f.exists()) runCatching { moveLocal(backup, f) }
        }
    }

    suspend fun move(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        val normalizedFrom = normalizeRel(from)
        val normalizedTo = normalizeRel(to)
        require(normalizedFrom.isNotBlank() && normalizedTo.isNotBlank()) { "Move path is empty" }
        val src = resolve(normalizedFrom)
        val dst = resolve(normalizedTo)
        require(src.exists()) { "Source missing: $from" }
        require(!dst.exists()) { "Destination already exists: $to" }
        beforeMutation?.invoke(Mutation.Move(normalizedFrom, normalizedTo))
        dst.parentFile?.mkdirs()
        if (!moveLocal(src, dst)) return@withContext false
        try {
            onMutation?.invoke(Mutation.Move(normalizedFrom, normalizedTo))
        } catch (t: Throwable) {
            check(moveLocal(dst, src)) { "Storage rollback failed for move $from -> $to" }
            reconcileBestEffort(Mutation.Sync(normalizedFrom))
            reconcileBestEffort(Mutation.Delete(normalizedTo))
            throw t
        }
        true
    }

    suspend fun applySearchReplace(rel: String, search: String, replace: String, replaceAll: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            require(search.isNotEmpty()) { "Search string must not be empty" }
            val old = readTextForEdit(rel)
            val count = countOccurrences(old, search)
            require(count > 0) { "Search string not found in $rel" }
            if (!replaceAll) require(count == 1) { "Search string must match exactly once in $rel (found $count)" }
            val updated = if (replaceAll) old.replace(search, replace) else old.replaceFirst(search, replace)
            writeText(rel, updated)
            if (replaceAll) count else 1
        }

     
    suspend fun isSearchVisible(rel: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeRel(rel)
        if (normalized.isBlank() || isInternalPath(normalized) || SensitivePathPolicy.isSensitive(normalized)) return@withContext false
        !ignored(normalized, loadIgnoreRules())
    }

     
    suspend fun search(
        query: String,
        maxResults: Int = 100,
        excludedPaths: Set<String> = emptySet(),
    ): String {
        val page = searchPage(query, maxResults, excludedPaths)
        if (query.trim().length < 2) return "(query too short)"
        return buildString {
            page.hits.forEach { appendLine("${it.path}:${it.line}: ${it.excerpt}") }
            if (page.scanLimitReached) appendLine("… scan stopped after $MAX_SCAN_ENTRIES entries …")
            else if (page.resultLimitReached) appendLine("… results limited to ${maxResults.coerceIn(1, MAX_SEARCH_RESULTS)} …")
            if (page.unreadableFiles > 0) appendLine("… ${page.unreadableFiles} unreadable file(s) skipped …")
        }
    }

    suspend fun searchPage(
        query: String,
        maxResults: Int = 100,
        excludedPaths: Set<String> = emptySet(),
        options: TextSearchOptions = TextSearchOptions(),
        documents: WorkspaceDocumentAuthority? = null,
    ): TextSearchPage = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext TextSearchPage(emptyList(), 0, false, false, 0)
        require(q.length <= 256) { "query too long" }
        val includePattern = options.includeGlob.trim()
        require(includePattern.length <= 256) { "file filter too long" }
        val include = includePattern.takeIf { it.isNotEmpty() }?.let(::globToRegex)
        val resultLimit = maxResults.coerceIn(1, MAX_SEARCH_RESULTS)
        val excluded = excludedPaths.mapTo(hashSetOf()) { normalizeRel(it) }
        val ignore = loadIgnoreRules()
        val hits = ArrayList<TextSearchHit>(minOf(resultLimit, 100))
        var scanned = 0
        var unreadable = 0
        var skippedLarge = 0
        var skippedDirty = 0
        val dirtyText = linkedMapOf<String, String>()
        for (snapshot in documents?.dirtySnapshots().orEmpty()) {
            currentCoroutineContext().ensureActive()
            if (!snapshot.dirty) continue
            val rel = runCatching { normalizeRel(snapshot.path).also { resolve(it) } }.getOrNull() ?: continue
            if (rel.isBlank() || isInternalPath(rel) || SensitivePathPolicy.isSensitive(rel) || ignored(rel, ignore)) continue
            if (include != null && !include.matches(rel) && !include.matches(rel.substringAfterLast('/'))) continue
            excluded += rel // Never show a stale disk match when the editor owns this path.
            if (snapshot.loaded && snapshot.editable && snapshot.kind in setOf(WorkspaceDocumentKind.TEXT, WorkspaceDocumentKind.LARGE) &&
                snapshot.content.length <= EditorLargeFilePolicy.MAX_EDITABLE_BYTES) {
                dirtyText[rel] = snapshot.content
            } else skippedDirty++
        }
        for ((path, content) in dirtyText.toSortedMap()) {
            var ln = 0
            for (raw in content.lineSequence()) {
                ln++
                if ((ln and 0xff) == 0) currentCoroutineContext().ensureActive()
                WorkspaceTextMatcher.appendLine(path, ln, raw, q, options, resultLimit, hits)
                if (hits.size >= resultLimit) break
            }
            if (hits.size >= resultLimit) break
        }
        val iterator = root.walkTopDown().onEnter { PathSecurity.canDescend(root, it) }.maxDepth(20).iterator()
        while (iterator.hasNext() && hits.size < resultLimit && scanned < MAX_SCAN_ENTRIES) {
            if ((scanned and 0x7f) == 0) currentCoroutineContext().ensureActive()
            val f = iterator.next()
            scanned++
            if (PathSecurity.isSymbolicLink(f) || !f.isFile) continue
            val rel = relPath(f)
            if (rel in excluded || isInternalPath(rel) || SensitivePathPolicy.isSensitive(rel) || ignored(rel, ignore)) continue
            if (include != null && !include.matches(rel) && !include.matches(f.name)) continue
            if (f.length() > EditorLargeFilePolicy.MAX_EDITABLE_BYTES) { skippedLarge++; continue }
            try {
                if (looksBinary(f)) continue
                f.useLines { lines ->
                    var ln = 0
                    for (raw in lines) {
                        ln++
                        if ((ln and 0xff) == 0) currentCoroutineContext().ensureActive()
                        WorkspaceTextMatcher.appendLine(rel, ln, raw, q, options, resultLimit, hits)
                        if (hits.size >= resultLimit) break
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                
                unreadable++
            }
        }
        TextSearchPage(hits, scanned, scanned >= MAX_SCAN_ENTRIES && iterator.hasNext(), hits.size >= resultLimit,
            unreadable, skippedLarge, skippedDirty)
    }

     
    suspend fun syncExisting(rel: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeRel(rel)
        require(normalized.isNotBlank()) { "File path is empty" }
        val f = resolve(normalized)
        require(f.isFile) { "Not a file: $rel" }
        onMutation?.invoke(Mutation.Write(normalized))
    }

    // Best-effort full reconciliation used after an external mutation rollback.
    suspend fun reconcileExternal(rel: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeRel(rel)
        require(normalized.isNotBlank()) { "Refusing to reconcile the project root implicitly" }
        onMutation?.invoke(Mutation.Sync(normalized))
    }

    fun resolveChecked(rel: String): File = resolve(rel)

    private fun resolve(rel: String): File = PathSecurity.resolveWithin(root, rel)

    private fun relPath(f: File): String =
        root.canonicalFile.toPath().relativize(f.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')

    private suspend fun readUtf8Bounded(file: File, maxBytes: Long, rel: String, largeFileException: Boolean): String {
        require(maxBytes in 1..Int.MAX_VALUE.toLong()) { "Invalid read limit" }
        val out = ByteArrayOutputStream(minOf(maxBytes.toInt(), 64 * 1024))
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n <= 0) break
                total += n
                if (total > maxBytes) {
                    if (largeFileException) throw LargeFileException(rel, maxOf(file.length(), total))
                    throw IllegalArgumentException("File grew beyond safe edit limit: $rel")
                }
                out.write(buffer, 0, n)
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (invalid: CharacterCodingException) {
            throw UnsupportedTextEncodingException(rel)
        }
    }

    private suspend fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxBytes: Long,
        freeSpaceRoot: File,
        expectedBytes: Long?,
        onProgress: (suspend (StreamWriteProgress) -> Unit)?,
    ): Long {
        val buffer = ByteArray(IMPORT_COPY_BUFFER_BYTES)
        var total = 0L
        var nextSpaceCheck = 0L
        var nextProgressUpdate = IMPORT_PROGRESS_INTERVAL_BYTES
        var nextProgressAtNanos = System.nanoTime() + IMPORT_PROGRESS_MAX_SILENCE_NS
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n <= 0) {
                reportStreamProgress(onProgress, StreamWriteProgress(StreamWritePhase.COPYING, total, expectedBytes))
                return total
            }
            total += n
            require(total <= maxBytes) { "Import exceeds ${maxBytes / (1024 * 1024)} MiB mobile safety limit" }
            if (total >= nextSpaceCheck) {
                // Use it only to reserve the remaining bytes, while the hard max above remains authoritative if the stream grows.


                val remaining = expectedBytes?.let { (it - total).coerceAtLeast(0L) } ?: 0L
                ensureImportFreeSpace(freeSpaceRoot, remaining)
                nextSpaceCheck = total + IMPORT_SPACE_CHECK_INTERVAL_BYTES
            }
            output.write(buffer, 0, n)
            val now = System.nanoTime()
            if (total >= nextProgressUpdate || now >= nextProgressAtNanos) {
                reportStreamProgress(onProgress, StreamWriteProgress(StreamWritePhase.COPYING, total, expectedBytes))
                nextProgressUpdate = total + IMPORT_PROGRESS_INTERVAL_BYTES
                nextProgressAtNanos = now + IMPORT_PROGRESS_MAX_SILENCE_NS
            }
        }
    }


    private suspend fun reportStreamProgress(
        callback: (suspend (StreamWriteProgress) -> Unit)?,
        progress: StreamWriteProgress,
        propagateCancellation: Boolean = true,
    ) {
        if (callback == null) return
        try {
            callback(progress)
        } catch (cancelled: CancellationException) {
            if (propagateCancellation) throw cancelled
        } catch (_: Throwable) {
            // A detached/recomposed UI must never turn a successful filesystem transaction into a failed import.

        }
    }

    private fun ensureImportFreeSpace(freeSpaceRoot: File, remainingBytes: Long) {
        val usable = freeSpaceRoot.usableSpace
        val required = MIN_IMPORT_FREE_BYTES + remainingBytes.coerceAtMost(MAX_STREAM_WRITE_BYTES)
        require(usable > required) {
            "Not enough free storage to finish import safely. Need ${required / (1024 * 1024)} MiB available including safety headroom."
        }
    }

    private fun normalizeRel(rel: String): String {
        val normalized = rel.replace('\\', '/')
        require(!normalized.startsWith('/')) { "Absolute paths are not allowed: $rel" }
        require('\u0000' !in normalized) { "Path contains NUL" }
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." }) { "Dot path segments are not allowed: $rel" }
        return segments.joinToString("/")
    }


    // Rollback reconciliation must run even if the user cancelled the original mutation.
    private suspend fun reconcileBestEffort(mutation: Mutation) {
        withContext(NonCancellable) {
            try {
                onMutation?.invoke(mutation)
            } catch (_: Throwable) {
                // The original mutation error remains authoritative; local rollback already ran.
            }
        }
    }

    private fun backupFile(target: File): File? {
        if (!target.isFile) return null
        val backup = File(target.parentFile, ".${target.name}.rollback-${System.nanoTime()}")
        // Keep the old inode as an O(1), zero-copy rollback snapshot before falling back to a physical copy.

        val linked = runCatching {
            Files.createLink(backup.toPath(), target.toPath())
            true
        }.getOrDefault(false)
        if (!linked) {
            val required = target.length() + MIN_IMPORT_FREE_BYTES
            require(root.usableSpace > required) {
                "Not enough free storage to create a safe rollback copy before overwrite"
            }
            target.copyTo(backup, overwrite = false)
        }
        return backup
    }

    private fun restoreFile(target: File, backup: File?) {
        if (backup == null) {
            PathSecurity.deleteTreeNoFollow(target)
            return
        }
        PathSecurity.deleteTreeNoFollow(target)
        replaceFile(backup, target)
    }

    private fun moveLocal(src: File, dst: File): Boolean {
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return true
        if (src.isDirectory) return false // same-workspace directory moves must be atomic rename; never recurse through symlinks
        val copied = runCatching { src.copyTo(dst, overwrite = false); true }.getOrDefault(false)
        if (!copied) return false
        val removed = PathSecurity.deleteTreeNoFollow(src)
        if (!removed) PathSecurity.deleteTreeNoFollow(dst)
        return removed
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = tempSibling(target)
        try {
            tmp.writeText(content)
            replaceFile(tmp, target)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun tempSibling(target: File): File = File(target.parentFile, ".${target.name}.droide-tmp-${System.nanoTime()}")

    private fun replaceFile(tmp: File, target: File) {
        runCatching {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun rejectBinary(f: File, rel: String) {
        if (looksBinary(f)) throw IllegalArgumentException("Binary file cannot be displayed as text: $rel")
    }

    private fun looksBinary(f: File): Boolean {
        if (!f.isFile || f.length() == 0L) return false
        val head = ByteArray(4096)
        val n = f.inputStream().use { it.read(head) }
        if (n <= 0) return false
        var suspicious = 0
        for (i in 0 until n) {
            val b = head[i].toInt() and 0xff
            if (b == 0) return true
            if (b < 0x09 || (b in 0x0E..0x1F)) suspicious++
        }
        return suspicious > n / 10
    }

    private fun loadIgnoreRules(): List<IgnoreRule> = runCatching {
        runCatching { PathSecurity.resolveWithin(root, ".gitignore") }.getOrNull()
            ?.takeIf { it.isFile && it.length() <= 1_000_000 }?.readLines().orEmpty().mapNotNull { raw ->
            var p = raw.trim()
            if (p.isEmpty() || p.startsWith("#")) return@mapNotNull null
            val negated = p.startsWith("!")
            if (negated) p = p.drop(1)
            if (p.isEmpty()) return@mapNotNull null
            val anchored = p.startsWith("/")
            p = p.trimStart('/').trimEnd('/')
            if (p.isEmpty()) return@mapNotNull null
            val body = globBody(p)
            val prefix = if (anchored || '/' in p) "^" else "(^|.*/)"
            IgnoreRule(Regex(prefix + body + "(?:/.*)?$"), negated)
        }
    }.getOrDefault(emptyList())

    private fun ignored(rel: String, rules: List<IgnoreRule>): Boolean {
        var ignored = false
        for (rule in rules) if (rule.regex.matches(rel)) ignored = !rule.negated
        return ignored
    }

    private fun isInternalPath(rel: String): Boolean = rel == ".git" || rel.startsWith(".git/") ||
        rel == ".droide" || rel.startsWith(".droide/")


    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return count
            count++
            from = i + needle.length
        }
    }

    private fun globToRegex(glob: String): Regex = Regex("^${globBody(glob)}$")

    private fun globBody(glob: String): String = buildString {
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**/", i) -> { append("(.*/)?"); i += 3 }
                glob.startsWith("**", i) -> { append(".*"); i += 2 }
                glob[i] == '*' -> { append("[^/]*"); i++ }
                glob[i] == '?' -> { append("[^/]"); i++ }
                else -> { append(Regex.escape(glob[i].toString())); i++ }
            }
        }
    }

    companion object {
        private const val MAX_DIRECTORY_ENTRIES = 5_000
        private const val MAX_SCAN_ENTRIES = 50_000
        private const val MAX_GLOB_RESULTS = 500
        private const val MAX_SEARCH_RESULTS = 500
        private const val MAX_TEXT_WRITE_BYTES = 5L * 1024L * 1024L


        private const val MAX_STREAM_WRITE_BYTES = 4L * 1024 * 1024 * 1024
        private const val IMPORT_COPY_BUFFER_BYTES = 256 * 1024
        private const val IMPORT_SPACE_CHECK_INTERVAL_BYTES = 16L * 1024 * 1024
        private const val IMPORT_PROGRESS_INTERVAL_BYTES = 4L * 1024 * 1024
        private const val IMPORT_PROGRESS_MAX_SILENCE_NS = 250L * 1_000_000L
        private const val MIN_IMPORT_FREE_BYTES = 192L * 1024 * 1024
    }
}

data class FileEntry(val path: String, val isDir: Boolean, val size: Long, val modified: Long)

data class DirectoryPageCursor(val path: String, val isDir: Boolean)
data class DirectoryPage(val entries: List<FileEntry>, val nextCursor: DirectoryPageCursor?)
