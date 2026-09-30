package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PathSecurityTest {
    private lateinit var root: java.io.File

    @Before fun setUp() { root = Files.createTempDirectory("droide-path-").toFile() }
    @After fun tearDown() { PathSecurity.deleteTreeNoFollow(root) }

    @Test fun blocksParentTraversal() {
        assertThrows(IllegalArgumentException::class.java) { PathSecurity.resolveWithin(root, "../escape") }
    }

    @Test fun blocksAbsolutePath() {
        assertThrows(IllegalArgumentException::class.java) { PathSecurity.resolveWithin(root, "/tmp/escape") }
    }

    @Test fun acceptsChildPath() {
        val child = PathSecurity.resolveWithin(root, "src/Main.kt")
        assertTrue(child.canonicalPath.startsWith(root.canonicalPath))
    }

    @Test fun blocksSymlinkEscapeWhenSupported() {
        val outside = Files.createTempDirectory("droide-outside-")
        try {
            val link = root.toPath().resolve("link")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: java.nio.file.FileSystemException) {
                return
            }
            assertThrows(IllegalArgumentException::class.java) { PathSecurity.resolveWithin(root, "link/secret.txt") }
        } finally {
            PathSecurity.deleteTreeNoFollow(outside.toFile())
        }
    }

    @Test fun deleteTreeNoFollowPreservesNestedSymlinkTarget() {
        val outside = Files.createTempDirectory("droide-delete-outside-")
        try {
            val marker = outside.resolve("KEEP.txt")
            Files.write(marker, "keep".toByteArray(Charsets.UTF_8))
            val tree = root.toPath().resolve("tree")
            Files.createDirectories(tree.resolve("ordinary"))
            Files.write(tree.resolve("ordinary/x.txt"), "x".toByteArray(Charsets.UTF_8))
            try {
                Files.createSymbolicLink(tree.resolve("escape"), outside)
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: java.nio.file.FileSystemException) {
                return
            }

            assertTrue(PathSecurity.deleteTreeNoFollow(tree.toFile()))
            assertFalse(Files.exists(tree, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("keep", String(Files.readAllBytes(marker), Charsets.UTF_8))
        } finally {
            PathSecurity.deleteTreeNoFollow(outside.toFile())
        }
    }

    @Test fun deleteTreeNoFollowUnlinksTopLevelSymlinkOnly() {
        val outside = Files.createTempDirectory("droide-delete-top-outside-")
        try {
            val marker = outside.resolve("KEEP.txt")
            Files.write(marker, "keep".toByteArray(Charsets.UTF_8))
            val link = root.toPath().resolve("top-link")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: java.nio.file.FileSystemException) {
                return
            }

            assertTrue(PathSecurity.deleteTreeNoFollow(link.toFile()))
            assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("keep", String(Files.readAllBytes(marker), Charsets.UTF_8))
        } finally {
            PathSecurity.deleteTreeNoFollow(outside.toFile())
        }
    }
}
