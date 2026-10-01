package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
enum class ExtensionHostIsolationKind {
    // A crash cannot crash Droide, but this is not an OS security sandbox.
    PROCESS_SEPARATE_REVIEWED,
}

@Serializable
enum class ExtensionHostState {
    STOPPED,
    STARTING,
    RUNNING,
    COOLDOWN,
    FAILED,
}

@Serializable
data class ExtensionHostLimits(
    val startupTimeoutMs: Long = 8_000,
    val commandTimeoutMs: Long = 20_000,
    val maxProtocolMessageBytes: Int = 2 * 1024 * 1024,
    val maxCommandArguments: Int = 64,
    val maxArgumentChars: Int = 128_000,
    val maxWorkspaceReadBytes: Int = 1_000_000,
    val maxWorkspaceWriteBytes: Int = 2_000_000,
    val maxRestartsPerWindow: Int = 3,
    val restartWindowMs: Long = 60_000,
    val restartCooldownMs: Long = 30_000,
    val processCpuSeconds: Int = 120,
    val processFileSizeKiB: Int = 64 * 1024,
    val processOpenFiles: Int = 256,
    val processCount: Int = 64,
    val processVirtualMemoryKiB: Int = 768 * 1024,
) {
    fun validate() {
        require(startupTimeoutMs in 1_000L..30_000L) { "Invalid extension-host startup timeout" }
        require(commandTimeoutMs in 1_000L..120_000L) { "Invalid extension-host command timeout" }
        require(maxProtocolMessageBytes in 64 * 1024..ContentLengthProtocol.MAX_MESSAGE_BYTES) { "Invalid extension-host message limit" }
        require(maxCommandArguments in 1..128) { "Invalid extension-host argument count" }
        require(maxArgumentChars in 1_024..1_000_000) { "Invalid extension-host argument budget" }
        require(maxWorkspaceReadBytes in 1_024..4_000_000) { "Invalid extension-host read budget" }
        require(maxWorkspaceWriteBytes in 1_024..4_000_000) { "Invalid extension-host write budget" }
        require(maxRestartsPerWindow in 1..10) { "Invalid extension-host restart budget" }
        require(restartWindowMs in 10_000L..10L * 60_000L) { "Invalid extension-host restart window" }
        require(restartCooldownMs in 5_000L..10L * 60_000L) { "Invalid extension-host restart cooldown" }
        processResourceLimits().validate()
    }

    fun processResourceLimits(): ProcessResourceLimits = ProcessResourceLimits(
        cpuSeconds = processCpuSeconds,
        fileSizeKiB = processFileSizeKiB,
        openFiles = processOpenFiles,
        processes = processCount,
        virtualMemoryKiB = processVirtualMemoryKiB,
    )
}

@Serializable
data class ExtensionHostSnapshot(
    val pluginId: String,
    val state: ExtensionHostState,
    val isolation: ExtensionHostIsolationKind = ExtensionHostIsolationKind.PROCESS_SEPARATE_REVIEWED,
    val grantedPermissions: Set<PluginPermission> = emptySet(),
    val startedAtEpochMs: Long? = null,
    val recentCrashCount: Int = 0,
    val cooldownUntilEpochMs: Long? = null,
    val lastError: String? = null,
)

// The host deliberately treats process separation and security isolation as different concepts.


class ExecutableExtensionHost(
    private val scope: CoroutineScope,
    private val workDir: File,
    private val files: FileRepository,
    private val processHost: StdioProcessHost,
    private val limits: ExtensionHostLimits = ExtensionHostLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private data class HostSession(
        val rpc: JsonRpcProcess,
        val manifest: DroidePluginManifest,
        val grants: Set<PluginPermission>,
        val commandMutex: Mutex = Mutex(),
        val startedAtEpochMs: Long,
        val exitWatcher: Job,
    )

    private data class FailureLedger(
        val crashes: ArrayDeque<Long> = ArrayDeque(),
        var cooldownUntilEpochMs: Long = 0L,
        var lastError: String? = null,
    )

    private val sessions = linkedMapOf<String, HostSession>()
    private val failures = linkedMapOf<String, FailureLedger>()
    private val states = linkedMapOf<String, ExtensionHostState>()

    init {
        limits.validate()
    }

    suspend fun startReviewedMarketplace(
        entry: PluginMarketplaceEntry,
        manifestBytes: ByteArray,
        records: List<ManagedPackageRecord>,
        grantedPermissions: Set<PluginPermission>,
    ) {
        PluginMarketplaceResolver.requireExecutableTrust(entry)
        val plugin = PluginMarketplaceResolver.parseAndVerifyManifest(entry, manifestBytes)
        val record = PluginMarketplaceResolver.resolveInstalled(entry, records)
        startVerified(record, plugin, grantedPermissions)
    }

    private suspend fun startVerified(
        record: ManagedPackageRecord,
        plugin: DroidePluginManifest,
        grantedPermissions: Set<PluginPermission>,
    ) {
        plugin.validate()
        require(record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name) { "Extensions must run in Device Workstation" }
        require(record.active) { "Extension managed package is not active" }
        require(record.familyId == plugin.packageFamilyId) { "Extension package family mismatch" }
        require(plugin.permissions.containsAll(grantedPermissions)) { "Cannot grant permissions the extension did not declare" }
        require(grantedPermissions.containsAll(plugin.permissions)) { "All declared extension permissions require explicit grant" }
        val executable = record.commands[plugin.entryCommand] ?: error("Extension entry command is not owned by the managed package")
        require(executable.startsWith(record.installRoot.trimEnd('/') + "/")) { "Extension executable escapes its managed package" }
        DeviceBridgeManager.requireSafeRemotePath(executable)
        requireStartBudget(plugin.id)

        val previous = synchronized(this) {
            states[plugin.id] = ExtensionHostState.STARTING
            sessions.remove(plugin.id)
        }
        previous?.exitWatcher?.cancel()
        previous?.rpc?.close()

        lateinit var rpc: JsonRpcProcess
        rpc = JsonRpcProcess(
            scope = scope,
            workDir = workDir,
            argv = listOf(executable, "--stdio"),
            processHost = processHost,
            maxMessageBytes = limits.maxProtocolMessageBytes,
            stderrBytes = 64 * 1024,
            resourceLimits = limits.processResourceLimits(),
            inboundRequestHandler = { method, params ->
                handleInbound(plugin, grantedPermissions, method, params)
            },
        )
        try {
            rpc.start()
            val response = rpc.request(
                "droide/initialize",
                buildJsonObject {
                    put("client", "Droide")
                    put("protocol", 2)
                    put("pluginId", plugin.id)
                    put("pluginVersion", plugin.version)
                    put("isolation", ExtensionHostIsolationKind.PROCESS_SEPARATE_REVIEWED.name)
                    put("permissions", buildJsonArray { grantedPermissions.sortedBy { it.name }.forEach { add(it.name) } })
                    put("limits", buildJsonObject {
                        put("maxMessageBytes", limits.maxProtocolMessageBytes)
                        put("maxWorkspaceReadBytes", limits.maxWorkspaceReadBytes)
                        put("maxWorkspaceWriteBytes", limits.maxWorkspaceWriteBytes)
                        put("commandTimeoutMs", limits.commandTimeoutMs)
                        put("cpuSeconds", limits.processCpuSeconds)
                        put("virtualMemoryKiB", limits.processVirtualMemoryKiB)
                    })
                },
                timeoutMs = limits.startupTimeoutMs,
            )
            require(response !is JsonNull) { "Extension did not acknowledge initialization" }
            val startedAt = clock()
            val watcher = scope.launch(start = CoroutineStart.LAZY) {
                val exit = runCatching { rpc.awaitExit() }.getOrDefault(1)
                onUnexpectedExit(plugin.id, exit, rpc.stderr)
            }
            synchronized(this) {
                sessions[plugin.id] = HostSession(rpc, plugin, grantedPermissions.toSet(), Mutex(), startedAt, watcher)
                states[plugin.id] = ExtensionHostState.RUNNING
            }
            watcher.start()
        } catch (t: Throwable) {
            rpc.close()
            recordFailure(plugin.id, t.message ?: t::class.java.simpleName)
            throw t
        }
    }

    suspend fun executeCommand(pluginId: String, commandId: String, arguments: List<JsonElement> = emptyList()): JsonElement {
        val session = synchronized(this) { sessions[pluginId] } ?: error("Extension host is not running: $pluginId")
        require(session.manifest.commands.any { it.id == commandId }) { "Extension command is not declared: $commandId" }
        require(arguments.size <= limits.maxCommandArguments) { "Too many extension command arguments" }
        val argumentChars = arguments.sumOf { it.toString().length }
        require(argumentChars <= limits.maxArgumentChars) { "Extension command arguments exceed host budget" }
        return session.commandMutex.withLock {
            try {
                session.rpc.request(
                    "droide/executeCommand",
                    buildJsonObject {
                        put("command", commandId)
                        put("arguments", JsonArray(arguments))
                    },
                    timeoutMs = limits.commandTimeoutMs,
                )
            } catch (timeout: TimeoutCancellationException) {
                stop(pluginId, "command timeout")
                throw IllegalStateException("Extension command timed out and the host was stopped: $commandId", timeout)
            } catch (t: Throwable) {
                throw t
            }
        }
    }

    suspend fun executeCommand(commandId: String, arguments: List<JsonElement> = emptyList()): JsonElement {
        val owners = synchronized(this) {
            sessions.values.filter { session -> session.manifest.commands.any { it.id == commandId } }
        }
        require(owners.size == 1) {
            if (owners.isEmpty()) "Extension command is not active: $commandId" else "Extension command is ambiguous: $commandId"
        }
        return executeCommand(owners.single().manifest.id, commandId, arguments)
    }

    @Synchronized
    fun isRunning(pluginId: String): Boolean = sessions[pluginId]?.rpc?.isRunning == true

    @Synchronized
    fun runningPluginIds(): Set<String> = sessions.filterValues { it.rpc.isRunning }.keys.toSet()

    @Synchronized
    fun grantedPermissions(pluginId: String): Set<PluginPermission> = sessions[pluginId]?.grants.orEmpty()

    @Synchronized
    fun snapshot(pluginId: String): ExtensionHostSnapshot {
        pruneFailures(pluginId, clock())
        val ledger = failures[pluginId]
        val session = sessions[pluginId]
        val now = clock()
        val state = when {
            session?.rpc?.isRunning == true -> ExtensionHostState.RUNNING
            ledger != null && ledger.cooldownUntilEpochMs > now -> ExtensionHostState.COOLDOWN
            else -> states[pluginId] ?: ExtensionHostState.STOPPED
        }
        return ExtensionHostSnapshot(
            pluginId = pluginId,
            state = state,
            grantedPermissions = session?.grants.orEmpty(),
            startedAtEpochMs = session?.startedAtEpochMs,
            recentCrashCount = ledger?.crashes?.size ?: 0,
            cooldownUntilEpochMs = ledger?.cooldownUntilEpochMs?.takeIf { it > now },
            lastError = ledger?.lastError,
        )
    }

    fun stop(pluginId: String, reason: String = "stopped") {
        val removed = synchronized(this) {
            states[pluginId] = ExtensionHostState.STOPPED
            sessions.remove(pluginId)
        }
        removed?.exitWatcher?.cancel()
        removed?.rpc?.close()
        synchronized(this) { failures[pluginId]?.lastError = reason.take(500) }
    }

    override fun close() {
        val all = synchronized(this) {
            val result = sessions.values.toList()
            sessions.clear()
            states.keys.toList().forEach { states[it] = ExtensionHostState.STOPPED }
            result
        }
        all.forEach { session -> session.exitWatcher.cancel(); session.rpc.close() }
    }

    private suspend fun handleInbound(
        plugin: DroidePluginManifest,
        grants: Set<PluginPermission>,
        method: String,
        params: JsonElement?,
    ): JsonElement = when (method) {
        "droide/grantedPermissions" -> buildJsonArray { grants.sortedBy { it.name }.forEach { add(it.name) } }
        "droide/hostInfo" -> buildJsonObject {
            put("protocol", 2)
            put("isolation", ExtensionHostIsolationKind.PROCESS_SEPARATE_REVIEWED.name)
            put("hardSandbox", false)
        }
        "droide/workspace/readText" -> {
            requirePermission(plugin, grants, PluginPermission.WORKSPACE_READ)
            val obj = params.asObject("workspace/readText")
            val path = requireWorkspacePath(obj.string("path"))
            val maxBytes = (obj["maxBytes"]?.jsonPrimitive?.intOrNull ?: limits.maxWorkspaceReadBytes)
                .coerceIn(1_024, limits.maxWorkspaceReadBytes)
            requireReadableWorkspacePath(path)
            val content = files.readTextForEdit(path, maxBytes.toLong())
            buildJsonObject {
                put("path", path)
                put("content", content)
                put("sha256", sha256(content.toByteArray(Charsets.UTF_8)))
            }
        }
        "droide/workspace/listFiles" -> {
            requirePermission(plugin, grants, PluginPermission.WORKSPACE_READ)
            val obj = params.asObject("workspace/listFiles")
            val path = requireWorkspacePath(obj.string("path", allowBlank = true))
            val maxEntries = (obj["maxEntries"]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(1, 256)
            if (path.isNotBlank()) requireReadableWorkspacePath(path)
            val entries = files.listFiles(path, maxEntries)
                .filterNot { entry -> isProtectedWorkspacePath(entry.path) }
            buildJsonArray {
                entries.forEach { entry ->
                    add(buildJsonObject {
                        put("path", entry.path)
                        put("directory", entry.isDir)
                        put("bytes", entry.size)
                        put("modifiedAtEpochMs", entry.modified)
                    })
                }
            }
        }
        "droide/workspace/writeText" -> {
            requirePermission(plugin, grants, PluginPermission.WORKSPACE_WRITE)
            val obj = params.asObject("workspace/writeText")
            val path = requireWorkspacePath(obj.string("path"))
            require(!isProtectedWorkspacePath(path)) {
                "Extension write targets a protected workspace path"
            }
            val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: error("Missing extension parameter: content")
            require(content.toByteArray(Charsets.UTF_8).size <= limits.maxWorkspaceWriteBytes) {
                "Extension workspace write exceeds host budget"
            }
            val expected = obj["expectedSha256"]?.jsonPrimitive?.contentOrNull
            if (expected != null) {
                require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid expected workspace SHA-256" }
                val actual = if (files.exists(path)) {
                    sha256(files.readTextForEdit(path, limits.maxWorkspaceWriteBytes.toLong()).toByteArray(Charsets.UTF_8))
                } else {
                    EMPTY_SHA256
                }
                require(actual == expected) { "Workspace file changed since the extension read it" }
            }
            files.writeText(path, content)
            buildJsonObject {
                put("path", path)
                put("sha256", sha256(content.toByteArray(Charsets.UTF_8)))
            }
        }
        else -> error("Unsupported extension host request: $method")
    }

    private fun requireReadableWorkspacePath(path: String) {
        require(!isProtectedWorkspacePath(path)) { "Extension read targets a protected workspace path" }
    }

    private fun isProtectedWorkspacePath(path: String): Boolean =
        path == ".droide" || path.startsWith(".droide/") || SensitivePathPolicy.isSensitive(path)

    private fun requirePermission(plugin: DroidePluginManifest, grants: Set<PluginPermission>, permission: PluginPermission) {
        require(permission in plugin.permissions && permission in grants) {
            "Extension '${plugin.id}' lacks ${permission.name} permission"
        }
    }

    private fun requireWorkspacePath(raw: String): String {
        require(!raw.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(raw)) { "Extension workspace path must be relative" }
        val normalized = raw.replace('\\', '/')
        if (normalized.isBlank()) return ""
        require(normalized.split('/').none { it == "." || it == ".." || it.isBlank() }) { "Invalid extension workspace path" }
        require(normalized.length <= 1_000 && '\u0000' !in normalized && '\n' !in normalized && '\r' !in normalized) {
            "Invalid extension workspace path"
        }
        val target = PathSecurity.resolveWithin(files.root, normalized)
        require(PathSecurity.contains(files.root, target)) { "Extension workspace path escapes project" }
        return files.root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
    }

    private fun JsonElement?.asObject(label: String): JsonObject = this as? JsonObject
        ?: error("Invalid $label parameters")

    private fun JsonObject.string(key: String, allowBlank: Boolean = false): String {
        val value = this[key]?.jsonPrimitive?.contentOrNull ?: if (allowBlank) "" else error("Missing extension parameter: $key")
        require(value.length <= 2_000 && '\u0000' !in value && '\n' !in value && '\r' !in value) { "Invalid extension parameter: $key" }
        if (!allowBlank) require(value.isNotBlank()) { "Empty extension parameter: $key" }
        return value
    }

    private fun requireStartBudget(pluginId: String) {
        val now = clock()
        synchronized(this) {
            pruneFailures(pluginId, now)
            val ledger = failures.getOrPut(pluginId) { FailureLedger() }
            require(ledger.cooldownUntilEpochMs <= now) {
                "Extension host is cooling down after repeated crashes: $pluginId"
            }
            require(ledger.crashes.size < limits.maxRestartsPerWindow) {
                ledger.cooldownUntilEpochMs = now + limits.restartCooldownMs
                states[pluginId] = ExtensionHostState.COOLDOWN
                "Extension host restart budget exhausted: $pluginId"
            }
        }
    }

    private fun onUnexpectedExit(pluginId: String, exitCode: Int, stderr: String) {
        val removed = synchronized(this) { sessions.remove(pluginId) }
        if (removed == null) return
        val detail = buildString {
            append("process exited with code ").append(exitCode)
            val tail = stderr.trim().takeLast(1_000)
            if (tail.isNotBlank()) append(": ").append(tail)
        }
        recordFailure(pluginId, detail)
    }

    private fun recordFailure(pluginId: String, error: String) {
        val now = clock()
        synchronized(this) {
            val ledger = failures.getOrPut(pluginId) { FailureLedger() }
            pruneFailures(pluginId, now)
            ledger.crashes.addLast(now)
            ledger.lastError = error.take(1_000)
            if (ledger.crashes.size >= limits.maxRestartsPerWindow) {
                ledger.cooldownUntilEpochMs = now + limits.restartCooldownMs
                states[pluginId] = ExtensionHostState.COOLDOWN
            } else {
                states[pluginId] = ExtensionHostState.FAILED
            }
        }
    }

    private fun pruneFailures(pluginId: String, now: Long) {
        val ledger = failures[pluginId] ?: return
        while (ledger.crashes.isNotEmpty() && now - ledger.crashes.peekFirst() > limits.restartWindowMs) {
            ledger.crashes.removeFirst()
        }
        if (ledger.cooldownUntilEpochMs in 1..now) {
            ledger.cooldownUntilEpochMs = 0L
            ledger.crashes.clear()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val EMPTY_SHA256: String = MessageDigest.getInstance("SHA-256")
            .digest(ByteArray(0)).joinToString("") { "%02x".format(it) }
    }
}
