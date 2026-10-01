package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalUbuntuJdkPolicyTest {
    private val commands = mapOf("java" to "jdk/bin/java", "javac" to "jdk/bin/javac", "jar" to "jdk/bin/jar")

    @Test fun completeJdkUsesItsRealTreeHome() {
        assertEquals(mapOf("JAVA_HOME" to "/owned/tree/jdk"),
            LocalUbuntuJdkPolicy.environment("toolchain.jdk", "/owned/tree", commands))
    }

    @Test fun otherFamiliesDoNotAcquireJavaHome() {
        assertEquals(emptyMap<String, String>(), LocalUbuntuJdkPolicy.environment("runtime.node", "/owned/tree", emptyMap()))
    }

    @Test fun runtimeWithoutCompilerIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalUbuntuJdkPolicy.environment("toolchain.jdk", "/owned/tree", commands - "javac")
        }
    }

    @Test fun jarFromAnotherJdkIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalUbuntuJdkPolicy.environment("toolchain.jdk", "/owned/tree", commands + ("jar" to "other/bin/jar"))
        }
    }

    @Test fun homeCannotEscapeReviewedTree() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalUbuntuJdkPolicy.environment("toolchain.jdk", "/owned/tree", commands.mapValues { "../" + it.value })
        }
    }

    @Test fun missingModuleImageFailsEvenWithExecutableJava() = withFakeJdk { home ->
        File(home, "lib/modules").delete()
        assertNotEquals(0, runHealth(home, false))
    }

    @Test fun compilerVersionMismatchFails() = withFakeJdk { home ->
        executable(File(home, "bin/javac"), "printf 'javac 21.0.1\\n'")
        assertNotEquals(0, runHealth(home, false))
    }

    @Test fun stagedAdmissionRejectsCompilerThatOnlySupportsVersion() = withFakeJdk { home ->
        executable(File(home, "bin/javac"), "if [ \"\$1\" = -version ]; then printf 'javac 17.0.1\\n'; else exit 7; fi")
        assertEquals(0, runHealth(home, false))
        assertNotEquals(0, runHealth(home, true))
    }

    @Test fun realJdkCompilesAndRunsAndRemovesTemporarySample() {
        val home = File(requireNotNull(System.getProperty("java.home")))
        assumeTrue(File("/bin/sh").isFile && File(home, "bin/javac").canExecute())
        val temporary = Files.createTempDirectory("droide-jdk-probe").toFile()
        try {
            assertEquals(0, runHealth(home, true, temporary))
            assertTrue(temporary.listFiles().orEmpty().isEmpty())
        } finally { temporary.deleteRecursively() }
    }

    private fun runHealth(home: File, compile: Boolean, temporary: File? = null): Int {
        val script = LocalUbuntuJdkPolicy.healthScript(home.path, compile)
        assertFalse(script.contains('\n'))
        assertTrue(script.length < 4_000)
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).apply {
            if (temporary != null) environment()["TMPDIR"] = temporary.path
        }.start()
        return try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            if (process.exitValue() != 0) println(output)
            process.exitValue()
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun withFakeJdk(block: (File) -> Unit) {
        assumeTrue(File("/bin/sh").isFile)
        val home = Files.createTempDirectory("droide jdk '").toFile()
        try {
            File(home, "release").writeText("JAVA_VERSION=17\n")
            File(home, "lib/modules").apply { requireNotNull(parentFile).mkdirs(); writeText("modules") }
            executable(File(home, "bin/java"), "printf '    java.specification.version = 17\\n'")
            executable(File(home, "bin/javac"), "printf 'javac 17.0.1\\n'")
            executable(File(home, "bin/jar"), "exit 0")
            block(home)
        } finally { home.deleteRecursively() }
    }

    private fun executable(file: File, body: String) {
        requireNotNull(file.parentFile).mkdirs(); file.writeText("#!/bin/sh\n$body\n"); check(file.setExecutable(true))
    }
}
