package com.baystudio.droide.core

import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class ProtocolProcessLifecycleTest {
    private class Host : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val serverOutput = PipedOutputStream()
        private val input = PipedInputStream(serverOutput, 65536)
        val closed = AtomicBoolean(false)
        val starts = AtomicInteger(0)
        var blockWrites = false
        val writeStarted = CompletableDeferred<Unit>()
        private val releaseWrite = CountDownLatch(1)
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        private val process = object : HostedStdioProcess {
            override val stdin = object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()))
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    writeStarted.complete(Unit)
                    if (blockWrites) {
                        releaseWrite.await()
                        throw IOException("transport closed while writing")
                    }
                }
                override fun close() { releaseWrite.countDown() }
            }
            override val stdout = input
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = !closed.get()
            override val exitCode: Int? get() = if (closed.get()) 0 else null
            override suspend fun awaitExit(): Int { while (!closed.get()) delay(10); return 0 }
            override fun close() = this@Host.close()
        }
        override suspend fun resolveExecutable(command: String) = command
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            starts.incrementAndGet(); entered.complete(Unit); gate?.await(); return process
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            releaseWrite.countDown()
            runCatching { input.close() }; runCatching { serverOutput.close() }
        }
    }
    private class Client(scope: CoroutineScope, host: Host, dap: Boolean) : AutoCloseable {
        private val rpc = if (!dap) JsonRpcProcess(scope, File("."), listOf("fake-lsp"), processHost = host) else null
        private val debug = if (dap) DapProcess(scope, File("."), listOf("fake-dap"), processHost = host) else null
        val running get() = rpc?.isRunning ?: debug!!.isRunning
        suspend fun start() { if (rpc != null) rpc.start() else debug!!.start() }
        suspend fun request(timeoutMs: Long = 3000) { if (rpc != null) rpc.request("test", timeoutMs = timeoutMs) else debug!!.request("test", timeoutMs = timeoutMs) }
        override fun close() { rpc?.close(); debug?.close() }
    }
    private suspend fun awaitClosed(host: Host) = withTimeout(3000) { while (!host.closed.get()) delay(10) }

    @Test fun protocolEofClosesStillAliveHostAndFailsPendingRequest() = runBlocking {
        for (dap in listOf(false, true)) {
            val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val client = Client(scope, host, dap)
            try {
                client.start()
                val pending = async { runCatching { client.request() } }
                withTimeout(3000) { host.writeStarted.await() }
                host.serverOutput.close(); awaitClosed(host)
                assertFalse(client.running); assertTrue(withTimeout(3000) { pending.await() }.isFailure)
            } finally { client.close(); host.close(); scope.cancel() }
        }
    }
    @Test fun malformedProtocolClosesStillAliveHost() = runBlocking {
        for (dap in listOf(false, true)) {
            val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val client = Client(scope, host, dap)
            try {
                client.start(); host.serverOutput.write("Content-Length: -1\r\n\r\n".toByteArray()); host.serverOutput.flush()
                awaitClosed(host); assertFalse(client.running)
            } finally { client.close(); host.close(); scope.cancel() }
        }
    }
    @Test fun concurrentStartLaunchesOnlyOneProcess() = runBlocking {
        for (dap in listOf(false, true)) {
            val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val client = Client(scope, host, dap)
            try {
                coroutineScope { (1..8).map { async { client.start() } }.awaitAll() }; assertEquals(1, host.starts.get())
            } finally { client.close(); host.close(); scope.cancel() }
        }
    }
    @Test fun closeDuringStartupClosesReturnedHostInsteadOfPublishingIt() = runBlocking {
        for (dap in listOf(false, true)) {
            val host = Host(); host.gate = CompletableDeferred(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val client = Client(scope, host, dap)
            try {
                val startup = async { runCatching { client.start() } }
                withTimeout(3000) { host.entered.await() }; client.close(); host.gate!!.complete(Unit)
                assertTrue(withTimeout(3000) { startup.await() }.isFailure); assertTrue(host.closed.get()); assertFalse(client.running)
            } finally { client.close(); host.close(); scope.cancel() }
        }
    }
    @Test fun debugOutputFloodCannotBlockResponsesNeededByEventConsumer() = runBlocking {
        val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val debug = DapProcess(scope, File("."), listOf("fake-dap"), processHost = host)
        val consumerWaiting = CompletableDeferred<Unit>(); val responseSeen = CompletableDeferred<Unit>()
        try {
            val consumer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                debug.events.collect { event ->
                    if (event.name == "stopped") {
                        consumerWaiting.complete(Unit); debug.request("threads", timeoutMs = 3000); responseSeen.complete(Unit)
                    }
                }
            }
            debug.start()
            ContentLengthProtocol.writeMessage(host.serverOutput, "{\"seq\":1,\"type\":\"event\",\"event\":\"stopped\"}")
            withTimeout(3000) { consumerWaiting.await() }
            repeat(400) { ContentLengthProtocol.writeMessage(host.serverOutput, "{\"seq\":2,\"type\":\"event\",\"event\":\"output\",\"body\":{\"output\":\"log\"}}") }
            ContentLengthProtocol.writeMessage(host.serverOutput, "{\"seq\":3,\"type\":\"response\",\"request_seq\":1,\"success\":true,\"body\":{\"threads\":[]}}")
            withTimeout(3000) { responseSeen.await() }; assertTrue(debug.isRunning); consumer.cancel()
        } finally { debug.close(); host.close(); scope.cancel() }
    }
    @Test fun requestDeadlineClosesBlockedStdinAndReturnsWithoutWaitingForWriter() = runBlocking {
        for (dap in listOf(false, true)) {
            val host = Host(); host.blockWrites = true; val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val client = Client(scope, host, dap)
            try {
                client.start(); val result = async { runCatching { client.request(timeoutMs = 200) } }
                withTimeout(3000) { host.writeStarted.await() }
                assertTrue(withTimeout(3000) { result.await() }.exceptionOrNull() is TimeoutCancellationException)
                assertTrue(host.closed.get()); assertFalse(client.running)
            } finally { client.close(); host.close(); scope.cancel() }
        }
    }
}
