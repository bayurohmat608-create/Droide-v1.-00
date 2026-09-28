package com.baystudio.droide.core

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

 
enum class WorkspaceAgentTransport { ACP_V1 }

data class WorkspaceAgentPluginSpec(
    val id: String,
    val familyId: String,
    val displayName: String,
    val command: String,
    val args: List<String>,
    val transport: WorkspaceAgentTransport = WorkspaceAgentTransport.ACP_V1,
    val planModeCandidates: List<String> = listOf("plan", "read-only", "ask", "architect"),
    val editModeCandidates: List<String> = listOf("agent", "code", "build", "auto_edit"),
)

object WorkspaceAgentPluginCatalog {
    val entries = listOf(
        WorkspaceAgentPluginSpec("opencode", "plugin.opencode", "OpenCode", "opencode", listOf("acp")),
        WorkspaceAgentPluginSpec("codex", "plugin.codex", "Codex", "codex-acp", emptyList(), planModeCandidates = listOf("read-only", "plan"), editModeCandidates = listOf("agent")),
        WorkspaceAgentPluginSpec("claude", "plugin.claude-code", "Claude Agent", "claude-agent-acp", emptyList(), planModeCandidates = listOf("ask", "architect", "plan"), editModeCandidates = listOf("code", "agent")),
        WorkspaceAgentPluginSpec("gemini", "plugin.gemini-cli", "Gemini CLI", "gemini", listOf("--acp"), editModeCandidates = listOf("auto_edit", "agent")),
        WorkspaceAgentPluginSpec("copilot", "plugin.github-copilot-cli", "GitHub Copilot CLI", "copilot", listOf("--acp", "--stdio")),
    )
}

data class WorkspaceAgentAvailability(
    val spec: WorkspaceAgentPluginSpec,
    val executable: String?,
) {
    val available: Boolean get() = executable != null
}

sealed interface WorkspaceAgentEvent {
    data class Text(val text: String) : WorkspaceAgentEvent
    data class Thought(val text: String) : WorkspaceAgentEvent
    data class Tool(val title: String, val raw: String) : WorkspaceAgentEvent
    data class Status(val message: String) : WorkspaceAgentEvent
    data class Usage(val snapshot: WorkspaceAgentUsageSnapshot) : WorkspaceAgentEvent
    data class SessionBound(val sessionId: String) : WorkspaceAgentEvent
}

data class WorkspaceAgentTurnResult(
    val agentId: String,
    val sessionId: String,
    val text: String,
    val stopReason: String,
    val changedFiles: List<String>,
    val conflicts: List<String>,
)






class WorkspaceAgentPluginHost(
    private val projectRoot: File,
    private val cacheDir: File,
    private val files: FileRepository,
    private val processHost: StdioProcessHost,
    private val androidDevelopment: AndroidDevelopmentManager,
    private val bridge: DeviceBridgeManager,
    private val approvals: ApprovalManager,
    private val permissions: PermissionEngine,
    parentScope: CoroutineScope,
) : Closeable {
    private data class LiveSession(
        val spec: WorkspaceAgentPluginSpec,
        val process: HostedStdioProcess,
        val rpc: AcpNdjsonConnection,
        val sessionId: String, val approvalSessionId: String,
        val mapper: WorkspacePathMapper,
        val modes: List<String>,
        val mutex: Mutex = Mutex(),
    )

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val sessions = ConcurrentHashMap<String, LiveSession>()
    private val sessionLifecycle = Mutex()

    suspend fun availability(): List<WorkspaceAgentAvailability> =
        WorkspaceAgentPluginCatalog.entries.map { spec ->
            WorkspaceAgentAvailability(spec, runSuspendCatching { processHost.resolveExecutable(spec.command) }.getOrNull())
        }

    suspend fun runTurn(
        agentId: String,
        prompt: String,
        mode: WorkspaceAgentMode = WorkspaceAgentMode.EDIT,
        resumeSessionId: String? = null,
        onEvent: (WorkspaceAgentEvent) -> Unit = {},
    ): WorkspaceAgentTurnResult {
        require(prompt.isNotBlank() && prompt.length <= 200_000) { "Agent prompt is empty or too large" }
        val session = ensureSession(agentId, resumeSessionId, onEvent)
        onEvent(WorkspaceAgentEvent.SessionBound(session.sessionId))
        return session.mutex.withLock {
            session.rpc.beginTurn(mode)
            try {
                val before = readRemoteSyncManifest(session.mapper.remoteRoot)
                setModeIfSupported(session, mode, onEvent)
                val streamed = StringBuilder()
                val updateListener: (JsonObject) -> Unit = listener@{ update ->
                    when (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
                        "agent_message_chunk" -> extractContentText(update["content"])?.let {
                            streamed.append(it); onEvent(WorkspaceAgentEvent.Text(it))
                        }
                        "agent_thought_chunk" -> extractContentText(update["content"])?.let { onEvent(WorkspaceAgentEvent.Thought(it)) }
                        "tool_call", "tool_call_update" -> onEvent(
                            WorkspaceAgentEvent.Tool(
                                title = update["title"]?.jsonPrimitive?.contentOrNull ?: update["kind"]?.jsonPrimitive?.contentOrNull ?: "Agent tool",
                                raw = update.toString().take(8_000),
                            )
                        )
                        "plan", "plan_update" -> onEvent(WorkspaceAgentEvent.Status("Agent updated its plan."))
                        "usage_update" -> WorkspaceAgentUsageSnapshot.fromUpdate(update)?.let { onEvent(WorkspaceAgentEvent.Usage(it)) }
                    }
                }
                session.rpc.updateListener = updateListener
                val result = try {
                    session.rpc.request(
                        "session/prompt",
                        buildJsonObject {
                            put("sessionId", session.sessionId)
                            put("prompt", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", prompt) }) })
                        },
                        timeoutMs = 0L,
                    )
                } finally {
                    session.rpc.updateListener = null
                }
                val stop = result["stopReason"]?.jsonPrimitive?.contentOrNull ?: "end_turn"
                session.rpc.freezeMutations()
                val authorizedWrites = session.rpc.drainDirectWrites()
                val reconciliation = reconcileRemoteWorkspace(
                    session.mapper,
                    before,
                    authorizedWrites,
                    allowWorkspaceMutation = mode == WorkspaceAgentMode.EDIT,
                    approvalSessionId = session.approvalSessionId, provenanceSessionId = session.sessionId,
                )
                WorkspaceAgentTurnResult(
                    agentId = agentId,
                    sessionId = session.sessionId,
                    text = streamed.toString().trim(),
                    stopReason = stop,
                    changedFiles = (authorizedWrites + reconciliation.first).distinct(),
                    conflicts = reconciliation.second,
                )
            } catch (failure: Throwable) {
                session.rpc.drainDirectWrites()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { androidDevelopment.prepareInteractiveShell(syncProject = true) }
                }
                throw failure
            } finally {
                session.rpc.endTurn()
            }
        }
    }

    suspend fun cancel(agentId: String, expectedSessionId: String? = null) {
        val session = sessions[agentId] ?: return
        if (expectedSessionId != null && expectedSessionId != session.sessionId) return
        try {
            session.rpc.cancelTurn(session.sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Reap the exact owned session so Stop remains best-effort and cannot surface a transport failure to UI.

            if (sessions.remove(agentId, session)) closeSession(session)
        }
    }

    suspend fun newSession(
        agentId: String,
        onEvent: (WorkspaceAgentEvent) -> Unit = {},
    ): String = sessionLifecycle.withLock {
        sessions.remove(agentId)?.let { previous ->
            previous.mutex.withLock { closeSession(previous) }
        }
        startSession(agentId, onEvent, resumeSessionId = null).sessionId
    }

    private suspend fun ensureSession(
        agentId: String,
        resumeSessionId: String?,
        onEvent: (WorkspaceAgentEvent) -> Unit,
    ): LiveSession = sessionLifecycle.withLock {
        sessions[agentId]?.takeIf { live ->
            live.process.isAlive && (resumeSessionId == null || resumeSessionId == live.sessionId)
        } ?: run {
            sessions.remove(agentId)?.let(::closeSession)
            startSession(agentId, onEvent, resumeSessionId)
        }
    }

    private suspend fun startSession(
        agentId: String,
        onEvent: (WorkspaceAgentEvent) -> Unit,
        resumeSessionId: String?,
    ): LiveSession {
        val spec = WorkspaceAgentPluginCatalog.entries.firstOrNull { it.id == agentId }
            ?: error("Unknown workspace agent: $agentId")
        val boot = WorkspaceAgentSessionBootstrap.start(
            spec, projectRoot, files, processHost, bridge, approvals, permissions, scope, resumeSessionId, onEvent,
        )
        return LiveSession(
            spec, boot.process, boot.rpc, boot.sessionId, boot.approvalSessionId, boot.mapper, boot.modes,
        ).also { sessions[agentId] = it }
    }

    private suspend fun setModeIfSupported(session: LiveSession, mode: WorkspaceAgentMode, onEvent: (WorkspaceAgentEvent) -> Unit) {
        val wanted = when (mode) {
            WorkspaceAgentMode.PLAN -> session.spec.planModeCandidates
            WorkspaceAgentMode.EDIT -> session.spec.editModeCandidates
        }.firstOrNull { candidate -> session.modes.any { it.equals(candidate, ignoreCase = true) } } ?: return
        runCatching {
            session.rpc.request(
                "session/set_mode",
                buildJsonObject { put("sessionId", session.sessionId); put("modeId", wanted) },
                timeoutMs = 10_000,
            )
            onEvent(WorkspaceAgentEvent.Status("${session.spec.displayName} mode: $wanted"))
        }
    }

    private enum class ReconcileOperation { IMPORT_TEXT, DELETE }

    private data class ReconcileCandidate(
        val relativePath: String,
        val expectedRemoteHash: String?,
        val operation: ReconcileOperation,
    )

    // Treat the Device Workstation as an agent sandbox, never as the source of truth.




    private suspend fun reconcileRemoteWorkspace(
        mapper: WorkspacePathMapper,
        before: Map<String, String>,
        authorizedWrites: Set<String>,
        allowWorkspaceMutation: Boolean,
        approvalSessionId: String,
        provenanceSessionId: String,
    ): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        val remote = mapper.remoteRoot
        val scan = bridge.shellBounded(
            "set -eu; cd ${DeviceBridgeManager.shellQuote(remote)}; " +
                "find . -type f ! -path './.git/*' ! -path './.gradle/*' ! -path './build/*' ! -path './.droide/*' ! -path './node_modules/*' " +
                "! -name '.droide-sync-manifest.tsv' ! -name 'local.properties' | sort | while IFS= read -r f; do " +
                "case \"${'$'}f\" in *'\\t'*|*'\\r'*) continue;; esac; toybox sha256sum \"${'$'}f\"; done",
            maxOutputBytes = 2_000_000,
        )
        check(scan.exitCode == 0) { "Could not inspect agent workspace changes: ${scan.combined.takeLast(4_000)}" }
        val current = parseHashListing(scan.stdout, manifestFormat = false)
        require(current.size <= 20_000) { "Agent workspace produced too many files" }

        val candidates = mutableListOf<ReconcileCandidate>()
        val conflicts = mutableListOf<String>()
        val touched = (before.keys + current.keys).toSortedSet()
        for (rel in touched) {
            if (before[rel] == current[rel] || !isSafeAgentRelativePath(rel)) continue
            val local = mapper.remoteToLocal("$remote/$rel") ?: continue
            val localHash = local.takeIf(File::isFile)?.let(::sha256)
            val base = before[rel]
            val now = current[rel]
            if (rel in authorizedWrites && now != null && localHash == now) continue
            if (now == null) {
                if (base != null && localHash == base) {
                    candidates += ReconcileCandidate(rel, null, ReconcileOperation.DELETE)
                } else if (base != null) {
                    conflicts += rel
                }
                continue
            }
            if (base != null && localHash != base) {
                conflicts += rel
                continue
            }
            if (base == null && local.exists()) {
                conflicts += rel
                continue
            }
            candidates += ReconcileCandidate(rel, now, ReconcileOperation.IMPORT_TEXT)
        }

        if (candidates.isNotEmpty() && !allowWorkspaceMutation) {
            

            androidDevelopment.prepareInteractiveShell(syncProject = true)
            return@withContext emptyList<String>() to (conflicts + candidates.map { it.relativePath }).distinct()
        }

        if (candidates.isNotEmpty()) {
            val preview = candidates.take(80).joinToString("\n") { candidate ->
                val verb = if (candidate.operation == ReconcileOperation.DELETE) "delete" else "write"
                "$verb ${candidate.relativePath}"
            } + if (candidates.size > 80) "\n… and ${candidates.size - 80} more" else ""
            val reconcileIdentity = candidates.joinToString("\n") { candidate ->
                "${candidate.operation}:${candidate.relativePath}:${candidate.expectedRemoteHash.orEmpty()}"
            }
            val resource = candidates.take(24).joinToString(",") { it.relativePath }
                .let { paths -> "${candidates.size} files:${paths}".take(4_096) }
            val approved = PermissionApprovalGate.approved(
                permissions = permissions,
                approvals = approvals,
                action = "edit_workspace",
                resource = resource,
                permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE,
                kind = "agent_workspace_reconcile",
                summary = "Apply ${candidates.size} workspace change${if (candidates.size == 1) "" else "s"} from external agent",
                detail = preview,
                provenance = AgentRequestProvenance.primary(provenanceSessionId),
                approvalSessionId = approvalSessionId,
                sessionGrantResource = sha256String(reconcileIdentity),
            )
            if (!approved) {
                // Discard sandbox-only mutations and restore the authoritative editor workspace.
                androidDevelopment.prepareInteractiveShell(syncProject = true)
                return@withContext emptyList<String>() to (conflicts + candidates.map { it.relativePath }).distinct()
            }
        }

        val changed = mutableListOf<String>()
        for (candidate in candidates) {
            val rel = candidate.relativePath
            val local = mapper.remoteToLocal("$remote/$rel") ?: continue
            val remotePath = "$remote/$rel"
            DeviceBridgeManager.requireSafeRemotePath(remotePath)
            when (candidate.operation) {
                ReconcileOperation.DELETE -> {
                    if (files.delete(rel)) changed += rel
                }
                ReconcileOperation.IMPORT_TEXT -> {
                    val temp = File(cacheDir, "agent-reconcile/${sha256String(remotePath)}.tmp")
                    temp.parentFile?.mkdirs()
                    try {
                        bridge.pull(remotePath, temp)
                        val expected = candidate.expectedRemoteHash
                        if (expected == null || sha256(temp) != expected || temp.length() > 2_000_000L || !isUtf8Text(temp)) {
                            conflicts += rel
                            continue
                        }
                        files.writeText(rel, temp.readText(Charsets.UTF_8))
                        changed += rel
                    } finally {
                        temp.delete()
                    }
                }
            }
        }
        // Always rebuild the mirror from the authoritative local workspace.

        androidDevelopment.prepareInteractiveShell(syncProject = true)
        changed.distinct() to conflicts.distinct()
    }

    private suspend fun readRemoteSyncManifest(remoteRoot: String): Map<String, String> {
        DeviceBridgeManager.requireSafeRemotePath(remoteRoot)
        val path = "$remoteRoot/.droide-sync-manifest.tsv"
        val result = bridge.shellBounded(
            "test -f ${DeviceBridgeManager.shellQuote(path)} && cat ${DeviceBridgeManager.shellQuote(path)}",
            maxOutputBytes = 2_000_000,
        )
        if (result.exitCode != 0) return emptyMap()
        val parsed = parseHashListing(result.stdout, manifestFormat = true)
        require(parsed.size <= 20_000) { "Agent workspace sync manifest contains too many files" }
        return parsed
    }

    private fun parseHashListing(text: String, manifestFormat: Boolean): Map<String, String> {
        val out = linkedMapOf<String, String>()
        text.lineSequence().take(20_001).forEach { raw ->
            val line = raw.trimEnd()
            val hash: String
            val relRaw: String
            if (manifestFormat) {
                val tab = line.indexOf('\t')
                if (tab != 64) return@forEach
                hash = line.substring(0, 64)
                relRaw = line.substring(tab + 1)
            } else {
                if (line.length < 68 || !line.substring(0, 64).matches(Regex("[0-9a-f]{64}"))) return@forEach
                hash = line.substring(0, 64)
                relRaw = line.substring(64).trim().removePrefix("*").removePrefix("./")
            }
            if (!hash.matches(Regex("[0-9a-f]{64}"))) return@forEach
            if (!isSafeAgentRelativePath(relRaw)) return@forEach
            out[relRaw] = hash
        }
        return out
    }

    private fun isSafeAgentRelativePath(path: String): Boolean {
        if (path.isBlank() || path.length > 500 || path.startsWith('/') || '\u0000' in path || '\n' in path || '\r' in path || '\t' in path) return false
        val parts = path.replace('\\', '/').split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
        if (parts.first() in setOf(".git", ".gradle", "build", ".droide", "node_modules")) return false
        return !SensitivePathPolicy.isSensitive(path)
    }

    private fun extractContentText(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element["text"]?.jsonPrimitive?.contentOrNull
        else -> null
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256String(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun isUtf8Text(file: File): Boolean = runCatching {
        val bytes = file.readBytes()
        if (bytes.any { it == 0.toByte() }) return@runCatching false
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    }.getOrDefault(false)

    private fun closeSession(session: LiveSession) {
        approvals.clearSessionApprovals(session.approvalSessionId, EXTERNAL_ACP_PERMISSION_SCOPE)
        runCatching { session.rpc.close() }
        runCatching { session.process.close() }
    }

    override fun close() {
        sessions.values.forEach(::closeSession)
        sessions.clear()
        scope.cancel()
    }
}

 
internal class AcpNdjsonConnection(
    private val process: HostedStdioProcess,
    private val files: FileRepository,
    private val mapper: WorkspacePathMapper,
    private val processHost: StdioProcessHost,
    private val bridge: DeviceBridgeManager,
    private val approvals: ApprovalManager,
    private val permissions: PermissionEngine,
    private val scope: CoroutineScope,
) : Closeable {
    private data class InboundCall(val method: String, val job: Job)

    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicLong(1)
    private val outboundInFlight = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val inbound = ConcurrentHashMap<String, InboundCall>()
    private val directWrites = ConcurrentHashMap.newKeySet<String>()
    private val permissionLedger = AcpPermissionLedger()
    private val accessPolicy = WorkspaceAgentAccessPolicy()
    private val terminal = AcpTerminalController(processHost, mapper, approvals, permissions, permissionLedger, accessPolicy, scope)
    private val writer = OutputStreamWriter(process.stdin, Charsets.UTF_8).buffered()
    private val writeMutex = Mutex()
    private val readerJob: Job
    @Volatile private var sessionId: String? = null
    @Volatile private var approvalSessionId: String? = null
    @Volatile var updateListener: ((JsonObject) -> Unit)? = null

    init {
        readerJob = scope.launch(Dispatchers.IO) {
            BufferedReader(InputStreamReader(process.stdout, Charsets.UTF_8)).use { reader ->
                while (process.isAlive) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank() || line.length > 2_000_000) continue
                    val msg = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    val rawId = msg["id"]
                    val numericId = rawId?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    if (rawId != null && ("result" in msg || "error" in msg)) {
                        if (numericId != null) pending.remove(numericId)?.let { deferred ->
                            val error = msg["error"] as? JsonObject
                            if (error != null) {
                                deferred.completeExceptionally(
                                    IllegalStateException(error["message"]?.jsonPrimitive?.contentOrNull ?: "ACP request failed")
                                )
                            } else {
                                deferred.complete((msg["result"] as? JsonObject) ?: JsonObject(emptyMap()))
                            }
                        }
                        continue
                    }
                    val method = msg["method"]?.jsonPrimitive?.contentOrNull ?: continue
                    val params = (msg["params"] as? JsonObject) ?: JsonObject(emptyMap())
                    if (rawId == null) {
                        if (method == "session/update") (params["update"] as? JsonObject)?.let { updateListener?.invoke(it) }
                    } else {
                        val key = rawId.toString()
                        if (inbound.size >= MAX_INBOUND_REQUESTS || inbound.containsKey(key)) {
                            send(buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("id", rawId)
                                put("error", buildJsonObject {
                                    put("code", -32000)
                                    put("message", if (inbound.containsKey(key)) "Duplicate ACP request id" else "Too many concurrent ACP client requests")
                                })
                            })
                            continue
                        }
                        lateinit var job: Job
                        job = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                handleInboundRequest(rawId, method, params)
                            } finally {
                                inbound.remove(key)
                            }
                        }
                        if (inbound.putIfAbsent(key, InboundCall(method, job)) == null) {
                            job.start()
                        } else {
                            job.cancel()
                        }
                    }
                }
            }
            val failure = IllegalStateException("ACP agent process closed")
            pending.values.forEach { it.completeExceptionally(failure) }
            pending.clear()
        }
    }

    fun bindSession(value: String, approvalOwner: String) {
        require(value.isNotBlank() && value.length <= 512) { "Invalid ACP session id" }; require(approvalOwner.isNotBlank() && approvalOwner.length <= 512) { "Invalid ACP approval session id" }
        approvals.clearSessionApprovals(approvalOwner, EXTERNAL_ACP_PERMISSION_SCOPE)
        approvalSessionId = approvalOwner; terminal.bindApprovalSession(approvalOwner)
        permissionLedger.clear()
        terminal.cancelActive()
        directWrites.clear()
        accessPolicy.setMode(WorkspaceAgentMode.PLAN)
        sessionId = value
    }

    fun beginTurn(mode: WorkspaceAgentMode) {
        permissionLedger.clear()
        terminal.cancelActive()
        directWrites.clear()
        accessPolicy.setMode(mode)
    }

    fun freezeMutations() {
        permissionLedger.clear()
        terminal.cancelActive()
        accessPolicy.setMode(WorkspaceAgentMode.PLAN)
    }

    fun endTurn() = freezeMutations()

    fun drainDirectWrites(): Set<String> {
        val out = directWrites.toSet()
        directWrites.removeAll(out)
        return out
    }

    suspend fun request(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        val inFlight = outboundInFlight.incrementAndGet()
        if (inFlight > MAX_PENDING_REQUESTS) {
            outboundInFlight.decrementAndGet()
            error("Too many pending ACP requests")
        }
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        return try {
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
            if (timeoutMs > 0) kotlinx.coroutines.withTimeout(timeoutMs) { deferred.await() } else deferred.await()
        } finally {
            pending.remove(id)
            outboundInFlight.decrementAndGet()
        }
    }

    suspend fun notify(method: String, params: JsonObject) {
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) })
    }

    suspend fun cancelTurn(expectedSessionId: String) {
        require(sessionId == expectedSessionId) { "ACP session mismatch" }
        accessPolicy.setMode(WorkspaceAgentMode.PLAN)
        permissionLedger.clear()
        terminal.cancelActive()
        val calls = inbound.entries.toList()
        for ((key, call) in calls) {
            if (!inbound.remove(key, call)) continue
            call.job.cancel()
            val id = runCatching { json.parseToJsonElement(key) }.getOrNull() ?: continue
            if (call.method == "session/request_permission") {
                send(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", buildJsonObject {
                        put("outcome", buildJsonObject { put("outcome", "cancelled") })
                    })
                })
            } else {
                send(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("error", buildJsonObject { put("code", -32800); put("message", "ACP request cancelled") })
                })
            }
        }
        notify("session/cancel", buildJsonObject { put("sessionId", expectedSessionId) })
    }

    private suspend fun handleInboundRequest(id: JsonElement, method: String, params: JsonObject) {
        try {
            validateSession(params)
            val result = when (method) {
                "session/request_permission" -> handlePermission(params)
                "fs/read_text_file" -> handleRead(params)
                "fs/write_text_file" -> handleWrite(params)
                "terminal/create" -> terminal.create(params)
                "terminal/output" -> terminal.output(params)
                "terminal/wait_for_exit" -> terminal.waitForExit(params)
                "terminal/kill" -> terminal.kill(params)
                "terminal/release" -> terminal.release(params)
                else -> throw IllegalArgumentException("Droide does not expose ACP client method: $method")
            }
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            send(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                put("error", buildJsonObject { put("code", -32603); put("message", failure.message ?: "ACP client request failed") })
            })
        }
    }

    private fun validateSession(params: JsonObject) {
        val expected = sessionId ?: error("ACP client request arrived before session binding")
        val supplied = params["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: error("ACP client request is missing required sessionId")
        require(supplied == expected) { "ACP request targeted a different session" }
    }

    private suspend fun handlePermission(params: JsonObject): JsonObject {
        val options = (params["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val tool = params["toolCall"] as? JsonObject
        val toolCallId = tool?.get("toolCallId")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= 512 }
            ?: error("ACP permission request is missing valid toolCallId")
        val title = tool["title"]?.jsonPrimitive?.contentOrNull
            ?: tool["kind"]?.jsonPrimitive?.contentOrNull
            ?: "Agent tool request"
        val detail = tool.toString().take(8_000)
        val toolKind = tool["kind"]?.jsonPrimitive?.contentOrNull
        val requestedGrant = permissionIdentity(tool)
        if (!accessPolicy.allowsWorkspaceMutation() && (requestedGrant != null || accessPolicy.rejectsPermissionKind(toolKind))) {
            return permissionOutcome(options, allowed = false)
        }
        val sid = sessionId ?: error("ACP session is not bound")
        val approved = if (requestedGrant != null) {
            PermissionApprovalGate.approved(
                permissions = permissions,
                approvals = approvals,
                action = requestedGrant.action,
                resource = requestedGrant.policyResource,
                permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE,
                kind = "agent_plugin_tool",
                summary = title,
                detail = detail,
                provenance = AgentRequestProvenance.primary(sid), approvalSessionId = approvalSessionId ?: error("ACP approval session is not bound"),
                sessionGrantAction = requestedGrant.action,
                sessionGrantResource = requestedGrant.sessionGrantResource,
            )
        } else {
            PermissionApprovalGate.approved(
                permissions = permissions,
                approvals = approvals,
                action = "agent_tool",
                resource = title.take(4_096),
                permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE,
                kind = "agent_plugin_tool",
                summary = title,
                detail = detail,
                provenance = AgentRequestProvenance.primary(sid), approvalSessionId = approvalSessionId ?: error("ACP approval session is not bound"),
            )
        }
        if (approved) {
            requestedGrant?.let { permissionLedger.grant(it.kind, toolCallId, it.operationFingerprint) }
        }
        // ACP always receives allowonce so the external agent cannot persist a broader allowalways rule outside Droide's session lifecycle.

        return permissionOutcome(options, approved)
    }

    private fun permissionOutcome(options: List<JsonObject>, allowed: Boolean): JsonObject {
        val wanted = if (allowed) "allow_once" else "reject_once"
        val selected = options.firstOrNull { it["kind"]?.jsonPrimitive?.contentOrNull == wanted }
            ?.get("optionId")?.jsonPrimitive?.contentOrNull
        val outcome = if (selected != null) {
            buildJsonObject { put("outcome", "selected"); put("optionId", selected) }
        } else {
            buildJsonObject { put("outcome", "cancelled") }
        }
        return buildJsonObject { put("outcome", outcome) }
    }

    private fun permissionIdentity(tool: JsonObject): AcpPermissionIdentity? {
        val raw = tool["rawInput"] as? JsonObject ?: return null
        return when (tool["kind"]?.jsonPrimitive?.contentOrNull) {
            "edit" -> {
                val path = firstString(raw, "path", "filePath", "file_path") ?: return null
                val content = firstString(raw, "content", "text", "newText", "new_text") ?: return null
                val canonicalPath = normalizeAgentPathForFingerprint(path) ?: return null
                val local = mapper.remoteToLocal(canonicalPath) ?: return null
                val rel = local.relativeTo(files.root).invariantSeparatorsPath
                AcpPermissionIdentities.mutation(canonicalPath, content, rel)
            }
            "execute" -> {
                val command = firstString(raw, "command", "executable") ?: return null
                if (runCatching { ProcessSecurityPolicy.requireExecutableName(command) }.isFailure) return null
                val args = when (val rawArgs = raw["args"]) {
                    null, JsonNull -> emptyList()
                    is JsonArray -> rawArgs.map { element ->
                        (element as? JsonPrimitive)?.contentOrNull ?: return null
                    }
                    else -> return null
                }
                val environment = parsePermissionEnvironment(raw["env"] ?: raw["environment"]) ?: return null
                val rawCwd = firstString(raw, "cwd", "workingDirectory", "working_directory") ?: mapper.remoteRoot
                val canonicalCwd = normalizeAgentPathForFingerprint(rawCwd) ?: return null
                AcpPermissionIdentities.execute(command, args, environment, canonicalCwd)
            }
            else -> null
        }
    }

    private fun normalizeAgentPathForFingerprint(value: String): String? {
        mapper.remoteToLocal(value)?.let { return mapper.localToRemote(it) }
        if (value.startsWith('/')) return null
        return runCatching { mapper.localRelativeToRemote(value) }.getOrNull()
    }

    private fun parsePermissionEnvironment(value: JsonElement?): Map<String, String>? = when (value) {
        null, JsonNull -> emptyMap()
        is JsonObject -> {
            if (value.size > 64) return null
            value.entries.associate { (key, element) ->
                if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}"))) return null
                key to ((element as? JsonPrimitive)?.contentOrNull ?: return null)
            }
        }
        is JsonArray -> {
            if (value.size > 64) return null
            val result = linkedMapOf<String, String>()
            value.forEach { element ->
                val obj = element as? JsonObject ?: return null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
                val envValue = obj["value"]?.jsonPrimitive?.contentOrNull ?: return null
                if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) || result.put(name, envValue) != null) return null
            }
            result
        }
        else -> null
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull }

    private suspend fun handleRead(params: JsonObject): JsonObject {
        val path = params["path"]?.jsonPrimitive?.contentOrNull ?: error("ACP read path is missing")
        val local = mapper.remoteToLocal(path) ?: error("ACP read escaped the workspace")
        val rel = local.relativeTo(files.root).invariantSeparatorsPath
        var content = files.readTextForEdit(rel, maxBytes = 2_000_000)
        val line = params["line"]?.jsonPrimitive?.intOrNull
        val limit = params["limit"]?.jsonPrimitive?.intOrNull
        if (line != null || limit != null) {
            val start = (line ?: 1).coerceAtLeast(1) - 1
            val count = (limit ?: 2_000).coerceIn(1, 10_000)
            content = content.lineSequence().drop(start).take(count).joinToString("\n")
        }
        return buildJsonObject { put("content", content) }
    }

    private suspend fun handleWrite(params: JsonObject): JsonObject {
        accessPolicy.requireMutation("filesystem write")
        val path = params["path"]?.jsonPrimitive?.contentOrNull ?: error("ACP write path is missing")
        val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("ACP write content is missing")
        require(content.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "ACP write is too large" }
        val local = mapper.remoteToLocal(path) ?: error("ACP write escaped the workspace")
        val rel = local.relativeTo(files.root).invariantSeparatorsPath
        val canonicalRemotePath = mapper.localToRemote(local)
        val identity = AcpPermissionIdentities.mutation(canonicalRemotePath, content, rel)
        val preapproved = permissionLedger.consume(identity.kind, identity.operationFingerprint)
        val sid = sessionId ?: error("ACP session is not bound")
        val approved = PermissionApprovalGate.approved(
            permissions = permissions,
            approvals = approvals,
            action = identity.action,
            resource = identity.policyResource,
            permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE,
            kind = "agent_plugin_write",
            summary = "Edit $rel",
            detail = "External ACP agent requested a direct text write (${content.length} characters).",
            provenance = AgentRequestProvenance.primary(sid), approvalSessionId = approvalSessionId ?: error("ACP approval session is not bound"),
            sessionGrantAction = identity.action,
            sessionGrantResource = identity.sessionGrantResource,
            preapproved = preapproved,
        )
        require(approved) { "ACP file write denied" }
        files.writeText(rel, content)
        val remote = mapper.localRelativeToRemote(rel)
        val parent = remote.substringBeforeLast('/', mapper.remoteRoot)
        val mk = bridge.shell("mkdir -p ${DeviceBridgeManager.shellQuote(parent)}")
        check(mk.exitCode == 0) { mk.combined }
        bridge.push(local, remote, mode = if (local.canExecute()) 493 else 420)
        directWrites += rel
        return JsonObject(emptyMap())
    }

    private suspend fun send(message: JsonObject) = writeMutex.withLock {
        writer.write(message.toString())
        writer.write("\n")
        writer.flush()
    }

    private companion object {
        const val MAX_PENDING_REQUESTS = 64
        const val MAX_INBOUND_REQUESTS = 32
    }

    override fun close() {
        inbound.values.forEach { it.job.cancel() }
        inbound.clear()
        terminal.close()
        permissionLedger.clear()
        readerJob.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
        runCatching { writer.close() }
        runCatching { process.close() }
    }
}
