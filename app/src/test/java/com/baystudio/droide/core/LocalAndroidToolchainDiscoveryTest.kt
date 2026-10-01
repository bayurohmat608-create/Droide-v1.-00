package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalAndroidToolchainDiscoveryTest {
    private val requirements = AndroidToolchainRequirements(compileSdk = 36, buildToolsVersion = "36.0.0")

    @Test fun cancellationEscapesResolution() = runBlocking {
        val cancelled = CancellationException("User stopped discovery")
        val discovery = LocalAndroidToolchainDiscovery(File(".")) { _, _ -> throw cancelled }
        try {
            discovery.resolve(requirements)
            error("Cancellation was converted to a toolchain failure")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test fun ordinaryProbeFailureKeepsDiagnostic() = runBlocking {
        val discovery = LocalAndroidToolchainDiscovery(File(".")) { _, _ -> error("Guest launcher failed") }
        val result = discovery.resolve(requirements)
        assertNull(result.snapshot)
        assertEquals("Guest launcher failed", result.failure)
    }

    @Test fun stoppingActiveProbeDoesNotReturnAnIncompleteToolchain() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var returned = false
        val job = launch {
            val discovery = LocalAndroidToolchainDiscovery(File(".")) { _, _ ->
                started.complete(Unit)
                awaitCancellation()
            }
            discovery.resolve(requirements)
            returned = true
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(returned)
    }

    @Test fun completeJdkIsAcceptedWithPathsContainingSpaces() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        val result = resolveGuest(root, javaHome)
        assertNull(result.failure)
        assertEquals(javaHome.canonicalPath, result.snapshot?.javaHome)
        assertEquals(17, result.snapshot?.javaVersion)
    }

    @Test fun runtimeWithoutCompilerIsRejected() = withGuest { root, javaHome, _ ->
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("compiler is missing"))
    }

    @Test fun compilerFromAnotherPathCannotRescueIncompleteJdk() = withGuest { root, javaHome, _ ->
        val otherJdk = File(root, "other-jdk")
        compiler(otherJdk, "17.0.20")
        val result = resolveGuest(root, javaHome, extraPath = File(otherJdk, "bin").path)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("compiler is missing"))
    }

    @Test fun compilerVersionMismatchIsRejected() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "21.0.1")
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("versions do not match"))
    }

    @Test fun compilerThatCannotExecuteIsRejected() = withGuest { root, javaHome, _ ->
        executable(File(javaHome, "bin/javac"), "exit 126")
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("compiler cannot execute"))
    }

    @Test fun java8JreLayoutResolvesToEnclosingJdk() = withGuest(javaVersion = "1.8") { root, javaHome, javaBin ->
        val jreJava = File(javaHome, "jre/bin/java").apply { parentFile.mkdirs() }
        check(javaBin.renameTo(jreJava))
        Files.createSymbolicLink(javaBin.toPath(), jreJava.toPath())
        compiler(javaHome, "1.8.0_472")
        val result = resolveGuest(root, javaHome)
        assertNull(result.failure)
        assertEquals(javaHome.canonicalPath, result.snapshot?.javaHome)
        assertEquals(8, result.snapshot?.javaVersion)
    }

    @Test fun explicitJavaHomeOverridesJavaFoundOnPath() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        val selected = File(root, "selected-jdk")
        executable(File(selected, "bin/java"), "printf '    java.specification.version = 21\\n'")
        compiler(selected, "21.0.1")
        val result = resolveGuest(root, javaHome, selectedHome = selected.path)
        assertNull(result.failure)
        assertEquals(selected.canonicalPath, result.snapshot?.javaHome)
        assertEquals(21, result.snapshot?.javaVersion)
    }

    @Test fun invalidSelectedHomeDoesNotFallBackToPath() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        val result = resolveGuest(root, javaHome, selectedHome = File(root, "missing").path)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("Selected JAVA_HOME"))
    }

    @Test fun selectedRuntimeWithoutCompilerDoesNotUseOtherJdkCompiler() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        val selected = File(root, "selected-jre")
        executable(File(selected, "bin/java"), "printf '    java.specification.version = 17\\n'")
        val result = resolveGuest(root, javaHome, selectedHome = selected.path)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("compiler is missing"))
    }

    private fun resolveGuest(root: File, javaHome: File, extraPath: String = "", selectedHome: String? = null) = runBlocking {
        LocalAndroidToolchainDiscovery(root) { script, _ ->
            val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).apply {
                environment().remove("JAVA_HOME")
                if (selectedHome != null) environment()["JAVA_HOME"] = selectedHome
                environment()["PATH"] = listOf(File(javaHome, "bin").path, extraPath, "/usr/bin", "/bin")
                    .filter(String::isNotEmpty).joinToString(":")
                environment()["ANDROID_HOME"] = File(root, "sdk").path
                environment()["ANDROID_SDK_ROOT"] = File(root, "sdk").path
            }.start()
            try {
                val completed = process.waitFor(5, TimeUnit.SECONDS)
                if (!completed) process.destroyForcibly()
                ExecResult(if (completed) process.exitValue() else -1, process.inputStream.bufferedReader().readText(), !completed)
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }.resolve(requirements)
    }

    private fun withGuest(javaVersion: String = "17", block: (File, File, File) -> Unit) {
        assumeTrue(File("/bin/sh").isFile)
        val root = Files.createTempDirectory("droide guest ").toFile()
        try {
            val javaHome = File(root, "jdk home")
            val javaBin = File(javaHome, "bin/java")
            executable(javaBin, "printf '    java.specification.version = $javaVersion\\n'")
            File(root, "sdk/platforms/android-36").mkdirs()
            val tools = File(root, "sdk/build-tools/36.0.0")
            listOf("aapt2", "zipalign", "apksigner").forEach { executable(File(tools, it), "exit 0") }
            block(root, javaHome, javaBin)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun compiler(javaHome: File, version: String) = executable(File(javaHome, "bin/javac"), "printf 'javac $version\\n'")

    private fun executable(file: File, body: String) {
        file.parentFile.mkdirs()
        file.writeText("#!/bin/sh\n$body\n")
        check(file.setExecutable(true))
    }
}
