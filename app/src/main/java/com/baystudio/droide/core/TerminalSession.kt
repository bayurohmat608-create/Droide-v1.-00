package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.util.ArrayDeque

 
interface ITerminalSession {
    val output: StateFlow<String>
    fun start()
    fun send(cmd: String)
    suspend fun execOnce(cmd: String, timeoutMs: Long = 30_000): ExecResult
    suspend fun execStreaming(cmd: String, timeoutMs: Long = 30_000, onOutput: (String) -> Unit): ExecResult =
        execOnce(cmd, timeoutMs)
    suspend fun execArgv(argv: List<String>, timeoutMs: Long = 30_000): ExecResult
    fun interrupt()
    fun clear()
    fun destroy()
}

 
interface TerminalMetadataSession {
    val terminalTitle: StateFlow<String?>
}

data class ExecResult(val exitCode: Int, val output: String, val timedOut: Boolean)






class TerminalSession(
    private val workDir: File,
    private val scope: CoroutineScope,
    private val launchSpec: TerminalLaunchSpec = TerminalLaunchSpec(),
) : ITerminalSession {
    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private val pendingCommandLock = Any()
    private val pendingCommands = ArrayDeque<String>()
    private var pendingCommandChars = 0
    private val _output = MutableStateFlow("")
    override val output: StateFlow<String> = _output.asStateFlow()
    private val outputCap = 200_000

    override fun start() {
        if (process != null) return
        scope.launch(Dispatchers.IO) {
            try {
                workDir.mkdirs()
                process = ProcessBuilder(launchSpec.interactiveArgv)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .apply { environment().putAll(launchSpec.environment) }
                    .start()
                synchronized(pendingCommandLock) {
                    stdin = process!!.outputStream.bufferedWriter()
                    flushPendingCommandsLocked()
                }
                appendLine("cd ${workDir.absolutePath}")
                java.io.InputStreamReader(process!!.inputStream, Charsets.UTF_8).use { reader ->
                    val buf = CharArray(4_096)
                    while (process != null) {
                        val n = reader.read(buf)
                        if (n <= 0) break
                        emit(Ansi.strip(String(buf, 0, n)))
                    }
                }
            } catch (e: Exception) {
                appendLine("[terminal error] ${e.message}")
            }
        }
    }

    override fun send(cmd: String) {
        require(cmd.length <= 64_000 && '\u0000' !in cmd) { "Terminal command is too large or invalid" }
        scope.launch(Dispatchers.IO) {
            try {
                appendLine("$ $cmd")
                synchronized(pendingCommandLock) {
                    val writer = stdin
                    if (writer != null) {
                        writer.write(cmd + "\n")
                        writer.flush()
                    } else {
                        check(pendingCommands.size < 32 && pendingCommandChars + cmd.length <= 256_000) {
                            "Terminal startup command queue is full"
                        }
                        pendingCommands.addLast(cmd)
                        pendingCommandChars += cmd.length
                    }
                }
            } catch (e: Exception) {
                appendLine("[write error] ${e.message}")
            }
        }
    }

    private fun flushPendingCommandsLocked() {
        val writer = stdin ?: return
        while (pendingCommands.isNotEmpty()) {
            val command = pendingCommands.removeFirst()
            pendingCommandChars -= command.length
            writer.write(command + "\n")
        }
        writer.flush()
        pendingCommandChars = 0
    }

    override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult =
        executeProcess(listOf(launchSpec.shell, "-c", cmd), timeoutMs) { }

    override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult =
        executeProcess(listOf(launchSpec.shell, "-c", cmd), timeoutMs, onOutput)

    override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult =
        executeProcess(argv, timeoutMs)

    private suspend fun executeProcess(
        payloadArgv: List<String>,
        timeoutMs: Long,
        onOutput: (String) -> Unit = { },
    ): ExecResult = withContext(Dispatchers.IO) {
        try {
            require(timeoutMs in 1..86_400_000L) { "Invalid process timeout" }
            workDir.mkdirs()
            val lease = RemoteProcessLease.createLocal("terminal")
            


            val targetArgv = launchSpec.command(
                payloadArgv,
                if (launchSpec.prefix.isEmpty()) emptyMap() else lease.environment,
            )
            val targetCommand = targetArgv.joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)
            val result = LocalProcessSupervisor.capture(
                argv = listOf(ownershipShell(), "-c", lease.wrap(targetCommand)),
                cwd = workDir,
                environment = launchSpec.environment + lease.environment,
                maxOutputBytes = 24_000,
                timeoutMs = timeoutMs,
                cleanup = { LocalExecutionSubstrate.terminateLease(lease) },
                onOutput = { chunk -> runCatching { onOutput(Ansi.strip(chunk)) } },
            )
            if (result.timedOut) {
                ExecResult(
                    -1,
                    (Ansi.strip(result.output) + "\n[TIMEOUT ${timeoutMs}ms]").takeLast(20_000),
                    true,
                )
            } else {
                ExecResult(result.exitCode, Ansi.strip(result.output).takeLast(20_000), false)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            ExecResult(-1, e.message ?: "error", false)
        }
    }


    private fun ownershipShell(): String =
        if (File("/system/bin/sh").canExecute()) "/system/bin/sh" else "/bin/sh"

    override fun interrupt() {
        scope.launch(Dispatchers.IO) {
            try {
                synchronized(pendingCommandLock) {
                    runCatching { stdin?.close() }
                    stdin = null
                    pendingCommands.clear()
                    pendingCommandChars = 0
                }
                ProcessIo.terminate(process, graceMs = 100)
                process = null
                appendLine("[shell restarted]")
                start()
            } catch (e: Exception) {
                appendLine("[restart error] ${e.message}")
            }
        }
    }

    override fun clear() { _output.value = "" }

    override fun destroy() {
        try { stdin?.apply { write("exit\n"); flush(); close() } } catch (_: Exception) {}
        synchronized(pendingCommandLock) {
            stdin = null
            pendingCommands.clear()
            pendingCommandChars = 0
        }
        ProcessIo.terminate(process)
        process = null
    }

    private fun emit(s: String) { _output.value = (_output.value + s).takeLast(outputCap) }
    private fun appendLine(s: String) { emit(s + "\n") }
}

object Ansi {
    private val regex = Regex("\u001B\\[[;\\d]*m|\u001B\\[\\d+[A-Z]|\u001B\\(B|\r")
    fun strip(s: String): String = s.replace(regex, "")
}
