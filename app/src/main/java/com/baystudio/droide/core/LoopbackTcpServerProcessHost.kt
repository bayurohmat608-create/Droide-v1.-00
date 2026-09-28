package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout










class LoopbackTcpServerProcessHost(
    private val delegate: StdioProcessHost,
    private val scope: CoroutineScope,
) : StdioProcessHost {
    override val executionScope: ProcessExecutionScope get() = delegate.executionScope

    override suspend fun resolveExecutable(command: String): String? = delegate.resolveExecutable(command)

    override suspend fun pathMapper(): WorkspacePathMapper? = delegate.pathMapper()

    override suspend fun start(
        argv: List<String>,
        environment: Map<String, String>,
        resourceLimits: ProcessResourceLimits?,
    ): HostedStdioProcess = withContext(Dispatchers.IO) {
        require(argv.isNotEmpty()) { "Loopback adapter argv must not be empty" }
        require(argv.any { it == REQUIRED_LISTEN_ARGUMENT }) {
            "Loopback adapter must bind an OS-selected Android-loopback port"
        }

        val serverProcess = delegate.start(argv, environment, resourceLimits)
        var startupReader: kotlinx.coroutines.Deferred<Endpoint>? = null
        var socket: Socket? = null
        try {
            startupReader = scope.async(Dispatchers.IO) { readBoundEndpoint(serverProcess.stdout) }
            val endpoint = withTimeout(STARTUP_TIMEOUT_MS) { startupReader.await() }
            check(serverProcess.isAlive) { "Loopback debug adapter exited after binding its DAP listener" }

            socket = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
            }
            val drainJob = drainServerStdout(serverProcess.stdout)
            LoopbackHostedStdioProcess(serverProcess, socket, drainJob)
        } catch (t: Throwable) {
            runCatching { socket?.close() }
            runCatching { serverProcess.close() }
            startupReader?.cancelAndJoin()
            throw t
        }
    }

    private fun readBoundEndpoint(input: InputStream): Endpoint {
        repeat(MAX_STARTUP_LINES) {
            val line = readBoundedAsciiLine(input) ?: error("Debug adapter closed stdout before announcing its DAP listener")
            val match = LISTEN_LINE.matchEntire(line.trim()) ?: return@repeat
            val port = match.groupValues[1].toIntOrNull() ?: error("Invalid Delve DAP listener port")
            require(port in 1..65535) { "Invalid Delve DAP listener port" }
            return Endpoint(LOOPBACK_HOST, port)
        }
        error("Debug adapter did not announce a private loopback DAP listener")
    }

    private fun readBoundedAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (out.size() <= MAX_STARTUP_LINE_BYTES) {
            val next = input.read()
            if (next < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (next == '\n'.code) return out.toString(Charsets.UTF_8.name()).trimEnd('\r')
            require(next in 0x09..0x7e) { "Unexpected byte in debug-adapter startup output" }
            out.write(next)
        }
        error("Debug-adapter startup line exceeded the safety bound")
    }

    private fun drainServerStdout(input: InputStream): Job = scope.launch(Dispatchers.IO) {
        val buffer = ByteArray(4_096)
        try {
            while (isActive) {
                val read = input.read(buffer)
                if (read < 0) break
            }
        } catch (_: Throwable) {
            
        }
    }

    private data class Endpoint(val host: String, val port: Int)

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val REQUIRED_LISTEN_ARGUMENT = "--listen=127.0.0.1:0"
        const val STARTUP_TIMEOUT_MS = 8_000L
        const val CONNECT_TIMEOUT_MS = 2_000
        const val MAX_STARTUP_LINES = 8
        const val MAX_STARTUP_LINE_BYTES = 2_048
        val LISTEN_LINE = Regex("^DAP server listening at: 127\\.0\\.0\\.1:(\\d{1,5})$")
    }
}

private class LoopbackHostedStdioProcess(
    private val serverProcess: HostedStdioProcess,
    private val socket: Socket,
    private val stdoutDrainJob: Job,
) : HostedStdioProcess {
    private val closed = AtomicBoolean(false)

    override val stdin: OutputStream get() = socket.getOutputStream()
    override val stdout: InputStream get() = socket.getInputStream()
    override val stderr: InputStream get() = serverProcess.stderr
    override val isAlive: Boolean get() = !closed.get() && !socket.isClosed && serverProcess.isAlive
    override val exitCode: Int? get() = serverProcess.exitCode

    override suspend fun awaitExit(): Int = serverProcess.awaitExit()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        stdoutDrainJob.cancel()
        runCatching { serverProcess.close() }
    }
}
