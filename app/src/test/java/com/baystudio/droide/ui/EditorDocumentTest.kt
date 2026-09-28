package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.PathSecurity
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EditorDocumentTest {
    private lateinit var root: java.io.File
    private lateinit var files: FileRepository

    @Before fun setUp() {
        root = Files.createTempDirectory("droide-editor-").toFile()
        root.resolve("Main.kt").writeText("fun main() = Unit\n")
        files = FileRepository(root)
    }

    @After fun tearDown() {
        PathSecurity.deleteTreeNoFollow(root)
    }

    @Test fun incrementalInsertAndDeleteStayInSync() = runBlocking {
        val document = EditorDocument("Main.kt")
        document.ensureLoaded(files)
        val original = document.content

        assertTrue(document.captureInsert(0, "// hi\n"))
        assertEquals("// hi\n" + original, document.content)
        assertTrue(document.captureDelete(0, "// hi\n"))
        assertEquals(original, document.content)
    }

    @Test fun incrementalDeleteFailsClosedWhenBufferDiverges() = runBlocking {
        val document = EditorDocument("Main.kt")
        document.ensureLoaded(files)
        assertFalse(document.captureDelete(0, "not-present"))
        assertEquals("fun main() = Unit\n", document.content)
    }
}
