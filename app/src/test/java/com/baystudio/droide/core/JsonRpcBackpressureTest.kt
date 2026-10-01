package com.baystudio.droide.core

import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class JsonRpcBackpressureTest {
    private class Host : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val output = PipedOutputStream()
        private val input = PipedInputStream(output, 262144)
        val writeSeen = CompletableDeferred<Unit>()
        val closed = AtomicBoolean(false)
        override suspend fun resolveExecutable(command: String) = command
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = object : HostedStdioProcess {
            override val stdin = object : OutputStream() {
                override fun write(b: Int) { writeSeen.complete(Unit) }
                override fun write(b: ByteArray, off: Int, len: Int) { writeSeen.complete(Unit) }
            }
            override val stdout = input
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = !closed.get()
            override val exitCode: Int? get() = if (closed.get()) 0 else null
            override suspend fun awaitExit(): Int { while (!closed.get()) delay(10); return 0 }
            override fun close() = this@Host.close()
        }
        fun frame(json: String) = ContentLengthProtocol.writeMessage(output, json)
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { input.close() }; runCatching { output.close() }
            }
        }
    }

    @Test fun optionalLogFloodDoesNotBlockAResponse() = runBlocking {
        val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rpc = JsonRpcProcess(scope, File("."), listOf("fixture"), processHost = host)
        val entered = CompletableDeferred<Unit>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            rpc.notifications.collect { entered.complete(Unit); awaitCancellation() }
        }
        try {
            rpc.start()
            val result = async { rpc.request("hover", timeoutMs = 3000) }
            host.writeSeen.await()
            host.frame("""{"jsonrpc":"2.0","method":"window/logMessage"}""")
            withTimeout(1000) { entered.await() }
            repeat(160) { host.frame("""{"jsonrpc":"2.0","method":"window/logMessage"}""") }
            host.frame("""{"jsonrpc":"2.0","id":1,"result":"ok"}""")
            assertEquals(JsonPrimitive("ok"), result.await())
            assertTrue(rpc.omittedNotifications > 0)
        } finally { collector.cancel(); rpc.close(); host.close(); scope.cancel() }
    }

    @Test fun criticalNotificationOverflowFailsExplicitlyInsteadOfTimingOut() = runBlocking {
        val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rpc = JsonRpcProcess(scope, File("."), listOf("fixture"), processHost = host)
        val entered = CompletableDeferred<Unit>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            rpc.notifications.collect { entered.complete(Unit); awaitCancellation() }
        }
        try {
            rpc.start()
            val result = async { runCatching { rpc.request("hover", timeoutMs = 3000) } }
            host.writeSeen.await()
            host.frame("""{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics"}""")
            withTimeout(1000) { entered.await() }
            withContext(Dispatchers.IO) {
                runCatching { repeat(160) { host.frame("""{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics"}""") } }
            }
            val failure = withTimeout(2000) { result.await().exceptionOrNull() }
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("notification queue overflow"))
            withTimeout(1000) { while (!host.closed.get()) delay(5) }
        } finally { collector.cancel(); rpc.close(); host.close(); scope.cancel() }
    }

    @Test fun slowInboundHandlerDoesNotBlockResponses() = runBlocking {
        val host = Host(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val rpc = JsonRpcProcess(scope, File("."), listOf("fixture"), processHost = host,
            inboundRequestHandler = { _, _ -> entered.complete(Unit); release.await(); JsonPrimitive("handled") })
        try {
            rpc.start()
            val result = async { rpc.request("hover", timeoutMs = 3000) }
            host.writeSeen.await()
            host.frame("""{"jsonrpc":"2.0","id":"server1","method":"workspace/configuration"}""")
            withTimeout(1000) { entered.await() }
            host.frame("""{"jsonrpc":"2.0","id":1,"result":"response"}""")
            assertEquals(JsonPrimitive("response"), withTimeout(1000) { result.await() })
        } finally { release.complete(Unit); rpc.close(); host.close(); scope.cancel() }
    }
}
