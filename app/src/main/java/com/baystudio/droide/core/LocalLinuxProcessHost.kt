package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

 
class LocalLinuxProcessHost(private val root: File, private val scope: CoroutineScope) : StdioProcessHost {
    override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
    override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
    override suspend fun resolveExecutable(command: String): String? {
        ProcessSecurityPolicy.requireExecutableName(command)
        val spec = LocalExecutionSubstrate.linuxLaunchSpec(root)
        val result = LocalProcessSupervisor.capture(
            spec.shellCommand("command -v ${LocalExecutionSubstrate.shellQuote(command)}"), root, spec.environment,
            maxOutputBytes = 8192, timeoutMs = 10_000,
        )
        return result.output.trim().takeIf {
            result.exitCode == 0 && !result.timedOut && it.startsWith('/') && '\n' !in it && '\r' !in it
        }
    }
    override suspend fun startInWorkspace(argv: List<String>, environment: Map<String, String>, remoteCwd: String): HostedStdioProcess {
        val cwd = File(remoteCwd).canonicalFile
        require(cwd.toPath().startsWith(root.canonicalFile.toPath()) && cwd.isDirectory) { "Process cwd escaped workspace" }
        return LocalLinuxProcessHost(cwd, scope).start(argv, environment, null)
    }
    override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess = withContext(Dispatchers.IO) {
        scope.ensureActive()
        ProcessSecurityPolicy.validateArgv(argv); ProcessSecurityPolicy.validateEnvironment(environment); resourceLimits?.validate()
        require(argv.first().startsWith('/') || Regex("[A-Za-z0-9_][A-Za-z0-9._+-]{0,127}").matches(argv.first())) {
            "Executable must be an absolute guest path or a bare command name"
        }
        val spec = LocalExecutionSubstrate.linuxLaunchSpec(root)
        val lease = RemoteProcessLease.createLocal("stdio")
        val payload = if (resourceLimits == null) argv else {
            val l = resourceLimits
            listOf("/bin/bash", "-c", "ulimit -c 0 && ulimit -t ${l.cpuSeconds} && " +
                "ulimit -f ${l.fileSizeKiB} && ulimit -n ${l.openFiles} && " +
                "ulimit -u ${l.processes} && ulimit -v ${l.virtualMemoryKiB} && exec \"\$@\"", "droide") + argv
        }
        val command = spec.prefix + (environment + lease.environment).map { (k, v) -> "$k=$v" } + payload
        val process = ProcessBuilder("/system/bin/sh", "-c", lease.wrap(command.joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)))
            .directory(root).apply { environment().putAll(spec.environment) }.start()
        LocalHostedProcess(process, lease, scope)
    }
}
private class LocalHostedProcess(private val process: Process, private val lease: RemoteProcessLease, private val scope: CoroutineScope) : HostedStdioProcess {
    private val closed = AtomicBoolean(false)
    private val completed = CompletableDeferred<Int>()
    @Volatile private var code: Int? = null
    override val stdin get() = process.outputStream
    override val stdout get() = process.inputStream
    override val stderr get() = process.errorStream
    override val isAlive get() = process.isAlive && !closed.get()
    override val exitCode get() = code
    override suspend fun awaitExit() = completed.await()
    init {
        
        scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                while (isActive && process.isAlive && !closed.get()) delay(25)
                if (!process.isAlive) code = process.exitValue()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { LocalExecutionSubstrate.terminateLease(lease) }
                    finally {
                        if (process.isAlive) LocalProcessSupervisor.terminate(process)
                        code = code ?: 143; completed.complete(code ?: 143)
                    }
                }
            }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.launch(NonCancellable + Dispatchers.IO) {
            try { LocalExecutionSubstrate.terminateLease(lease) }
            finally {
                LocalProcessSupervisor.terminate(process)
                code = code ?: 143; completed.complete(code ?: 143)
            }
        }
    }
}
