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
    private val startMutex = Mutex()
    @Volatile private var closed = false
    private val nextSeq = AtomicInteger(1)
    private val pendingCount = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val _events = MutableSharedFlow<Event>(replay = 8, extraBufferCapacity = 248)
    val events: SharedFlow<Event> = _events

    @Volatile private var process: HostedStdioProcess? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var stderrJob: Job? = null
    private val _stderr = BoundedOutput(128 * 1024)
    private var omittedOutputEvents = 0L

    val isRunning: Boolean get() = process?.isAlive == true
    val stderr: String get() = _stderr.utf8()

    init {
        require(processHost.executionScope in setOf(ProcessExecutionScope.LOCAL_LINUX_ARM64, ProcessExecutionScope.DEVICE_ADB)) {
            "DAP protocol processes must execute in a bound Linux or Device Workstation backend"
        }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        startMutex.withLock {
            check(!closed) { "DapProcess has been closed; create a new session" }
            if (isRunning) return@withLock
            check(process == null) { "Previous protocol transport is shutting down" }
            require(argv.isNotEmpty()) { "Protocol argv must not be empty" }
            val p = processHost.start(argv, environment)
            var published = false
            try {
                currentCoroutineContext().ensureActive()
                synchronized(this@DapProcess) {
                    check(!closed && scope.isActive) { "Protocol session was canceled during startup" }
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
        command: String,
        arguments: JsonElement? = null,
        timeoutMs: Long = 15_000,
    ): JsonObject {
        require(timeoutMs in 1..300_000) { "Invalid DAP request timeout" }
        return withTimeout(timeoutMs) {
            val deferred = requestAsync(command, arguments)
            try { deferred.await() } finally {
                if (!deferred.isCompleted) deferred.cancel()
            }
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
        val encoded = json.encodeToString(JsonObject.serializer(), obj)
        writeMutex.withLock {
            val out = process?.stdin ?: error("Protocol process is not running")
            val writer = scope.async(Dispatchers.IO) { ContentLengthProtocol.writeMessage(out, encoded) }
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
                    val raw = ContentLengthProtocol.readMessage(input) ?: break
                    val obj = json.parseToJsonElement(raw) as? JsonObject ?: continue
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "response" -> handleResponse(obj)
                        "event" -> {
                            val name = obj["event"]?.jsonPrimitive?.contentOrNull ?: continue
                            publishEvent(name, obj["body"])
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
            synchronized(this@DapProcess) {
                if (process === p) close()
            }
            runCatching { p.close() }
            _events.tryEmit(Event("terminated", buildJsonObject {
                put("reason", reason.message ?: "adapter exited")
            }))
        }
    }

    private fun publishEvent(name: String, body: JsonElement?) {
        if (name != "output") {
            check(_events.tryEmit(Event(name, body))) { "Debug adapter event queue exceeded its safety limit" }
            return
        }
        val rendered = if (omittedOutputEvents > 0 && body is JsonObject) {
            val text = (body["output"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            JsonObject(body + ("output" to JsonPrimitive("[Droide: $omittedOutputEvents output event(s) omitted while the debugger UI was busy]\n$text")))
        } else body
        if (_events.tryEmit(Event(name, rendered))) omittedOutputEvents = 0
        else omittedOutputEvents = (omittedOutputEvents + 1).coerceAtMost(Long.MAX_VALUE - 1)
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

    @Synchronized
    override fun close() {
        closed = true
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
