package com.baystudio.droide.core

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Agents receive process semantics, never a free-form shell string.




internal class AcpTerminalController(
    private val processHost: StdioProcessHost,
    private val mapper: WorkspacePathMapper,
    private val approvals: ApprovalManager,
    private val permissions: PermissionEngine,
    private val permissionLedger: AcpPermissionLedger,
    private val accessPolicy: WorkspaceAgentAccessPolicy,
    private val scope: CoroutineScope,
) : Closeable {
    private data class TerminalSession(
        val id: String,
        val process: HostedStdioProcess,
        val outputLimitBytes: Int,
        val output: StringBuilder = StringBuilder(),
        val lock: Any = Any(),
        @Volatile var truncated: Boolean = false,
        @Volatile var exitCode: Int? = null,
        val readers: MutableList<Job> = mutableListOf(),
    )

    private val ids = AtomicLong(1)
    private val terminals = ConcurrentHashMap<String, TerminalSession>()
    private val createMutex = Mutex()
    @Volatile private var approvalSessionId: String? = null

    fun bindApprovalSession(value: String) { approvalSessionId = value }

    suspend fun create(params: JsonObject): JsonObject = createMutex.withLock {
        accessPolicy.requireMutation("terminal execution")
        check(terminals.size < MAX_TERMINALS) { "Too many active ACP terminals" }
        val command = params["command"]?.jsonPrimitive?.contentOrNull ?: error("ACP terminal command is missing")
        ProcessSecurityPolicy.requireExecutableName(command)
        val args = (params["args"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        val environment = parseEnvironment(params["env"] as? JsonArray)
        val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull ?: mapper.remoteRoot
        val cwdLocal = mapper.remoteToLocal(cwd) ?: error("ACP terminal cwd escaped the workspace")
        val outputLimit = (params["outputByteLimit"]?.jsonPrimitive?.longOrNull ?: DEFAULT_OUTPUT_BYTES.toLong())
            .coerceIn(MIN_OUTPUT_BYTES.toLong(), MAX_OUTPUT_BYTES.toLong()).toInt()
        val resolved = processHost.resolveExecutable(command) ?: error("ACP terminal executable '$command' is unavailable")
        val argv = listOf(resolved) + args
        ProcessSecurityPolicy.validateArgv(argv)
        ProcessSecurityPolicy.validateEnvironment(environment)

        val canonicalCwd = mapper.localToRemote(cwdLocal)
        val identity = AcpPermissionIdentities.execute(command, args, environment, canonicalCwd)
        val preapproved = permissionLedger.consume(identity.kind, identity.operationFingerprint)
        val sid = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: error("ACP terminal session id is missing")
        val approved = PermissionApprovalGate.approved(
            permissions = permissions,
            approvals = approvals,
            action = identity.action,
            resource = identity.policyResource,
            permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE,
            kind = "agent_plugin_terminal",
            summary = "Run ${argv.take(4).joinToString(" ")}${if (argv.size > 4) " …" else ""}",
            detail = buildString {
                appendLine("cwd: $cwd")
                appendLine("argv: ${argv.joinToString(" ")}")
                if (environment.isNotEmpty()) appendLine("environment keys: ${environment.keys.sorted().joinToString()}")
            },
            provenance = AgentRequestProvenance.primary(sid),
            approvalSessionId = approvalSessionId ?: error("ACP terminal approval session is not bound"),
            sessionGrantAction = identity.action,
            sessionGrantResource = identity.sessionGrantResource,
            preapproved = preapproved,
        )
        require(approved) { "ACP terminal command denied" }

        val process = processHost.startInWorkspace(argv, environment, cwd)
        val id = "droide-acp-terminal-${ids.getAndIncrement()}"
        val session = TerminalSession(id, process, outputLimit)
        terminals[id] = session
        session.readers += scope.launch(Dispatchers.IO) { drain(process.stdout, session) }
        session.readers += scope.launch(Dispatchers.IO) { drain(process.stderr, session) }
        session.readers += scope.launch(Dispatchers.IO) {
            session.exitCode = runCatching { process.awaitExit() }.getOrElse { process.exitCode ?: 1 }
        }
        buildJsonObject { put("terminalId", id) }
    }

    fun output(params: JsonObject): JsonObject {
        val terminal = requireTerminal(params)
        val snapshot = synchronized(terminal.lock) { terminal.output.toString() }
        return buildJsonObject {
            put("output", snapshot)
            put("truncated", terminal.truncated)
            val code = terminal.exitCode ?: terminal.process.exitCode
            if (code != null) put("exitStatus", buildJsonObject { put("exitCode", code.coerceAtLeast(0)) })
        }
    }

    suspend fun waitForExit(params: JsonObject): JsonObject {
        val terminal = requireTerminal(params)
        val code = terminal.exitCode ?: runCatching { terminal.process.awaitExit() }.getOrElse { terminal.process.exitCode ?: 1 }
        terminal.exitCode = code
        return buildJsonObject { put("exitCode", code.coerceAtLeast(0)) }
    }

    fun kill(params: JsonObject): JsonObject {
        val terminal = requireTerminal(params)
        terminal.process.close()
        terminal.exitCode = terminal.process.exitCode ?: 143
        return JsonObject(emptyMap())
    }

    fun release(params: JsonObject): JsonObject {
        val id = params["terminalId"]?.jsonPrimitive?.contentOrNull ?: error("ACP terminal id is missing")
        val terminal = terminals.remove(id) ?: error("Unknown ACP terminal: $id")
        terminal.readers.forEach(Job::cancel)
        terminal.process.close()
        return JsonObject(emptyMap())
    }

    fun cancelActive() {
        terminals.values.forEach { terminal ->
            terminal.readers.forEach(Job::cancel)
            terminal.process.close()
        }
        terminals.clear()
    }

    private fun requireTerminal(params: JsonObject): TerminalSession {
        val id = params["terminalId"]?.jsonPrimitive?.contentOrNull ?: error("ACP terminal id is missing")
        return terminals[id] ?: error("Unknown ACP terminal: $id")
    }

    private suspend fun drain(input: java.io.InputStream, session: TerminalSession) = withContext(Dispatchers.IO) {
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            val chars = CharArray(4_096)
            while (true) {
                val count = reader.read(chars)
                if (count <= 0) break
                appendBounded(session, String(chars, 0, count))
            }
        }
    }

    private fun appendBounded(session: TerminalSession, value: String) {
        if (value.isEmpty()) return
        synchronized(session.lock) {
            session.output.append(value)
            val bytes = session.output.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size <= session.outputLimitBytes) return
            session.truncated = true
            val kept = bytes.copyOfRange(bytes.size - session.outputLimitBytes, bytes.size)
            var start = 0
            while (start < kept.size && (kept[start].toInt() and 0xC0) == 0x80) start++
            session.output.setLength(0)
            session.output.append(kept.copyOfRange(start, kept.size).toString(Charsets.UTF_8))
        }
    }

    private fun parseEnvironment(raw: JsonArray?): Map<String, String> {
        if (raw == null) return emptyMap()
        require(raw.size <= 64) { "ACP terminal environment is too large" }
        val out = linkedMapOf<String, String>()
        raw.mapNotNull { it as? JsonObject }.forEach { entry ->
            val name = entry["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val value = entry["value"]?.jsonPrimitive?.contentOrNull ?: ""
            require(name !in out) { "Duplicate ACP terminal environment key: $name" }
            out[name] = value
        }
        return out
    }

    override fun close() = cancelActive()

    private companion object {
        const val MIN_OUTPUT_BYTES = 4 * 1024
        const val DEFAULT_OUTPUT_BYTES = 256 * 1024
        const val MAX_OUTPUT_BYTES = 1024 * 1024
        const val MAX_TERMINALS = 8
    }
}

internal enum class AcpPermissionKind { MUTATION, EXECUTE }

// One-shot bridge between ACP's permission request and the immediately following client operation.



internal class AcpPermissionLedger {
    private data class Grant(
        val toolCallId: String,
        val operationFingerprint: String,
        val expiresAt: Long,
    )

    private val lock = Any()
    private val grants = mutableMapOf<AcpPermissionKind, Grant>()

    fun grant(kind: AcpPermissionKind, toolCallId: String, operationFingerprint: String) {
        require(toolCallId.isNotBlank() && toolCallId.length <= 512)
        require(operationFingerprint.matches(Regex("[0-9a-f]{64}")))
        synchronized(lock) {
            grants[kind] = Grant(
                toolCallId = toolCallId,
                operationFingerprint = operationFingerprint,
                expiresAt = System.currentTimeMillis() + GRANT_TTL_MS,
            )
        }
    }

    fun consume(kind: AcpPermissionKind, operationFingerprint: String): Boolean = synchronized(lock) {
        val grant = grants.remove(kind) ?: return@synchronized false
        System.currentTimeMillis() <= grant.expiresAt &&
            constantTimeEquals(grant.operationFingerprint, operationFingerprint)
    }

    fun clear() = synchronized(lock) { grants.clear() }

    companion object {
        private const val GRANT_TTL_MS = 15_000L

        fun mutationFingerprint(canonicalRemotePath: String, content: String): String = digest(
            listOf("mutation", canonicalRemotePath, content),
        )

        fun executeFingerprint(
            command: String,
            args: List<String>,
            environment: Map<String, String>,
            canonicalCwd: String,
        ): String = digest(
            buildList {
                add("execute")
                add(command)
                add(canonicalCwd)
                add(args.size.toString())
                addAll(args)
                add(environment.size.toString())
                environment.toSortedMap().forEach { (key, value) ->
                    add(key)
                    add(value)
                }
            },
        )

        private fun digest(parts: List<String>): String {
            val md = MessageDigest.getInstance("SHA-256")
            parts.forEach { part ->
                val bytes = part.toByteArray(Charsets.UTF_8)
                md.update(((bytes.size ushr 24) and 0xff).toByte())
                md.update(((bytes.size ushr 16) and 0xff).toByte())
                md.update(((bytes.size ushr 8) and 0xff).toByte())
                md.update((bytes.size and 0xff).toByte())
                md.update(bytes)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
    }
}
