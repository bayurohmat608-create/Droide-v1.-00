package com.baystudio.droide.core

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

 
internal object VerifiedToolchainPack {
    class Snapshot internal constructor(val file: File, val sha256: String) : Closeable {
        override fun close() {
            check(!file.exists() || file.delete()) { "Could not remove verified toolchain snapshot" }
        }
    }

    fun create(cacheDir: File, source: File, expectedSha256: String, maxBytes: Long): Snapshot {
        require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid SHA-256" }
        require(maxBytes > 0L) { "Invalid toolchain archive size limit" }
        val sourcePath = source.toPath()
        val attrs = Files.readAttributes(sourcePath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Toolchain pack must be a regular non-symlink file" }
        require(attrs.size() <= maxBytes) { "Toolchain archive exceeds ${maxBytes} bytes" }
        cacheDir.mkdirs()
        require(cacheDir.isDirectory) { "Toolchain snapshot cache is unavailable" }
        val snapshot = File.createTempFile("droide-toolchain-verified-", ".zip", cacheDir)
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            Files.newInputStream(sourcePath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                snapshot.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= maxBytes) { "Toolchain archive grew beyond the safety limit while reading" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(expectedSha256, ignoreCase = true)) { "Toolchain pack checksum mismatch" }
            Snapshot(snapshot, actual.lowercase())
        } catch (failure: Throwable) {
            snapshot.delete()
            throw failure
        }
    }
}
