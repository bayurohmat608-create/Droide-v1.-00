package com.baystudio.droide.core

import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


internal class LocalAgentWorkspaceMirror(private val source: File, val directory: File) {
    private val mutex = Mutex()

    suspend fun refresh(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(source.isDirectory && !PathSecurity.isSymbolicLink(source)) { "Agent source workspace is unavailable" }
            check(directory.isDirectory || directory.mkdirs()) { "Agent mirror storage is unavailable" }
            check(!PathSecurity.isSymbolicLink(directory)) { "Agent mirror cannot be a symlink" }
            require(!PathSecurity.contains(source, directory) && !PathSecurity.contains(directory, source)) { "Agent mirror must be separate from source" }
            val context = currentCoroutineContext()
            val sourcePath = source.canonicalFile.toPath()
            // Remove all symlinks before resolving target parents. Never follow agent-created links.
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    if (Files.isSymbolicLink(file)) Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
            })
            val entries = linkedMapOf<String, WorkspaceSyncManifest.Entry>()
            var bytes = 0L
            Files.walkFileTree(sourcePath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    if (dir == sourcePath) return FileVisitResult.CONTINUE
                    val rel = sourcePath.relativize(dir).toString().replace(File.separatorChar, '/')
                    if (!AgentWorkspacePathPolicy.visible(rel)) return FileVisitResult.SKIP_SUBTREE
                    val target = PathSecurity.resolveWithin(directory, rel)
                    if (target.exists() && !target.isDirectory) Files.delete(target.toPath())
                    check(target.isDirectory || target.mkdirs()) { "Cannot create agent mirror directory" }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    val rel = sourcePath.relativize(path).toString().replace(File.separatorChar, '/')
                    if (!attrs.isRegularFile || Files.isSymbolicLink(path) || !AgentWorkspacePathPolicy.visible(rel)) return FileVisitResult.CONTINUE
                    require(entries.size < 20_000) { "Agent mirror exceeds 20,000 source files" }
                    bytes += attrs.size(); require(bytes <= 512_000_000L) { "Agent mirror exceeds 512 MB of source files" }
                    val target = PathSecurity.resolveWithin(directory, rel)
                    if (target.isDirectory) check(PathSecurity.deleteTreeNoFollow(target)) { "Cannot replace stale agent mirror directory" }
                    check(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "Cannot create agent mirror directory" }
                    val temp = Files.createTempFile(target.parentFile.toPath(), ".droide-copy-", ".tmp").toFile()
                    try {
                        path.toFile().inputStream().use { input -> temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                context.ensureActive(); val n = input.read(buffer); if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                        } }
                        check(temp.setExecutable(path.toFile().canExecute(), true)) { "Cannot project executable mode" }
                        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } finally { temp.delete() }
                    // Hash the copied bytes, so a simultaneous source edit is detected during reconciliation.
                    entries[rel] = WorkspaceSyncManifest.Entry(sha256(target, context), if (path.toFile().canExecute()) 493 else 420)
                    return FileVisitResult.CONTINUE
                }
            })
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    val rel = directory.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                    if (rel != ".droide-sync-manifest.tsv" && AgentWorkspacePathPolicy.visible(rel) && rel !in entries) Files.deleteIfExists(path)
                    return FileVisitResult.CONTINUE
                }
            })
            val manifest = File(directory, ".droide-sync-manifest.tsv")
            val temp = File(directory, ".droide-sync-next.tmp")
            try {
                temp.writeText(WorkspaceSyncManifest.serialize(entries))
                Files.move(temp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            Unit
        }
    }

    private fun sha256(file: File, context: kotlin.coroutines.CoroutineContext): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { context.ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
