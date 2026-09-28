package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.PathSecurity
import com.baystudio.droide.core.WorkspaceDocumentKind
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceDocumentAuthorityAdapterTest {
    private lateinit var root: java.io.File
    private lateinit var files: FileRepository

    @Before fun setUp() {
        root = Files.createTempDirectory("droide-document-authority-").toFile()
        root.resolve("Main.kt").writeText("fun main() = Unit\n")
        files = FileRepository(root)
    }

    @After fun tearDown() {
        PathSecurity.deleteTreeNoFollow(root)
    }

    @Test fun snapshotsReflectLiveUnsavedEditorBufferWithoutSavingDisk() = runBlocking {
        val editorState = EditorWorkspaceState()
        val document = editorState.document("Main.kt")
        document.ensureLoaded(files)
        val authority = EditorWorkspaceDocumentAuthority(editorState, Dispatchers.Unconfined)

        val clean = requireNotNull(authority.snapshot("Main.kt"))
        assertEquals("fun main() = Unit\n", clean.content)
        assertFalse(clean.dirty)
        assertEquals(WorkspaceDocumentKind.TEXT, clean.kind)

        assertTrue(document.captureInsert(0, "// unsaved\n"))
        document.captureSelection(3, 8)
        val dirty = requireNotNull(authority.snapshot("Main.kt"))

        assertEquals("// unsaved\nfun main() = Unit\n", dirty.content)
        assertTrue(dirty.dirty)
        assertTrue(dirty.changeVersion > clean.changeVersion)
        assertEquals(3, dirty.selectionStart)
        assertEquals(8, dirty.selectionEnd)
        assertEquals("fun main() = Unit\n", root.resolve("Main.kt").readText())
    }

    @Test fun dirtySnapshotsOnlyReturnDirtyLiveDocuments() = runBlocking {
        val editorState = EditorWorkspaceState()
        val main = editorState.document("Main.kt")
        main.ensureLoaded(files)
        root.resolve("Other.kt").writeText("val x = 1\n")
        val other = editorState.document("Other.kt")
        other.ensureLoaded(files)
        other.capture("val x = 2\n")

        val authority = EditorWorkspaceDocumentAuthority(editorState, Dispatchers.Unconfined)
        assertEquals(listOf("Other.kt"), authority.dirtySnapshots().map { it.path })
    }
}
