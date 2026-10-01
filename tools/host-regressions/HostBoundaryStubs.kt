package com.baystudio.droide.core

import java.io.File

// These replace only Android launch boundaries in the host routing test. No guest is started.
internal class LaunchBoundaryReached : IllegalStateException("Host launch boundary reached")
internal object LocalExecutionSubstrate {
    var observedRoot: File? = null
    var observedCwd: File? = null
    var observedToolchainRoot: File? = null
    var launchFixture: TerminalLaunchSpec? = null
    fun linuxLaunchSpec(workDir: File, alpine: Boolean = false, workspaceRoot: File = workDir, toolchainWorkspaceRoot: File = workspaceRoot): TerminalLaunchSpec {
        observedRoot = workspaceRoot.canonicalFile
        observedCwd = workDir.canonicalFile
        observedToolchainRoot = toolchainWorkspaceRoot.canonicalFile
        launchFixture?.let { return it }
        throw LaunchBoundaryReached()
    }
    fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    fun terminateLease(lease: RemoteProcessLease) = Unit
}
internal object LocalProcessSupervisor {
    var handler: ((List<String>, File) -> ExecResult)? = null
    suspend fun capture(argv: List<String>, cwd: File, environment: Map<String, String>, maxOutputBytes: Int, timeoutMs: Long, keepTail: Boolean = false): ExecResult =
        handler?.invoke(argv, cwd) ?: error("Native launch is outside host test scope")
    fun terminate(process: Process) = Unit
}
internal class RemoteProcessLease {
    val environment = emptyMap<String, String>()
    fun wrap(command: String) = command
    companion object { fun createLocal(label: String) = RemoteProcessLease() }
}
