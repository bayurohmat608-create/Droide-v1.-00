package com.baystudio.droide.core

import java.io.File
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

 
object PathSecurity {
    fun resolveWithin(root: File, relative: String): File {
        require(relative.indexOf('\u0000') < 0) { "Path contains NUL" }
        require(!File(relative).isAbsolute) { "Absolute paths are not allowed: $relative" }
        val rootPath = root.canonicalFile.toPath().normalize()
        val candidate = File(root, relative).canonicalFile.toPath().normalize()
        require(candidate == rootPath || candidate.startsWith(rootPath)) {
            "Path escapes project root: $relative"
        }
        return candidate.toFile()
    }

    fun contains(root: File, candidate: File): Boolean {
        val rootPath: Path = root.canonicalFile.toPath().normalize()
        val child = candidate.canonicalFile.toPath().normalize()
        return child == rootPath || child.startsWith(rootPath)
    }


    fun isSymbolicLink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    // True only for real directories inside root; symbolic-link directories are never traversed.
    fun canDescend(root: File, directory: File): Boolean =
        contains(root, directory) && !isSymbolicLink(directory)

    // Recursive delete that never follows symbolic links.
    fun deleteTreeNoFollow(target: File): Boolean {
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return true
        return runCatching {
            Files.walkFileTree(target.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            true
        }.getOrDefault(false)
    }

    fun safeLeafName(name: String): String {
        require(name.isNotEmpty()) { "Name is empty" }
        require(name != "." && name != "..") { "Invalid name" }
        require('/' !in name && '\\' !in name && '\u0000' !in name) { "Name must not contain path separators" }
        require(name.none { it.code < 0x20 || it.code == 0x7f }) { "Name must not contain control characters" }
        require(name.length <= 255) { "Name is too long" }
        return name
    }
}
