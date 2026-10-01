package com.baystudio.droide.core

import com.flyfishxu.kadb.shell.AdbShellPacket
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

 
enum class ProcessExecutionScope { LOCAL_LINUX_ARM64, DEVICE_ADB }

// It deliberately exposes byte streams, not shell strings, so protocol traffic cannot be reinterpreted by a command shell.


interface HostedStdioProcess : Closeable {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    val isAlive: Boolean
    val exitCode: Int?
    suspend fun awaitExit(): Int
}


data class ProcessResourceLimits(
    val cpuSeconds: Int = 120,
    val fileSizeKiB: Int = 64 * 1024,
    val openFiles: Int = 256,
    val processes: Int = 64,
    val virtualMemoryKiB: Int = 768 * 1024,
) {
    fun validate() {
        require(cpuSeconds in 5..3600) { "Invalid CPU limit" }
        require(fileSizeKiB in 1024..1024 * 1024) { "Invalid file-size limit" }
        require(openFiles in 32..4096) { "Invalid open-file limit" }
        require(processes in 8..512) { "Invalid process-count limit" }
        require(virtualMemoryKiB in 128 * 1024..4 * 1024 * 1024) { "Invalid virtual-memory limit" }
    }
}

 
interface StdioProcessHost {
    val executionScope: ProcessExecutionScope
    suspend fun resolveExecutable(command: String): String?
    suspend fun start(
        argv: List<String>,
        environment: Map<String, String> = emptyMap(),
        resourceLimits: ProcessResourceLimits? = null,
    ): HostedStdioProcess
    suspend fun startInWorkspace(argv: List<String>, environment: Map<String, String> = emptyMap(), remoteCwd: String): HostedStdioProcess {
        require(remoteCwd == pathMapper()?.remoteRoot) { "Unsupported process working directory" }
        return start(argv, environment)
    }
    suspend fun pathMapper(): WorkspacePathMapper? = null
}


class DeviceWorkstationProcessHost(
    private val bridge: DeviceBridgeManager,
    private val development: AndroidDevelopmentManager,
    private val localRoot: File,
    private val scope: CoroutineScope,
) : StdioProcessHost {
    override val executionScope: ProcessExecutionScope = ProcessExecutionScope.DEVICE_ADB

    @Volatile private var prepared: AndroidDevelopmentManager.InteractiveShellConfig? = null

    private suspend fun prepare(syncProject: Boolean): AndroidDevelopmentManager.InteractiveShellConfig {
        val next = development.prepareInteractiveShell(syncProject = syncProject)
        prepared = next
        return next
    }

    override suspend fun pathMapper(): WorkspacePathMapper {
        val config = prepared ?: prepare(syncProject = false)
        return WorkspacePathMapper(localRoot, config.remoteWorkspace)
    }

    suspend fun refreshWorkspace(): WorkspacePathMapper {
        val config = prepare(syncProject = true)
        return WorkspacePathMapper(localRoot, config.remoteWorkspace)
    }

    override suspend fun resolveExecutable(command: String): String? {
        ProcessSecurityPolicy.requireExecutableName(command)
        val config = prepare(syncProject = false)
        return DeviceWorkstationExecutableProbe.resolve(bridge, config, command)
    }

    override suspend fun start(
        argv: List<String>,
        environment: Map<String, String>,
        resourceLimits: ProcessResourceLimits?,
    ): HostedStdioProcess {
        val config = prepare(syncProject = true)
        return startRemote(argv, environment, config, config.remoteWorkspace, resourceLimits)
    }

     
    override suspend fun startInWorkspace(
        argv: List<String>,
        environment: Map<String, String>,
        remoteCwd: String,
    ): HostedStdioProcess {
        val config = prepare(syncProject = false)
        val root = config.remoteWorkspace.trimEnd('/')
        require(remoteCwd == root || remoteCwd.startsWith("$root/")) { "Remote process cwd escaped the workspace" }
        DeviceBridgeManager.requireSafeRemotePath(remoteCwd)
        return startRemote(argv, environment, config, remoteCwd, null)
    }

    private suspend fun startRemote(
        argv: List<String>,
        environment: Map<String, String>,
        config: AndroidDevelopmentManager.InteractiveShellConfig,
        remoteCwd: String,
        resourceLimits: ProcessResourceLimits?,
    ): HostedStdioProcess {
        ProcessSecurityPolicy.validateArgv(argv)
        ProcessSecurityPolicy.validateEnvironment(environment)
        resourceLimits?.validate()
        require(ProcessSecurityPolicy.isAllowedRemoteExecutable(argv.first(), DeviceBridgeManager.remoteRoot())) {
            "Remote executable is outside Droide-managed/system roots"
        }
        val command = buildString {
            append(config.shellPrefix())
            if (remoteCwd != config.remoteWorkspace) {
                append("cd ").append(DeviceBridgeManager.shellQuote(remoteCwd)).append("; ")
            }
            environment.forEach { (key, value) ->
                append("export ").append(key).append('=').append(DeviceBridgeManager.shellQuote(value)).append("; ")
            }
            resourceLimits?.let { limits ->
                append("/system/bin/toybox ulimit -P \$\$ -Sc 0 && ")
                append("/system/bin/toybox ulimit -P \$\$ -St ").append(limits.cpuSeconds).append(" && ")
                append("/system/bin/toybox ulimit -P \$\$ -Sf ").append(limits.fileSizeKiB).append(" && ")
                append("/system/bin/toybox ulimit -P \$\$ -Sn ").append(limits.openFiles).append(" && ")
                append("/system/bin/toybox ulimit -P \$\$ -Su ").append(limits.processes).append(" && ")
                append("/system/bin/toybox ulimit -P \$\$ -Sv ").append(limits.virtualMemoryKiB).append(" && ")
            }
            append("exec ")
            append(argv.joinToString(" ") { DeviceBridgeManager.shellQuote(it) })
        }
        val lease = RemoteProcessLease.create("stdio-protocol")
        val stream = bridge.openRawShell(lease.wrap(command))
        return RemoteHostedStdioProcess(stream, scope) { terminate ->
            

            scope.launch(NonCancellable + Dispatchers.IO) {
                if (terminate) {
                    runSuspendCatching { bridge.ensureHealthyConnection() }
                    if (bridge.state.value.connected != null) {
                        runSuspendCatching { bridge.shellBounded(lease.terminateCommand(), maxOutputBytes = 16_384) }
                    }
                } else if (bridge.state.value.connected != null) {
                    // IDE-owned stdio protocols must not leave detached descendants behind after a normal parent exit.


                    runSuspendCatching { bridge.shellBounded(lease.terminateCommand(), maxOutputBytes = 16_384) }
                }
            }
        }
    }
}

private class RemoteHostedStdioProcess(
    private val stream: com.flyfishxu.kadb.shell.AdbShellStream,
    scope: CoroutineScope,
    private val onFinished: (terminateRemote: Boolean) -> Unit,
) : HostedStdioProcess {
    private val alive = AtomicBoolean(true)
    private val sawRemoteExit = AtomicBoolean(false)
    private val cleanupDispatched = AtomicBoolean(false)
    private val exit = kotlinx.coroutines.CompletableDeferred<Int>()
    @Volatile private var observedExitCode: Int? = null
    private val stdoutPipe = PipedInputStream(128 * 1024)
    private val stdoutWriter = PipedOutputStream(stdoutPipe)
    private val stderrPipe = PipedInputStream(64 * 1024)
    private val stderrWriter = PipedOutputStream(stderrPipe)
    private val readJob: Job

    override val stdin: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            check(alive.get()) { "Remote process is closed" }
            require(off >= 0 && len >= 0 && off + len <= b.size) { "Invalid write range" }
            if (len == 0) return
            stream.write(b.copyOfRange(off, off + len))
        }
        override fun flush() = Unit
        override fun close() {
            if (alive.get()) runCatching { stream.closeStdin() }
        }
    }
    override val stdout: InputStream get() = stdoutPipe
    override val stderr: InputStream get() = stderrPipe
    override val isAlive: Boolean get() = alive.get()
    override val exitCode: Int? get() = observedExitCode
    override suspend fun awaitExit(): Int = exit.await()

    init {
        readJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && alive.get()) {
                    when (val packet = stream.read()) {
                        is AdbShellPacket.StdOut -> stdoutWriter.write(packet.payload)
                        is AdbShellPacket.StdError -> stderrWriter.write(packet.payload)
                        is AdbShellPacket.Exit -> {
                            val code = packet.payload.firstOrNull()?.toUByte()?.toInt() ?: 1
                            sawRemoteExit.set(true)
                            observedExitCode = code
                            if (!exit.isCompleted) exit.complete(code)
                            break
                        }
                    }
                }
            } finally {
                alive.set(false)
                dispatchCleanup(terminateRemote = !sawRemoteExit.get())
                if (!exit.isCompleted) {
                    observedExitCode = observedExitCode ?: 1
                    exit.complete(observedExitCode ?: 1)
                }
                runCatching { stdoutWriter.close() }
                runCatching { stderrWriter.close() }
                runCatching { stream.close() }
            }
        }
    }

    override fun close() {
        if (!alive.getAndSet(false)) return
        dispatchCleanup(terminateRemote = true)
        observedExitCode = observedExitCode ?: 143
        if (!exit.isCompleted) exit.complete(observedExitCode ?: 143)
        readJob.cancel()
        runCatching { stream.closeStdin() }
        runCatching { stream.close() }
        runCatching { stdoutWriter.close() }
        runCatching { stderrWriter.close() }
        runCatching { stdoutPipe.close() }
        runCatching { stderrPipe.close() }
    }

    private fun dispatchCleanup(terminateRemote: Boolean) {
        if (cleanupDispatched.compareAndSet(false, true)) onFinished(terminateRemote)
    }
}
