package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class PluginPermission {
    WORKSPACE_READ,
    WORKSPACE_WRITE,
    PROCESS_EXEC,
    NETWORK,
    DEVICE_BRIDGE,
}

@Serializable
data class DroidePluginCommand(
    val id: String,
    val title: String,
)

@Serializable
data class DroidePluginManifest(
    val schema: Int = 1,
    val id: String,
    val version: String,
    val packageFamilyId: String,
    val entryCommand: String,
    val permissions: Set<PluginPermission> = emptySet(),
    val commands: List<DroidePluginCommand> = emptyList(),
) {
    fun validate() {
        require(schema == 1) { "Unsupported plugin manifest schema" }
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{1,119}"))) { "Invalid plugin id" }
        require(version.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid plugin version" }
        require(packageFamilyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid plugin package family" }
        require(entryCommand.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid plugin entry command" }
        require(commands.size <= 128) { "Too many plugin commands" }
        require(commands.map { it.id }.distinct().size == commands.size) { "Duplicate plugin command id" }
        require(commands.all { it.id.matches(Regex("[a-z0-9][a-z0-9._-]{1,159}")) && it.title.isNotBlank() && it.title.length <= 160 }) {
            "Invalid plugin command"
        }
    }
}

// moves executable lifecycle ownership into the referenced API.


class ManagedPluginRuntime(
    scope: CoroutineScope,
    workDir: File,
    files: FileRepository,
    processHost: StdioProcessHost,
    limits: ExtensionHostLimits = ExtensionHostLimits(),
) : AutoCloseable {
    private val host = ExecutableExtensionHost(
        scope = scope,
        workDir = workDir,
        files = files,
        processHost = processHost,
        limits = limits,
    )

    suspend fun startReviewedMarketplace(
        entry: PluginMarketplaceEntry,
        manifestBytes: ByteArray,
        records: List<ManagedPackageRecord>,
        grantedPermissions: Set<PluginPermission>,
    ) = host.startReviewedMarketplace(entry, manifestBytes, records, grantedPermissions)

    suspend fun executeCommand(commandId: String, arguments: List<JsonElement> = emptyList()): JsonElement =
        host.executeCommand(commandId, arguments)

    suspend fun executeCommand(pluginId: String, commandId: String, arguments: List<JsonElement> = emptyList()): JsonElement =
        host.executeCommand(pluginId, commandId, arguments)

    fun isRunning(pluginId: String): Boolean = host.isRunning(pluginId)
    fun grantedPermissions(pluginId: String): Set<PluginPermission> = host.grantedPermissions(pluginId)
    fun runningPluginIds(): Set<String> = host.runningPluginIds()
    fun snapshot(pluginId: String): ExtensionHostSnapshot = host.snapshot(pluginId)
    fun stop(pluginId: String) = host.stop(pluginId)
    override fun close() = host.close()
}
