package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

// SHA-256 helper that never follows the final path component and can enforce project containment.
internal object SafeFileDigest {
    fun sha256RegularNoFollow(file: File, containmentRoot: File? = null): String {
        if (containmentRoot != null) require(PathSecurity.contains(containmentRoot, file)) { "File escapes project root: ${file.path}" }
        val path = file.toPath()
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Expected regular non-symlink file: ${file.path}" }
        if (containmentRoot != null) require(PathSecurity.contains(containmentRoot, file)) { "File escapes project root: ${file.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (containmentRoot != null) require(PathSecurity.contains(containmentRoot, file)) { "File escaped project root while hashing: ${file.path}" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
