package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

 
class JsonRpcProcess(
    private val scope: CoroutineScope,
    private val workDir: File,
    private val argv: List<String>,
    private val environment: Map<String, String> = emptyMap(),
    private val processHost: StdioProcessHost,
    private val inboundRequestHandler: suspend (String, JsonElement?) -> JsonElement? = { _, _ -> JsonNull },
    private val maxMessageBytes: Int = ContentLengthProtocol.MAX_MESSAGE_BYTES,
    private val stderrBytes: Int = 64 * 1024,
    private val resourceLimits: ProcessResourceLimits? = null,
) : AutoCloseable {
    data class Notification(val method: String, val params: JsonElement?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val writeMutex = Mutex()
    private val startMutex = Mutex()
    @Volatile private var closed = false
    private val nextId = AtomicLong(1)
    private val pendingCount = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private data class InboundRequest(val id: JsonPrimitive, val method: String, val params: JsonElement?, val bytes: Int)
    private val inboundRequests = Channel<InboundRequest>(16)
    private val inboundBytes = AtomicInteger(0)
    private val omittedNotificationCount = AtomicLong(0)
    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 128)
    val notifications: SharedFlow<Notification> = _notifications

    @Volatile private var process: HostedStdioProcess? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var stderrJob: Job? = null
    @Volatile private var inboundJob: Job? = null
    private val _stderr = BoundedOutput(stderrBytes.coerceIn(4 * 1024, 1024 * 1024))

    val isRunning: Boolean get() = process?.isAlive == true
    val stderr: String get() = _stderr.utf8()
    val omittedNotifications: Long get() = omittedNotificationCount.get()

    init {
        require(processHost.executionScope in setOf(ProcessExecutionScope.LOCAL_LINUX_ARM64, ProcessExecutionScope.DEVICE_ADB)) {
            "JSON-RPC protocol processes must execute in a bound Linux or Device Workstation backend"
        }
        require(maxMessageBytes in 1..ContentLengthProtocol.MAX_MESSAGE_BYTES) { "Invalid JSON-RPC message limit" }
        require(stderrBytes in 4 * 1024..1024 * 1024) { "Invalid JSON-RPC stderr limit" }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        startMutex.withLock {
            check(!closed) { "JsonRpcProcess has been closed; create a new session" }
            if (isRunning) return@withLock
            check(process == null) { "Previous protocol transport is shutting down" }
            require(argv.isNotEmpty()) { "Protocol argv must not be empty" }
            val p = processHost.start(argv, environment, resourceLimits)
            var published = false
            try {
                currentCoroutineContext().ensureActive()
                synchronized(this@JsonRpcProcess) {
                    check(!closed && scope.isActive) { "Protocol session was canceled during startup" }
                    process = p
                    inboundJob = scope.launch(Dispatchers.IO) {
                        try {
                            for (request in inboundRequests) {
                                try { handleInboundRequest(request.id, request.method, request.params) }
                                finally { inboundBytes.addAndGet(-request.bytes) }
                            }
                        } finally {
                            synchronized(this@JsonRpcProcess) { if (process === p) close() }
                        }
                    }
                    readerJob = scope.launch(Dispatchers.IO) { readLoop(p) }
                    stderrJob = scope.launch(Dispatchers.IO) {
                        val buf = ByteArray(4096)
                        p.stderr.use { input ->
                            while (isActive) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                _stderr.write(buf, 0, n)
                            }
                        }
                    }
                    published = true
                }
            } catch (cancelled: CancellationException) {
                if (published) close()
                throw cancelled
            } finally {
                if (!published) runCatching { p.close() }
            }
        }
    }

    suspend fun request(
        method: String,
        params: JsonElement? = null,
        timeoutMs: Long = 15_000,
        cancellationNotificationMethod: String? = null,
    ): JsonElement {
        check(isRunning) { "JSON-RPC process is not running" }
        require(method.isNotBlank() && method.length <= 256) { "Invalid JSON-RPC method" }
        require(timeoutMs in 1..300_000) { "Invalid JSON-RPC request timeout" }
        if (pendingCount.incrementAndGet() > 128) {
            pendingCount.decrementAndGet()
            error("Too many pending JSON-RPC requests")
        }
        val id = nextId.getAndIncrement()
        val idElement = JsonPrimitive(id)
        val deferred = CompletableDeferred<JsonElement>()
        pending[idKey(idElement)] = deferred
        deferred.invokeOnCompletion {
            pending.remove(idKey(idElement), deferred)
            pendingCount.decrementAndGet()
        }
        var writeStarted = false
        try {
            return withTimeout(timeoutMs) {
                send(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    if (params != null) put("params", params)
                }, onWriteStarted = { writeStarted = true })
                deferred.await()
            }
        } catch (cancel: CancellationException) {
            // Cancellation can win while awaiting a completed writer. Track admission to
            // the serialized write, rather than the awaiter's return, so a live transport
            // still receives cancellation for a frame that may already have been sent.
            if (writeStarted && isRunning && cancellationNotificationMethod != null) {
                sendCancellationNotification(cancellationNotificationMethod, idElement)
            }
            throw cancel
        } finally {
            pending.remove(idKey(idElement), deferred)
            if (!deferred.isCompleted) deferred.cancel()
        }
    }


    private suspend fun sendCancellationNotification(method: String, id: JsonPrimitive) {
        withContext(NonCancellable) {
            withTimeoutOrNull(250L) {
                runCatching {
                    send(buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", method)
                        put("params", buildJsonObject { put("id", id) })
                    })
                }
            }
        }
    }

    suspend fun notify(method: String, params: JsonElement? = null) {
        check(isRunning) { "JSON-RPC process is not running" }
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        })
    }

    private suspend fun send(obj: JsonObject, onWriteStarted: (() -> Unit)? = null) {
        val encoded = json.encodeToString(JsonObject.serializer(), obj)
        writeMutex.withLock {
            val out = process?.stdin ?: error("Protocol process is not running")
            onWriteStarted?.invoke()
            val writer = scope.async(Dispatchers.IO) { ContentLengthProtocol.writeMessage(out, encoded, maxMessageBytes) }
            try {
                withTimeout(15_000) { writer.await() }
            } finally {
                if (!writer.isCompleted || writer.isCancelled) {
                    withContext(NonCancellable + Dispatchers.IO) { close() }
                    writer.cancel()
                }
            }
        }
    }

    private suspend fun readLoop(p: HostedStdioProcess) {
        var failure: Throwable? = null
        try {
            p.stdout.use { input ->
                while (currentCoroutineContext().isActive) {
                    val raw = ContentLengthProtocol.readMessage(input, maxMessageBytes) ?: break
                    val obj = json.parseToJsonElement(raw) as? JsonObject ?: continue
                    val method = obj["method"]?.jsonPrimitive?.contentOrNull
                    val idPrimitive = obj["id"] as? JsonPrimitive
                    when {
                        method != null && idPrimitive != null -> {
                            val bytes = raw.toByteArray(Charsets.UTF_8).size
                            if (inboundBytes.addAndGet(bytes) > MAX_INBOUND_BYTES) {
                                inboundBytes.addAndGet(-bytes)
                                error("JSON-RPC inbound request queue exceeded its byte limit")
                            }
                            if (!inboundRequests.trySend(InboundRequest(idPrimitive, method, obj["params"], bytes)).isSuccess) {
                                inboundBytes.addAndGet(-bytes)
                                error("JSON-RPC inbound request queue overflow")
                            }
                        }
                        method != null -> {
                            if (!_notifications.tryEmit(Notification(method, obj["params"]))) {
                                if (method in LOSSY_NOTIFICATIONS) omittedNotificationCount.incrementAndGet()
                                else error("JSON-RPC notification queue overflow; consumer is too slow, restart the protocol session")
                            }
                        }
                        idPrimitive != null -> {
                            val waiter = pending.remove(idKey(idPrimitive)) ?: continue
                            val err = obj["error"]
                            if (err != null && err !is JsonNull) waiter.completeExceptionally(IllegalStateException(err.toString()))
                            else waiter.complete(obj["result"] ?: JsonNull)
                        }
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            failure = t
        } finally {
            val error = failure ?: IllegalStateException("JSON-RPC process exited")
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
            synchronized(this@JsonRpcProcess) {
                if (process === p) close()
            }
            runCatching { p.close() }
        }
    }

    suspend fun awaitExit(): Int {
        val active = process ?: error("JSON-RPC process is not running")
        return active.awaitExit()
    }

    private fun idKey(id: JsonPrimitive): String = if (id.isString) "s:${id.content}" else "n:${id.content}"

    private suspend fun handleInboundRequest(id: JsonPrimitive, method: String, params: JsonElement?) {
        val response = try {
            val result = withTimeout(15_000) { inboundRequestHandler(method, params) } ?: JsonNull
            buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
        } catch (timeout: TimeoutCancellationException) {
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                put("error", buildJsonObject { put("code", -32000); put("message", "IDE request handler timed out") })
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                put("error", buildJsonObject { put("code", -32603); put("message", t.message ?: "Internal error") })
            }
        }
        send(response)
    }

    @Synchronized
    override fun close() {
        closed = true
        readerJob?.cancel()
        stderrJob?.cancel()
        inboundJob?.cancel()
        inboundRequests.close()
        readerJob = null
        stderrJob = null
        inboundJob = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        runCatching { process?.close() }
        process = null
    }

    private companion object {
        const val MAX_INBOUND_BYTES = 8 * 1024 * 1024
        val LOSSY_NOTIFICATIONS = setOf("window/logMessage", "telemetry/event", "$/progress")
    }
}
