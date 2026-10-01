package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DeferredEditorRecoveryTest {
    @Test fun manyRecoveredTabsDoNotReadAnyBodyUntilOpened() = runBlocking {
        val root = Files.createTempDirectory("droide-lazy-recovery-").toFile()
        try {
            var reads = 0
            val refs = (1..40).associate { index ->
                val path = "file$index.txt"
                path to EditorRecoveryBuffer(path, "fake.bin", null, Any()) { reads++; "unsaved" }
            }
            val state = EditorWorkspaceState()
            val snapshot = EditorRecoverySnapshot(refs.keys.toList(), "file1.txt", emptyMap(), deferredBuffers = refs)
            val tabs = EditorTabRetention.restoreDocuments(snapshot, FileRepository(root), state)
            assertEquals(40, tabs.size)
            assertEquals(40, state.dirtyDocuments().size)
            assertEquals(0, reads)
            assertTrue(state.allDocuments().none { it.loaded })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun openingOneBufferRestoresItsBaselineSelectionAndReviewMode() = runBlocking {
        val root = Files.createTempDirectory("droide-recovery-open-").toFile()
        try {
            root.resolve("one.txt").writeText("original")
            var reads = 0
            val ref = EditorRecoveryBuffer("one.txt", "fake.bin", null, Any()) { reads++; "recovered changes" }
            val doc = EditorDocument("one.txt")
            doc.restoreDeferred(ref, EditorSelectionSnapshot(3, 8), review = true)
            doc.ensureLoaded(FileRepository(root))
            assertEquals(1, reads)
            assertEquals("recovered changes", doc.content)
            assertEquals("original", doc.savedContent)
            assertEquals(3, doc.selectionStart); assertEquals(8, doc.selectionEnd)
            assertTrue(doc.reviewMode); assertTrue(doc.dirty); assertNull(doc.pendingRecoveryBuffer)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun unreadableRecoveryStaysDirtyAndBlocksPersistedOverwrite() = runBlocking {
        val root = Files.createTempDirectory("droide-recovery-failed-").toFile()
        try {
            val files = FileRepository(root)
            val state = EditorWorkspaceState()
            val doc = state.document("one.txt")
            doc.restoreDeferred(EditorRecoveryBuffer("one.txt", "fake.bin", null, Any()) { error("blob unavailable") })
            doc.ensureLoaded(files)
            assertTrue(doc.dirty); assertNotNull(doc.pendingRecoveryBuffer)
            val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined, files)
            assertTrue(runCatching { authority.validatePersistedMutation(FileRepository.Mutation.Write("one.txt", "disk")) }.exceptionOrNull() is WorkspaceDocumentConflictException)
            assertTrue(doc.save(files).isFailure)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun discardingOneDeferredBufferPreservesTheOther() {
        val state = EditorWorkspaceState()
        for (path in listOf("one.txt", "two.txt")) state.document(path).restoreDeferred(EditorRecoveryBuffer(path, "fake.bin", null, Any()) { "unsaved" })
        state.document("one.txt").discardUnsaved()
        assertEquals(listOf("two.txt"), state.dirtyDocuments().map { it.path })
        assertEquals(setOf("two.txt"), state.deferredRecoveryBuffers().keys)
    }
}
