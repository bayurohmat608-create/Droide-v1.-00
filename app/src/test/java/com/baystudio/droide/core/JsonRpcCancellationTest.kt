package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcCancellationTest {
    @Test fun transmittedLspRequestEmitsProtocolCancellation() = runBlocking {
        val host = PipeProcessHost()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rpc = JsonRpcProcess(
            scope = scope,
            workDir = File("."),
            argv = listOf("fake-language-server"),
            processHost = host,
        )
        try {
            rpc.start()
            val request = async(Dispatchers.Default) {
                rpc.request(
                    method = "textDocument/completion",
                    params = JsonObject(emptyMap()),
                    timeoutMs = 10_000,
                    cancellationNotificationMethod = "$/cancelRequest",
                )
            }

            val first = withContext(Dispatchers.IO) {
                ContentLengthProtocol.readMessage(host.serverReadsClient) ?: error("missing request")
            }
            val firstJson = Json.parseToJsonElement(first).jsonObject
            val requestId = firstJson.getValue("id").jsonPrimitive.content.toLong()
            assertEquals("textDocument/completion", firstJson.getValue("method").jsonPrimitive.content)

            request.cancel()
            runCatching { request.await() }.onSuccess { error("cancelled request unexpectedly completed") }
                .onFailure { assertTrue(it is CancellationException) }

            val cancel = withContext(Dispatchers.IO) {
                ContentLengthProtocol.readMessage(host.serverReadsClient) ?: error("missing cancel notification")
            }
            val cancelJson = Json.parseToJsonElement(cancel).jsonObject
            assertEquals("$/cancelRequest", cancelJson.getValue("method").jsonPrimitive.content)
            assertEquals(requestId, cancelJson.getValue("params").jsonObject.getValue("id").jsonPrimitive.content.toLong())
        } finally {
            rpc.close()
            host.close()
            scope.cancel()
        }
    }

    private class PipeProcessHost : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64

        private val clientWrites = PipedOutputStream()
        val serverReadsClient = PipedInputStream(clientWrites, 64 * 1024)
        private val serverWrites = PipedOutputStream()
        private val clientReads = PipedInputStream(serverWrites, 64 * 1024)
        private val alive = AtomicBoolean(true)

        private val hosted = object : HostedStdioProcess {
            override val stdin = clientWrites
            override val stdout = clientReads
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive: Boolean get() = alive.get()
            override val exitCode: Int? get() = if (alive.get()) null else 0
            override suspend fun awaitExit(): Int = 0
            override fun close() { this@PipeProcessHost.close() }
        }

        override suspend fun resolveExecutable(command: String): String = command
        override suspend fun start(
            argv: List<String>,
            environment: Map<String, String>,
            resourceLimits: ProcessResourceLimits?,
        ): HostedStdioProcess = hosted

        override fun close() {
            if (!alive.compareAndSet(true, false)) return
            runCatching { clientWrites.close() }
            runCatching { serverReadsClient.close() }
            runCatching { serverWrites.close() }
            runCatching { clientReads.close() }
        }
    }
}
