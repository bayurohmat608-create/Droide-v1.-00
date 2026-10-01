package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProjectWorkspaceMaintenanceTest {
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("droide-startup").toFile()
        try { block(dir) } finally { PathSecurity.deleteTreeNoFollow(dir) }
    }

    @Test fun abandonedImportIsDetachedBeforeDeferredDisposal() = fixture { root ->
        val owned = File(root, "p-owned").apply { mkdirs() }
        File(owned, "source.kt").writeText("keep")
        val orphan = File(root, "p-orphan").apply { mkdirs() }
        File(orphan, "large.src").writeText("unfinished")
        val pending = ProjectWorkspaceMaintenance.prepare(root, mapOf("p-owned" to owned))
        assertFalse(orphan.exists())
        assertEquals("unfinished", File(root, ".delete-p-orphan/large.src").readText())
        assertEquals("keep", File(owned, "source.kt").readText())
        assertTrue(ProjectWorkspaceMaintenance.collect(root, pending).isEmpty())
        assertFalse(File(root, ".delete-p-orphan").exists())
    }

    @Test fun registeredInterruptedDeletionIsRestored() = fixture { root ->
        val backup = File(root, ".delete-p-owned").apply { mkdirs() }
        File(backup, "source").writeText("recover")
        val target = File(root, "p-owned")
        assertTrue(ProjectWorkspaceMaintenance.prepare(root, mapOf("p-owned" to target)).isEmpty())
        assertEquals("recover", File(target, "source").readText())
    }

    @Test fun registeredDuplicateBackupIsPreserved() = fixture { root ->
        val target = File(root, "p-owned").apply { mkdirs() }
        val backup = File(root, ".delete-p-owned").apply { mkdirs() }
        File(backup, "source").writeText("backup")
        val pending = ProjectWorkspaceMaintenance.prepare(root, mapOf("p-owned" to target))
        assertTrue(pending.isEmpty())
        assertTrue(backup.exists())
    }

    @Test fun orphanSymlinkNeverDeletesItsExternalTarget() = fixture { root ->
        val outside = Files.createTempDirectory("droide-outside").toFile()
        try {
            File(outside, "source").writeText("external")
            Files.createSymbolicLink(File(root, "p-link").toPath(), outside.toPath())
            assertTrue(ProjectWorkspaceMaintenance.prepare(root, emptyMap()).isEmpty())
            assertEquals("external", File(outside, "source").readText())
        } finally { PathSecurity.deleteTreeNoFollow(outside) }
    }

    @Test fun detachedNestedSymlinkIsUnlinkedWithoutFollowingIt() = fixture { root ->
        val outside = Files.createTempDirectory("droide-outside").toFile()
        try {
            File(outside, "source").writeText("external")
            val orphan = File(root, "p-orphan").apply { mkdirs() }
            Files.createSymbolicLink(File(orphan, "link").toPath(), outside.toPath())
            val pending = ProjectWorkspaceMaintenance.prepare(root, emptyMap())
            ProjectWorkspaceMaintenance.collect(root, pending)
            assertEquals("external", File(outside, "source").readText())
        } finally { PathSecurity.deleteTreeNoFollow(outside) }
    }

    @Test fun cancellationAndLiveRootsCannotEnterCollector() = fixture { root ->
        val target = File(root, "p-owned").apply { mkdirs() }
        File(target, "source").writeText("keep")
        try { ProjectWorkspaceMaintenance.collect(root, listOf(target)); fail("live root accepted") }
        catch (_: IllegalArgumentException) { }
        val job = Job().apply { cancel() }
        try { withContext(job) { ProjectWorkspaceMaintenance.prepare(root, emptyMap()) }; fail("cancel ignored") }
        catch (_: CancellationException) { }
        assertEquals("keep", File(target, "source").readText())
    }
}
