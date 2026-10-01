package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UniversalToolDiscoveryCertificationStep(
    val id: String,
    val outcome: String,
    val durationMs: Long,
    val detail: String,
)

@Serializable
data class UniversalToolDiscoveryCertificationReport(
    val schema: Int = 1,
    val generatedAtEpochMs: Long,
    val projectId: String,
    val device: AndroidDeviceIdentitySnapshot,
    val postflightDevice: AndroidDeviceIdentitySnapshot,
    val pathExecutable: String,
    val offPathExecutable: String,
    val contributedKinds: List<String>,
    val steps: List<UniversalToolDiscoveryCertificationStep>,
    val evidenceDigestSha256: String = "",
) {
    fun validate(requireDigest: Boolean = true) {
        require(schema == 1) { "Unsupported universal-tool certification schema" }
        require(generatedAtEpochMs > 0) { "Invalid universal-tool certification timestamp" }
        require(projectId.isNotBlank() && projectId.length <= 160) { "Invalid project id" }
        device.validate(requirePhysical = true)
        postflightDevice.validate(requirePhysical = true)
        require(device.samePhysicalIdentity(postflightDevice)) { "Physical Android identity changed during universal-tool certification" }
        listOf(pathExecutable, offPathExecutable).forEach { path ->
            require(ProcessSecurityPolicy.isAllowedRemoteExecutable(path, DeviceBridgeManager.remoteRoot())) {
                "Certification executable escaped the Droide workstation sandbox"
            }
        }
        require(contributedKinds.toSet() == DroideToolKind.entries.map { it.name }.toSet()) {
            "Universal-tool certification did not cover every contributed tool kind"
        }
        require(steps.map { it.id } == REQUIRED_STEPS) { "Universal-tool certification steps are incomplete or out of order" }
        require(steps.all { it.outcome == "PASS" }) { "Universal-tool certification contains a non-PASS step" }
        require(steps.all { it.durationMs in 0..MAX_STEP_MS && it.detail.length <= 4_000 }) { "Invalid universal-tool certification step" }
        if (requireDigest) {
            require(evidenceDigestSha256.matches(HEX64)) { "Invalid universal-tool certification digest" }
            require(evidenceDigestSha256 == evidenceDigest(this)) { "Universal-tool certification digest mismatch" }
        }
    }

    fun withEvidenceDigest(): UniversalToolDiscoveryCertificationReport {
        validate(requireDigest = false)
        return copy(evidenceDigestSha256 = evidenceDigest(this)).also { it.validate() }
    }

    companion object {
        val REQUIRED_STEPS = listOf(
            "physical-preflight",
            "manual-path-discovery",
            "manual-offpath-discovery",
            "ambiguity-rejection",
            "extension-protocol-binding",
            "all-tool-kinds-execution",
            "agent-same-registry",
            "postflight-cleanup",
        )
        internal const val MAX_STEP_MS = 10L * 60L * 1_000L
        internal val HEX64 = Regex("[0-9a-f]{64}")

        fun evidenceDigest(report: UniversalToolDiscoveryCertificationReport): String {
            val canonical = buildString {
                append("schema=").append(report.schema).append('\n')
                append("generatedAtEpochMs=").append(report.generatedAtEpochMs).append('\n')
                append("projectId=").append(report.projectId).append('\n')
                append("device=").append(report.device.propertiesSha256).append('\n')
                append("postflightDevice=").append(report.postflightDevice.propertiesSha256).append('\n')
                append("pathExecutable=").append(report.pathExecutable).append('\n')
                append("offPathExecutable=").append(report.offPathExecutable).append('\n')
                append("contributedKinds=").append(report.contributedKinds.sorted().joinToString(",")).append('\n')
                report.steps.forEachIndexed { index, step ->
                    append("step[").append(index).append("]=")
                        .append(step.id).append('|').append(step.outcome).append('|').append(step.durationMs).append('|')
                        .append(sha256(step.detail)).append('\n')
                }
            }
            return sha256(canonical)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}


class UniversalToolDiscoveryCertificationManager(
    context: Context,
    private val projectId: String,
    private val development: AndroidDevelopmentManager,
    private val bridge: DeviceBridgeManager,
    private val capabilities: UniversalCapabilityRegistry,
    private val execution: BuildRunDebugCoordinator,
) {
    private val evidence = UniversalToolDiscoveryEvidenceStore(context.applicationContext, projectId)
    private val mutex = Mutex()
    private val identity = AndroidDeviceIdentityCollector(bridge)

    suspend fun certify(): UniversalToolDiscoveryCertificationReport = mutex.withLock {
        check(bridge.state.value.connected != null) { "Connect Device Workstation before running verification" }
        val config = development.prepareInteractiveShell(syncProject = false)
        val preflight = identity.collect(requirePhysical = true)
        val root = DeviceBridgeManager.remoteRoot()
        val nonce = java.lang.Long.toHexString(System.nanoTime()).takeLast(10).padStart(10, '0')
        val extensionId = "tool-discovery.cert.$nonce"
        val pathCommand = "toolp$nonce"
        val offCommand = "toolu$nonce"
        val ambiguousCommand = "toola$nonce"
        val pathExecutable = "$root/user/bin/$pathCommand"
        val base = "$root/user/tool-discovery-certification/$nonce"
        val offPathExecutable = "$base/unique/$offCommand"
        val ambiguousA = "$base/a/$ambiguousCommand"
        val ambiguousB = "$base/b/$ambiguousCommand"
        listOf(pathExecutable, offPathExecutable, ambiguousA, ambiguousB).forEach(DeviceBridgeManager::requireSafeRemotePath)
        val steps = mutableListOf<UniversalToolDiscoveryCertificationStep>()
        var extensionRegistered = false

        suspend fun <T> step(id: String, block: suspend () -> Pair<T, String>): T {
            val started = System.currentTimeMillis()
            return try {
                val (value, detail) = block()
                steps += UniversalToolDiscoveryCertificationStep(id, "PASS", System.currentTimeMillis() - started, detail.take(4_000))
                value
            } catch (t: Throwable) {
                throw IllegalStateException("Verification failed at $id: ${t.message}", t)
            }
        }

        fun probeScript(marker: String) = "#!/system/bin/sh\nprintf '%s:%s\\n' ${DeviceBridgeManager.shellQuote(marker)} \"\$*\"\n"
        suspend fun createProbe(path: String, marker: String) {
            val parent = path.substringBeforeLast('/')
            val script = probeScript(marker)
            val result = bridge.shellBounded(
                "mkdir -p ${DeviceBridgeManager.shellQuote(parent)} && " +
                    "printf %s ${DeviceBridgeManager.shellQuote(script)} > ${DeviceBridgeManager.shellQuote(path)} && " +
                    "chmod 700 ${DeviceBridgeManager.shellQuote(path)}",
                maxOutputBytes = 64 * 1024,
            )
            check(result.exitCode == 0) { "Could not create verification probe: ${result.combined.take(1_000)}" }
        }

        try {
            step("physical-preflight") { preflight to "Physical Android identity captured via remote getprop (${preflight.manufacturer} ${preflight.model}, API ${preflight.api})." }
            createProbe(pathExecutable, "PATH")
            createProbe(offPathExecutable, "OFFPATH")
            createProbe(ambiguousA, "AMBIG-A")
            createProbe(ambiguousB, "AMBIG-B")

            val inventory = DeviceWorkstationExecutableProbe.inventory(bridge, config)
            step("manual-path-discovery") {
                check(inventory[pathCommand] == pathExecutable) { "User-bin PATH executable did not win authoritative discovery" }
                Unit to "PATH-authoritative manual executable resolved to $pathExecutable"
            }
            step("manual-offpath-discovery") {
                check(inventory[offCommand] == offPathExecutable) { "Unique safe off-PATH executable was not discovered" }
                Unit to "Unique safe-root fallback executable resolved to $offPathExecutable"
            }
            step("ambiguity-rejection") {
                check(ambiguousCommand !in inventory) { "Ambiguous off-PATH executable basename was guessed instead of rejected" }
                Unit to "Two safe-root executables sharing basename $ambiguousCommand were rejected as ambiguous"
            }

            capabilities.updateExternalExecutables(inventory)
            val languageId = "tool$nonce"
            val languageExtension = ".tool$nonce"
            val tools = DroideToolKind.entries.map { kind ->
                DroideToolContribution(
                    id = kind.name.lowercase(),
                    title = "${kind.name.lowercase()} verification probe",
                    kind = kind,
                    command = pathCommand,
                    args = listOf(kind.name.lowercase()),
                )
            }
            val manifest = DroideExtensionManifest(
                id = extensionId,
                version = "1.0.0",
                publisher = "droide-certification",
                contributes = DroideExtensionContributions(
                    languages = listOf(DroideLanguageContribution(languageId, extensions = setOf(languageExtension))),
                    languageServers = listOf(DroideLanguageServerContribution("lsp", setOf(languageId), pathCommand, listOf("lsp"))),
                    debuggers = listOf(DroideDebugAdapterContribution("dap", setOf(languageId), pathCommand, listOf("dap"))),
                    tools = tools,
                ),
            ).also(DroideExtensionManifest::validate)
            capabilities.registerExtension(manifest, enabled = true)
            extensionRegistered = true

            step("extension-protocol-binding") {
                val expectedSourcePrefix = "extension:$extensionId"
                val lsp = capabilities.languageServers().singleOrNull { it.id == "$expectedSourcePrefix:lsp" }
                    ?: error("LSP contribution was not registered")
                val dap = capabilities.debugAdapters().singleOrNull { it.id == "$expectedSourcePrefix:dap" }
                    ?: error("DAP contribution was not registered")
                val lspCommand = lsp.commands.single().first()
                val dapCommand = dap.commandCandidates.single().first()
                check(lspCommand == pathCommand && dapCommand == pathCommand) { "Protocol contributions diverged from the contributed executable" }
                check(DeviceWorkstationExecutableProbe.resolve(bridge, config, lspCommand) == pathExecutable) { "LSP command did not resolve through workstation discovery" }
                check(DeviceWorkstationExecutableProbe.resolve(bridge, config, dapCommand) == pathExecutable) { "DAP command did not resolve through workstation discovery" }
                Unit to "Extension language/LSP/DAP metadata bound to the same discovered PATH executable without guessing binary semantics"
            }

            step("all-tool-kinds-execution") {
                DroideToolKind.entries.forEach { kind ->
                    val toolId = "extension:$extensionId:${kind.name.lowercase()}"
                    val plan = capabilities.toolPlan(toolId) ?: error("${kind.name} contribution did not produce a resolved plan")
                    check(plan.steps.singleOrNull()?.firstOrNull() == pathExecutable) { "${kind.name} plan was not bound to exact discovered path" }
                    val output = execution.executeContributedTool(toolId)
                    check("PATH:${kind.name.lowercase()}" in output) { "${kind.name} contribution did not execute through Device Workstation" }
                }
                Unit to "RUN/FORMATTER/LINTER/BUILD/TEST/CLI all executed through the same resolved executable capability"
            }

            step("agent-same-registry") {
                val agentCapabilities = AgentCapabilityActions(capabilities, execution)
                val prepared = agentCapabilities.prepareRun("extension:$extensionId:cli", null)
                check(prepared.executable.resolvedPath == pathExecutable) { "Agent capability preparation diverged from IDE executable resolution" }
                val output = agentCapabilities.run(prepared)
                check("PATH:cli" in output) { "Agent did not execute the same contributed CLI capability" }
                Unit to "Agent preparation and execution revalidated the exact same Universal Capability Registry binding"
            }

            capabilities.unregisterExtension(extensionId)
            extensionRegistered = false
            val cleanup = bridge.shellBounded(
                "rm -f ${DeviceBridgeManager.shellQuote(pathExecutable)} && rm -rf ${DeviceBridgeManager.shellQuote(base)}",
                maxOutputBytes = 64 * 1024,
            )
            check(cleanup.exitCode == 0) { "Verification probe cleanup failed: ${cleanup.combined.take(1_000)}" }
            val refreshed = DeviceWorkstationExecutableProbe.inventory(bridge, config)
            capabilities.updateExternalExecutables(refreshed)
            val postflight = identity.collect(requirePhysical = true)
            step("postflight-cleanup") {
                check(preflight.samePhysicalIdentity(postflight)) { "Device identity changed during verification" }
                check(pathCommand !in refreshed && offCommand !in refreshed && ambiguousCommand !in refreshed) { "Verification probe remained after cleanup" }
                Unit to "Probe extension and executables removed; capability inventory refreshed; physical target remained stable"
            }

            UniversalToolDiscoveryCertificationReport(
                generatedAtEpochMs = System.currentTimeMillis(),
                projectId = projectId,
                device = preflight,
                postflightDevice = postflight,
                pathExecutable = pathExecutable,
                offPathExecutable = offPathExecutable,
                contributedKinds = DroideToolKind.entries.map { it.name },
                steps = steps.toList(),
            ).withEvidenceDigest().also(evidence::persist)
        } finally {
            withContext(NonCancellable) {
                if (extensionRegistered) capabilities.unregisterExtension(extensionId)
                try {
                    bridge.shellBounded(
                        "rm -f ${DeviceBridgeManager.shellQuote(pathExecutable)} && rm -rf ${DeviceBridgeManager.shellQuote(base)}",
                        maxOutputBytes = 64 * 1024,
                    )
                    capabilities.updateExternalExecutables(DeviceWorkstationExecutableProbe.inventory(bridge, config))
                } catch (_: Throwable) {
                    // Best-effort cleanup must never mask the original certification failure.
                }
            }
        }
    }

    fun latestReport(): UniversalToolDiscoveryCertificationReport? = evidence.latest()
    fun exportLatest(output: OutputStream): UniversalToolDiscoveryCertificationReport = evidence.exportLatest(output)
}

private class UniversalToolDiscoveryEvidenceStore(context: Context, projectId: String) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; prettyPrint = true }
    private val root = File(File(context.filesDir, "universal-tool-discovery-certification"), PathSecurity.safeLeafName(projectId))
    private val latest = File(root, "latest.json")

    fun persist(report: UniversalToolDiscoveryCertificationReport) {
        report.validate()
        check(root.isDirectory || root.mkdirs()) { "Could not create verification evidence directory" }
        val bytes = json.encodeToString(UniversalToolDiscoveryCertificationReport.serializer(), report).toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..1_000_000) { "Verification report is too large" }
        val temp = File(root, "latest.json.tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(latest) || run { latest.delete(); temp.renameTo(latest) }) { "Could not save verification evidence" }
    }

    fun latest(): UniversalToolDiscoveryCertificationReport? = runCatching {
        if (!latest.isFile || latest.length() !in 1..1_000_000) return@runCatching null
        json.decodeFromString(UniversalToolDiscoveryCertificationReport.serializer(), latest.readText(Charsets.UTF_8))
            .also(UniversalToolDiscoveryCertificationReport::validate)
    }.getOrNull()

    fun exportLatest(output: OutputStream): UniversalToolDiscoveryCertificationReport {
        val report = latest() ?: error("No validated device verification is available")
        output.write(json.encodeToString(UniversalToolDiscoveryCertificationReport.serializer(), report).toByteArray(Charsets.UTF_8))
        return report
    }
}
