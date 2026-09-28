package com.baystudio.droide.ui

import com.baystudio.droide.core.BuildDiagnostic
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.LspDiagnostic
import com.baystudio.droide.core.PathSecurity
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentIdeContextCaptureTest {
    private lateinit var root: java.io.File
    private lateinit var files: FileRepository

    @Before fun setUp() {
        root = Files.createTempDirectory("droide-agent-context-").toFile()
        root.resolve("Main.kt").writeText("fun main() = Unit\n")
        root.resolve("Other.kt").writeText("val other = 1\n")
        files = FileRepository(root)
    }

    @After fun tearDown() {
        PathSecurity.deleteTreeNoFollow(root)
    }

    @Test fun captureUsesLiveUnsavedBufferSelectionAndPrioritizesActiveProblems() = runBlocking {
        val state = EditorWorkspaceState()
        val main = state.document("Main.kt")
        main.ensureLoaded(files)
        main.capture("fun main() { missing() }\n")
        main.captureSelection(13, 20)
        val other = state.document("Other.kt")
        other.ensureLoaded(files)
        other.capture("val other = 2\n")

        val lsp = mapOf(
            root.resolve("Main.kt").toURI().toString() to listOf(LspDiagnostic(1, 14, 1, 21, 1, "Unresolved reference missing", "Kotlin")),
            root.resolve("Other.kt").toURI().toString() to listOf(LspDiagnostic(1, 1, 1, 4, 2, "Other warning", "Kotlin")),
        )
        val build = listOf(BuildDiagnostic("Main.kt", 1, 14, 1, "Build sees missing", "Gradle"))

        val snapshot = captureAgentIdeContext(
            workspaceRoot = root,
            activeFile = "Main.kt",
            activeLanguage = "Kotlin",
            openFiles = listOf("Other.kt", "Main.kt"),
            editorState = state,
            cursorLocation = 1 to 21,
            lspDiagnostics = lsp,
            buildDiagnostics = build,
            capturedAtMs = 42L,
        )

        val active = requireNotNull(snapshot.activeDocument)
        assertEquals("Main.kt", active.path)
        assertEquals("missing", active.selectedText)
        assertTrue(active.dirty)
        assertEquals(1, active.cursorLine)
        assertEquals(21, active.cursorColumn)
        assertEquals(listOf("Main.kt", "Other.kt"), snapshot.dirtyDocuments.map { it.path })
        assertEquals("Main.kt", snapshot.problems.first().path)
        assertEquals(42L, snapshot.capturedAtMs)
        assertEquals("fun main() = Unit\n", root.resolve("Main.kt").readText())
    }
}
