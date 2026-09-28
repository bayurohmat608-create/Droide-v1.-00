package com.baystudio.droide.core

import java.nio.file.Files
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRepositorySearchPageTest {
    private fun dirty(path: String, text: String, kind: WorkspaceDocumentKind = WorkspaceDocumentKind.TEXT,
                      editable: Boolean = true) = WorkspaceDocumentSnapshot(
        path, text, true, kind, true, editable, 1, 1, 0, 0, 0,
    )

    private fun authority(vararg snapshots: WorkspaceDocumentSnapshot) = object : WorkspaceDocumentAuthority {
        override suspend fun snapshot(path: String) = snapshots.firstOrNull { it.path == path }
        override suspend fun snapshots() = snapshots.toList()
    }

    @Test fun `dirty editor text supersedes disk and returns every match location`() = runBlocking {
        val root = Files.createTempDirectory("droide-live-search-").toFile()
        try {
            root.resolve("active.kt").writeText("needle only on disk\n")
            val repo = FileRepository(root)
            val editor = authority(dirty("active.kt", "not on disk\nneedle twice needle\n"))
            val page = repo.searchPage("needle", documents = editor)
            assertEquals(listOf(1, 14), page.hits.map { it.column })
            assertEquals(listOf(2, 2), page.hits.map { it.line })
            assertEquals(0, page.skippedDirtyBuffers)
            assertTrue(repo.searchPage("needle", documents = authority(dirty("active.kt", "no match"))).hits.isEmpty())
            assertEquals(1, repo.searchPage("needle").hits.size)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun `case whole word and include filter apply to persisted and live text`() = runBlocking {
        val root = Files.createTempDirectory("droide-filter-search-").toFile()
        try {
            root.resolve("a.kt").writeText("Foo foo foo_bar foo\n")
            root.resolve("b.py").writeText("foo\n")
            val repo = FileRepository(root)
            val options = TextSearchOptions(caseSensitive = true, wholeWord = true, includeGlob = "**/*.kt")
            assertEquals(listOf(5, 17), repo.searchPage("foo", options = options).hits.map { it.column })
            assertEquals(3, repo.searchPage("foo", options = options.copy(caseSensitive = false)).hits.size)
            val live = authority(dirty("a.kt", "foo_ foo Foo"))
            assertEquals(listOf(6), repo.searchPage("foo", options = options, documents = live).hits.map { it.column })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun `whole word respects supplementary Unicode letters beside a match`() = runBlocking {
        val root = Files.createTempDirectory("droide-unicode-word-").toFile()
        try {
            root.resolve("letter.kt").writeText("𐐀bar bar\n")
            val page = FileRepository(root).searchPage("bar", options = TextSearchOptions(wholeWord = true))
            assertEquals(listOf(7), page.hits.map { it.column })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun `skipped large files and unavailable dirty buffers are disclosed`() = runBlocking {
        val root = Files.createTempDirectory("droide-skipped-search-").toFile()
        try {
            RandomAccessFile(root.resolve("huge.kt"), "rw").use { it.setLength(EditorLargeFilePolicy.MAX_EDITABLE_BYTES + 1) }
            root.resolve("binary.kt").writeText("needle persisted\n")
            val repo = FileRepository(root)
            val page = repo.searchPage("needle", documents = authority(dirty("binary.kt", "needle live", WorkspaceDocumentKind.BINARY, false)))
            assertTrue(page.hits.isEmpty())
            assertEquals(1, page.skippedLargeFiles)
            assertEquals(1, page.skippedDirtyBuffers)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun `text search locates match after column eight thousand and preserves colon path`() = runBlocking {
        val root = Files.createTempDirectory("droide-search-page-").toFile()
        try {
            val repo = FileRepository(root)
            val path = "module:12.kt"
            root.resolve(path).writeText("x".repeat(9_000) + "needle\n")
            val page = repo.searchPage("needle")
            assertEquals(1, page.hits.size)
            assertEquals(path, page.hits.single().path)
            assertEquals(1, page.hits.single().line)
            assertEquals(9_001, page.hits.single().column)
            assertTrue(page.hits.single().excerpt.contains("needle"))
            assertFalse(page.scanLimitReached)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun `text file-pattern and quick open pages reveal their output limits`() = runBlocking {
        val root = Files.createTempDirectory("droide-query-page-").toFile()
        try {
            root.resolve("needle-first.kt").writeText("needle\n")
            root.resolve("needle-second.kt").writeText("needle\n")
            val repo = FileRepository(root)
            assertTrue(repo.searchPage("needle", maxResults = 1).resultLimitReached)
            val pattern = repo.globPage("**/*.kt", max = 1)
            assertEquals(1, pattern.paths.size)
            assertTrue(pattern.resultLimitReached)
            val quick = repo.findFilesPage("needle", maxResults = 1)
            assertEquals(1, quick.paths.size)
            assertTrue(quick.resultLimitReached)
            assertEquals(2, repo.findFiles("needle").size)
            assertTrue(repo.glob("x".repeat(257)).startsWith("(glob error:"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
