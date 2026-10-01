package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalAgentWorkspaceMirrorTest {
    @Test fun sourceCanReplaceAFileWithDirectoryAndBack() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val item = File(source, "item").apply { writeText("first") }
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            assertTrue(item.delete()); assertTrue(item.mkdir())
            File(item, "child").writeText("second")
            mirror.refresh()
            assertEquals("second", File(copy, "item/child").readText())
            assertTrue(PathSecurity.deleteTreeNoFollow(item)); item.writeText("third")
            mirror.refresh()
            assertEquals("third", File(copy, "item").readText())
            assertEquals("third", item.readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun nativeEditsStayInMirrorUntilApprovedReconciliation() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "main.kt").writeText("source")
            File(source, ".env").writeText("secret")
            File(source, "module/node_modules/dependency").apply { parentFile.mkdirs(); writeText("ignore") }
            val copy = File(root, "mirror")
            val mirror = LocalAgentWorkspaceMirror(source, copy)
            mirror.refresh()
            assertFalse(File(copy, ".env").exists())
            assertFalse(File(copy, "module/node_modules").exists())
            File(copy, "main.kt").writeText("agent")
            File(copy, "unapproved.kt").writeText("agent-new")
            assertEquals("source", File(source, "main.kt").readText())
            mirror.refresh()
            assertEquals("source", File(copy, "main.kt").readText())
            assertFalse(File(copy, "unapproved.kt").exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun versionOneAndTwoManifestsKeepCorrectSourceHashes() {
        val hash = "a".repeat(64)
        assertEquals(mapOf("main.kt" to hash), AgentWorkspacePathPolicy.manifest("$hash\tmain.kt\n"))
        assertEquals(mapOf("main.kt" to hash), AgentWorkspacePathPolicy.manifest("$hash\t493\tmain.kt\n"))
        assertTrue(AgentWorkspacePathPolicy.manifest("$hash\t420\t.env\n").isEmpty())
        assertFalse(AgentWorkspacePathPolicy.visible("module/node_modules/x"))
    }

    @Test fun checksumPathsKeepSpacesAndRejectMalformedOrDuplicatedRows() {
        val hash = "a".repeat(64)
        assertEquals(mapOf("file name " to hash), AgentWorkspacePathPolicy.hashListing("$hash  ./file name \n"))
        assertTrue(AgentWorkspacePathPolicy.hashListing("$hash  ./module/node_modules/x\n").isEmpty())
        for (listing in listOf("truncated row", "$hash  ./main\n$hash  ./main\n")) {
            try { AgentWorkspacePathPolicy.hashListing(listing); fail("Unsafe checksum listing accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun hostileMirrorSymlinkCannotModifyOriginalOrExternalFiles() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "dir/main").apply { parentFile.mkdirs(); writeText("source") }
            val outside = File(root, "outside").apply { mkdirs() }
            File(outside, "main").writeText("outside")
            val copy = File(root, "mirror").apply { mkdirs() }
            Files.createSymbolicLink(File(copy, "dir").toPath(), outside.toPath())
            LocalAgentWorkspaceMirror(source, copy).refresh()
            assertEquals("outside", File(outside, "main").readText())
            assertEquals("source", File(copy, "dir/main").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun cancellationCannotChangeAuthoritativeSource() = runBlocking {
        val root = Files.createTempDirectory("droide-agent").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "main").writeText("source")
            val job = Job().apply { cancel() }
            try { withContext(job) { LocalAgentWorkspaceMirror(source, File(root, "mirror")).refresh() }; fail("Cancellation ignored") }
            catch (_: CancellationException) { }
            assertEquals("source", File(source, "main").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
