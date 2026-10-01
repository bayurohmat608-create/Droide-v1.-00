package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
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









class AuthenticatedReverseTcpProcessHost(
    private val delegate: StdioProcessHost,
    private val scope: CoroutineScope,
    private val contract: AuthenticatedReverseTcpContract,
) : StdioProcessHost {
    override val executionScope: ProcessExecutionScope get() = delegate.executionScope

    init {
        contract.validate()
    }

    override suspend fun resolveExecutable(command: String): String? = delegate.resolveExecutable(command)

    override suspend fun pathMapper(): WorkspacePathMapper? = delegate.pathMapper()

    override suspend fun start(
        argv: List<String>,
        environment: Map<String, String>,
        resourceLimits: ProcessResourceLimits?,
    ): HostedStdioProcess = withContext(Dispatchers.IO) {
        require(argv.isNotEmpty()) { "Reverse-TCP adapter argv must not be empty" }
        val token = createToken()
        val listener = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), 1)
            soTimeout = ACCEPT_SOCKET_TIMEOUT_MS
        }
        require(argv.none { it == contract.connectArgument || it.startsWith("${contract.connectArgument}=") }) {
            "Reverse-TCP adapter argv already contains the managed connect argument"
        }
        require(argv.none { it == contract.authTokenArgument || it.startsWith("${contract.authTokenArgument}=") }) {
            "Reverse-TCP adapter argv already contains the managed authentication argument"
        }
        val launchArgv = buildList {
            addAll(argv)
            add(contract.connectArgument)
            add(listener.localPort.toString())
            add(contract.authTokenArgument)
            add(token)
        }

        var process: HostedStdioProcess? = null
        var socket: Socket? = null
        var acceptJob: kotlinx.coroutines.Deferred<Socket>? = null
        var stdoutDrain: Job? = null
        try {
            acceptJob = scope.async(Dispatchers.IO) { listener.accept() }
            process = delegate.start(launchArgv, environment, resourceLimits)
            // Drain ordinary process stdout from launch time so verbose startup diagnostics cannot fill a pipe and deadlock before the authenticated connection is.


            stdoutDrain = drainProcessStdout(process.stdout)
            socket = withTimeout(STARTUP_TIMEOUT_MS) { acceptJob.await() }
            listener.close()
            check(process.isAlive) { "Reverse-TCP debug adapter exited before protocol authentication" }
            require(socket.inetAddress.hostAddress == LOOPBACK_HOST && socket.inetAddress.isLoopbackAddress) {
                "Reverse-TCP debug adapter connected from a non-loopback address"
            }
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = AUTHENTICATION_TIMEOUT_MS

            val authenticatedInput = authenticatePrelude(socket.getInputStream(), token, contract.authHeaderName)
            socket.soTimeout = 0
            ReverseTcpHostedStdioProcess(process, socket, authenticatedInput, requireNotNull(stdoutDrain))
        } catch (t: Throwable) {
            runCatching { listener.close() }
            runCatching { socket?.close() }
            stdoutDrain?.cancel()
            runCatching { process?.close() }
            acceptJob?.cancelAndJoin()
            throw t
        }
    }

    private fun authenticatePrelude(input: InputStream, expectedToken: String, authHeaderName: String): InputStream {
        val raw = ByteArrayOutputStream(256)
        var state = 0
        while (raw.size() < ContentLengthProtocol.MAX_HEADER_BYTES) {
            val b = input.read()
            require(b >= 0) { "Reverse-TCP adapter closed before authentication" }
            require(b <= 0x7f) { "Reverse-TCP authentication header must be ASCII" }
            raw.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        require(state == 4) { "Reverse-TCP authentication header exceeded safety bound" }

        val headerText = raw.toString(Charsets.US_ASCII.name())
        val lines = headerText.removeSuffix("\r\n\r\n").split("\r\n")
        var token: String? = null
        var contentLength: String? = null
        lines.forEach { line ->
            val split = line.indexOf(": ")
            require(split > 0) { "Malformed reverse-TCP protocol header" }
            val name = line.substring(0, split)
            val value = line.substring(split + 2)
            when {
                name.equals(authHeaderName, ignoreCase = true) -> {
                    require(token == null) { "Duplicate reverse-TCP authentication header" }
                    token = value
                }
                name.equals("Content-Length", ignoreCase = true) -> {
                    require(contentLength == null) { "Duplicate Content-Length header" }
                    val length = value.toIntOrNull() ?: error("Invalid reverse-TCP Content-Length")
                    require(length in 0..ContentLengthProtocol.MAX_MESSAGE_BYTES) { "Reverse-TCP DAP frame exceeds safety bound" }
                    contentLength = length.toString()
                }
                else -> error("Unexpected reverse-TCP protocol header: $name")
            }
        }
        require(constantTimeEquals(token.orEmpty(), expectedToken)) { "Reverse-TCP adapter authentication failed" }
        val length = requireNotNull(contentLength) { "Reverse-TCP adapter did not start with a DAP Content-Length frame" }
        val sanitized = "Content-Length: $length\r\n\r\n".toByteArray(Charsets.US_ASCII)
        return SequenceInputStream(ByteArrayInputStream(sanitized), input)
    }

    private fun drainProcessStdout(input: InputStream): Job = scope.launch(Dispatchers.IO) {
        val buffer = ByteArray(4_096)
        try {
            while (isActive) {
                if (input.read(buffer) < 0) break
            }
        } catch (_: Throwable) {
            
        }
    }

    private fun createToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun constantTimeEquals(actual: String, expected: String): Boolean {
        val a = actual.toByteArray(Charsets.UTF_8)
        val b = expected.toByteArray(Charsets.UTF_8)
        var diff = a.size xor b.size
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val av = if (i < a.size) a[i].toInt() else 0
            val bv = if (i < b.size) b[i].toInt() else 0
            diff = diff or (av xor bv)
        }
        return diff == 0
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val STARTUP_TIMEOUT_MS = 8_000L
        const val ACCEPT_SOCKET_TIMEOUT_MS = 8_000
        const val AUTHENTICATION_TIMEOUT_MS = 8_000
        const val TOKEN_BYTES = 32
        val RANDOM = SecureRandom()
    }
}

private class ReverseTcpHostedStdioProcess(
    private val adapterProcess: HostedStdioProcess,
    private val socket: Socket,
    private val authenticatedInput: InputStream,
    private val stdoutDrain: Job,
) : HostedStdioProcess {
    private val closed = AtomicBoolean(false)

    override val stdin: OutputStream get() = socket.getOutputStream()
    override val stdout: InputStream get() = authenticatedInput
    override val stderr: InputStream get() = adapterProcess.stderr
    override val isAlive: Boolean get() = !closed.get() && !socket.isClosed && adapterProcess.isAlive
    override val exitCode: Int? get() = adapterProcess.exitCode
    override suspend fun awaitExit(): Int = adapterProcess.awaitExit()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        stdoutDrain.cancel()
        runCatching { adapterProcess.close() }
    }
}
