package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRepositoryDirectoryPageTest {
    @Test fun directoryWithMoreThanFiveThousandFilesRemainsAccessibleAcrossPages() = runBlocking {
        val root = Files.createTempDirectory("droide-directory-page-").toFile()
        try {
            val folder = root.resolve("huge").apply { mkdirs() }
            repeat(5_001) { i -> folder.resolve("entry-%04d.txt".format(i)).writeText("x") }
            val repo = FileRepository(root)
            val seen = mutableSetOf<String>()
            var cursor: DirectoryPageCursor? = null
            do {
                val page = repo.listFilesPage("huge", cursor, pageSize = 1_000)
                assertTrue(page.entries.size <= 1_000)
                assertTrue(page.entries.all { seen.add(it.path) })
                cursor = page.nextCursor
            } while (cursor != null)
            assertEquals(5_001, seen.size)
            assertTrue("huge/entry-5000.txt" in seen)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun cursorIsFolderBoundAndDirectoriesSortBeforeFiles() = runBlocking {
        val root = Files.createTempDirectory("droide-directory-order-").toFile()
        try {
            root.resolve("z.txt").writeText("x")
            root.resolve("B").mkdir()
            root.resolve("a").mkdir()
            val repo = FileRepository(root)
            val first = repo.listFilesPage(pageSize = 1)
            assertEquals("a", first.entries.single().path)
            assertNotNull(first.nextCursor)
            val second = repo.listFilesPage(after = first.nextCursor, pageSize = 1)
            assertEquals("B", second.entries.single().path)
            assertEquals("z.txt", repo.listFilesPage(after = second.nextCursor, pageSize = 1).entries.single().path)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.listFilesPage("a", first.nextCursor) }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
