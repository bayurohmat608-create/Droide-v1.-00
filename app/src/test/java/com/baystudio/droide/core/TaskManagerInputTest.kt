package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskManagerInputTest {
    @Test fun malformedTaskIsRejectedWholeWhileEmptyArgumentsArePreserved() = runBlocking {
        val root = Files.createTempDirectory("droide-task-input-").toFile()
        try {
            root.resolve(".droide").mkdirs()
            root.resolve(".droide/tasks.json").writeText("""{"tasks":[
              {"label":"valid","command":["python3",""],"executionScope":"local-linux-arm64"},
              {"label":"bad-object","command":["python3",{}]},
              {"label":"bad-number","command":["python3",12]},
              {"label":"bad-cwd","command":["python3"],"cwd":[]},
              {"label":"bad-scope","command":["python3"],"executionScope":false},
              {"label":"local-app","command":["python3"],"executionScope":"local"}
            ]}""")
            val tasks = TaskManager(root).list()
            assertEquals(listOf("valid"), tasks.map { it.label })
            assertEquals(listOf("python3", ""), tasks.single().argv)
            assertEquals(TaskExecutionScope.LOCAL_LINUX_ARM64, tasks.single().executionScope)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun projectMetadataSymlinkOutsideWorkspaceIsNotAutodetected() = runBlocking {
        val root = Files.createTempDirectory("droide-task-root-").toFile()
        val other = Files.createTempDirectory("droide-task-other-").toFile()
        try {
            val metadata = other.resolve("package.json").apply { writeText("""{"scripts":{"test":"private"}}""") }
            Files.createSymbolicLink(root.resolve("package.json").toPath(), metadata.toPath())
            assertTrue(TaskManager(root).list().isEmpty())
        } finally { PathSecurity.deleteTreeNoFollow(root); PathSecurity.deleteTreeNoFollow(other) }
    }
}
