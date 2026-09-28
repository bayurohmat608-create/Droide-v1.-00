package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

 
internal class WorkspaceSyncFingerprintCache {
    data class FileIdentity(val sizeBytes: Long, val executable: Boolean, val sha256: String)

    private data class Entry(
        val sizeBytes: Long,
        val modifiedNanos: Long,
        val executable: Boolean,
        val sha256: String,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun identity(relativePath: String, file: File, forceHash: Boolean, digest: (File) -> String): FileIdentity {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val size = attrs.size()
        val modifiedNanos = attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS)
        val executable = file.canExecute()
        val cached = entries[relativePath]
        val hash = if (!forceHash && cached != null && cached.sizeBytes == size &&
            cached.modifiedNanos == modifiedNanos && cached.executable == executable
        ) cached.sha256 else digest(file).also { entries[relativePath] = Entry(size, modifiedNanos, executable, it) }
        return FileIdentity(size, executable, hash)
    }

    fun retainOnly(relativePaths: Set<String>) {
        entries.keys.toList().filter { it !in relativePaths }.forEach(entries::remove)
    }
}
