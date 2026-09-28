package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class WorkspaceAgentBootstrapResult(
    val process: HostedStdioProcess,
    val rpc: AcpNdjsonConnection,
    val sessionId: String,
    val approvalSessionId: String,
    val mapper: WorkspacePathMapper,
    val modes: List<String>,
)

 
internal object WorkspaceAgentSessionBootstrap {
    suspend fun start(
        spec: WorkspaceAgentPluginSpec,
        projectRoot: File,
        files: FileRepository,
        processHost: StdioProcessHost,
        bridge: DeviceBridgeManager,
        approvals: ApprovalManager,
        permissions: PermissionEngine,
        scope: CoroutineScope,
        resumeSessionId: String?,
        onEvent: (WorkspaceAgentEvent) -> Unit,
    ): WorkspaceAgentBootstrapResult {
        val executable = processHost.resolveExecutable(spec.command)
            ?: error("${spec.displayName} is not installed or not executable in Device Workstation")
        onEvent(WorkspaceAgentEvent.Status("Starting ${spec.displayName} ACP session…"))
        val mapper = requireNotNull(processHost.pathMapper()) { "Linux workspace mapper is unavailable" }
        val process = processHost.start(listOf(executable) + spec.args)
        val rpc = AcpNdjsonConnection(process, files, mapper, processHost, bridge, approvals, permissions, scope)
        try {
            val init = rpc.request(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", 1)
                    put("clientCapabilities", buildJsonObject {
                        put("fs", buildJsonObject { put("readTextFile", true); put("writeTextFile", true) })
                        put("terminal", true)
                    })
                    put("clientInfo", buildJsonObject { put("name", "Droide"); put("version", "1.00") })
                },
                timeoutMs = 30_000,
            )
            val negotiated = init["protocolVersion"]?.jsonPrimitive?.intOrNull
                ?: init["protocolVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            require(negotiated == 1) { "${spec.displayName} negotiated unsupported ACP protocol $negotiated" }
            val supportsLoad = (init["agentCapabilities"] as? JsonObject)
                ?.get("loadSession")?.jsonPrimitive?.booleanOrNull == true
            val mcpServers = projectMcpServers(projectRoot, mapper, processHost)
            val sessionResult: JsonObject
            val sessionId: String
            if (resumeSessionId != null) {
                require(supportsLoad) { "${spec.displayName} cannot resume ACP sessions; start a new session instead" }
                require(resumeSessionId.isNotBlank() && resumeSessionId.length <= 512) { "Invalid ACP resume session id" }
                sessionResult = rpc.request(
                    "session/load",
                    buildJsonObject {
                        put("sessionId", resumeSessionId)
                        put("cwd", mapper.remoteRoot)
                        put("mcpServers", mcpServers)
                    },
                    timeoutMs = 30_000,
                )
                sessionId = resumeSessionId
                onEvent(WorkspaceAgentEvent.Status("Resumed ${spec.displayName} ACP session."))
            } else {
                sessionResult = rpc.request(
                    "session/new",
                    buildJsonObject { put("cwd", mapper.remoteRoot); put("mcpServers", mcpServers) },
                    timeoutMs = 30_000,
                )
                sessionId = sessionResult["sessionId"]?.jsonPrimitive?.contentOrNull
                    ?: error("ACP agent did not return a sessionId")
            }
            val approvalSessionId = "acp-${sha256(agentId = spec.id, sessionId = sessionId)}"
            rpc.bindSession(sessionId, approvalSessionId)
            return WorkspaceAgentBootstrapResult(
                process, rpc, sessionId, approvalSessionId, mapper, extractModes(sessionResult),
            )
        } catch (failure: Throwable) {
            rpc.close()
            process.close()
            throw failure
        }
    }

    private suspend fun projectMcpServers(
        projectRoot: File,
        mapper: WorkspacePathMapper,
        processHost: StdioProcessHost,
    ): JsonArray {
        val servers = McpManager(projectRoot, AgentPluginSource.EMPTY).list().take(MAX_EXTERNAL_MCP_SERVERS)
        return buildJsonArray {
            servers.forEach { server ->
                val executable = runSuspendCatching { processHost.resolveExecutable(server.argv.first()) }.getOrNull()
                    ?: return@forEach
                val args = mutableListOf<String>()
                for (raw in server.argv.drop(1)) args += mapArgument(projectRoot, raw, mapper) ?: return@forEach
                if (runCatching { ProcessSecurityPolicy.validateArgv(listOf(executable) + args) }.isFailure) return@forEach
                add(buildJsonObject {
                    put("name", server.name.take(80))
                    put("command", executable)
                    put("args", JsonArray(args.map(::JsonPrimitive)))
                    put("env", JsonArray(emptyList()))
                })
            }
        }
    }

    private fun mapArgument(projectRoot: File, raw: String, mapper: WorkspacePathMapper): String? {
        if (raw.length > 8_000 || raw.any { it == '\u0000' || it == '\n' || it == '\r' }) return null
        if (raw.contains(".droide/.runtime-agent-plugins") || raw.startsWith(".droide/")) return null
        val root = projectRoot.canonicalFile
        val absolute = File(raw)
        if (absolute.isAbsolute) {
            val canonical = runCatching { absolute.canonicalFile }.getOrNull() ?: return null
            if (!PathSecurity.contains(root, canonical)) return null
            if (!visible(canonical.relativeTo(root).invariantSeparatorsPath)) return null
            return mapper.localToRemote(canonical)
        }
        val candidate = runCatching { PathSecurity.resolveWithin(root, raw) }.getOrNull()
        if (candidate?.exists() == true) {
            val canonical = candidate.canonicalFile
            if (!visible(canonical.relativeTo(root).invariantSeparatorsPath)) return null
            return mapper.localToRemote(canonical)
        }
        return raw
    }

    private fun visible(relativePath: String): Boolean =
        relativePath.replace('\\', '/').substringBefore('/') !in setOf(".git", ".gradle", "build", ".droide", "node_modules")

    private fun extractModes(result: JsonObject): List<String> {
        val modeObject = result["modes"] as? JsonObject ?: return emptyList()
        val available = modeObject["availableModes"] as? JsonArray ?: return emptyList()
        return available.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
    }

    private fun sha256(agentId: String, sessionId: String): String = MessageDigest.getInstance("SHA-256")
        .digest((agentId + "\u0000" + sessionId).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private const val MAX_EXTERNAL_MCP_SERVERS = 16
}
