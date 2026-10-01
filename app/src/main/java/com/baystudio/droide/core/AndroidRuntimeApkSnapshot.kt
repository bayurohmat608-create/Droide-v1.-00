package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext


internal class AndroidRuntimeApkSnapshot(private val runtimeTemporaryRoot: File) {
    suspend fun <T> withSnapshot(apk: File, action: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        require(apk.isFile && apk.extension.equals("apk", ignoreCase = true) && !PathSecurity.isSymbolicLink(apk)) { "APK not found or is a symlink: ${apk.path}" }
        val stamp = AndroidApkSnapshotStamp.read(apk)
        val expectedBytes = stamp.size
        require(expectedBytes in 1..MAX_APK_BYTES) { "APK size is outside the supported bound" }
        require(!PathSecurity.isSymbolicLink(runtimeTemporaryRoot)) { "Runtime temporary root is a symlink" }
        check(runtimeTemporaryRoot.mkdirs() || runtimeTemporaryRoot.isDirectory) { "Runtime temporary storage is unavailable" }
        val parent = File(runtimeTemporaryRoot, "apk-runtime")
        require(!PathSecurity.isSymbolicLink(parent) && PathSecurity.contains(runtimeTemporaryRoot, parent)) { "APK staging directory escaped runtime storage" }
        check(parent.mkdirs() || parent.isDirectory) { "APK staging directory is unavailable" }
        check(parent.usableSpace >= expectedBytes + STORAGE_RESERVE_BYTES) { "Insufficient storage to snapshot the APK for install/run/debug" }
        val directory = Files.createTempDirectory(parent.toPath(), "session-").toFile()
        var failure: Throwable? = null
        try {
            val snapshot = File(directory, "artifact.apk")
            var copied = 0L
            Files.newByteChannel(apk.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { source ->
                require(source.size() == expectedBytes) { "APK changed before being copied" }
                Channels.newInputStream(source).use { input ->
                    FileOutputStream(snapshot).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= expectedBytes) { "APK changed while being copied" }
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                    stamp.verifyCopy(apk, copied, source.size())
                }
            }
            check(snapshot.setReadOnly()) { "Could not seal the runtime APK snapshot" }
            action(snapshot)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!PathSecurity.deleteTreeNoFollow(directory)) {
                val cleanup = IllegalStateException("Could not clean up the runtime APK snapshot")
                failure?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
    }

    companion object {
        private const val MAX_APK_BYTES = 2L * 1024 * 1024 * 1024
        private const val STORAGE_RESERVE_BYTES = 64L * 1024 * 1024
    }
}

internal data class AndroidApkSnapshotStamp(val size: Long, val modified: FileTime, val fileKey: Any?) {
    fun verifyCopy(source: File, copied: Long, openedSize: Long) {
        require(copied == size && openedSize == size && read(source) == this) { "APK changed while being copied" }
    }

    companion object {
        fun read(source: File): AndroidApkSnapshotStamp {
            val attrs = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(attrs.isRegularFile) { "APK must be a regular file" }
            return AndroidApkSnapshotStamp(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey())
        }
    }
}
