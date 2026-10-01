package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.*

// Startup only detaches abandoned trees.
internal object ProjectWorkspaceMaintenance {
    suspend fun prepare(projectsRoot: File, registered: Map<String, File>): List<File> = withContext(Dispatchers.IO) {
        val root = projectsRoot.canonicalFile
        check(root.isDirectory || root.mkdirs()) { "Project storage is unavailable" }
        val targets = registered.mapNotNull { (id, file) ->
            runCatching { id to ProjectWorkspacePolicy.requireDirectChild(root, file) }.getOrNull()
        }.toMap()
        val owned = targets.values.map { it.path }.toSet()
        val pending = mutableListOf<File>()
        Files.newDirectoryStream(root.toPath()).use { entries ->
            for (path in entries) {
                currentCoroutineContext().ensureActive()
                if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                val file = path.toFile()
                when {
                    file.name.startsWith(".delete-") -> {
                        val target = targets[file.name.removePrefix(".delete-")]
                        if (target == null) pending += file
                        else if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) move(path, target.toPath())
                        // A registered project's duplicate tree is retained for recovery.
                    }
                    file.name.startsWith("p-") && file.absolutePath !in owned -> {
                        val detached = File(root, ".delete-${file.name}")
                        if (!Files.exists(detached.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            move(path, detached.toPath())
                            pending += detached
                        }
                    }
                }
            }
        }
        pending.distinctBy { it.absolutePath }
    }

    suspend fun collect(projectsRoot: File, detached: List<File>): List<String> = withContext(Dispatchers.IO) {
        val root = projectsRoot.canonicalFile.toPath()
        val context = currentCoroutineContext()
        val failed = mutableListOf<String>()
        for (file in detached) {
            context.ensureActive()
            val path = file.absoluteFile.toPath().normalize()
            require(path.parent == root && file.name.startsWith(".delete-")) { "Cleanup must target a detached direct-child tree" }
            if (Files.isSymbolicLink(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue
            try {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        context.ensureActive(); return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(item: Path, attrs: BasicFileAttributes): FileVisitResult {
                        context.ensureActive(); Files.deleteIfExists(item); return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                        context.ensureActive(); if (error != null) throw error
                        Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
                    }
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                failed += file.name
            }
        }
        failed
    }

    private fun move(from: Path, to: Path) {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(from, to) }
    }
}
