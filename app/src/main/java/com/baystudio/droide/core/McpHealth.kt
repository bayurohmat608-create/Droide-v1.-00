package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class McpHealthState {
    CONFIGURED,
    STARTING,
    CONNECTED,
    NEEDS_AUTH,
    DEGRADED,
    UNAVAILABLE,
    ERROR,
    DISABLED,
}

enum class McpHealthPhase {
    CONFIGURATION,
    ENVIRONMENT,
    PERMISSION,
    PROCESS,
    PROTOCOL,
    DISCOVERY,
    READY,
    TOOL_CALL,
}

data class McpHealthSnapshot(
    val serverName: String,
    val state: McpHealthState,
    val phase: McpHealthPhase,
    val message: String,
    val detail: String? = null,
    val toolsCount: Int? = null,
    val protocolVersion: String? = null,
    val command: String? = null,
    val source: String = "Workspace",
    val configFingerprint: String? = null,
    val lastCheckedAtMs: Long = System.currentTimeMillis(),
    val workspaceIssue: Boolean = false,
) {
    val requiresAttention: Boolean
        get() = state == McpHealthState.STARTING || state == McpHealthState.NEEDS_AUTH || state == McpHealthState.DEGRADED
    val isError: Boolean
        get() = state == McpHealthState.UNAVAILABLE || state == McpHealthState.ERROR
}

// It must never persist tokens, headers or full argv.



object McpHealthRegistry {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<List<McpHealthSnapshot>>>()

    fun observe(workDir: File): StateFlow<List<McpHealthSnapshot>> =
        flows.computeIfAbsent(workspaceKey(workDir)) { MutableStateFlow(emptyList()) }.asStateFlow()

    fun syncConfigured(workDir: File, servers: List<McpServer>) {
        val flow = flow(workDir)
        val existing = flow.value.associateBy { it.serverName }
        val keptIssues = flow.value.filter { it.workspaceIssue }
        val next = servers.map { server ->
            val previous = existing[server.name]
            when {
                previous != null && previous.configFingerprint == server.configFingerprint && !previous.workspaceIssue ->
                    previous.copy(command = server.argv.first(), source = server.source)
                else -> McpHealthSnapshot(
                    serverName = server.name,
                    state = McpHealthState.CONFIGURED,
                    phase = McpHealthPhase.CONFIGURATION,
                    message = "Configured · Device Workstation executable not checked yet",
                    command = server.argv.first(),
                    source = server.source,
                    configFingerprint = server.configFingerprint,
                )
            }
        }
        flow.value = (keptIssues + next).sortedWith(compareBy<McpHealthSnapshot>({ it.workspaceIssue }, { it.serverName.lowercase() }))
    }

    fun clearConfigIssue(workDir: File) = mutate(workDir) { current -> current.filterNot { it.workspaceIssue } }

    fun configIssue(workDir: File, message: String, detail: String? = null) = upsert(
        workDir,
        McpHealthSnapshot(
            serverName = "MCP configuration",
            state = McpHealthState.ERROR,
            phase = McpHealthPhase.CONFIGURATION,
            message = message.take(240),
            detail = sanitize(detail),
            workspaceIssue = true,
        ),
    )

    fun permissionDeferred(workDir: File, server: McpServer, denied: Boolean = false) = updateServer(workDir, server.name) { previous ->
        base(previous, server).copy(
            state = if (denied) McpHealthState.DISABLED else McpHealthState.CONFIGURED,
            phase = McpHealthPhase.PERMISSION,
            message = if (denied) "Disabled by Agent permission" else "Configured · permission required before start",
            detail = null,
            lastCheckedAtMs = System.currentTimeMillis(),
        )
    }

    fun starting(workDir: File, server: McpServer, phase: McpHealthPhase, message: String) = updateServer(workDir, server.name) { previous ->
        base(previous, server).copy(
            state = McpHealthState.STARTING,
            phase = phase,
            message = message.take(240),
            detail = null,
            lastCheckedAtMs = System.currentTimeMillis(),
        )
    }

    fun connected(workDir: File, server: McpServer, toolsCount: Int, protocolVersion: String?) = updateServer(workDir, server.name) { previous ->
        base(previous, server).copy(
            state = McpHealthState.CONNECTED,
            phase = McpHealthPhase.READY,
            message = "Connected · $toolsCount tool${if (toolsCount == 1) "" else "s"}",
            detail = null,
            toolsCount = toolsCount,
            protocolVersion = protocolVersion ?: previous?.protocolVersion,
            lastCheckedAtMs = System.currentTimeMillis(),
        )
    }

    fun sessionClosed(workDir: File, serverName: String, reason: String) = mutate(workDir) { current ->
        current.map { previous ->
            if (previous.serverName != serverName || previous.workspaceIssue || previous.state !in setOf(McpHealthState.CONNECTED, McpHealthState.STARTING)) previous
            else previous.copy(
                state = McpHealthState.CONFIGURED,
                phase = McpHealthPhase.CONFIGURATION,
                message = "Configured · session closed ($reason)",
                toolsCount = null,
                lastCheckedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun failure(workDir: File, server: McpServer, phase: McpHealthPhase, failure: Throwable) = updateServer(workDir, server.name) { previous ->
        val text = failure.message.orEmpty()
        val unavailable = text.contains("executable not found", ignoreCase = true) ||
            text.contains("executable not available", ignoreCase = true) ||
            text.contains("execution host is not bound", ignoreCase = true)
        val toolLevel = phase == McpHealthPhase.TOOL_CALL && !text.contains("timed out", true) &&
            !text.contains("exited", true) && !text.contains("invalid JSON", true) &&
            !text.contains("process is not running", true)
        base(previous, server).copy(
            state = when {
                unavailable -> McpHealthState.UNAVAILABLE
                toolLevel -> McpHealthState.DEGRADED
                else -> McpHealthState.ERROR
            },
            phase = if (unavailable) McpHealthPhase.ENVIRONMENT else phase,
            message = humanMessage(text, unavailable, toolLevel),
            detail = sanitize(text),
            lastCheckedAtMs = System.currentTimeMillis(),
        )
    }

    private fun humanMessage(text: String, unavailable: Boolean, toolLevel: Boolean): String = when {
        unavailable -> "Executable not found"
        text.contains("timed out", true) -> "Server response timed out"
        text.contains("exited", true) -> "Server process exited"
        text.contains("invalid JSON", true) -> "Invalid MCP protocol output"
        text.contains("protocol", true) || text.contains("initialize", true) || text.contains("discover", true) -> "Protocol negotiation failed"
        toolLevel -> "Tool call failed; server may still be available"
        text.isBlank() -> "MCP operation failed"
        else -> text.substringBefore(';').take(120)
    }

    private fun base(previous: McpHealthSnapshot?, server: McpServer): McpHealthSnapshot = previous?.takeIf {
        it.configFingerprint == server.configFingerprint && !it.workspaceIssue
    } ?: McpHealthSnapshot(
        serverName = server.name,
        state = McpHealthState.CONFIGURED,
        phase = McpHealthPhase.CONFIGURATION,
        message = "Configured · starts on demand",
        command = server.argv.first(),
        source = server.source,
        configFingerprint = server.configFingerprint,
    )

    private fun updateServer(workDir: File, serverName: String, transform: (McpHealthSnapshot?) -> McpHealthSnapshot) = mutate(workDir) { current ->
        val previous = current.firstOrNull { it.serverName == serverName && !it.workspaceIssue }
        (current.filterNot { it.serverName == serverName && !it.workspaceIssue } + transform(previous))
            .sortedWith(compareBy<McpHealthSnapshot>({ it.workspaceIssue }, { it.serverName.lowercase() }))
    }

    private fun upsert(workDir: File, snapshot: McpHealthSnapshot) = mutate(workDir) { current ->
        (current.filterNot { it.serverName == snapshot.serverName && it.workspaceIssue == snapshot.workspaceIssue } + snapshot)
            .sortedWith(compareBy<McpHealthSnapshot>({ it.workspaceIssue }, { it.serverName.lowercase() }))
    }

    private fun mutate(workDir: File, block: (List<McpHealthSnapshot>) -> List<McpHealthSnapshot>) {
        val flow = flow(workDir)
        synchronized(flow) { flow.value = block(flow.value) }
    }

    private fun flow(workDir: File): MutableStateFlow<List<McpHealthSnapshot>> =
        flows.computeIfAbsent(workspaceKey(workDir)) { MutableStateFlow(emptyList()) }

    private fun workspaceKey(workDir: File): String = runCatching { workDir.canonicalPath }.getOrElse { workDir.absolutePath }

    private fun sanitize(value: String?): String? {
        val text = value
            ?.replace(Regex("""(?i)authorization\s*[:=]\s*bearer\s+[^\s;,]+"""), "Authorization: Bearer <redacted>")
            ?.replace(Regex("""(?i)(token|api[_-]?key|secret|password)\s*[:=]\s*[^\s;,]+"""), "\$1=<redacted>")
            ?.replace(Regex("""(?i)(access_token|refresh_token)=([^&\s]+)"""), "\$1=<redacted>")
            ?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.take(1_000)
        return text?.takeIf { it.isNotBlank() }
    }
}
