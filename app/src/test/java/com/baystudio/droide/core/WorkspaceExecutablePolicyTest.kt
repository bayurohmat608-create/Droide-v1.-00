package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceExecutablePolicyTest {
    @Test fun projectsPortableExecutableIntentWithoutFilenameGuessing() {
        val root = Files.createTempDirectory("droide-executable-policy").toFile()
        try {
            val shebang = File(root, "configure").apply { writeText("#!/bin/sh\necho ok\n"); setExecutable(false, false) }
            val elf = File(root, "a.out").apply { writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1)); setExecutable(false, false) }
            val plain = File(root, "scripts/configure").apply { parentFile.mkdirs(); writeText("echo plain\n"); setExecutable(false, false) }

            assertEquals(WorkspaceExecutablePolicy.EXECUTABLE_MODE, WorkspaceExecutablePolicy.projectedMode(root, shebang))
            assertEquals(WorkspaceExecutablePolicy.EXECUTABLE_MODE, WorkspaceExecutablePolicy.projectedMode(root, elf))
            assertEquals(WorkspaceExecutablePolicy.REGULAR_MODE, WorkspaceExecutablePolicy.projectedMode(root, plain))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun explicitExecutableBitSurvivesSafStyleStaging() {
        val root = Files.createTempDirectory("droide-executable-preserve").toFile()
        try {
            val source = File(root, "source/tool.txt").apply { parentFile.mkdirs(); writeText("payload"); assertTrue(setExecutable(true, false)) }
            val staged = File(root, "stage/tool.txt").apply { parentFile.mkdirs(); writeText("payload"); setExecutable(false, false) }
            WorkspaceExecutablePolicy.preserveExplicitExecutableBit(root, source, File(root, "stage"), staged)
            assertTrue(staged.canExecute())
        } finally {
            root.deleteRecursively()
        }
    }
}
