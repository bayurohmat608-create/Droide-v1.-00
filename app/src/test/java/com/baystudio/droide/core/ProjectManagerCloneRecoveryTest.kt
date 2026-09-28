package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectManagerCloneRecoveryTest {
    @Test fun initRemovesUnregisteredManagedCloneWorkspaceButKeepsRegisteredProject() = runBlocking {
        val appDir = Files.createTempDirectory("droide-project-clone-recovery-").toFile()
        try {
            val projectsRoot = appDir.resolve("projects").apply { mkdirs() }
            val orphan = projectsRoot.resolve("p-orphan").apply { mkdirs(); resolve("partial.txt").writeText("partial") }
            val kept = projectsRoot.resolve("p-kept").apply { mkdirs(); resolve("README.md").writeText("ok") }
            val store = MemoryProjectStore(
                listOf(DroideProject("p-kept", "Kept", kept.absolutePath)),
            )
            val manager = ProjectManager(appDir, store)

            manager.init()

            assertFalse(orphan.exists())
            assertTrue(kept.isDirectory)
            assertTrue(manager.projects.value.any { it.id == "p-kept" })
        } finally {
            PathSecurity.deleteTreeNoFollow(appDir)
        }
    }

    private class MemoryProjectStore(initial: List<DroideProject>) : ProjectStore {
        private var projects = initial
        private var active: String? = null
        override suspend fun load(): List<DroideProject> = projects
        override suspend fun save(all: List<DroideProject>) { projects = all }
        override suspend fun loadActiveId(): String? = active
        override suspend fun saveActiveId(id: String) { active = id }
    }
}
