package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuestDocumentFormatterTest {
    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("formatter '").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun formatsUnsavedSnapshotWithoutChangingOriginal() = workspace { root -> runBlocking {
        val original = File(root, "a file.py").apply { writeText("saved") }
        val result = GuestDocumentFormatter.format(root, original.name, "x=1") { argv ->
            assertEquals(listOf("ruff", "format"), argv.take(2))
            val snapshot = File(argv.last())
            assertEquals(root.canonicalFile, snapshot.parentFile.canonicalFile)
            assertEquals("x=1", snapshot.readText())
            assertEquals("saved", original.readText())
            snapshot.writeText("x = 1\n")
            ExecResult(0, "", false)
        }
        assertEquals("x = 1\n", result?.text)
        assertEquals("saved", original.readText())
        assertEquals(listOf(original.name), root.list()!!.toList())
    } }

    @Test fun failureAndCancellationCleanSnapshot() = workspace { root -> runBlocking {
        File(root, "a.py").writeText("saved")
        for (cancel in listOf(false, true)) {
            try {
                GuestDocumentFormatter.format(root, "a.py", "unsaved") {
                    if (cancel) throw CancellationException("stop")
                    ExecResult(127, "missing", false)
                }
                fail("Expected failure")
            } catch (failure: Exception) {
                if (cancel) assertTrue(failure is CancellationException)
                else assertTrue(failure.message.orEmpty().contains("Install ruff"))
            }
            assertEquals(listOf("a.py"), root.list()!!.toList())
            assertEquals("saved", File(root, "a.py").readText())
        }
    } }

    @Test fun invalidUtf8OutputIsRejected() = workspace { root -> runBlocking {
        File(root, "a.go").writeText("saved")
        try {
            GuestDocumentFormatter.format(root, "a.go", "unsaved") { argv ->
                File(argv.last()).writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
                ExecResult(0, "", false)
            }
            fail("Expected invalid UTF-8")
        } catch (_: java.nio.charset.CharacterCodingException) { }
        assertEquals(listOf("a.go"), root.list()!!.toList())
    } }

    @Test fun oversizedInputDoesNotLaunchFormatter() = workspace { root -> runBlocking {
        File(root, "a.py").writeText("saved")
        try {
            GuestDocumentFormatter.format(root, "a.py", "x".repeat(2_000_001)) { error("Must not launch") }
            fail("Expected size limit")
        } catch (_: IllegalArgumentException) { }
        assertEquals(listOf("a.py"), root.list()!!.toList())
    } }

    @Test fun prettierReceivesIndentSettingsAsSeparateArguments() = workspace { root -> runBlocking {
        File(root, "a.ts").writeText("saved")
        val style = CodeStyleProfile(IndentStyle.TABS, 2, 2, 4, "test")
        GuestDocumentFormatter.format(root, "a.ts", "unsaved", style) { argv ->
            assertEquals(listOf("prettier", "--tab-width", "2", "--use-tabs", "--write"), argv.dropLast(1))
            ExecResult(0, "", false)
        }
    } }
}
