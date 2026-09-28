package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// The Agent must run verify/retry afterwards and receive a healthy tools/list result before claiming the MCP server is fixed.















class McpRepairEngine(
    private val workDir: File,
    private val plugins: AgentPluginSource = AgentPluginSource.EMPTY,
    private val processHost: StdioProcessHost? = null,
) {
    data class ConfigRepairPreview(
        val serverName: String,
        val permissionResource: String,
        val summary: String,
        val detail: String,
        val expectedBeforeSha256: String,
        val afterSha256: String,
        val beforeText: String,
        val afterText: String,
    )

    data class RollbackPreview(
        val serverName: String,
        val repairId: String,
        val permissionResource: String,
        val summary: String,
        val detail: String,
        val expectedCurrentSha256: String,
        val restoreText: String,
    )

    private val manager = McpManager(workDir, plugins, processHost)

    fun status(serverName: String? = null): String {
        manager.list()
        val rows = McpHealthRegistry.observe(workDir).value
            .filter { it.workspaceIssue || serverName.isNullOrBlank() || it.serverName == serverName }
        return buildJsonObject {
            put("proof", "health_snapshot_only")
            put("workloadExecuted", false)
            put("servers", buildJsonArray {
                rows.forEach { item ->
                    add(buildJsonObject {
                        put("server", item.serverName)
                        put("state", item.state.name)
                        put("phase", item.phase.name)
                        put("message", item.message)
                        item.detail?.let { put("detail", it) }
                        item.toolsCount?.let { put("tools", it) }
                        item.protocolVersion?.let { put("protocolVersion", it) }
                        item.command?.let { put("command", it) }
                        put("source", item.source)
                        put("mutableByRepairEngine", item.source == WORKSPACE_SOURCE && !item.workspaceIssue)
                        put("lastCheckedAtMs", item.lastCheckedAtMs)
                    })
                }
            })
        }.toString().take(MAX_RESULT_CHARS)
    }

    fun diagnose(serverName: String): String {
        val servers = manager.list()
        val server = servers.firstOrNull { it.name == serverName }
        if (server == null) {
            val issue = McpHealthRegistry.observe(workDir).value.firstOrNull { it.workspaceIssue }
            return buildJsonObject {
                put("server", serverName)
                put("diagnosis", if (issue != null) "workspace_configuration_error" else "server_not_configured")
                put("workloadExecuted", false)
                put("repairable", issue != null)
                issue?.let { put("healthMessage", it.message); it.detail?.let { detail -> put("healthDetail", detail) } }
                put("next", if (issue != null) "Inspect and repair .droide/mcp.json with normal file-edit approval, then verify the intended server." else "Inspect .droide/mcp.json or the contributing Agent plugin.")
            }.toString()
        }
        val health = McpHealthRegistry.observe(workDir).value.firstOrNull { it.serverName == serverName }
        val executionBound = processHost?.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64
        val recommendations = recommendations(health, executionBound, server.source == WORKSPACE_SOURCE)
        return buildJsonObject {
            put("server", server.name)
            put("proof", "static_diagnosis_only")
            put("workloadExecuted", false)
            put("source", server.source)
            put("repairableConfig", server.source == WORKSPACE_SOURCE)
            put("command", server.argv.first())
            put("executionBound", executionBound)
            put("executionScope", processHost?.executionScope?.name ?: "UNBOUND")
            put("executableAvailability", "verify_on_device_workstation")
            put("protocolPreference", server.protocol.name)
            health?.let {
                put("healthState", it.state.name)
                put("healthPhase", it.phase.name)
                put("healthMessage", it.message)
                it.detail?.let { detail -> put("healthDetail", detail) }
            }
            put("recommendedActions", buildJsonArray { recommendations.forEach { add(JsonPrimitive(it)) } })
            put("verificationRequiredForFixedClaim", true)
        }.toString().take(MAX_RESULT_CHARS)
    }

    suspend fun verify(serverName: String, clearCatalog: Boolean = false): String {
        if (clearCatalog) McpToolRegistry.invalidate(workDir, serverName)
        val tools = manager.discoverTools(serverName)
        val health = McpHealthRegistry.observe(workDir).value.firstOrNull { it.serverName == serverName }
        return buildJsonObject {
            put("server", serverName)
            put("verified", health?.state == McpHealthState.CONNECTED)
            put("workloadExecuted", true)
            put("operation", if (clearCatalog) "refresh_catalog_and_verify" else "verify")
            put("tools", tools.size)
            health?.protocolVersion?.let { put("protocolVersion", it) }
            put("healthState", health?.state?.name ?: "UNKNOWN")
            put("healthPhase", health?.phase?.name ?: "UNKNOWN")
            put("fixedClaimAllowed", health?.state == McpHealthState.CONNECTED)
        }.toString().take(MAX_RESULT_CHARS)
    }

    fun previewConfigRepair(serverName: String, patch: JsonObject): ConfigRepairPreview {
        val server = manager.list().firstOrNull { it.name == serverName }
            ?: error("MCP server not found: $serverName")
        require(server.source == WORKSPACE_SOURCE) {
            "MCP server '$serverName' is contributed by ${server.source}; the repair engine may only mutate workspace MCP configuration."
        }
        val file = configFile()
        require(file.isFile && file.length() in 1..MAX_CONFIG_BYTES.toLong()) { "Workspace MCP config is unavailable or too large" }
        val before = file.readText()
        val beforeSha = sha256(before)
        val after = applyPatch(before, serverName, patch)
        val afterSha = sha256(after)
        require(afterSha != beforeSha) { "Repair patch does not change the MCP configuration" }
        val patchDescription = describePatch(patch)
        return ConfigRepairPreview(
            serverName = serverName,
            permissionResource = McpRepairPolicy.repairPermissionResource(serverName, beforeSha, afterSha),
            summary = "Repair MCP configuration: $serverName",
            detail = "Workspace .droide/mcp.json will be changed transactionally. $patchDescription A rollback snapshot will be created. Verification is required afterwards.",
            expectedBeforeSha256 = beforeSha,
            afterSha256 = afterSha,
            beforeText = before,
            afterText = after,
        )
    }

    fun applyConfigRepair(preview: ConfigRepairPreview): String = synchronized(repairLock()) {
        val file = configFile()
        val current = file.takeIf(File::isFile)?.readText() ?: error("Workspace MCP config disappeared before repair")
        require(sha256(current) == preview.expectedBeforeSha256) {
            "MCP configuration changed after approval; repair aborted before writing."
        }
        val repairId = snapshotStore().save(
            serverName = preview.serverName,
            beforeText = preview.beforeText,
            afterSha256 = preview.afterSha256,
        )
        atomicWrite(file, preview.afterText)
        McpToolRegistry.invalidate(workDir, preview.serverName)
        manager.list()
        buildJsonObject {
            put("server", preview.serverName)
            put("repairApplied", true)
            put("repairId", repairId)
            put("verificationRequired", true)
            put("fixedClaimAllowed", false)
            put("next", "Run mcp operation=verify. The updated configuration has a new permission identity and must be trusted independently before execution.")
        }.toString()
    }

    fun previewRollback(serverName: String, repairId: String): RollbackPreview {
        require(REPAIR_ID.matches(repairId)) { "Invalid MCP repair id" }
        val snapshot = snapshotStore().load(repairId) ?: error("MCP repair snapshot not found or expired")
        require(snapshot.serverName == serverName) { "Repair snapshot belongs to a different MCP server" }
        val file = configFile()
        val current = file.takeIf(File::isFile)?.readText() ?: error("Workspace MCP config is unavailable")
        val currentSha = sha256(current)
        require(currentSha == snapshot.afterSha256) {
            "MCP configuration changed after this repair; rollback refuses to overwrite newer user changes."
        }
        val restoreSha = sha256(snapshot.beforeText)
        return RollbackPreview(
            serverName = serverName,
            repairId = repairId,
            permissionResource = McpRepairPolicy.rollbackPermissionResource(serverName, repairId, currentSha, restoreSha),
            summary = "Rollback MCP repair: $serverName",
            detail = "Restore the exact pre-repair .droide/mcp.json snapshot. Newer configuration changes are protected by a hash check. Verification is required afterwards.",
            expectedCurrentSha256 = currentSha,
            restoreText = snapshot.beforeText,
        )
    }

    fun applyRollback(preview: RollbackPreview): String = synchronized(repairLock()) {
        val file = configFile()
        val current = file.takeIf(File::isFile)?.readText() ?: error("Workspace MCP config is unavailable")
        require(sha256(current) == preview.expectedCurrentSha256) {
            "MCP configuration changed after rollback approval; rollback aborted before writing."
        }
        atomicWrite(file, preview.restoreText)
        snapshotStore().delete(preview.repairId)
        McpToolRegistry.invalidate(workDir, preview.serverName)
        manager.list()
        buildJsonObject {
            put("server", preview.serverName)
            put("rollbackApplied", true)
            put("verificationRequired", true)
            put("fixedClaimAllowed", false)
            put("next", "Run mcp operation=verify before claiming the server is healthy.")
        }.toString()
    }

    private fun recommendations(health: McpHealthSnapshot?, executableAvailable: Boolean, mutable: Boolean): List<String> {
        val out = linkedSetOf<String>()
        if (!executableAvailable) {
            out += "environment_probe"
            out += "install_or_configure_runtime_via_normal_toolchain_or_shell_permission"
            if (mutable) out += "repair_config_command_if_an_existing_runtime_should_be_used"
            out += "verify"
            return out.toList()
        }
        when (health?.state) {
            McpHealthState.CONNECTED -> Unit
            McpHealthState.DISABLED -> out += "request_mcp_permission"
            McpHealthState.DEGRADED -> { out += "refresh_catalog"; out += "verify" }
            McpHealthState.NEEDS_AUTH -> out += "start_user_authentication_flow"
            McpHealthState.ERROR -> when (health.phase) {
                McpHealthPhase.PROTOCOL -> { if (mutable) out += "repair_config_protocol"; out += "verify" }
                McpHealthPhase.CONFIGURATION -> { if (mutable) out += "repair_workspace_config" else out += "repair_contributing_plugin" }
                else -> { out += "retry"; out += "verify" }
            }
            McpHealthState.UNAVAILABLE -> { out += "environment_probe"; out += "verify" }
            else -> out += "verify"
        }
        return out.toList()
    }

    private fun applyPatch(beforeText: String, serverName: String, patch: JsonObject): String {
        require(patch.isNotEmpty() && patch.keys.all { it in McpRepairPolicy.patchKeys }) { "MCP repair patch supports only command, args and protocol" }
        val root = McpJson.parseObject(beforeText)
        val servers = root["servers"] as? JsonObject ?: error("MCP config requires object field 'servers'")
        val existing = servers[serverName] as? JsonObject ?: error("Workspace MCP server not found in .droide/mcp.json: $serverName")
        val mutable = existing.toMutableMap()
        patch["command"]?.let { value ->
            val command = value.jsonPrimitive.contentOrNull?.trim() ?: error("MCP command must be a string")
            require(McpRepairPolicy.safeCommand(command)) { "Unsafe or invalid MCP command" }
            mutable["command"] = JsonPrimitive(command)
        }
        patch["args"]?.let { value ->
            val array = value as? JsonArray ?: error("MCP args must be an array")
            require(array.size <= MAX_ARG_COUNT) { "Too many MCP args" }
            val normalized = array.map { element ->
                val arg = element.jsonPrimitive.contentOrNull ?: error("MCP args must contain strings")
                require(McpRepairPolicy.validArgument(arg)) { "Invalid MCP argument" }
                JsonPrimitive(arg)
            }
            mutable["args"] = JsonArray(normalized)
        }
        patch["protocol"]?.let { value ->
            val protocol = McpRepairPolicy.normalizeProtocol(value.jsonPrimitive.contentOrNull ?: error("MCP protocol must be a string"))
            mutable["protocol"] = JsonPrimitive(protocol)
        }
        val nextServers = servers.toMutableMap().apply { put(serverName, JsonObject(mutable)) }
        val nextRoot = root.toMutableMap().apply { put("servers", JsonObject(nextServers)) }
        return JsonObject(nextRoot).toString() + "\n"
    }

    private fun describePatch(patch: JsonObject): String {
        val pieces = mutableListOf<String>()
        patch["command"]?.jsonPrimitive?.contentOrNull?.let { pieces += "command=${McpRepairPolicy.redactArgument(it)}" }
        (patch["args"] as? JsonArray)?.let { args -> pieces += "args=${args.size} item(s) (values hidden from approval preview)" }
        patch["protocol"]?.jsonPrimitive?.contentOrNull?.let { pieces += "protocol=${McpRepairPolicy.normalizeProtocol(it)}" }
        return pieces.joinToString("; ").take(1_200)
    }

    private fun configFile(): File = PathSecurity.resolveWithin(workDir, ".droide/mcp.json")

    private fun snapshotStore(): McpRepairSnapshotStore = McpRepairSnapshotStore(workDir)

    private fun repairLock(): Any {
        val key = runCatching { workDir.canonicalPath }.getOrElse { workDir.absolutePath }
        return REPAIR_LOCKS.computeIfAbsent(key) { Any() }
    }

    private fun atomicWrite(target: File, content: String) {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) { "MCP config exceeds size limit" }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.repair-${System.nanoTime()}.tmp")
        try {
            temp.writeText(content)
            runCatching {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private object McpJson {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }
        fun parseObject(text: String): JsonObject = json.parseToJsonElement(text) as? JsonObject ?: error("MCP config root must be an object")
    }

    companion object {
        private const val WORKSPACE_SOURCE = "Workspace"
        private const val MAX_CONFIG_BYTES = 100_000
        private const val MAX_RESULT_CHARS = 32_000
        private const val MAX_ARG_COUNT = 64
        private val REPAIR_ID = Regex("mcp-[a-z0-9-]{8,80}")
        private val REPAIR_LOCKS = ConcurrentHashMap<String, Any>()
    }
}

