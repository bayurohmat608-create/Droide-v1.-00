package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.PathSecurity
import com.baystudio.droide.core.WorkspaceDocumentConflictException
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

 
class EditorDocumentIoTest {
    @Test fun failedReloadPreservesDirtyText() = runBlocking {
        val root = Files.createTempDirectory("droide-reload-").toFile()
        try {
            root.resolve("Main.kt").writeText("A")
            val files = FileRepository(root); val doc = EditorDocument("Main.kt")
            doc.ensureLoaded(files); doc.capture("user-B")
            root.resolve("Main.kt").writeBytes(byteArrayOf(0, 1, 2, 3))
            doc.reload(files, discardUnsaved = true)
            assertEquals("user-B", doc.content); assertEquals("A", doc.savedContent); assertTrue(doc.dirty)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun automaticReloadCannotDiscardNewerFormatterEdits() = runBlocking {
        val root = Files.createTempDirectory("droide-format-").toFile()
        try {
            root.resolve("Main.kt").writeText("A")
            val files = FileRepository(root); val doc = EditorDocument("Main.kt")
            doc.ensureLoaded(files); doc.capture("typed-during-formatter")
            root.resolve("Main.kt").writeText("formatted-A"); doc.reload(files)
            assertEquals("typed-during-formatter", doc.content); assertTrue(doc.dirty)
            doc.reload(files, discardUnsaved = true)
            assertEquals("formatted-A", doc.content); assertFalse(doc.dirty)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun largeFileSaveRetainsOptimizedDirtyState() = runBlocking {
        val root = Files.createTempDirectory("droide-large-").toFile()
        try {
            val text = "x".repeat(3 * 1024 * 1024); root.resolve("Large.txt").writeText(text)
            val doc = EditorDocument("Large.txt")
            val files = FileRepository(root, onMutation = { doc.captureInsert(0, "new") })
            doc.ensureLoaded(files); assertTrue(doc.largeFileOptimized); doc.capture(text + "saved")
            assertTrue(doc.save(files).isFailure); assertTrue(doc.dirty)
            assertEquals(text + "saved", doc.savedContent)
            assertEquals(text + "saved", root.resolve("Large.txt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun overCeilingFileStaysReadOnly() = runBlocking {
        val root = Files.createTempDirectory("droide-preview-").toFile()
        try {
            val target = root.resolve("Huge.txt")
            target.outputStream().use { out ->
                val block = ByteArray(1024 * 1024) { 'x'.code.toByte() }; repeat(21) { out.write(block) }
            }
            val files = FileRepository(root); val doc = EditorDocument("Huge.txt"); doc.ensureLoaded(files)
            assertEquals(EditorDocumentKind.LARGE, doc.kind); assertFalse(doc.editable)
            assertTrue(doc.save(files).isFailure); assertEquals(21L * 1024 * 1024, target.length())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun directoryLoadFailsClosedAndCanRetry() = runBlocking {
        val root = Files.createTempDirectory("droide-directory-").toFile()
        try {
            val target = root.resolve("Main.kt"); target.mkdir()
            val files = FileRepository(root); val doc = EditorDocument("Main.kt"); doc.ensureLoaded(files)
            assertFalse(doc.loaded); assertFalse(doc.editable); assertNotNull(doc.loadError)
            assertTrue(doc.save(files).isFailure); assertTrue(target.isDirectory)
            target.delete(); target.writeText("repaired"); doc.ensureLoaded(files)
            assertNull(doc.loadError); assertTrue(doc.editable); assertEquals("repaired", doc.content)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun persistedCallbackThenTypingKeepsNewTextDirty() = runBlocking {
        val root = Files.createTempDirectory("droide-save-").toFile()
        val editor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            root.resolve("Main.kt").writeText("A")
            withContext(editor) {
                val state = EditorWorkspaceState(); val doc = state.document("Main.kt")
                val authority = EditorWorkspaceDocumentAuthority(state, editor)
                val files = FileRepository(root, beforeMutation = authority::validatePersistedMutation,
                    onMutation = { mutation -> authority.persistedMutation(mutation); withContext(editor) { doc.capture("new-C") } })
                doc.ensureLoaded(files); doc.capture("save-B")
                assertTrue(doc.save(files).isFailure)
                assertEquals("save-B", root.resolve("Main.kt").readText())
                assertEquals("save-B", doc.savedContent); assertEquals("new-C", doc.content); assertTrue(doc.dirty)
            }
        } finally { editor.close(); PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun typingBeforePersistedCallbackRollsDiskBack() = runBlocking {
        val root = Files.createTempDirectory("droide-rollback-").toFile()
        val editor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            root.resolve("Main.kt").writeText("A")
            withContext(editor) {
                val state = EditorWorkspaceState(); val doc = state.document("Main.kt")
                val authority = EditorWorkspaceDocumentAuthority(state, editor); var changed = false
                val files = FileRepository(root, beforeMutation = authority::validatePersistedMutation,
                    onMutation = { mutation ->
                        if (!changed) { changed = true; withContext(editor) { doc.capture("new-C") } }
                        authority.persistedMutation(mutation)
                    })
                doc.ensureLoaded(files); doc.capture("save-B")
                assertTrue(doc.save(files).exceptionOrNull() is WorkspaceDocumentConflictException)
                assertEquals("A", root.resolve("Main.kt").readText())
                assertEquals("A", doc.savedContent); assertEquals("new-C", doc.content); assertTrue(doc.dirty)
            }
        } finally { editor.close(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
