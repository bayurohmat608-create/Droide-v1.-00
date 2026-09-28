package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
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
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 128)
    val notifications: SharedFlow<Notification> = _notifications

    @Volatile private var process: HostedStdioProcess? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var stderrJob: Job? = null
    private val _stderr = BoundedOutput(stderrBytes.coerceIn(4 * 1024, 1024 * 1024))

    val isRunning: Boolean get() = process?.isAlive == true
    val stderr: String get() = _stderr.utf8()

    init {
        require(processHost.executionScope in setOf(ProcessExecutionScope.LOCAL_LINUX_ARM64, ProcessExecutionScope.DEVICE_ADB)) {
            "JSON-RPC protocol processes must execute in a bound Linux or Device Workstation backend"
        }
        require(maxMessageBytes in 1..ContentLengthProtocol.MAX_MESSAGE_BYTES) { "Invalid JSON-RPC message limit" }
        require(stderrBytes in 4 * 1024..1024 * 1024) { "Invalid JSON-RPC stderr limit" }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        require(argv.isNotEmpty()) { "JSON-RPC argv must not be empty" }
        val p = processHost.start(argv, environment, resourceLimits)
        process = p
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
    }

    suspend fun request(
        method: String,
        params: JsonElement? = null,
        timeoutMs: Long = 15_000,
        cancellationNotificationMethod: String? = null,
    ): JsonElement {
        check(isRunning) { "JSON-RPC process is not running" }
        val id = nextId.getAndIncrement()
        val idElement = JsonPrimitive(id)
        val deferred = CompletableDeferred<JsonElement>()
        pending[idKey(idElement)] = deferred
        var transmitted = false
        try {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (params != null) put("params", params)
            })
            transmitted = true
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (cancel: CancellationException) {
            if (transmitted && cancellationNotificationMethod != null) {
                sendCancellationNotification(cancellationNotificationMethod, idElement)
            }
            throw cancel
        } finally {
            pending.remove(idKey(idElement))
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

    private suspend fun send(obj: JsonObject) {
        val out = process?.stdin ?: error("JSON-RPC process is not running")
        val encoded = json.encodeToString(JsonObject.serializer(), obj)
        writeMutex.withLock { withContext(Dispatchers.IO) { ContentLengthProtocol.writeMessage(out, encoded, maxMessageBytes) } }
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
                        method != null && idPrimitive != null -> handleInboundRequest(idPrimitive, method, obj["params"])
                        method != null -> _notifications.emit(Notification(method, obj["params"]))
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
        }
    }

    suspend fun awaitExit(): Int {
        val active = process ?: error("JSON-RPC process is not running")
        return active.awaitExit()
    }

    private fun idKey(id: JsonPrimitive): String = if (id.isString) "s:${id.content}" else "n:${id.content}"

    private suspend fun handleInboundRequest(id: JsonPrimitive, method: String, params: JsonElement?) {
        val response = try {
            val result = inboundRequestHandler(method, params) ?: JsonNull
            buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
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

    override fun close() {
        readerJob?.cancel()
        stderrJob?.cancel()
        readerJob = null
        stderrJob = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        runCatching { process?.close() }
        process = null
    }
}
