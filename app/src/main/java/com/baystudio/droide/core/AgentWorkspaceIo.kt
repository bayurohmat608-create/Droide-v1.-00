package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.*

internal interface AgentWorkspaceIo {
    val checksumCommand: String
    fun requirePath(path: String)
    suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult
    suspend fun refresh()
    suspend fun pull(path: String, output: File)
    suspend fun push(input: File, path: String)
}

internal class BridgeAgentWorkspaceIo(
    private val bridge: DeviceBridgeManager,
    private val resync: suspend () -> Unit = {},
) : AgentWorkspaceIo {
    override val checksumCommand = "toybox sha256sum"
    override fun requirePath(path: String) = DeviceBridgeManager.requireSafeRemotePath(path)
    override suspend fun shellBounded(command: String, maxOutputBytes: Int) = bridge.shellBounded(command, maxOutputBytes)
    override suspend fun refresh() = resync()
    override suspend fun pull(path: String, output: File) { bridge.pull(path, output) }
    override suspend fun push(input: File, path: String) {
        requirePath(path)
        val result = bridge.shell("mkdir -p ${DeviceBridgeManager.shellQuote(path.substringBeforeLast('/'))}")
        check(result.exitCode == 0) { result.combined }
        bridge.push(input, path, mode = if (input.canExecute()) 493 else 420)
    }
}

internal class LocalAgentWorkspaceProcessHost(
    private val source: File,
    cacheDir: File,
    private val scope: CoroutineScope,
) : StdioProcessHost {
    private val identity = java.security.MessageDigest.getInstance("SHA-256")
        .digest(source.canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }
    private val mirror = LocalAgentWorkspaceMirror(source, File(cacheDir, "agent-workspaces/$identity"))
    private val delegate get() = LocalLinuxProcessHost(mirror.directory, scope, toolchainWorkspaceRoot = source)
    override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
    val workspaceIo: AgentWorkspaceIo = object : AgentWorkspaceIo {
        override val checksumCommand = "sha256sum"
        override fun requirePath(path: String) {
            val base = mirror.directory.canonicalFile.toPath()
            val requested = File(path).absoluteFile.toPath().normalize()
            require(requested.startsWith(base) && requested.toFile().canonicalFile.toPath().startsWith(base)) { "Agent path escaped local mirror" }
        }
        override suspend fun refresh() = mirror.refresh()
        override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult {
            val launch = LocalExecutionSubstrate.linuxLaunchSpec(mirror.directory, toolchainWorkspaceRoot = source)
            val result = LocalProcessSupervisor.capture(launch.shellCommand(command), mirror.directory, launch.environment,
                maxOutputBytes = maxOutputBytes, timeoutMs = 60_000)
            return BridgeShellResult(if (result.timedOut) 124 else result.exitCode, result.output, "",
                truncated = result.output.toByteArray(Charsets.UTF_8).size >= maxOutputBytes)
        }
        override suspend fun pull(path: String, output: File) = withContext(Dispatchers.IO) {
            requirePath(path)
            require(File(path).isFile && !Files.isSymbolicLink(File(path).toPath()) && File(path).length() <= 2_000_000L) { "Unsafe or oversized agent output" }
            File(path).inputStream().use { input -> output.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    bytes += count; require(bytes <= 2_000_000L) { "Agent output grew beyond the import limit" }
                    sink.write(buffer, 0, count)
                }
            } }
            Unit
        }
        override suspend fun push(input: File, path: String) = withContext(Dispatchers.IO) {
            requirePath(path)
            val target = File(path); check(target.parentFile.isDirectory || target.parentFile.mkdirs())
            Files.copy(input.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            check(target.setExecutable(input.canExecute(), true))
            Unit
        }
    }
    override suspend fun resolveExecutable(command: String) = LocalLinuxProcessHost(source, scope).resolveExecutable(command)
    override suspend fun pathMapper(): WorkspacePathMapper {
        if (!File(mirror.directory, ".droide-sync-manifest.tsv").isFile) mirror.refresh()
        return WorkspacePathMapper(source, mirror.directory.canonicalPath)
    }
    override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = delegate.start(argv, environment, resourceLimits)
    override suspend fun startInWorkspace(argv: List<String>, environment: Map<String, String>, remoteCwd: String) = delegate.startInWorkspace(argv, environment, remoteCwd)
}
