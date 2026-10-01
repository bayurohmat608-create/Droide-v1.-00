package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SafManifestFile(
    val version: Int = 2,
     
    val entries: Map<String, String> = emptyMap(),
)

// Use same-filesystem replacement for atomic activation.


class SafMirror(private val context: Context, treeUri: String, private val localRoot: File) {
    private val uri = Uri.parse(treeUri)
    private val manifest = PathSecurity.resolveWithin(localRoot, ".droide/saf_manifest.json")
    private val legacyManifest = PathSecurity.resolveWithin(localRoot, ".droide/saf_manifest.txt")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val writeJournal = SafWriteJournal(context, uri, localRoot, ::find, ::ensureParent,
        { loadManifest().entries }, { node, path -> fingerprintExternalFile(node, path) })

    suspend fun refreshFromTree(
        maxFiles: Int = MAX_MIRROR_FILES,
        maxBytes: Long = MAX_MIRROR_BYTES,
        maxDepth: Int = MAX_MIRROR_DEPTH,
        onProgress: ((SafMirrorProgress) -> Unit)? = null,
    ): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            writeJournal.recoverPendingWrites()
            require(maxFiles > 0 && maxBytes > 0 && maxDepth > 0) { "Invalid SAF mirror limits" }
            var lastReport = 0L
            val report: (SafMirrorProgress) -> Unit = { progress ->
                val now = System.currentTimeMillis()
                if (now - lastReport > 64L || progress.phase != SafMirrorPhase.COPYING_EXTERNAL && progress.phase != SafMirrorPhase.SCANNING_LOCAL) {
                    lastReport = now
                // Progress presentation must never become a filesystem correctness dependency.
                runCatching { onProgress?.invoke(progress) }
                }
            }
            report(SafMirrorProgress(SafMirrorPhase.PREPARING))
            localRoot.parentFile?.mkdirs()
            val stage = File(localRoot.parentFile, ".${localRoot.name}.saf-stage-${UUID.randomUUID()}")
            val backup = File(localRoot.parentFile, ".${localRoot.name}.saf-backup-${UUID.randomUUID()}")
            PathSecurity.deleteTreeNoFollow(stage)
            PathSecurity.deleteTreeNoFollow(backup)
            check(stage.mkdirs()) { "Cannot create SAF staging directory" }

            val job = currentCoroutineContext()[Job]
            val checkCancelled: () -> Unit = { job?.ensureActive(); Unit }
            try {
                val external = copyExternalSnapshot(stage, maxFiles, maxBytes, maxDepth, checkCancelled, report)
                report(SafMirrorProgress(SafMirrorPhase.SCANNING_LOCAL))
                val local = scanLocal(localRoot, maxFiles, maxBytes, maxDepth, checkCancelled, report)
                report(SafMirrorProgress(SafMirrorPhase.RESOLVING, external.files, external.bytes))
                val previous = loadManifest()
                val resolution = resolve(previous, local.entries, external.entries, checkCancelled)

                if (resolution.conflicts.isNotEmpty()) {
                    val preview = resolution.conflicts.sorted().take(12).joinToString(", ")
                    throw IllegalStateException(
                        "SAF sync conflict: both local mirror and external folder changed ${resolution.conflicts.size} path(s): $preview" +
                            if (resolution.conflicts.size > 12) " …" else ""
                    )
                }

                // Observe a startup/watchdog cancellation immediately before entering the atomic commit section.


                currentCoroutineContext().ensureActive()
                report(SafMirrorProgress(SafMirrorPhase.APPLYING, external.files, external.bytes))
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    applyLocalWinnersToExternal(resolution.localWinners, local.entries, external.entries)
                    applyLocalWinnersToStage(stage, resolution.localWinners, local.entries)


                    val desired = resolvedEntries(external.entries, local.entries, resolution.localWinners)
                    preserveLocalExecutableIntent(stage, local.entries, checkCancelled)
                    report(SafMirrorProgress(SafMirrorPhase.FINALIZING, external.files, external.bytes))
                    swapStageIntoLocal(stage, backup)
                    saveManifest(desired)
                    writeJournal.clearCommittedWrites()
                    legacyManifest.delete()
                    "SAF synchronized: ${desired.values.count { it.startsWith("f:") }} files"
                }
            } finally {
                PathSecurity.deleteTreeNoFollow(stage)
                PathSecurity.deleteTreeNoFollow(backup)
            }
        }
    }

    suspend fun apply(mutation: FileRepository.Mutation) = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            writeJournal.recoverPendingWrites()
            val checkCancelled: () -> Unit = {}
            val known = loadManifest().entries.toMutableMap()
            require(known.values.none { it == LEGACY_UNKNOWN }) {
                "SAF mirror metadata is from an older version. Refresh the project before editing."
            }
            when (mutation) {
                is FileRepository.Mutation.Write -> {
                    assertExternalUnchanged(mutation.path, known, includeTree = false, checkCancelled)
                    val local = PathSecurity.resolveWithin(localRoot, mutation.path)
                    known[mutation.path] = writeFile(mutation.path, local, checkCancelled)
                }
                is FileRepository.Mutation.CreateDirectory -> {
                    val clean = mutation.path.trimEnd('/')
                    assertExternalUnchanged(clean, known, includeTree = false, checkCancelled)
                    ensureDirectory(clean)
                    known[clean] = DIR
                }
                is FileRepository.Mutation.Delete -> {
                    assertExternalUnchanged(mutation.path, known, includeTree = true, checkCancelled)
                    find(mutation.path)?.let {
                        if (!it.delete()) throw IllegalStateException("Cannot delete SAF path: ${mutation.path}")
                    }
                    removeKnownTree(known, mutation.path)
                }
                is FileRepository.Mutation.Move -> {
                    assertExternalUnchanged(mutation.from, known, includeTree = true, checkCancelled)
                    assertExternalUnchanged(mutation.to, known, includeTree = true, checkCancelled)
                    val dest = PathSecurity.resolveWithin(localRoot, mutation.to)
                    syncLocalPath(dest, mutation.to, known, checkCancelled)
                    find(mutation.from)?.let { source ->
                        if (!source.delete()) throw IllegalStateException("Cannot remove old SAF path: ${mutation.from}")
                    }
                    removeKnownTree(known, mutation.from)
                }
                is FileRepository.Mutation.Sync -> {
                    val clean = mutation.path.trim('/').replace('\\', '/')
                    require(clean.isNotBlank()) { "Root SAF reconciliation must use refreshFromTree()" }
                    assertExternalUnchanged(clean, known, includeTree = true, checkCancelled)
                    removeKnownTree(known, clean)
                    val local = PathSecurity.resolveWithin(localRoot, clean)
                    when {
                        local.exists() || PathSecurity.isSymbolicLink(local) -> syncLocalPath(local, clean, known, checkCancelled)
                        else -> find(clean)?.let {
                            if (!it.delete()) throw IllegalStateException("Cannot reconcile SAF path: $clean")
                        }
                    }
                }
            }
            saveManifest(known)
            writeJournal.clearCommittedWrites()
        }
    }

    // Recheck authoritative state at commit boundaries to avoid stale writes.


    private fun assertExternalUnchanged(path: String, known: Map<String, String>, includeTree: Boolean, checkCancelled: () -> Unit = {}) {
        val clean = path.trim('/').replace('\\', '/')
        require(clean.isNotBlank()) { "Empty SAF path" }

        
        val parts = segments(clean)
        for (i in 1 until parts.size) {
            checkCancelled()
            val ancestor = parts.take(i).joinToString("/")
            val expected = known[ancestor]
            val actualNode = find(ancestor)
            val actual = when {
                actualNode == null -> null
                actualNode.isDirectory -> DIR
                actualNode.isFile -> fingerprintExternalFile(actualNode, ancestor, checkCancelled)
                else -> null
            }
            if (expected != actual) throw safConcurrentConflict(ancestor)
        }

        val expected = if (includeTree) {
            known.filterKeys { it == clean || it.startsWith("$clean/") }
        } else {
            known[clean]?.let { mapOf(clean to it) } ?: emptyMap()
        }
        val actual = snapshotExternalPath(clean, includeTree, checkCancelled)
        if (expected != actual) throw safConcurrentConflict(clean)
    }

    private fun safConcurrentConflict(path: String) = IllegalStateException(
        "SAF sync conflict at '$path': the external folder changed after the last sync. Refresh before retrying; no external data was overwritten."
    )

    private fun snapshotExternalPath(path: String, includeTree: Boolean, checkCancelled: () -> Unit = {}): Map<String, String> {
        val clean = path.trim('/').replace('\\', '/')
        val node = find(clean) ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        var files = 0
        var bytes = 0L

        fun visit(cur: DocumentFile, rel: String, depth: Int) {
            checkCancelled()
            require(depth <= MAX_MIRROR_DEPTH) { "SAF path nesting exceeds $MAX_MIRROR_DEPTH levels" }
            when {
                cur.isDirectory -> {
                    out[rel] = DIR
                    if (!includeTree && rel == clean) return
                    for (child in cur.listFiles()) {
                        val raw = child.name ?: throw IllegalStateException("SAF contains an unnamed entry below $rel")
                        val name = try {
                            PathSecurity.safeLeafName(raw)
                        } catch (t: Throwable) {
                            throw IllegalStateException("Unsupported SAF filename '$raw' below $rel", t)
                        }
                        val childRel = "$rel/$name"
                        if (!isRuntimePath(childRel)) visit(child, childRel, depth + 1)
                    }
                }
                cur.isFile -> {
                    if (++files > MAX_MIRROR_FILES) throw IllegalStateException("SAF path exceeds $MAX_MIRROR_FILES files")
                    val size = cur.length().coerceAtLeast(0L)
                    bytes += size
                    if (bytes > MAX_MIRROR_BYTES) throw IllegalStateException("SAF path exceeds ${MAX_MIRROR_BYTES / (1024 * 1024)} MiB mirror limit")
                    out[rel] = fingerprintExternalFile(cur, rel, checkCancelled)
                }
            }
        }
        visit(node, clean, 0)
        return out
    }

    private fun fingerprintExternalFile(file: DocumentFile, rel: String, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Cannot read SAF file while checking conflict: $rel")
        input.use { source ->
            val buffer = ByteArray(SAF_COPY_BUFFER_BYTES)
            var total = 0L
            while (true) {
                checkCancelled()
                val n = source.read(buffer)
                if (n <= 0) break
                total += n
                if (total > MAX_MIRROR_BYTES) throw IllegalStateException("SAF file exceeds ${MAX_MIRROR_BYTES / (1024 * 1024)} MiB mirror limit: $rel")
                digest.update(buffer, 0, n)
            }
        }
        return "f:${digest.digest().toHex()}"
    }

    private data class Snapshot(val entries: Map<String, String>, val files: Int, val bytes: Long)
    private data class Resolution(val localWinners: Set<String>, val conflicts: Set<String>)


    private fun copyExternalSnapshot(
        stage: File,
        maxFiles: Int,
        maxBytes: Long,
        maxDepth: Int,
        checkCancelled: () -> Unit = {},
        onProgress: (SafMirrorProgress) -> Unit = {},
    ): Snapshot {
        return try {
            copyExternalSnapshotDirect(stage, maxFiles, maxBytes, maxDepth, checkCancelled, onProgress)
        } catch (unsupported: SafDirectQueryException) {
            // Rare/non-conforming providers still get the conservative compatibility path.
            PathSecurity.deleteTreeNoFollow(stage)
            check(stage.mkdirs()) { "Cannot recreate SAF staging directory" }
            copyExternalSnapshotLegacy(stage, maxFiles, maxBytes, maxDepth, checkCancelled, onProgress)
        }
    }

    private fun copyExternalSnapshotDirect(
        stage: File,
        maxFiles: Int,
        maxBytes: Long,
        maxDepth: Int,
        checkCancelled: () -> Unit = {},
        onProgress: (SafMirrorProgress) -> Unit = {},
    ): Snapshot {
        val query = SafDocumentsTreeQuery(context, uri)
        val rootId = query.rootDocumentId()
        val entries = linkedMapOf<String, String>()
        var files = 0
        var bytes = 0L
        var nextProgressBytes = 0L

        onProgress(SafMirrorProgress(SafMirrorPhase.COPYING_EXTERNAL))

        fun copyFile(src: SafDocumentNode, dst: File, rel: String) {
            checkCancelled()
            if (++files > maxFiles) throw IllegalStateException("SAF project exceeds $maxFiles files")
            if (src.size >= 0L && bytes + src.size > maxBytes) {
                throw IllegalStateException("SAF project exceeds ${maxBytes / (1024 * 1024)} MiB mirror limit")
            }
            ensureStagingFreeSpace(stage, src.size.coerceAtLeast(0L))
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, ".${dst.name}.saf-tmp-${System.nanoTime()}")
            val digest = MessageDigest.getInstance("SHA-256")
            var nextSpaceCheck = bytes + SAF_SPACE_CHECK_INTERVAL_BYTES
            try {
                val input = context.contentResolver.openInputStream(src.uri)
                    ?: throw IllegalStateException("Cannot read SAF file: $rel")
                input.use { srcIn ->
                    tmp.outputStream().buffered(SAF_COPY_BUFFER_BYTES).use { out ->
                        val buf = ByteArray(SAF_COPY_BUFFER_BYTES)
                        while (true) {
                            checkCancelled()
                            val n = srcIn.read(buf)
                            if (n <= 0) break
                            bytes += n
                            if (bytes > maxBytes) {
                                throw IllegalStateException("SAF project exceeds ${maxBytes / (1024 * 1024)} MiB mirror limit")
                            }
                            if (bytes >= nextSpaceCheck) {
                                ensureStagingFreeSpace(stage, 0L)
                                nextSpaceCheck = bytes + SAF_SPACE_CHECK_INTERVAL_BYTES
                            }
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            if (bytes >= nextProgressBytes) {
                                onProgress(SafMirrorProgress(SafMirrorPhase.COPYING_EXTERNAL, files, bytes, rel))
                                nextProgressBytes = bytes + SAF_PROGRESS_INTERVAL_BYTES
                            }
                        }
                    }
                }
                replace(tmp, dst)
                if (src.modified > 0L) dst.setLastModified(src.modified)
                entries[rel] = "f:${digest.digest().toHex()}"
                onProgress(SafMirrorProgress(SafMirrorPhase.COPYING_EXTERNAL, files, bytes, rel))
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }

        fun copyDir(parentDocumentId: String, dst: File, relDir: String, depth: Int) {
            checkCancelled()
            require(depth <= maxDepth) { "SAF project nesting exceeds $maxDepth levels" }
            check(dst.isDirectory || dst.mkdirs()) { "Cannot create staging directory: ${dst.path}" }
            if (relDir.isNotBlank()) entries[relDir] = DIR
            for (child in query.listChildren(parentDocumentId)) {
                checkCancelled()
                val rel = if (relDir.isBlank()) child.name else "$relDir/${child.name}"
                if (isRuntimePath(rel)) continue
                val out = PathSecurity.resolveWithin(stage, rel)
                if (child.isDirectory) copyDir(child.documentId, out, rel, depth + 1)
                else if (child.isFile) copyFile(child, out, rel)
            }
        }

        copyDir(rootId, stage, "", 0)
        return Snapshot(entries, files, bytes)
    }

    private fun ensureStagingFreeSpace(stage: File, nextFileBytes: Long) {
        val spaceRoot = stage.parentFile ?: stage
        val usable = spaceRoot.usableSpace
        val required = MIN_SAFE_FREE_BYTES + nextFileBytes.coerceAtMost(MAX_MIRROR_BYTES)
        require(usable > required) {
            "Not enough free storage for SAF import. Need ${required / (1024 * 1024)} MiB available including safety headroom."
        }
    }

    private fun copyExternalSnapshotLegacy(
        stage: File, maxFiles: Int, maxBytes: Long, maxDepth: Int, checkCancelled: () -> Unit = {},
        onProgress: (SafMirrorProgress) -> Unit = {},
    ): Snapshot {
        val root = tree()
        val entries = linkedMapOf<String, String>()
        var files = 0
        var bytes = 0L
        var nextProgressBytes = 0L

        onProgress(SafMirrorProgress(SafMirrorPhase.COPYING_EXTERNAL))

        fun copyFile(src: DocumentFile, dst: File, rel: String) {
            checkCancelled()
            if (++files > maxFiles) throw IllegalStateException("SAF project exceeds $maxFiles files")
            val declaredSize = src.length().coerceAtLeast(0L)
            if (declaredSize > 0L && bytes + declaredSize > maxBytes) {
                throw IllegalStateException("SAF project exceeds ${maxBytes / (1024 * 1024)} MiB mirror limit")
            }
            ensureStagingFreeSpace(stage, declaredSize)
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, ".${dst.name}.saf-tmp-${System.nanoTime()}")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                val input = context.contentResolver.openInputStream(src.uri)
                    ?: throw IllegalStateException("Cannot read SAF file: $rel")
                input.use { srcIn ->
                    tmp.outputStream().buffered(SAF_COPY_BUFFER_BYTES).use { out ->
                        val buf = ByteArray(SAF_COPY_BUFFER_BYTES)
                        while (true) {
                            checkCancelled()
                            val n = srcIn.read(buf)
                            if (n <= 0) break
                            bytes += n
                            if (bytes > maxBytes) {
                                throw IllegalStateException("SAF project exceeds ${maxBytes / (1024 * 1024)} MiB mirror limit")
                            }
                            if (bytes % SAF_SPACE_CHECK_INTERVAL_BYTES < n) ensureStagingFreeSpace(stage, 0L)
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            if (bytes >= nextProgressBytes) {
                                onProgress(SafMirrorProgress(SafMirrorPhase.COPYING_EXTERNAL, files, bytes, rel))
                                nextProgressBytes = bytes + SAF_PROGRESS_INTERVAL_BYTES
                            }
                        }
                    }
                }
                replace(tmp, dst)
                entries[rel] = "f:${digest.digest().toHex()}"
                onProgress(SafMirrorProgress(SafMirrorPhase.COPYING_EXTERNAL, files, bytes, rel))
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }

        fun copyDir(src: DocumentFile, dst: File, relDir: String, depth: Int) {
            checkCancelled()
            require(depth <= maxDepth) { "SAF project nesting exceeds $maxDepth levels" }
            check(dst.isDirectory || dst.mkdirs()) { "Cannot create staging directory: ${dst.path}" }
            if (relDir.isNotBlank()) entries[relDir] = DIR
            for (child in src.listFiles()) {
                val rawName = child.name ?: throw IllegalStateException("SAF contains an unnamed entry below ${relDir.ifBlank { "/" }}")
                val name = try {
                    PathSecurity.safeLeafName(rawName)
                } catch (t: Throwable) {
                    throw IllegalStateException("Unsupported SAF filename '$rawName' below ${relDir.ifBlank { "/" }}", t)
                }
                val rel = if (relDir.isBlank()) name else "$relDir/$name"
                if (isRuntimePath(rel)) continue
                val out = PathSecurity.resolveWithin(stage, rel)
                when {
                    child.isDirectory -> copyDir(child, out, rel, depth + 1)
                    child.isFile -> copyFile(child, out, rel)
                }
            }
        }

        copyDir(root, stage, "", 0)
        return Snapshot(entries, files, bytes)
    }

    private fun scanLocal(
        root: File,
        maxFiles: Int,
        maxBytes: Long,
        maxDepth: Int,
        checkCancelled: () -> Unit = {},
        onProgress: (SafMirrorProgress) -> Unit = {},
    ): Snapshot {
        val entries = linkedMapOf<String, String>()
        var files = 0
        var bytes = 0L
        var nextProgressBytes = 0L
        if (!root.exists()) return Snapshot(entries, 0, 0)
        val base = root.toPath()
        Files.walkFileTree(base, emptySet(), maxDepth, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                if (dir == base) return FileVisitResult.CONTINUE
                if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
                val rel = base.relativize(dir).toString().replace(File.separatorChar, '/')
                if (isRuntimePath(rel)) return FileVisitResult.SKIP_SUBTREE
                entries[rel] = DIR
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
                val rel = base.relativize(file).toString().replace(File.separatorChar, '/')
                if (isRuntimePath(rel)) return FileVisitResult.CONTINUE
                if (++files > maxFiles) throw IllegalStateException("Local SAF mirror exceeds $maxFiles files")
                val size = Files.size(file)
                bytes += size
                if (bytes > maxBytes) throw IllegalStateException("Local SAF mirror exceeds ${maxBytes / (1024 * 1024)} MiB limit")
                entries[rel] = fingerprintFile(file.toFile(), checkCancelled)
                if (bytes >= nextProgressBytes || files % SAF_PROGRESS_FILE_INTERVAL == 0) {
                    onProgress(SafMirrorProgress(SafMirrorPhase.SCANNING_LOCAL, files, bytes, rel))
                    nextProgressBytes = bytes + SAF_PROGRESS_INTERVAL_BYTES
                }
                return FileVisitResult.CONTINUE
            }
        })
        onProgress(SafMirrorProgress(SafMirrorPhase.SCANNING_LOCAL, files, bytes))
        return Snapshot(entries, files, bytes)
    }

    private fun resolve(previous: SafManifestFile, local: Map<String, String>, external: Map<String, String>, checkCancelled: () -> Unit = {}): Resolution {
        val localWinners = linkedSetOf<String>()
        val conflicts = linkedSetOf<String>()
        val all = linkedSetOf<String>().apply {
            addAll(previous.entries.keys)
            addAll(local.keys)
            addAll(external.keys)
        }

        for (path in all.sortedBy { it.count { ch -> ch == '/' } }) {
            checkCancelled()
            val prev = previous.entries[path]
            val localNow = local[path]
            val externalNow = external[path]

            if (prev == LEGACY_UNKNOWN) {
                // Never guess if the two sides differ.
                when {
                    localNow == externalNow -> Unit
                    localNow == null && externalNow != null -> Unit
                    localNow != null && externalNow == null -> conflicts += path
                    else -> conflicts += path
                }
                continue
            }

            if (prev == null) {
                when {
                    localNow == externalNow -> Unit
                    localNow == null -> Unit 
                    externalNow == null -> localWinners += path 
                    else -> conflicts += path 
                }
                continue
            }

            val localChanged = localNow != prev
            val externalChanged = externalNow != prev
            when {
                localChanged && externalChanged && localNow != externalNow -> conflicts += path
                localChanged -> localWinners += path
                else -> Unit 
            }
        }
        return Resolution(localWinners, conflicts)
    }

     
    private fun resolvedEntries(
        external: Map<String, String>,
        local: Map<String, String>,
        localWinners: Set<String>,
    ): Map<String, String> {
        val desired = external.toMutableMap()
        for (root in collapsePaths(localWinners)) {
            val prefix = "$root/"
            desired.keys.removeAll { it == root || it.startsWith(prefix) }
            local.forEach { (path, fingerprint) ->
                if (path == root || path.startsWith(prefix)) desired[path] = fingerprint
            }
        }
        return desired.toSortedMap()
    }

    private fun applyLocalWinnersToExternal(
        paths: Set<String>,
        local: Map<String, String>,
        externalAtScan: Map<String, String>,
        checkCancelled: () -> Unit = {},
    ) {
        val roots = collapsePaths(paths)
        val deletions = roots.filter { local[it] == null }
            .sortedWith(compareByDescending<String> { it.count { ch -> ch == '/' } }.thenByDescending { it.length })
        for (path in deletions) {
            checkCancelled()
            assertExternalUnchanged(path, externalAtScan, includeTree = true, checkCancelled)
            find(path)?.let { if (!it.delete()) throw IllegalStateException("Cannot delete SAF path: $path") }
        }

        val writes = roots.filter { local[it] != null }
            .sortedWith(compareBy<String> { it.count { ch -> ch == '/' } }.thenBy { it })
        for (path in writes) {
            checkCancelled()
            assertExternalUnchanged(path, externalAtScan, includeTree = local[path] == DIR, checkCancelled)
            val file = PathSecurity.resolveWithin(localRoot, path)
            syncLocalPath(file, path, null, checkCancelled)
        }
    }

    private fun collapsePaths(paths: Set<String>): Set<String> {
        val ordered = paths.sortedWith(compareBy<String> { it.count { ch -> ch == '/' } }.thenBy { it })
        val roots = linkedSetOf<String>()
        for (path in ordered) {
            var cursor = path
            var covered = false
            while (true) {
                if (cursor in roots) { covered = true; break }
                val slash = cursor.lastIndexOf('/')
                if (slash < 0) break
                cursor = cursor.substring(0, slash)
            }
            if (!covered) roots += path
        }
        return roots
    }


    private fun preserveLocalExecutableIntent(
        stage: File,
        localEntries: Map<String, String>,
        checkCancelled: () -> Unit = {},
    ) {
        localEntries.forEach { (rel, fingerprint) ->
            checkCancelled()
            if (!fingerprint.startsWith("f:")) return@forEach
            val source = PathSecurity.resolveWithin(localRoot, rel)
            if (!source.isFile || PathSecurity.isSymbolicLink(source) || !source.canExecute()) return@forEach
            val staged = PathSecurity.resolveWithin(stage, rel)
            if (!staged.isFile || PathSecurity.isSymbolicLink(staged)) return@forEach
            WorkspaceExecutablePolicy.preserveExplicitExecutableBit(localRoot, source, stage, staged)
        }
    }

    private fun applyLocalWinnersToStage(stage: File, paths: Set<String>, local: Map<String, String>, checkCancelled: () -> Unit = {}) {
        val ordered = collapsePaths(paths).sortedWith(compareBy<String> { it.count { ch -> ch == '/' } }.thenBy { it })
        for (path in ordered) {
            checkCancelled()
            val src = PathSecurity.resolveWithin(localRoot, path)
            val dst = PathSecurity.resolveWithin(stage, path)
            when (local[path]) {
                null -> PathSecurity.deleteTreeNoFollow(dst)
                DIR -> {
                    if (dst.exists() && !dst.isDirectory) PathSecurity.deleteTreeNoFollow(dst)
                    check(dst.isDirectory || dst.mkdirs()) { "Cannot stage local directory: $path" }
                }
                else -> {
                    if (dst.isDirectory) PathSecurity.deleteTreeNoFollow(dst)
                    dst.parentFile?.mkdirs()
                    copyFileAtomic(src, dst, checkCancelled)
                }
            }
        }
    }

    private fun swapStageIntoLocal(stage: File, backup: File) {
        check(backup.mkdirs()) { "Cannot create SAF rollback directory" }
        val movedOld = mutableListOf<Pair<File, File>>()
        val installed = mutableListOf<File>()
        try {
            localRoot.listFiles().orEmpty().forEach { current ->
                if (current.name == ".droide" || current.name == ".git") return@forEach
                val dst = File(backup, current.name)
                move(current, dst)
                movedOld += current to dst
            }
            stage.listFiles().orEmpty().forEach { staged ->
                val dst = File(localRoot, staged.name)
                move(staged, dst)
                installed += dst
            }
        } catch (t: Throwable) {
            installed.asReversed().forEach { runCatching { PathSecurity.deleteTreeNoFollow(it) } }
            movedOld.asReversed().forEach { (original, stored) ->
                if (stored.exists() || PathSecurity.isSymbolicLink(stored)) runCatching { move(stored, original) }
            }
            throw IllegalStateException("SAF local swap failed and was rolled back: ${t.message}", t)
        }
        PathSecurity.deleteTreeNoFollow(backup)
    }

    private fun syncLocalPath(local: File, rel: String, known: MutableMap<String, String>?, checkCancelled: () -> Unit = {}) {
        checkCancelled()
        require(!PathSecurity.isSymbolicLink(local)) { "Symbolic links are not synchronized: $rel" }
        when {
            local.isFile -> {
                find(rel)?.takeIf { it.isDirectory }?.let {
                    if (!it.delete()) throw IllegalStateException("Cannot replace SAF directory with file: $rel")
                }
                val fingerprint = writeFile(rel, local, checkCancelled)
                known?.set(rel, fingerprint)
            }
            local.isDirectory -> syncDirectory(local, rel, known, checkCancelled)
            else -> throw IllegalStateException("Local path missing: $rel")
        }
    }

    private fun tree(): DocumentFile = DocumentFile.fromTreeUri(context, uri)
        ?.takeIf { it.isDirectory }
        ?: throw IllegalStateException("SAF tree permission unavailable")

    private fun find(path: String): DocumentFile? {
        var cur = tree()
        for (segment in segments(path)) cur = cur.findFile(segment) ?: return null
        return cur
    }

    private fun ensureDirectory(path: String): DocumentFile {
        var cur = tree()
        for (segment in segments(path)) {
            val existing = cur.findFile(segment)
            cur = when {
                existing == null -> cur.createDirectory(segment)
                    ?: throw IllegalStateException("Cannot create SAF directory: $segment")
                existing.isDirectory -> existing
                else -> {
                    if (!existing.delete()) throw IllegalStateException("Cannot replace SAF file with directory: $segment")
                    cur.createDirectory(segment) ?: throw IllegalStateException("Cannot create SAF directory: $segment")
                }
            }
        }
        return cur
    }

    private fun ensureParent(path: String): Pair<DocumentFile, String> {
        val parts = segments(path)
        require(parts.isNotEmpty()) { "Empty path" }
        var cur = tree()
        for (segment in parts.dropLast(1)) {
            val existing = cur.findFile(segment)
            cur = when {
                existing == null -> cur.createDirectory(segment)
                    ?: throw IllegalStateException("Cannot create SAF directory $segment")
                existing.isDirectory -> existing
                else -> throw IllegalStateException("SAF path component is not a directory: $segment")
            }
        }
        return cur to parts.last()
    }

    private fun writeFile(path: String, local: File, checkCancelled: () -> Unit = {}): String =
        writeJournal.writeFile(path, local, checkCancelled)

    private fun syncDirectory(local: File, rel: String, known: MutableMap<String, String>?, checkCancelled: () -> Unit = {}) {
        val clean = rel.trim('/').replace('\\', '/')
        require(clean.isNotBlank()) { "Root sync must use refreshFromTree()" }
        ensureDirectory(clean)
        known?.set(clean, DIR)
        local.walkTopDown().onEnter { PathSecurity.canDescend(localRoot, it) }.forEach { item ->
            checkCancelled()
            if (item == local || PathSecurity.isSymbolicLink(item) || !PathSecurity.contains(localRoot, item)) return@forEach
            val suffix = local.toPath().relativize(item.toPath()).toString().replace(File.separatorChar, '/')
            val childRel = "$clean/$suffix"
            if (isRuntimePath(childRel)) return@forEach
            if (item.isDirectory) {
                ensureDirectory(childRel)
                known?.set(childRel, DIR)
            } else if (item.isFile) {
                val fingerprint = writeFile(childRel, item, checkCancelled)
                known?.set(childRel, fingerprint)
            }
        }
    }

    private fun removeKnownTree(known: MutableMap<String, String>, path: String) {
        val base = path.trimEnd('/')
        known.keys.removeAll { it == base || it.startsWith("$base/") }
    }

    private fun segments(path: String): List<String> = path.replace('\\', '/').split('/')
        .filter { it.isNotEmpty() }
        .map(PathSecurity::safeLeafName)

    private fun loadManifest(): SafManifestFile {
        if (manifest.isFile && manifest.length() <= MAX_MANIFEST_BYTES) {
            runCatching { json.decodeFromString<SafManifestFile>(manifest.readText()) }.getOrNull()?.let { return it }
        }
        if (legacyManifest.isFile && legacyManifest.length() <= 2_000_000) {
            val entries = legacyManifest.readLines().map { it.trim() }.filter { it.isNotEmpty() }.associate { raw ->
                raw.trimEnd('/') to LEGACY_UNKNOWN
            }
            return SafManifestFile(version = 1, entries = entries)
        }
        return SafManifestFile()
    }

    private fun saveManifest(entries: Map<String, String>) {
        manifest.parentFile?.mkdirs()
        val tmp = File(manifest.parentFile, ".${manifest.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(json.encodeToString(SafManifestFile.serializer(), SafManifestFile(entries = entries.toSortedMap())))
            replace(tmp, manifest)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun fingerprintFile(file: File, checkCancelled: () -> Unit = {}): String {
        require(file.isFile && !PathSecurity.isSymbolicLink(file)) { "Not a regular file: ${file.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(SAF_COPY_BUFFER_BYTES).use { input ->
            val buf = ByteArray(SAF_COPY_BUFFER_BYTES)
            while (true) {
                checkCancelled()
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return "f:${digest.digest().toHex()}"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun copyFileAtomic(src: File, dst: File, checkCancelled: () -> Unit = {}) {
        require(src.isFile && !PathSecurity.isSymbolicLink(src)) { "Not a regular file: ${src.path}" }
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, ".${dst.name}.tmp-${System.nanoTime()}")
        try {
            src.inputStream().buffered(SAF_COPY_BUFFER_BYTES).use { input ->
                tmp.outputStream().buffered(SAF_COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(SAF_COPY_BUFFER_BYTES)
                    while (true) {
                        checkCancelled()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                    }
                }
            }
            replace(tmp, dst)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun isRuntimePath(path: String): Boolean {
        val p = path.trimStart('/').replace('\\', '/')
        return p == ".droide" || p.startsWith(".droide/") || p == ".git" || p.startsWith(".git/")
    }

    private fun replace(tmp: File, target: File) {
        target.parentFile?.mkdirs()
        runCatching {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun move(source: File, target: File) {
        target.parentFile?.mkdirs()
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath())
        }
    }

    companion object {
        private const val DIR = "d"
        private const val LEGACY_UNKNOWN = "?"
        private const val MAX_MIRROR_FILES = 50_000
        private const val MAX_MIRROR_DEPTH = 64
        private const val MAX_MIRROR_BYTES = 4L * 1024 * 1024 * 1024
        private const val SAF_COPY_BUFFER_BYTES = 256 * 1024
        private const val SAF_PROGRESS_INTERVAL_BYTES = 4L * 1024 * 1024
        private const val SAF_PROGRESS_FILE_INTERVAL = 64
        private const val SAF_SPACE_CHECK_INTERVAL_BYTES = 16L * 1024 * 1024
        private const val MIN_SAFE_FREE_BYTES = 192L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    }
}
