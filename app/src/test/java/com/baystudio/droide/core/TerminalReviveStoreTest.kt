package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalReviveStoreTest {
    @Test
    fun roundTripPreservesOnlyBoundedLaunchMetadata() {
        val appDir = Files.createTempDirectory("droide-revive-test").toFile()
        val projectRoot = File(appDir, "projects/default").apply { mkdirs() }
        val nested = File(projectRoot, "src").apply { mkdirs() }
        try {
            val store = TerminalReviveStore(appDir)
            store.save(
                projectKey = "default",
                projectRoot = projectRoot,
                tabs = listOf(TerminalReviveDescriptor("t1", "Local", nested)),
                activeId = "t1",
            )

            val restored = store.load("default", projectRoot)
            assertNotNull(restored)
            assertEquals("t1", restored!!.activeId)
            assertEquals(1, restored.tabs.size)
            assertEquals(nested.canonicalFile, restored.tabs.single().workDir.canonicalFile)
        } finally {
            appDir.deleteRecursively()
        }
    }

    @Test
    fun tamperedWorkdirOutsideProjectFailsClosed() {
        val appDir = Files.createTempDirectory("droide-revive-test").toFile()
        val projectRoot = File(appDir, "projects/default").apply { mkdirs() }
        try {
            val stateDir = File(appDir, ".droide/terminal-revive").apply { mkdirs() }
            File(stateDir, "default.json").writeText(
                """{"schema":1,"projectKey":"default","activeId":"t1","tabs":[{"id":"t1","title":"Local","workDir":"../../shared_prefs"}]}"""
            )
            val restored = TerminalReviveStore(appDir).load("default", projectRoot)
            assertNull(restored)
        } finally {
            appDir.deleteRecursively()
        }
    }
}
