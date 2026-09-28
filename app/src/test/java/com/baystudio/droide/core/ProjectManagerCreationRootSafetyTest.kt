package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectManagerCreationRootSafetyTest {
    @Test fun blankTemplateNameStillCreatesDedicatedWorkspace() = runBlocking {
        val appDir = Files.createTempDirectory("droide-project-create-blank-").toFile()
        try {
            val manager = ProjectManager(appDir, MemoryProjectStore(emptyList()))
            manager.init()

            val (project, _) = manager.createFromTemplate("   ", "empty")
            val projectsRoot = appDir.resolve("projects").canonicalFile
            val root = project.rootPath.let(::java.io.File).canonicalFile

            assertEquals("Empty Project", project.name)
            assertFalse(root == projectsRoot)
            assertEquals(projectsRoot, root.parentFile?.canonicalFile)
            assertTrue(root.name.startsWith("p-"))
        } finally {
            PathSecurity.deleteTreeNoFollow(appDir)
        }
    }

    @Test fun projectsContainerAndNestedRootsAreRejected() = runBlocking {
        val appDir = Files.createTempDirectory("droide-project-create-root-").toFile()
        try {
            val manager = ProjectManager(appDir, MemoryProjectStore(emptyList()))
            manager.init()
            val projectsRoot = appDir.resolve("projects").canonicalFile
            val nested = projectsRoot.resolve("p-parent/nested")

            assertTrue(runCatching { manager.add("Bad root", projectsRoot.absolutePath) }.isFailure)
            assertTrue(runCatching { manager.add("Nested root", nested.absolutePath) }.isFailure)
        } finally {
            PathSecurity.deleteTreeNoFollow(appDir)
        }
    }

    @Test fun corruptStoredContainerRootIsRepairedToIdDerivedChild() = runBlocking {
        val appDir = Files.createTempDirectory("droide-project-create-repair-").toFile()
        try {
            val projectsRoot = appDir.resolve("projects").apply { mkdirs() }.canonicalFile
            projectsRoot.resolve("sibling-project.txt").writeText("must stay outside repaired project")
            val store = MemoryProjectStore(
                listOf(DroideProject("p-corrupt", "Corrupt", projectsRoot.absolutePath)),
            )
            val manager = ProjectManager(appDir, store)

            manager.init()

            val repaired = manager.projects.value.first { it.id == "p-corrupt" }
            val repairedRoot = java.io.File(repaired.rootPath).canonicalFile
            assertEquals(projectsRoot.resolve("p-corrupt").canonicalFile, repairedRoot)
            assertEquals(projectsRoot, repairedRoot.parentFile?.canonicalFile)
            assertTrue(projectsRoot.resolve("sibling-project.txt").isFile)
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
