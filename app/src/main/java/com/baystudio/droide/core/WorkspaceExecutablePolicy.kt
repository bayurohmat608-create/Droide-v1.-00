package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes










internal object WorkspaceExecutablePolicy {
    const val REGULAR_MODE = 420 
    const val EXECUTABLE_MODE = 493 

    fun projectedMode(projectRoot: File, file: File): Int {
        requireSafeRegular(projectRoot, file)
        if (file.canExecute()) return EXECUTABLE_MODE
        return if (hasPortableExecutableHeader(file)) EXECUTABLE_MODE else REGULAR_MODE
    }

     
    fun preserveExplicitExecutableBit(projectRoot: File, source: File, stagingRoot: File, staged: File) {
        requireSafeRegular(projectRoot, source)
        if (!source.canExecute()) return
        require(PathSecurity.contains(stagingRoot, staged)) { "SAF staged target escaped staging root" }
        require(staged.isFile && !PathSecurity.isSymbolicLink(staged)) { "SAF staged target is not a regular file" }
        check(staged.setExecutable(true, false)) { "Cannot preserve executable bit for ${source.name}" }
    }

    private fun hasPortableExecutableHeader(file: File): Boolean {
        val header = ByteArray(4)
        val read = Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            input.read(header)
        }
        if (read >= 2 && header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()) return true
        return read >= 4 &&
            header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
    }

    private fun requireSafeRegular(projectRoot: File, file: File) {
        require(PathSecurity.contains(projectRoot, file)) { "Workspace executable probe escaped project root" }
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink && !PathSecurity.isSymbolicLink(file)) {
            "Workspace executable probe requires a regular non-symlink file"
        }
    }
}
