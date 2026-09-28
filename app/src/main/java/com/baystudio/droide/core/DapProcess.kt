package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*







class DapProcess(
    private val scope: CoroutineScope,
    private val workDir: File,
    private val argv: List<String>,
    private val environment: Map<String, String> = emptyMap(),
    private val processHost: StdioProcessHost,
    private val reverseRequestHandler: suspend (String, JsonElement?) -> ReverseResponse = { command, _ ->
        ReverseResponse(false, message = "Droide does not support reverse DAP request: $command")
    },
) : AutoCloseable {
    data class Event(val name: String, val body: JsonElement?)
    data class ReverseResponse(
        val success: Boolean,
        val body: JsonElement? = null,
        val message: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val writeMutex = Mutex()
    private val nextSeq = AtomicInteger(1)
    private val pendingCount = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val _events = MutableSharedFlow<Event>(replay = 8, extraBufferCapacity = 248)
    val events: SharedFlow<Event> = _events

    @Volatile private var process: HostedStdioProcess? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var stderrJob: Job? = null
    private val _stderr = BoundedOutput(128 * 1024)

    val isRunning: Boolean get() = process?.isAlive == true
    val stderr: String get() = _stderr.utf8()

    init {
        require(processHost.executionScope in setOf(ProcessExecutionScope.LOCAL_LINUX_ARM64, ProcessExecutionScope.DEVICE_ADB)) {
            "DAP protocol processes must execute in a bound Linux or Device Workstation backend"
        }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        require(argv.isNotEmpty()) { "DAP argv must not be empty" }
        val p = processHost.start(argv, environment)
        process = p
        readerJob = scope.launch(Dispatchers.IO) { readLoop(p) }
        stderrJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(4_096)
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
        command: String,
        arguments: JsonElement? = null,
        timeoutMs: Long = 15_000,
    ): JsonObject {
        val deferred = requestAsync(command, arguments)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            if (!deferred.isCompleted) deferred.cancel()
        }
    }

    suspend fun requestAsync(command: String, arguments: JsonElement? = null): Deferred<JsonObject> {
        check(isRunning) { "DAP process is not running" }
        require(command.isNotBlank() && command.length <= 200) { "Invalid DAP command" }
        val inFlight = pendingCount.incrementAndGet()
        if (inFlight > MAX_PENDING_REQUESTS) {
            pendingCount.decrementAndGet()
            error("Too many pending DAP requests")
        }
        val seq = nextSeq.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[seq] = deferred
        deferred.invokeOnCompletion {
            pending.remove(seq, deferred)
            pendingCount.decrementAndGet()
        }
        try {
            send(buildJsonObject {
                put("seq", seq)
                put("type", "request")
                put("command", command)
                if (arguments != null) put("arguments", arguments)
            })
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        }
        return deferred
    }

    private suspend fun send(obj: JsonObject) {
        val out = process?.stdin ?: error("DAP process is not running")
        val encoded = json.encodeToString(JsonObject.serializer(), obj)
        writeMutex.withLock {
            withContext(Dispatchers.IO) { ContentLengthProtocol.writeMessage(out, encoded) }
        }
    }

    private suspend fun readLoop(p: HostedStdioProcess) {
        var failure: Throwable? = null
        try {
            p.stdout.use { input ->
                while (currentCoroutineContext().isActive) {
                    val raw = ContentLengthProtocol.readMessage(input) ?: break
                    val obj = json.parseToJsonElement(raw) as? JsonObject ?: continue
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "response" -> handleResponse(obj)
                        "event" -> {
                            val name = obj["event"]?.jsonPrimitive?.contentOrNull ?: continue
                            _events.emit(Event(name, obj["body"]))
                        }
                        "request" -> handleReverseRequest(obj)
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            failure = t
        } finally {
            val reason = failure ?: IllegalStateException("Debug adapter exited")
            pending.values.forEach { it.completeExceptionally(reason) }
            pending.clear()
            _events.tryEmit(Event("terminated", buildJsonObject {
                put("reason", reason.message ?: "adapter exited")
            }))
        }
    }

    private fun handleResponse(obj: JsonObject) {
        val requestSeq = obj["request_seq"]?.jsonPrimitive?.intOrNull ?: return
        val waiter = pending.remove(requestSeq) ?: return
        val success = obj["success"]?.jsonPrimitive?.booleanOrNull ?: false
        if (success) {
            waiter.complete(obj)
        } else {
            val message = obj["message"]?.jsonPrimitive?.contentOrNull
                ?: (obj["body"] as? JsonObject)?.get("error")?.toString()
                ?: "DAP request failed"
            waiter.completeExceptionally(IllegalStateException(message.take(4_000)))
        }
    }

    private suspend fun handleReverseRequest(obj: JsonObject) {
        val requestSeq = obj["seq"]?.jsonPrimitive?.intOrNull ?: return
        val command = obj["command"]?.jsonPrimitive?.contentOrNull ?: return
        val response = try {
            reverseRequestHandler(command, obj["arguments"])
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            ReverseResponse(false, message = t.message ?: "Reverse request failed")
        }
        send(buildJsonObject {
            put("seq", nextSeq.getAndIncrement())
            put("type", "response")
            put("request_seq", requestSeq)
            put("success", response.success)
            put("command", command)
            response.message?.let { put("message", it.take(2_000)) }
            response.body?.let { put("body", it) }
        })
    }

    private companion object {
        const val MAX_PENDING_REQUESTS = 128
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
