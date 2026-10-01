package com.baystudio.droide.core
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
 
enum class DebugState { IDLE, STARTING, RUNNING, STOPPED, TERMINATED, ERROR }
data class DebugBreakpoint(
    val path: String,
     
    val line: Int,
    val verified: Boolean = false,
    val id: Int? = null,
    val message: String? = null,
     
    val requestedLine: Int = line,
)
data class DebugThread(val id: Int, val name: String)
data class DebugFrame(val id: Int, val name: String, val path: String?, val line: Int, val column: Int)
data class DebugVariable(
    val name: String,
    val value: String,
    val type: String?,
    val variablesReference: Int,
    val evaluateName: String? = null,
)
data class DebugWatch(
    val expression: String,
    val value: String = "",
    val type: String? = null,
    val variablesReference: Int = 0,
    val error: String? = null,
)

data class DebugConfiguration(
    val name: String,
    val request: String,
    val adapter: List<String>,
    val arguments: JsonObject,
    val source: String,
    val executionScope: ProcessExecutionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64,
    val adapterTransport: DebugAdapterTransport = DebugAdapterTransport.STDIO,
    val reverseTcpContract: AuthenticatedReverseTcpContract? = null,
    val androidJdwp: Boolean = false,
    val androidJdwpPid: Int? = null,
)

data class AndroidDebugProcess(val pid: Int, val processName: String?)


class DebugManager(
    private val scope: CoroutineScope,
    private val files: FileRepository,
    private val terminalManager: TerminalManager,
    private val executableDirs: List<File> = emptyList(),
    private val stateStore: DebugStateStore? = null,
    private val remoteProcessHost: StdioProcessHost? = null,
    private val deviceBridge: DeviceBridgeManager? = null,
    private val extraDebugAdapters: () -> List<DebugAdapterRegistry.Spec> = { emptyList() },
) : AutoCloseable {
    private val root = files.root
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val sessionMutex = Mutex()
    private var dap: DapProcess? = null
    private var eventJob: Job? = null
    private var launchJob: Deferred<JsonObject>? = null
    private var initializedSignal = CompletableDeferred<Unit>()
    private var activeConfig: DebugConfiguration? = null
    private var activePathMapper: WorkspacePathMapper? = null
    private var activeJdwpProxy: AndroidJdwpProxy? = null
    private var capabilities: JsonObject = buildJsonObject {}
    private val stopGeneration = AtomicLong(0L)

    private val _state = MutableStateFlow(DebugState.IDLE)
    val state: StateFlow<DebugState> = _state.asStateFlow()
    private val _status = MutableStateFlow("No debug session")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()
    private val _breakpoints = MutableStateFlow<Map<String, List<DebugBreakpoint>>>(emptyMap())
    val breakpoints: StateFlow<Map<String, List<DebugBreakpoint>>> = _breakpoints.asStateFlow()
    private val _threads = MutableStateFlow<List<DebugThread>>(emptyList())
    val threads: StateFlow<List<DebugThread>> = _threads.asStateFlow()
    private val _frames = MutableStateFlow<List<DebugFrame>>(emptyList())
    val frames: StateFlow<List<DebugFrame>> = _frames.asStateFlow()
    private val _variables = MutableStateFlow<List<DebugVariable>>(emptyList())
    val variables: StateFlow<List<DebugVariable>> = _variables.asStateFlow()
    private val _watches = MutableStateFlow<List<DebugWatch>>(emptyList())
    val watches: StateFlow<List<DebugWatch>> = _watches.asStateFlow()
    private val _selectedThread = MutableStateFlow<Int?>(null)
    val selectedThread: StateFlow<Int?> = _selectedThread.asStateFlow()
    private val _selectedFrame = MutableStateFlow<Int?>(null)
    val selectedFrame: StateFlow<Int?> = _selectedFrame.asStateFlow()
    private val _lastStop = MutableStateFlow<DebugStopSnapshot?>(null)
    val lastStop: StateFlow<DebugStopSnapshot?> = _lastStop.asStateFlow()

    suspend fun restoreState() {
        val saved = stateStore?.load() ?: return
        val restoredBreakpoints = linkedMapOf<String, List<DebugBreakpoint>>()
        for ((path, lines) in saved.breakpoints) {
            val safe = runCatching { files.resolveChecked(path) }.getOrNull() ?: continue
            if (!PathSecurity.contains(root, safe)) continue
            restoredBreakpoints[path] = lines.map { DebugBreakpoint(path, it) }
        }
        _breakpoints.value = restoredBreakpoints
        _watches.value = saved.watches.map { DebugWatch(it) }
    }

    private fun persistStateAsync() {
        val store = stateStore ?: return
        val snapshot = DebugPersistentState(
            breakpoints = _breakpoints.value.mapValues { (_, list) -> list.map { it.requestedLine } },
            watches = _watches.value.map { it.expression },
        )
        scope.launch {
            runSuspendCatching { store.save(snapshot) }.onFailure {
                appendOutput("[debug state] Save failed: ${it.message ?: "unknown error"}\n")
            }
        }
    }

     
    suspend fun androidJdwpProcesses(): List<AndroidDebugProcess> = withContext(Dispatchers.IO) {
        val bridge = deviceBridge ?: return@withContext emptyList()
        if (bridge.state.value.connected == null) return@withContext emptyList()
        val pids = runSuspendCatching { bridge.jdwpPids() }.getOrDefault(emptyList()).take(128)
        if (pids.isEmpty()) return@withContext emptyList()
        val command = buildString {
            append("for p in ")
            append(pids.joinToString(" "))
            append("; do n=$(cat /proc/${'$'}p/cmdline 2>/dev/null | toybox tr '\\000' ' ' | toybox head -c 240); printf '%s\\t%s\\n' \"${'$'}p\" \"${'$'}n\"; done")
        }
        val names = runSuspendCatching { bridge.shellBounded(command, maxOutputBytes = 64 * 1024) }
            .getOrNull()?.stdout.orEmpty().lineSequence().mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                val pid = parts.firstOrNull()?.trim()?.toIntOrNull() ?: return@mapNotNull null
                pid to parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }.toMap()
        pids.map { AndroidDebugProcess(it, names[it]) }
    }


    suspend fun hasAndroidAttachProvider(activeFile: String): Boolean =
        androidAttachConfigurations(activeFile, 1).isNotEmpty()

    suspend fun androidAttachConfigurations(activeFile: String, pid: Int): List<DebugConfiguration> = withContext(Dispatchers.IO) {
        require(pid > 0) { "Invalid JDWP pid" }
        val custom = loadCustomConfigurations(activeFile, androidPid = pid, androidOnly = true)
        val builtIn = mutableListOf<DebugConfiguration>()
        val remote = remoteProcessHost
        if (remote != null && activeFile.substringAfterLast('.', "").lowercase() in setOf("kt", "kts")) {
            val executable = runSuspendCatching { remote.resolveExecutable("kotlin-debug-adapter") }.getOrNull()
            val mapper = runSuspendCatching { remote.pathMapper() }.getOrNull()
            if (executable != null && mapper != null) {
                builtIn += DebugConfiguration(
                    name = "Kotlin/JDWP attach (Workstation)",
                    request = "attach",
                    adapter = listOf(executable),
                    arguments = buildJsonObject {
                        put("projectRoot", mapper.remoteRoot)
                        put("hostName", "${'$'}{jdwpHost}")
                        put("port", "${'$'}{jdwpPort}")
                        put("timeout", 30_000)
                    },
                    source = "built-in:user-installed-kotlin-debug-adapter",
                    executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64,
                    androidJdwp = true,
                    androidJdwpPid = pid,
                )
            }
        }
        (custom + builtIn).distinctBy { Triple(it.name, it.executionScope, it.adapter) }
    }

    suspend fun configurations(activeFile: String): List<DebugConfiguration> = withContext(Dispatchers.IO) {
        val custom = loadCustomConfigurations(activeFile, androidPid = null, androidOnly = false)
        val builtIn = mutableListOf<DebugConfiguration>()
        for (spec in DebugAdapterRegistry.forPath(activeFile, extraDebugAdapters())) {
            // Never prefer an app-local binary: workspace/debugger code must not inherit Droide's application UID.

            val remote = remoteProcessHost ?: continue
            val candidate = resolveRemoteCommand(spec.commandCandidates, remote) ?: continue
            val mapper = runSuspendCatching { remote.pathMapper() }.getOrNull() ?: continue
            builtIn += DebugConfiguration(
                name = "${spec.id} (Workstation): ${activeFile.substringAfterLast('/')}",
                request = "launch",
                adapter = candidate,
                arguments = mapJsonPathsToRemote(spec.defaultArguments(activeFile, root), mapper) as JsonObject,
                source = spec.source,
                executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64,
                adapterTransport = spec.transport, reverseTcpContract = spec.reverseTcp,
            )
        }
        (custom + builtIn).distinctBy { Triple(it.name, it.executionScope, it.adapter) }
    }

    fun addBreakpoint(path: String, line: Int) {
        require(line >= 1) { "Breakpoint line must be >= 1" }
        files.resolveChecked(path)
        val current = _breakpoints.value[path].orEmpty()
        if (current.any { it.requestedLine == line || it.line == line }) return
        _breakpoints.value = _breakpoints.value.toMutableMap().apply {
            put(path, (current + DebugBreakpoint(path, line)).sortedBy { it.line })
        }
        persistStateAsync()
        scope.launch { if (_state.value != DebugState.IDLE && _state.value != DebugState.TERMINATED) syncBreakpoints(path) }
    }

    fun toggleBreakpoint(path: String, line: Int) {
        val current = _breakpoints.value[path].orEmpty()
        if (current.any { it.requestedLine == line || it.line == line }) {
            _breakpoints.value = _breakpoints.value.toMutableMap().apply {
                put(path, current.filterNot { it.requestedLine == line || it.line == line })
            }
            persistStateAsync()
            scope.launch { if (_state.value != DebugState.IDLE && _state.value != DebugState.TERMINATED) syncBreakpoints(path) }
        } else addBreakpoint(path, line)
    }

    fun clearBreakpoint(path: String, line: Int) {
        val current = _breakpoints.value[path].orEmpty()
        _breakpoints.value = _breakpoints.value.toMutableMap().apply {
            put(path, current.filterNot { it.requestedLine == line || it.line == line })
        }
        persistStateAsync()
        scope.launch { if (_state.value != DebugState.IDLE && _state.value != DebugState.TERMINATED) syncBreakpoints(path) }
    }

    fun breakpointForRequestedLine(path: String, line: Int): DebugBreakpoint? =
        _breakpoints.value[path].orEmpty().firstOrNull { it.requestedLine == line }

    suspend fun start(config: DebugConfiguration) = sessionMutex.withLock {
        stopLocked(terminateDebuggee = true)
        _state.value = DebugState.STARTING
        _status.value = "Starting ${config.name}…"
        _output.value = ""
        _threads.value = emptyList()
        _frames.value = emptyList()
        _variables.value = emptyList()
        _lastStop.value = null
        var effectiveConfig = config
        if (config.androidJdwp) {
            val pid = requireNotNull(config.androidJdwpPid) { "Android JDWP attach requires a selected process" }
            val bridge = deviceBridge ?: error("Device Bridge is unavailable")
            check(bridge.state.value.connected != null) { "Device Workstation is not connected" }
            val proxy = AndroidJdwpProxy(AndroidJdwpTransport(bridge), scope)
            val endpoint = proxy.start(pid)
            activeJdwpProxy = proxy
            effectiveConfig = config.copy(
                adapter = config.adapter.map { replaceJdwpString(it, endpoint) },
                arguments = replaceJdwpElement(config.arguments, endpoint) as JsonObject,
            )
        }
        activeConfig = effectiveConfig
        initializedSignal = CompletableDeferred()

        check(effectiveConfig.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) {
            "IDE-owned debug adapters must execute in the selected Linux backend"
        }
        val baseHost: StdioProcessHost = remoteProcessHost
            ?: error("Local Linux debug host is unavailable")
        check(baseHost.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) {
            "Debug host does not match the selected backend"
        }
        val host = baseHost.forDebugAdapterTransport(
            scope, effectiveConfig.adapterTransport, effectiveConfig.reverseTcpContract,
        )
        activePathMapper = if (effectiveConfig.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) {
            host.pathMapper() ?: error("Local Linux path mapper is unavailable")
        } else null
        val process = DapProcess(
            scope = scope,
            workDir = root,
            argv = effectiveConfig.adapter,
            processHost = host,
            reverseRequestHandler = ::handleReverseRequest,
        )
        try {
            process.start()
            dap = process
            eventJob = scope.launch {
                try { process.events.collect(::handleEvent) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    _state.value = DebugState.ERROR
                    _status.value = "Debug event failed: ${failure.message ?: failure::class.java.simpleName}"
                    process.close()
                }
            }
            val initResponse = process.request("initialize", buildJsonObject {
                put("clientID", "droide")
                put("clientName", "Droide")
                put("adapterID", effectiveConfig.name.take(120))
                put("locale", "en-US")
                put("linesStartAt1", true)
                put("columnsStartAt1", true)
                put("pathFormat", "path")
                put("supportsVariableType", true)
                put("supportsVariablePaging", true)
                put("supportsRunInTerminalRequest", true)
                put("supportsMemoryReferences", false)
                put("supportsProgressReporting", false)
                put("supportsInvalidatedEvent", true)
            }, timeoutMs = 12_000)
            capabilities = initResponse["body"] as? JsonObject ?: buildJsonObject {}

            
            launchJob = process.requestAsync(effectiveConfig.request, effectiveConfig.arguments)
            withTimeout(12_000) { initializedSignal.await() }
            _breakpoints.value.keys.toList().forEach { syncBreakpoints(it) }
            process.request("setExceptionBreakpoints", buildJsonObject {
                put("filters", buildJsonArray {})
            }, timeoutMs = 5_000)
            if (capabilities["supportsConfigurationDoneRequest"]?.jsonPrimitive?.booleanOrNull == true) {
                process.request("configurationDone", buildJsonObject {}, timeoutMs = 8_000)
            }
            withTimeout(20_000) { launchJob?.await() }
            

            when (_state.value) {
                DebugState.STARTING -> {
                    _state.value = DebugState.RUNNING
                    _status.value = "Debugging ${effectiveConfig.name}"
                }
                DebugState.RUNNING, DebugState.STOPPED -> Unit
                DebugState.TERMINATED, DebugState.ERROR -> error("Debug session ended during startup")
                DebugState.IDLE -> error("Debug session became idle during startup")
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { stopLocked(terminateDebuggee = true) }
            throw cancel
        } catch (t: Throwable) {
            val stderr = process.stderr.takeLast(4_000)
            _state.value = DebugState.ERROR
            _status.value = buildString {
                append(t.message ?: "Debug adapter failed")
                if (stderr.isNotBlank()) append("\n").append(stderr)
            }.take(6_000)
            withContext(NonCancellable) { stopLocked(terminateDebuggee = true, preserveError = true) }
            throw t
        }
    }

    suspend fun stop(terminateDebuggee: Boolean = true) = sessionMutex.withLock {
        stopLocked(terminateDebuggee)
    }

    suspend fun shutdown() = sessionMutex.withLock {
        withContext(NonCancellable) { stopLocked(terminateDebuggee = true) }
    }

    suspend fun continueExecution(threadId: Int? = _selectedThread.value) {
        val id = threadId ?: refreshThreads().firstOrNull()?.id ?: error("No debug thread")
        request("continue", buildJsonObject { put("threadId", id) })
        _state.value = DebugState.RUNNING
        _status.value = "Running"
        _watches.value = _watches.value.map { it.copy(value = "", type = null, variablesReference = 0, error = null) }
    }

    suspend fun pause(threadId: Int? = _selectedThread.value) {
        val id = threadId ?: refreshThreads().firstOrNull()?.id ?: error("No debug thread")
        request("pause", buildJsonObject { put("threadId", id) })
    }

    suspend fun next(threadId: Int? = _selectedThread.value) = step("next", threadId)
    suspend fun stepIn(threadId: Int? = _selectedThread.value) = step("stepIn", threadId)
    suspend fun stepOut(threadId: Int? = _selectedThread.value) = step("stepOut", threadId)

    suspend fun refreshThreads(): List<DebugThread> {
        val response = request("threads", buildJsonObject {})
        val items = ((response["body"] as? JsonObject)?.get("threads") as? JsonArray).orEmpty()
        val parsed = items.take(512).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            DebugThread(id, o["name"]?.jsonPrimitive?.contentOrNull ?: "Thread $id")
        }
        _threads.value = parsed
        if (_selectedThread.value !in parsed.map { it.id }) _selectedThread.value = parsed.firstOrNull()?.id
        return parsed
    }

    suspend fun selectThread(threadId: Int) {
        _selectedThread.value = threadId
        refreshStack(threadId)
    }

    suspend fun refreshStack(threadId: Int = _selectedThread.value ?: error("No thread selected"), startFrame: Int = 0, levels: Int = 200): List<DebugFrame> {
        require(startFrame >= 0 && levels in 1..500) { "Invalid stack paging" }
        val response = request("stackTrace", buildJsonObject {
            put("threadId", threadId)
            put("startFrame", startFrame)
            put("levels", levels)
        })
        val items = ((response["body"] as? JsonObject)?.get("stackFrames") as? JsonArray).orEmpty()
        val parsed = items.take(levels).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val source = o["source"] as? JsonObject
            val rawPath = source?.get("path")?.jsonPrimitive?.contentOrNull
            val relative = rawPath?.let(::relativePathOrNull)
            DebugFrame(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: "Frame $id",
                path = relative,
                line = o["line"]?.jsonPrimitive?.intOrNull ?: 1,
                column = o["column"]?.jsonPrimitive?.intOrNull ?: 1,
            )
        }
        _frames.value = parsed
        _selectedFrame.value = parsed.firstOrNull()?.id
        parsed.firstOrNull()?.let { refreshFrameVariables(it.id) }
        return parsed
    }

    suspend fun selectFrame(frameId: Int) {
        _selectedFrame.value = frameId
        refreshFrameVariables(frameId)
        refreshWatches()
    }

    suspend fun refreshFrameVariables(frameId: Int = _selectedFrame.value ?: error("No frame selected")): List<DebugVariable> {
        val scopesResponse = request("scopes", buildJsonObject { put("frameId", frameId) })
        val scopes = ((scopesResponse["body"] as? JsonObject)?.get("scopes") as? JsonArray).orEmpty()
        val out = mutableListOf<DebugVariable>()
        for (scopeEl in scopes.take(32)) {
            val scopeObj = scopeEl as? JsonObject ?: continue
            val ref = scopeObj["variablesReference"]?.jsonPrimitive?.intOrNull ?: 0
            if (ref == 0) continue
            val scopeName = scopeObj["name"]?.jsonPrimitive?.contentOrNull ?: "Scope"
            out += DebugVariable("[$scopeName]", "", null, ref)
            out += variables(ref).take(500)
            if (out.size >= 2_000) break
        }
        _variables.value = out.take(2_000)
        return _variables.value
    }

    suspend fun variables(reference: Int, start: Int? = null, count: Int? = null): List<DebugVariable> {
        require(reference > 0) { "variablesReference must be > 0" }
        val response = request("variables", buildJsonObject {
            put("variablesReference", reference)
            start?.let { put("start", it.coerceAtLeast(0)) }
            count?.let { put("count", it.coerceIn(1, 2_000)) }
        })
        val items = ((response["body"] as? JsonObject)?.get("variables") as? JsonArray).orEmpty()
        return items.take(2_000).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            DebugVariable(
                name = o["name"]?.jsonPrimitive?.contentOrNull?.take(500) ?: return@mapNotNull null,
                value = o["value"]?.jsonPrimitive?.contentOrNull?.take(4_000) ?: "",
                type = o["type"]?.jsonPrimitive?.contentOrNull?.take(500),
                variablesReference = o["variablesReference"]?.jsonPrimitive?.intOrNull ?: 0,
                evaluateName = o["evaluateName"]?.jsonPrimitive?.contentOrNull?.take(1_000),
            )
        }
    }

    fun addWatch(expression: String) {
        val clean = expression.trim().take(20_000)
        require(clean.isNotBlank()) { "Watch expression is empty" }
        if (_watches.value.any { it.expression == clean }) return
        _watches.value = (_watches.value + DebugWatch(clean)).takeLast(200)
        persistStateAsync()
        if (_state.value == DebugState.STOPPED) scope.launch { refreshWatches() }
    }

    fun removeWatch(expression: String) {
        _watches.value = _watches.value.filterNot { it.expression == expression }
        persistStateAsync()
    }

    fun clearWatches() {
        _watches.value = emptyList()
        persistStateAsync()
    }

    suspend fun refreshWatches() {
        if (_state.value != DebugState.STOPPED) return
        val frameId = _selectedFrame.value
        _watches.value = _watches.value.map { watch -> evaluateWatch(watch.expression, frameId) }
    }

    suspend fun evaluate(expression: String, frameId: Int? = _selectedFrame.value, context: String = "repl"): String {
        val body = evaluateBody(expression, frameId, context)
        return body["result"]?.jsonPrimitive?.contentOrNull.orEmpty().take(20_000)
    }

    private suspend fun evaluateWatch(expression: String, frameId: Int?): DebugWatch = try {
        val body = evaluateBody(expression, frameId, "watch")
        DebugWatch(
            expression = expression,
            value = body["result"]?.jsonPrimitive?.contentOrNull.orEmpty().take(20_000),
            type = body["type"]?.jsonPrimitive?.contentOrNull?.take(500),
            variablesReference = body["variablesReference"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        DebugWatch(expression = expression, error = (t.message ?: "Evaluation failed").take(2_000))
    }

    private suspend fun evaluateBody(expression: String, frameId: Int?, context: String): JsonObject {
        require(expression.isNotBlank() && expression.length <= 20_000) { "Invalid debug expression" }
        val response = request("evaluate", buildJsonObject {
            put("expression", expression)
            frameId?.let { put("frameId", it) }
            put("context", context)
        })
        return response["body"] as? JsonObject ?: buildJsonObject {}
    }

    private suspend fun step(command: String, threadId: Int?) {
        val id = threadId ?: _selectedThread.value ?: error("No debug thread")
        request(command, buildJsonObject { put("threadId", id) })
        _state.value = DebugState.RUNNING
        _status.value = "Running"
        _watches.value = _watches.value.map { it.copy(value = "", type = null, variablesReference = 0, error = null) }
    }

    private suspend fun syncBreakpoints(path: String) {
        val process = dap ?: return
        val sourceFile = files.resolveChecked(path)
        val requested = _breakpoints.value[path].orEmpty().sortedBy { it.line }
        val response = process.request("setBreakpoints", buildJsonObject {
            put("source", buildJsonObject {
                put("name", sourceFile.name)
                put("path", activePathMapper?.localToRemote(sourceFile) ?: sourceFile.absolutePath)
            })
            put("breakpoints", buildJsonArray {
                requested.forEach { bp -> add(buildJsonObject { put("line", bp.line) }) }
            })
            put("sourceModified", false)
        }, timeoutMs = 8_000)
        val returned = ((response["body"] as? JsonObject)?.get("breakpoints") as? JsonArray).orEmpty()
        val merged = requested.mapIndexed { index, bp ->
            val obj = returned.getOrNull(index) as? JsonObject
            bp.copy(
                verified = obj?.get("verified")?.jsonPrimitive?.booleanOrNull ?: false,
                id = obj?.get("id")?.jsonPrimitive?.intOrNull,
                line = obj?.get("line")?.jsonPrimitive?.intOrNull ?: bp.line,
                message = obj?.get("message")?.jsonPrimitive?.contentOrNull?.take(1_000),
            )
        }
        _breakpoints.value = _breakpoints.value.toMutableMap().apply { put(path, merged) }
    }

    private suspend fun handleEvent(event: DapProcess.Event) {
        when (event.name) {
            "initialized" -> {
                _status.value = "Debugger initialized"
                initializedSignal.complete(Unit)
            }
            "output" -> {
                val body = event.body as? JsonObject
                val category = body?.get("category")?.jsonPrimitive?.contentOrNull
                val text = body?.get("output")?.jsonPrimitive?.contentOrNull.orEmpty()
                appendOutput(if (category.isNullOrBlank()) text else "[$category] $text")
            }
            "stopped" -> {
                val body = event.body as? JsonObject
                val threadId = body?.get("threadId")?.jsonPrimitive?.intOrNull
                val reason = body?.get("reason")?.jsonPrimitive?.contentOrNull?.take(120) ?: "stopped"
                val description = (body?.get("description") ?: body?.get("text"))
                    ?.jsonPrimitive?.contentOrNull?.take(1_000)
                val hitBreakpointIds = (body?.get("hitBreakpointIds") as? JsonArray)
                    .orEmpty().take(128).mapNotNull { it.jsonPrimitive.intOrNull }
                _lastStop.value = DebugStopSnapshot(
                    generation = stopGeneration.incrementAndGet(),
                    reason = reason,
                    description = description,
                    threadId = threadId,
                    hitBreakpointIds = hitBreakpointIds,
                )
                _state.value = DebugState.STOPPED
                _status.value = "Paused: $reason"
                refreshThreads()
                val selected = threadId ?: _selectedThread.value ?: _threads.value.firstOrNull()?.id
                if (selected != null) {
                    _selectedThread.value = selected
                    refreshStack(selected)
                }
                refreshWatches()
            }
            "continued" -> {
                _state.value = DebugState.RUNNING
                _status.value = "Running"
                _watches.value = _watches.value.map { it.copy(value = "", type = null, variablesReference = 0, error = null) }
            }
            "thread" -> if (_state.value == DebugState.STOPPED) refreshThreads()
            "breakpoint" -> consumeBreakpointEvent(event.body)
            "terminated", "exited" -> {
                _state.value = DebugState.TERMINATED
                _status.value = if (event.name == "exited") {
                    val code = (event.body as? JsonObject)?.get("exitCode")?.jsonPrimitive?.intOrNull
                    if (code == null) "Debuggee exited" else "Debuggee exited ($code)"
                } else "Debug session terminated"
                _threads.value = emptyList()
                _frames.value = emptyList()
                _variables.value = emptyList()
                // Tear the transport down outside this collector so cancelling eventJob cannot cancel the collector itself mid-event.

                scope.launch {
                    sessionMutex.withLock {
                        if (_state.value == DebugState.TERMINATED) {
                            stopLocked(terminateDebuggee = false, preserveTerminalState = true)
                        }
                    }
                }
            }
        }
    }

    private fun consumeBreakpointEvent(bodyEl: JsonElement?) {
        val body = bodyEl as? JsonObject ?: return
        val bp = body["breakpoint"] as? JsonObject ?: return
        val id = bp["id"]?.jsonPrimitive?.intOrNull ?: return
        val current = _breakpoints.value.toMutableMap()
        for ((path, list) in current) {
            val updated = list.map { old ->
                if (old.id != id) old else old.copy(
                    verified = bp["verified"]?.jsonPrimitive?.booleanOrNull ?: old.verified,
                    line = bp["line"]?.jsonPrimitive?.intOrNull ?: old.line,
                    message = bp["message"]?.jsonPrimitive?.contentOrNull ?: old.message,
                )
            }
            current[path] = updated
        }
        _breakpoints.value = current
    }

    private suspend fun handleReverseRequest(command: String, arguments: JsonElement?): DapProcess.ReverseResponse = when (command) {
        "runInTerminal" -> {
            val args = arguments as? JsonObject
            val rawArgs = args?.get("args") as? JsonArray
            val argv = rawArgs?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull }
            val rawCwd = args?.get("cwd")
            val cwdRaw = (rawCwd as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            val rawEnv = args?.get("env")
            val malformedEnv = (rawEnv as? JsonObject)?.values?.any { value ->
                value != JsonNull && (value !is JsonPrimitive || !value.isString)
            } == true
            val environment = (rawEnv as? JsonObject).orEmpty().map { (key, value) ->
                key to (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            }
            if (argv.isNullOrEmpty() || argv.size > 256 || argv.any { it == null || it.length > 32_000 || '\u0000' in it } ||
                (rawCwd != null && cwdRaw == null) || (rawEnv != null && rawEnv !is JsonObject) ||
                malformedEnv || environment.size > 128 || environment.any { (key, value) ->
                    !key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) ||
                        (value != null && (value.length > 32_000 || '\u0000' in value))
                }
            ) {
                DapProcess.ReverseResponse(false, message = "runInTerminal did not include a safe argv")
            } else {
                runSuspendCatching {
                    val safeArgv = argv.orEmpty().filterNotNull()
                    check(activeConfig?.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64) {
                        "runInTerminal requires the active local Linux debug backend"
                    }
                    val mapper = activePathMapper ?: error("Local Linux debug path mapper unavailable")
                    val target = when {
                        cwdRaw.isNullOrBlank() -> mapper.remoteRoot
                        cwdRaw.startsWith("file:") -> mapper.uriToLocal(cwdRaw)?.let(mapper::localToRemote)
                            ?: error("Debugger requested terminal outside workspace")
                        mapper.remoteToLocal(cwdRaw) != null -> cwdRaw
                        File(cwdRaw).isAbsolute -> mapper.localToRemote(safeWorkingDirectory(cwdRaw))
                        else -> mapper.localRelativeToRemote(cwdRaw)
                    }
                    require(mapper.remoteToLocal(target)?.isDirectory == true) {
                        "Debugger requested terminal outside workspace"
                    }
                    val shellCommand = buildString {
                        append("cd -- ").append(LocalExecutionSubstrate.shellQuote(target)).append(" || exit; ")
                        environment.forEach { (key, value) ->
                            if (value == null) append("unset ").append(key).append("; ")
                            else append("export ").append(key).append('=').append(LocalExecutionSubstrate.shellQuote(value)).append("; ")
                        }
                        append("exec ")
                        append(safeArgv.joinToString(" ") { LocalExecutionSubstrate.shellQuote(it) })
                    }
                    val terminal = terminalManager.createLinuxTerminal(title = "debug", activate = true)
                    terminal.session.send(shellCommand)
                    DapProcess.ReverseResponse(true, buildJsonObject {})
                }.getOrElse { DapProcess.ReverseResponse(false, message = it.message ?: "Could not open debug terminal") }
            }
        }
        else -> DapProcess.ReverseResponse(false, message = "Unsupported reverse DAP request: $command")
    }

    private fun safeWorkingDirectory(raw: String): File {
        val f = File(raw)
        val resolved = if (f.isAbsolute) f.canonicalFile else File(root, raw).canonicalFile
        require(PathSecurity.contains(root, resolved)) { "Debugger requested terminal outside workspace" }
        require(resolved.isDirectory) { "Debugger working directory does not exist" }
        return resolved
    }

    private suspend fun request(command: String, args: JsonElement?): JsonObject =
        dap?.request(command, args, timeoutMs = 12_000) ?: error("No active debug adapter")

    private suspend fun stopLocked(
        terminateDebuggee: Boolean,
        preserveError: Boolean = false,
        preserveTerminalState: Boolean = false,
    ) {
        val process = dap
        try {
            if (process != null && process.isRunning) {
                runSuspendCatching {
                    val supportsTerminate = capabilities["supportsTerminateRequest"]?.jsonPrimitive?.booleanOrNull == true
                    if (terminateDebuggee && supportsTerminate) {
                        process.request("terminate", buildJsonObject {}, timeoutMs = 3_000)
                    } else {
                        process.request(
                            "disconnect",
                            buildJsonObject { put("terminateDebuggee", terminateDebuggee) },
                            timeoutMs = 3_000,
                        )
                    }
                }
            }
        } finally {
            // Cleanup must finish even if the caller is already cancelled while waiting for DAP.
            withContext(NonCancellable) {
                launchJob?.cancel()
                launchJob = null
                eventJob?.cancel()
                eventJob = null
                process?.close()
                if (dap === process) dap = null
                activeConfig = null
                activePathMapper = null
                activeJdwpProxy?.close()
                activeJdwpProxy = null
                capabilities = buildJsonObject {}
                _threads.value = emptyList()
                _frames.value = emptyList()
                _variables.value = emptyList()
                _selectedThread.value = null
                _selectedFrame.value = null
                if (!preserveError && !preserveTerminalState) {
                    _state.value = DebugState.IDLE
                    _status.value = "No debug session"
                }
            }
        }
    }

    private fun appendOutput(text: String) {
        if (text.isEmpty()) return
        _output.value = (_output.value + text).takeLast(200_000)
    }

    private fun resolveCommand(candidates: List<List<String>>): List<String>? {
        for (candidate in candidates) {
            if (candidate.isEmpty()) continue
            val exe = ExecutableResolver.resolve(candidate.first(), executableDirs) ?: continue
            return listOf(exe) + candidate.drop(1)
        }
        return null
    }

    private suspend fun loadCustomConfigurations(
        activeFile: String,
        androidPid: Int?,
        androidOnly: Boolean,
    ): List<DebugConfiguration> {
        val rel = ".droide/launch.json"
        val launch = runCatching { files.resolveChecked(rel) }.getOrNull() ?: return emptyList()
        if (!launch.isFile || launch.length() !in 1..256_000) return emptyList()
        val rootObj = runCatching { json.parseToJsonElement(launch.readText()) as? JsonObject }.getOrNull() ?: return emptyList()
        val array = rootObj["configurations"] as? JsonArray ?: return emptyList()
        return array.take(64).mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.take(200) ?: return@mapNotNull null
            val request = obj["request"]?.jsonPrimitive?.contentOrNull?.takeIf { it == "launch" || it == "attach" } ?: return@mapNotNull null
            val androidJdwp = obj["androidJdwp"]?.jsonPrimitive?.booleanOrNull == true
            if (androidOnly != androidJdwp) return@mapNotNull null
            if (androidJdwp && androidPid == null) return@mapNotNull null
            val adapterId = obj["adapterId"]?.jsonPrimitive?.contentOrNull?.take(100)
            val adapterRaw = obj["adapter"] as? JsonArray
            if ((adapterId == null) == (adapterRaw == null)) return@mapNotNull null
            val spec = adapterId?.let { DebugAdapterRegistry.byId(it, extraDebugAdapters()) } ?: if (adapterId != null) return@mapNotNull null else null
            val templates = if (spec != null) {
                spec.commandCandidates
            } else {
                val raw = adapterRaw?.mapNotNull { it.jsonPrimitive.contentOrNull?.take(2_000) }.orEmpty()
                if (raw.isEmpty() || raw.size > 64) return@mapNotNull null
                listOf(raw)
            }
            val requestedScope = obj["executionScope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            val expandedCandidates = templates.map { candidate -> candidate.map { expandString(it, activeFile, null) } }
            // Project configuration cannot redirect backend authority.
            if (requestedScope != null && requestedScope !in setOf("local", "local_linux_arm64")) return@mapNotNull null
            val remote = remoteProcessHost ?: return@mapNotNull null
            val remoteAdapter = resolveRemoteCommand(expandedCandidates, remote) ?: return@mapNotNull null
            val mapper = runSuspendCatching { remote.pathMapper() }.getOrNull() ?: return@mapNotNull null
            val mappedAdapter = listOf(remoteAdapter.first()) + remoteAdapter.drop(1).map(mapper::toRemoteProtocolString)
            val args = mapJsonPathsToRemote(
                expandElement(obj["arguments"] as? JsonObject ?: buildJsonObject {}, activeFile),
                mapper,
            ) as JsonObject
            DebugConfiguration(
                name, request, mappedAdapter, args, source = rel,
                executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64,
                androidJdwp = androidJdwp, androidJdwpPid = androidPid,
                adapterTransport = spec?.transport ?: DebugAdapterTransport.STDIO, reverseTcpContract = spec?.reverseTcp,
            )
        }
    }

    private suspend fun resolveRemoteCommand(
        candidates: List<List<String>>,
        host: StdioProcessHost,
    ): List<String>? {
        for (candidate in candidates) {
            if (candidate.isEmpty()) continue
            val executable = runSuspendCatching { host.resolveExecutable(candidate.first()) }.getOrNull() ?: continue
            return listOf(executable) + candidate.drop(1)
        }
        return null
    }

    private fun mapJsonPathsToRemote(el: JsonElement, mapper: WorkspacePathMapper): JsonElement = when (el) {
        is JsonPrimitive -> if (el.isString) JsonPrimitive(mapper.toRemoteProtocolString(el.content)) else el
        is JsonArray -> JsonArray(el.map { mapJsonPathsToRemote(it, mapper) })
        is JsonObject -> JsonObject(el.mapValues { mapJsonPathsToRemote(it.value, mapper) })
        else -> el
    }

    private fun expandElement(el: JsonElement, activeFile: String): JsonElement = when (el) {
        is JsonPrimitive -> if (el.isString) JsonPrimitive(expandString(el.content, activeFile, null)) else el
        is JsonArray -> JsonArray(el.map { expandElement(it, activeFile) })
        is JsonObject -> JsonObject(el.mapValues { expandElement(it.value, activeFile) })
        else -> el
    }

    private fun expandString(value: String, activeFile: String, port: Int?): String {
        val active = files.resolveChecked(activeFile)
        return value
            .replace("${'$'}{workspaceFolder}", root.absolutePath)
            .replace("${'$'}{file}", active.absolutePath)
            .replace("${'$'}{relativeFile}", activeFile)
            .replace("${'$'}{fileDirname}", active.parentFile?.absolutePath ?: root.absolutePath)
            .replace("${'$'}{fileBasename}", active.name)
            .replace("${'$'}{port}", port?.toString() ?: "")
    }

    private fun replaceJdwpString(value: String, endpoint: AndroidJdwpProxy.Endpoint): String = value
        .replace("${'$'}{jdwpHost}", endpoint.host)
        .replace("${'$'}{jdwpPort}", endpoint.port.toString())
        .replace("${'$'}{jdwpPid}", endpoint.pid.toString())

    private fun replaceJdwpElement(element: JsonElement, endpoint: AndroidJdwpProxy.Endpoint): JsonElement = when (element) {
        is JsonPrimitive -> if (!element.isString) element else when (element.content) {
            "${'$'}{jdwpPort}" -> JsonPrimitive(endpoint.port)
            "${'$'}{jdwpPid}" -> JsonPrimitive(endpoint.pid)
            else -> JsonPrimitive(replaceJdwpString(element.content, endpoint))
        }
        is JsonArray -> JsonArray(element.map { replaceJdwpElement(it, endpoint) })
        is JsonObject -> JsonObject(element.mapValues { replaceJdwpElement(it.value, endpoint) })
        else -> element
    }

    private fun relativePathOrNull(raw: String): String? {
        activePathMapper?.remoteToLocal(raw)?.let { file ->
            return file.relativeTo(root.canonicalFile).invariantSeparatorsPath
        }
        val file = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        if (!PathSecurity.contains(root, file)) return null
        return file.relativeTo(root.canonicalFile).invariantSeparatorsPath
    }

    override fun close() {
        launchJob?.cancel()
        launchJob = null
        eventJob?.cancel()
        eventJob = null
        dap?.close()
        dap = null
        activeConfig = null
        activePathMapper = null
        activeJdwpProxy?.close()
        activeJdwpProxy = null
        capabilities = buildJsonObject {}
        _lastStop.value = null
        _state.value = DebugState.IDLE
        _status.value = "No debug session"
    }
}
