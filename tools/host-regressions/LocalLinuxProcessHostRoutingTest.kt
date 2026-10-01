package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalLinuxProcessHostRoutingTest {
    @Test fun agentMirrorUsesSourceToolchainSelections() = runBlocking {
        val base = Files.createTempDirectory("droide-mirror-routing-").toFile()
        val source = base.resolve("source").apply { mkdirs() }
        val mirror = base.resolve("mirror").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = LocalLinuxProcessHost(mirror, scope, toolchainWorkspaceRoot = source)
            val failure = runCatching { host.start(listOf("node")) }.exceptionOrNull()
            assertTrue(failure is LaunchBoundaryReached)
            assertEquals(mirror.canonicalFile, LocalExecutionSubstrate.observedRoot)
            assertEquals(source.canonicalFile, LocalExecutionSubstrate.observedToolchainRoot)
        } finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(base) }
    }
    @Test fun subdirectoryLaunchKeepsWorkspaceRootForToolchainsAndCli() = runBlocking {
        val root = Files.createTempDirectory("droide-routing-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cwd = root.resolve("module").apply { mkdirs() }
            val host = LocalLinuxProcessHost(root, scope)
            val failure = runCatching { host.startInWorkspace(listOf("node"), emptyMap(), cwd.path) }.exceptionOrNull()
            assertTrue(failure is LaunchBoundaryReached)
            assertEquals(root.canonicalFile, LocalExecutionSubstrate.observedRoot)
            assertEquals(cwd.canonicalFile, LocalExecutionSubstrate.observedCwd)
            assertEquals(root.canonicalPath, host.pathMapper().remoteRoot)
        } finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun subdirectoryExecutableProbeUsesSameWorkspaceIdentity() = runBlocking {
        val root = Files.createTempDirectory("droide-probe-routing-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cwd = root.resolve("module").apply { mkdirs() }
            val failure = runCatching { LocalLinuxProcessHost(root, scope, cwd).resolveExecutable("java") }.exceptionOrNull()
            assertTrue(failure is LaunchBoundaryReached)
            assertEquals(root.canonicalFile, LocalExecutionSubstrate.observedRoot)
            assertEquals(cwd.canonicalFile, LocalExecutionSubstrate.observedCwd)
        } finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun outsideWorkingDirectoryIsRejectedBeforeLaunch() = runBlocking {
        val root = Files.createTempDirectory("droide-root-").toFile()
        val outside = Files.createTempDirectory("droide-outside-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val failure = runCatching { LocalLinuxProcessHost(root, scope).startInWorkspace(listOf("node"), emptyMap(), outside.path) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        } finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(root); PathSecurity.deleteTreeNoFollow(outside) }
    }
}
