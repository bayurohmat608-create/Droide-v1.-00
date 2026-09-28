package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectManagerRecentProjectsTest {
    @Test fun removeFromRecentKeepsRegistryAndWorkspace() = runBlocking {
        val appDir = Files.createTempDirectory("droide-recents-").toFile()
        try {
            val root = appDir.resolve("projects/p-alpha").apply { mkdirs(); resolve("keep.txt").writeText("keep") }
            val store = MemoryProjectStore(listOf(DroideProject("p-alpha", "Alpha", root.absolutePath)))
            val recents = MemoryRecentsStore(null)
            val manager = ProjectManager(appDir, store, recents)
            manager.init()

            manager.removeFromRecent("p-alpha")

            assertTrue(root.resolve("keep.txt").isFile)
            assertTrue(manager.projects.value.any { it.id == "p-alpha" })
            assertFalse(manager.recentProjects.value.any { it.id == "p-alpha" })
            assertTrue(manager.otherProjects.value.any { it.id == "p-alpha" })
        } finally {
            PathSecurity.deleteTreeNoFollow(appDir)
        }
    }

    @Test fun clearRecentKeepsActiveProjectAndOpeningOtherRestoresIt() = runBlocking {
        val appDir = Files.createTempDirectory("droide-recents-active-").toFile()
        try {
            val a = appDir.resolve("projects/p-a").apply { mkdirs() }
            val b = appDir.resolve("projects/p-b").apply { mkdirs() }
            val store = MemoryProjectStore(
                listOf(DroideProject("p-a", "A", a.absolutePath), DroideProject("p-b", "B", b.absolutePath)),
            )
            val recents = MemoryRecentsStore(listOf("p-a", "p-b"))
            val manager = ProjectManager(appDir, store, recents)
            manager.init()
            manager.switch("p-a")
            manager.clearRecent()

            assertTrue(manager.recentProjects.value.any { it.id == "p-a" })
            assertFalse(manager.recentProjects.value.any { it.id == "p-b" })
            assertTrue(manager.otherProjects.value.any { it.id == "p-b" })

            manager.switch("p-b")
            assertTrue(manager.recentProjects.value.any { it.id == "p-b" })
            assertFalse(manager.otherProjects.value.any { it.id == "p-b" })
        } finally {
            PathSecurity.deleteTreeNoFollow(appDir)
        }
    }

    @Test fun deleteWorkspaceNeverTouchesExternalSafTree() = runBlocking {
        val appDir = Files.createTempDirectory("droide-delete-saf-").toFile()
        val external = Files.createTempDirectory("droide-external-saf-").toFile()
        try {
            external.resolve("user-source.txt").writeText("external")
            val mirror = appDir.resolve("projects/p-saf").apply { mkdirs(); resolve("mirror.txt").writeText("mirror") }
            val project = DroideProject("p-saf", "SAF", mirror.absolutePath, treeUri = external.toURI().toString())
            val store = MemoryProjectStore(listOf(project))
            val recents = MemoryRecentsStore(listOf("p-saf"))
            val manager = ProjectManager(appDir, store, recents)
            manager.init()

            manager.deleteWorkspace("p-saf")

            assertFalse(mirror.exists())
            assertTrue(external.resolve("user-source.txt").isFile)
            assertFalse(manager.projects.value.any { it.id == "p-saf" })
        } finally {
            PathSecurity.deleteTreeNoFollow(appDir)
            PathSecurity.deleteTreeNoFollow(external)
        }
    }

    @Test fun deleteStagingIsRestoredWhenRegistryStillContainsProject() = runBlocking {
        val appDir = Files.createTempDirectory("droide-delete-recover-").toFile()
        try {
            val projects = appDir.resolve("projects").apply { mkdirs() }
            val staging = projects.resolve(".delete-p-recover").apply { mkdirs(); resolve("source.txt").writeText("recover") }
            val expectedRoot = projects.resolve("p-recover")
            val store = MemoryProjectStore(listOf(DroideProject("p-recover", "Recover", expectedRoot.absolutePath)))
            val manager = ProjectManager(appDir, store, MemoryRecentsStore(listOf("p-recover")))

            manager.init()

            assertFalse(staging.exists())
            assertTrue(expectedRoot.resolve("source.txt").isFile)
        } finally {
            PathSecurity.deleteTreeNoFollow(appDir)
        }
    }

    @Test fun recentsPersistenceFailureDoesNotAbortActiveProjectSwitch() = runBlocking {
        val appDir = Files.createTempDirectory("droide-recents-switch-failure-").toFile()
        try {
            val root = appDir.resolve("projects/p-switch").apply { mkdirs() }
            val store = MemoryProjectStore(listOf(DroideProject("p-switch", "Switch", root.absolutePath)))
            val recents = FailingRecentsStore()
            val manager = ProjectManager(appDir, store, recents)
            manager.init()
            recents.failWrites = true

            manager.switch("p-switch")

            assertTrue(manager.activeId.value == "p-switch")
            assertTrue(store.activeId() == "p-switch")
        } finally {
            PathSecurity.deleteTreeNoFollow(appDir)
        }
    }

    private class MemoryProjectStore(initial: List<DroideProject>) : ProjectStore {
        private var projects = initial
        private var active: String? = "default"
        override suspend fun load(): List<DroideProject> = projects
        override suspend fun save(all: List<DroideProject>) { projects = all }
        override suspend fun loadActiveId(): String? = active
        override suspend fun saveActiveId(id: String) { active = id }
        fun activeId(): String? = active
    }

    private class MemoryRecentsStore(initial: List<String>?) : ProjectRecentsStore {
        private var ids = initial
        override suspend fun load(): List<String>? = ids
        override suspend fun save(ids: List<String>) { this.ids = ids }
    }

    private class FailingRecentsStore : ProjectRecentsStore {
        var failWrites = false
        override suspend fun load(): List<String>? = emptyList()
        override suspend fun save(ids: List<String>) {
            if (failWrites) error("simulated recents persistence failure")
        }
    }
}
