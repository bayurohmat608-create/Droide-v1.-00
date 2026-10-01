package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeWholeTreeArchiveSecurityTest {
    @Test fun safeRelativeSymlinkIsProjectedWithoutMaterializingTheLink() {
        val archive = tarGz(
            TarEntry("bin", type = '5', mode = 493),
            TarEntry("bin/tool", data = "payload".toByteArray(), mode = 493),
            TarEntry("links", type = '5', mode = 493),
            TarEntry("links/tool", type = '2', link = "../bin/tool", mode = 511),
        )
        SafeWholeTreeArchive.project(
            archive = archive,
            format = DeclarativeGitHubArtifactFormat.TAR_GZ,
            executablePaths = setOf("bin/tool"),
            maxFiles = 16,
            maxUnpackedBytes = 1024 * 1024,
            linkPolicy = DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE,
        ).use { projection ->
            assertEquals(1, projection.symlinks.size)
            assertEquals("bin/tool", projection.symlinks.single().resolvedPath)
            assertEquals(false, File(projection.root, "links/tool").exists())
        }
        archive.parentFile?.deleteRecursively()
    }


    @Test fun releasedProjectedFileIsRemovedWithoutDeletingSiblingPayloads() {
        val archive = tarGz(
            TarEntry("bin", type = '5', mode = 493),
            TarEntry("bin/tool", data = "tool".toByteArray(), mode = 493),
            TarEntry("bin/helper", data = "helper".toByteArray(), mode = 493),
        )
        SafeWholeTreeArchive.project(
            archive = archive,
            format = DeclarativeGitHubArtifactFormat.TAR_GZ,
            executablePaths = setOf("bin/tool", "bin/helper"),
            maxFiles = 16,
            maxUnpackedBytes = 1024 * 1024,
        ).use { projection ->
            val tool = projection.files.single { it.relativePath == "bin/tool" }
            val helper = projection.files.single { it.relativePath == "bin/helper" }
            check(tool.file.isFile && helper.file.isFile)
            projection.releaseLocalPayload(tool)
            assertEquals(false, tool.file.exists())
            assertEquals(true, helper.file.isFile)
        }
        archive.parentFile?.deleteRecursively()
    }

    @Test fun symlinkMayNotBeAnAncestorOfAnyProjectedEntry() {
        val archive = tarGz(
            TarEntry("bin", type = '5', mode = 493),
            TarEntry("bin/tool", data = "payload".toByteArray(), mode = 493),
            TarEntry("alias", type = '2', link = "bin", mode = 511),
            TarEntry("alias/tool", data = "shadow".toByteArray(), mode = 493),
        )
        try {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                SafeWholeTreeArchive.project(
                    archive = archive,
                    format = DeclarativeGitHubArtifactFormat.TAR_GZ,
                    executablePaths = setOf("bin/tool"),
                    maxFiles = 16,
                    maxUnpackedBytes = 1024 * 1024,
                    linkPolicy = DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE,
                )
            }
            check(failure.message.orEmpty().contains("entry below a symlink"))
        } finally {
            archive.parentFile?.deleteRecursively()
        }
    }

    private data class TarEntry(
        val name: String,
        val data: ByteArray = byteArrayOf(),
        val type: Char = '0',
        val link: String = "",
        val mode: Int = 420,
    )

    private fun tarGz(vararg entries: TarEntry): File {
        val dir = java.nio.file.Files.createTempDirectory("droide-whole-tree-").toFile()
        val file = File(dir, "fixture.tar.gz")
        GZIPOutputStream(FileOutputStream(file)).use { gzip ->
            entries.forEach { entry ->
                val header = ByteArray(512)
                ascii(header, 0, 100, entry.name)
                octal(header, 100, 8, entry.mode.toLong())
                octal(header, 108, 8, 0)
                octal(header, 116, 8, 0)
                octal(header, 124, 12, if (entry.type == '0') entry.data.size.toLong() else 0)
                octal(header, 136, 12, 0)
                for (i in 148..155) header[i] = ' '.code.toByte()
                header[156] = entry.type.code.toByte()
                ascii(header, 157, 100, entry.link)
                ascii(header, 257, 6, "ustar")
                ascii(header, 263, 2, "00")
                val checksum = header.sumOf { it.toInt() and 0xff }.toLong()
                val check = checksum.toString(8).padStart(6, '0')
                ascii(header, 148, 6, check)
                header[154] = 0
                header[155] = ' '.code.toByte()
                gzip.write(header)
                if (entry.type == '0') {
                    gzip.write(entry.data)
                    val padding = (512 - entry.data.size % 512) % 512
                    if (padding > 0) gzip.write(ByteArray(padding))
                }
            }
            gzip.write(ByteArray(1024))
        }
        return file
    }

    private fun ascii(target: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= length)
        bytes.copyInto(target, offset)
    }

    private fun octal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0')
        ascii(target, offset, length - 1, text)
        target[offset + length - 1] = 0
    }
}
