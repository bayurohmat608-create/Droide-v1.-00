package com.baystudio.droide.core

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch









class AndroidJdwpProxy(
    private val transport: AndroidJdwpTransport,
    private val scope: CoroutineScope,
) : Closeable {
    data class Endpoint(val pid: Int, val host: String, val port: Int)

    private val closed = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var client: Socket? = null
    private var connection: AndroidJdwpTransport.Connection? = null
    private var acceptJob: Job? = null

    @Synchronized
    fun start(pid: Int): Endpoint {
        check(!closed.get()) { "JDWP proxy is closed" }
        check(server == null) { "JDWP proxy is already running" }
        require(pid > 0) { "Invalid JDWP pid" }
        val listener = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST)).apply {
            reuseAddress = false
            soTimeout = 0
        }
        server = listener
        acceptJob = scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            var jdwp: AndroidJdwpTransport.Connection? = null
            try {
                socket = listener.accept().apply {
                    tcpNoDelay = true
                    keepAlive = true
                }
                synchronized(this@AndroidJdwpProxy) { client = socket }
                val liveJdwp = transport.connect(pid)
                jdwp = liveJdwp
                synchronized(this@AndroidJdwpProxy) { connection = liveJdwp }

                val debuggerToVm = launch(Dispatchers.IO) {
                    socket.getInputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (!closed.get()) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) {
                                liveJdwp.output.write(buffer, 0, count)
                                liveJdwp.output.flush()
                            }
                        }
                    }
                }
                val vmToDebugger = launch(Dispatchers.IO) {
                    socket.getOutputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (!closed.get()) {
                            val count = liveJdwp.input.read(buffer)
                            if (count < 0) break
                            if (count > 0) {
                                output.write(buffer, 0, count)
                                output.flush()
                            }
                        }
                    }
                }
                debuggerToVm.join()
                vmToDebugger.cancelAndJoin()
            } finally {
                runCatching { jdwp?.close() }
                runCatching { socket?.close() }
                close()
            }
        }
        return Endpoint(pid, LOOPBACK_HOST, listener.localPort)
    }

    companion object {
        const val LOOPBACK_HOST: String = "127.0.0.1"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        runCatching { client?.close() }
        runCatching { connection?.close() }
        acceptJob?.cancel()
        server = null
        client = null
        connection = null
        acceptJob = null
    }
}
