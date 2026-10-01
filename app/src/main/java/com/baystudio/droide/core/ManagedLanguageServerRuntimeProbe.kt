package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ManagedLanguageServerProbeResult(
    val command: String,
    val capabilities: Set<String>,
)

 
internal object ManagedLanguageServerProbePolicy {
    fun validateInitialize(result: JsonElement): Set<String> {
        val negotiated = LspNegotiatedCapabilities.fromInitialize(result)
        val usable = linkedSetOf<String>()
        if (negotiated.completion) usable += "completion"
        if (negotiated.signatureHelp) usable += "signatureHelp"
        if (negotiated.hover) usable += "hover"
        if (negotiated.definition) usable += "definition"
        if (negotiated.references) usable += "references"
        if (negotiated.implementation) usable += "implementation"
        if (negotiated.documentSymbol) usable += "documentSymbol"
        if (negotiated.workspaceSymbol) usable += "workspaceSymbol"
        if (negotiated.semanticTokens) usable += "semanticTokens"
        if (negotiated.codeAction) usable += "codeAction"
        if (negotiated.callHierarchy) usable += "callHierarchy"
        if (negotiated.rename) usable += "rename"
        if (negotiated.formatting) usable += "formatting"
        if (negotiated.pullDiagnostics) usable += "diagnostics"
        require(usable.isNotEmpty()) {
            "Language server initialized but advertised no Droide-usable code-intelligence capability"
        }
        return usable
    }
}

// A version command alone is not enough: the exact package-owned executable must complete LSP init.


class ManagedLanguageServerRuntimeProbe(
    private val scope: CoroutineScope,
    private val workDir: File,
    private val bridge: DeviceBridgeManager,
    private val development: AndroidDevelopmentManager,
) {
    suspend fun verify(
        plan: ManagedLanguageServerPlanEntry,
        record: ManagedPackageRecord,
    ): ManagedLanguageServerProbeResult = withTimeout(PROBE_TIMEOUT_MS) {
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        require(record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name) {
            "Managed language-server probe requires Device Workstation scope"
        }
        require(record.familyId == plan.packageFamilyId && record.version == plan.pinnedVersion) {
            "Managed language-server record does not match the pinned plan"
        }
        val spec = LanguageServerRegistry.builtIns.firstOrNull { it.id == plan.languageServerId }
            ?: error("Unknown language server ${plan.languageServerId}")
        val launch = spec.commands.firstOrNull { it.firstOrNull() == plan.command }
            ?: error("Language-server registry does not expose ${plan.command}")
        val executable = record.commands[plan.command]
            ?: error("Managed package does not own command ${plan.command}")
        require(executable.startsWith(record.installRoot.trimEnd('/') + "/")) {
            "Managed language-server executable is outside its package root"
        }
        DeviceBridgeManager.requireSafeRemotePath(executable)
        require(ProcessSecurityPolicy.isAllowedRemoteExecutable(executable, DeviceBridgeManager.remoteRoot())) {
            "Managed language-server executable is outside Droide/system roots"
        }

        val processHost = DeviceWorkstationProcessHost(bridge, development, workDir, scope)
        val mapper = processHost.pathMapper()
        val rootUri = mapper.remoteUriFor(workDir)
        var rpc: JsonRpcProcess? = null
        try {
            val process = JsonRpcProcess(
                scope = scope,
                workDir = workDir,
                argv = listOf(executable) + launch.drop(1),
                processHost = processHost,
                inboundRequestHandler = { method, params ->
                    when (method) {
                        "workspace/configuration" -> {
                            val count = ((params as? JsonObject)?.get("items") as? JsonArray)?.size ?: 0
                            buildJsonArray { repeat(count) { add(JsonNull) } }
                        }
                        "workspace/workspaceFolders" -> buildJsonArray {
                            add(buildJsonObject { put("uri", rootUri); put("name", workDir.name.ifBlank { "workspace" }) })
                        }
                        "client/registerCapability", "client/unregisterCapability" ->
                            error("Dynamic registration was not advertised by Droide")
                        "window/workDoneProgress/create" -> JsonNull
                        "workspace/applyEdit" -> buildJsonObject {
                            put("applied", false)
                            put("failureReason", "Server-driven workspace edits require explicit IDE review")
                        }
                        "window/showMessageRequest" -> JsonNull
                        else -> JsonNull
                    }
                },
            )
            rpc = process
            process.start()
            val result = process.request(
                "initialize",
                buildJsonObject {
                    put("processId", JsonNull)
                    put("clientInfo", buildJsonObject { put("name", "Droide Runtime Probe"); put("version", "1.00") })
                    put("rootUri", rootUri)
                    put("workspaceFolders", buildJsonArray {
                        add(buildJsonObject { put("uri", rootUri); put("name", workDir.name.ifBlank { "workspace" }) })
                    })
                    put("capabilities", LspProtocolContract.clientCapabilities())
                    put("initializationOptions", buildJsonObject {})
                    put("trace", "off")
                },
                timeoutMs = INITIALIZE_TIMEOUT_MS,
            )
            val capabilities = ManagedLanguageServerProbePolicy.validateInitialize(result)
            process.notify("initialized", buildJsonObject {})
            delay(POST_INITIALIZED_STABILITY_MS)
            check(process.isRunning) {
                "Language server exited immediately after initialized${stderrSuffix(process)}"
            }
            process.request("shutdown", timeoutMs = SHUTDOWN_TIMEOUT_MS)
            process.notify("exit")
            ManagedLanguageServerProbeResult(plan.command, capabilities)
        } catch (failure: Throwable) {
            val suffix = rpc?.let(::stderrSuffix).orEmpty()
            val message = failure.message.orEmpty()
            if (suffix.isNotEmpty() && !message.contains(suffix)) {
                throw IllegalStateException(message.ifBlank { "Managed language-server runtime probe failed" } + suffix, failure)
            }
            throw failure
        } finally {
            rpc?.close()
        }
    }

    private fun stderrSuffix(rpc: JsonRpcProcess): String = rpc.stderr.trim().take(2_000)
        .takeIf(String::isNotEmpty)
        ?.let { " | stderr: $it" }
        .orEmpty()

    companion object {
        private const val INITIALIZE_TIMEOUT_MS = 20_000L
        private const val SHUTDOWN_TIMEOUT_MS = 7_000L
        private const val PROBE_TIMEOUT_MS = 35_000L
        private const val POST_INITIALIZED_STABILITY_MS = 120L
    }
}
