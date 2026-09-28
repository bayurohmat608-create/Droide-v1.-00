package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspaceReadTest {
    @Test fun liveTextBufferWinsOverDiskWithoutSaving() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-read").toFile()
        root.resolve("Main.kt").writeText("A-disk")
        val files = FileRepository(root)
        val authority = fixedAuthority(
            WorkspaceDocumentSnapshot(
                path = "Main.kt",
                content = "first\nB-unsaved\nthird",
                loaded = true,
                kind = WorkspaceDocumentKind.TEXT,
                dirty = true,
                editable = true,
                revision = 3,
                changeVersion = 7,
                selectionStart = 0,
                selectionEnd = 0,
                savedContentLength = 6,
            )
        )

        val output = AgentWorkspaceReader(files, authority).read("./Main.kt")
        assertTrue(output.contains("source=editor_buffer"))
        assertTrue(output.contains("dirty=true"))
        assertTrue(output.contains("revision=3"))
        assertTrue(output.contains("change_version=7"))
        assertTrue(output.contains("B-unsaved"))
        assertFalse(output.contains("A-disk"))
        assertTrue(root.resolve("Main.kt").readText() == "A-disk")

        val ranged = AgentWorkspaceReader(files, authority).read("Main.kt", 2, 2)
        assertTrue(ranged.contains("   2│ B-unsaved"))
        assertFalse(ranged.contains("first"))
    }

    @Test fun missingOrNonTextLiveDocumentFallsBackToDisk() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-read-fallback").toFile()
        root.resolve("Main.kt").writeText("disk-truth")
        val files = FileRepository(root)
        val binaryAuthority = fixedAuthority(
            WorkspaceDocumentSnapshot(
                path = "Main.kt",
                content = "editor-preview-must-not-win",
                loaded = true,
                kind = WorkspaceDocumentKind.BINARY,
                dirty = false,
                editable = false,
                revision = 1,
                changeVersion = 0,
                selectionStart = 0,
                selectionEnd = 0,
                savedContentLength = 0,
            )
        )

        assertTrue(AgentWorkspaceReader(files, null).read("Main.kt") == "disk-truth")
        assertTrue(AgentWorkspaceReader(files, binaryAuthority).read("Main.kt") == "disk-truth")
    }

    private fun fixedAuthority(snapshot: WorkspaceDocumentSnapshot): WorkspaceDocumentAuthority =
        object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? =
                snapshot.takeIf { it.path == path }
            override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = listOf(snapshot)
        }
}
