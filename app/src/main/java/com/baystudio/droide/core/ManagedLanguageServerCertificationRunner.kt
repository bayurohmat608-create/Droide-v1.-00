package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ManagedPackageCertificationCheck(
    val id: String,
    val outcome: String,
    val durationMs: Long,
    val detail: String = "",
)





@Serializable
data class ManagedLanguageServerCertificationReport(
    val schema: Int = 1,
    val generatedAtEpochMs: Long,
    val artifactSha256: String,
    val familyId: String,
    val version: String,
    val abi: String,
    val androidApi: Int,
    val physicalDevice: Boolean,
    val deviceFingerprint: String,
    val languageServerId: String,
    val deviceIdentityPropertiesSha256: String,
    val bridgeEndpoint: String,
    val checks: List<ManagedPackageCertificationCheck>,
    val evidenceDigestSha256: String = "",
) {
    fun validate(requireDigest: Boolean = true) {
        require(schema == 1) { "Unsupported managed-package certification schema" }
        require(generatedAtEpochMs > 0) { "Invalid certification timestamp" }
        require(artifactSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid artifact SHA-256" }
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid family id" }
        require(version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))) { "Invalid version" }
        require(abi.matches(Regex("[A-Za-z0-9._-]{1,40}"))) { "Invalid ABI" }
        require(androidApi in 21..99) { "Invalid Android API" }
        require(physicalDevice) { "Managed package certification requires a physical Android device" }
        require(deviceFingerprint.length in 8..300) { "Invalid device fingerprint" }
        require(languageServerId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid language server id" }
        require(deviceIdentityPropertiesSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid device identity digest" }
        require(bridgeEndpoint.isNotBlank() && bridgeEndpoint.length <= 300) { "Invalid bridge endpoint" }
        require(checks.map { it.id } == REQUIRED_CHECKS) { "Certification checks are incomplete or out of order" }
        require(checks.all { it.outcome in setOf("PASS", "FAIL", "SKIP") }) { "Invalid certification outcome" }
        require(checks.all { it.durationMs in 0..120_000 && it.detail.length <= 4_000 }) { "Invalid certification check" }
        if (requireDigest) {
            require(evidenceDigestSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid certification digest" }
            require(evidenceDigestSha256 == evidenceDigest(this)) { "Managed package evidence digest mismatch" }
        }
    }

    val passed: Boolean get() = checks.all { it.outcome == "PASS" }

    fun withEvidenceDigest(): ManagedLanguageServerCertificationReport {
        validate(requireDigest = false)
        return copy(evidenceDigestSha256 = evidenceDigest(this)).also { it.validate() }
    }

    companion object {
        val REQUIRED_CHECKS = listOf("install", "integrity", "health", "launch", "protocol", "shutdown")

        fun evidenceDigest(report: ManagedLanguageServerCertificationReport): String {
            val canonical = buildString {
                append("schema=").append(report.schema).append('\n')
                append("generatedAtEpochMs=").append(report.generatedAtEpochMs).append('\n')
                append("artifactSha256=").append(report.artifactSha256).append('\n')
                append("familyId=").append(report.familyId).append('\n')
                append("version=").append(report.version).append('\n')
                append("abi=").append(report.abi).append('\n')
                append("androidApi=").append(report.androidApi).append('\n')
                append("physicalDevice=").append(report.physicalDevice).append('\n')
                append("deviceFingerprint=").append(report.deviceFingerprint).append('\n')
                append("languageServerId=").append(report.languageServerId).append('\n')
                append("deviceIdentityPropertiesSha256=").append(report.deviceIdentityPropertiesSha256).append('\n')
                append("bridgeEndpoint=").append(report.bridgeEndpoint).append('\n')
                report.checks.forEachIndexed { index, check ->
                    append("check[").append(index).append("]=")
                        .append(check.id).append('|')
                        .append(check.outcome).append('|')
                        .append(check.durationMs).append('|')
                        .append(AndroidDeviceIdentityRules.sha256(check.detail)).append('\n')
                }
            }
            return AndroidDeviceIdentityRules.sha256(canonical)
        }
    }
}

// The runner never mutates the production catalog.





class ManagedLanguageServerCertificationRunner(
    private val scope: CoroutineScope,
    private val workDir: File,
    private val bridge: DeviceBridgeManager,
    private val development: AndroidDevelopmentManager,
    private val registry: ManagedPackageRegistry,
    private val deviceIdentity: AndroidDeviceIdentityCollector = AndroidDeviceIdentityCollector(bridge),
) {
    suspend fun run(languageServerId: String): ManagedLanguageServerCertificationReport {
        val plan = ManagedLanguageServerPlan.forServer(languageServerId)
            ?: error("No managed package plan exists for language server $languageServerId")
        val record = registry.find(plan.packageFamilyId, plan.pinnedVersion)
            ?: error("Managed package ${plan.packageFamilyId}@${plan.pinnedVersion} is not installed")
        check(record.active) { "Managed package is installed but not active" }
        check(record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name) { "Managed LSP package must run in Device Workstation" }
        val artifactSha = record.artifactSha256 ?: error("Installed package predates artifact-hash receipts; reinstall the exact certified package")
        check(artifactSha.matches(Regex("[0-9a-f]{64}"))) { "Installed package has an invalid artifact receipt" }

        val identity = deviceIdentity.collect()
        check(identity.physical) { "Managed LSP certification requires a physical Android device" }
        val abi = record.abi ?: error("Installed package predates ABI receipts; reinstall the exact certified package")
        check(abi in identity.abis) { "Connected device does not advertise package ABI $abi" }

        val checks = mutableListOf<ManagedPackageCertificationCheck>()
        suspend fun checkStep(id: String, block: suspend () -> String): Boolean {
            val started = System.currentTimeMillis()
            return try {
                val detail = block().take(4_000)
                checks += ManagedPackageCertificationCheck(id, "PASS", System.currentTimeMillis() - started, detail)
                true
            } catch (t: Throwable) {
                checks += ManagedPackageCertificationCheck(id, "FAIL", System.currentTimeMillis() - started, (t.message ?: t::class.java.simpleName).take(4_000))
                false
            }
        }
        fun skipRemaining(fromExclusive: String) {
            val start = ManagedLanguageServerCertificationReport.REQUIRED_CHECKS.indexOf(fromExclusive) + 1
            ManagedLanguageServerCertificationReport.REQUIRED_CHECKS.drop(start).forEach { id ->
                if (checks.none { it.id == id }) checks += ManagedPackageCertificationCheck(id, "SKIP", 0, "Skipped after an earlier certification failure")
            }
        }

        val root = record.installRoot.trimEnd('/')
        DeviceBridgeManager.requireSafeRemotePath(root)
        val shell = development.prepareInteractiveShell(syncProject = false)

        if (!checkStep("install") {
                val result = bridge.shellBounded(
                    "test -d ${DeviceBridgeManager.shellQuote(root)} && " +
                        "test -s ${DeviceBridgeManager.shellQuote("$root/package.json")} && " +
                        "test -s ${DeviceBridgeManager.shellQuote("$root/${record.checksumFile}")}",
                    maxOutputBytes = 64 * 1024,
                )
                check(result.exitCode == 0) { "Installed package receipt/files are incomplete" }
                "installRoot=$root"
            }) {
            skipRemaining("install")
            return report(plan, record, artifactSha, abi, identity, checks)
        }

        if (!checkStep("integrity") {
                val result = bridge.shellBounded(
                    "cd ${DeviceBridgeManager.shellQuote(root)} && toybox sha256sum -c ${DeviceBridgeManager.shellQuote(record.checksumFile)}",
                    maxOutputBytes = 512 * 1024,
                )
                check(result.exitCode == 0) { "Installed package integrity verification failed: ${result.combined.take(2_000)}" }
                result.stdout
            }) {
            skipRemaining("integrity")
            return report(plan, record, artifactSha, abi, identity, checks)
        }

        if (!checkStep("health") {
                check(record.healthChecks.isNotEmpty()) { "Package declares no health checks" }
                val details = mutableListOf<String>()
                for (runtimeCommand in plan.requiredCommands.sorted()) {
                    require(runtimeCommand.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Unsafe runtime dependency command" }
                    val exists = bridge.shellBounded(
                        shell.shellPrefix() + "command -v ${DeviceBridgeManager.shellQuote(runtimeCommand)}",
                        maxOutputBytes = 32 * 1024,
                    )
                    check(exists.exitCode == 0 && exists.stdout.isNotBlank()) { "Required runtime command is unavailable: $runtimeCommand" }
                    details += "$runtimeCommand: ${exists.stdout.trim().lineSequence().firstOrNull().orEmpty().take(300)}"
                }
                for (requirement in plan.runtimeRequirements) {
                    val command = shell.shellPrefix() + (listOf(requirement.command) + requirement.versionArgs)
                        .joinToString(" ") { DeviceBridgeManager.shellQuote(it) }
                    val versionResult = bridge.shellBounded(command, maxOutputBytes = 32 * 1024)
                    check(versionResult.exitCode == 0) { "Could not read ${requirement.command} runtime version: ${versionResult.combined.take(1_000)}" }
                    val actual = RuntimeVersion.parseFirst(versionResult.combined)
                        ?: error("Could not parse ${requirement.command} runtime version")
                    val minimum = RuntimeVersion(requirement.minimumMajor, requirement.minimumMinor, requirement.minimumPatch)
                    check(actual >= minimum) { "${requirement.command} $actual is below required ${requirement.minimumVersion}" }
                    details += "${requirement.command}-version=$actual (minimum ${requirement.minimumVersion})"
                }
                record.healthChecks.take(32).forEach { health ->
                    val executable = "$root/payload/${health.executable}"
                    DeviceBridgeManager.requireSafeRemotePath(executable)
                    val command = shell.shellPrefix() + listOf(executable, *health.args.toTypedArray())
                        .joinToString(" ") { DeviceBridgeManager.shellQuote(it) }
                    val result = bridge.shellBounded(command, maxOutputBytes = 128 * 1024)
                    check(result.exitCode == 0) { "Health check failed for ${health.executable}: ${result.combined.take(1_000)}" }
                    details += "${health.executable}: ${result.combined.trim().take(500)}"
                }
                details.joinToString("\n")
            }) {
            skipRemaining("health")
            return report(plan, record, artifactSha, abi, identity, checks)
        }

        val spec = LanguageServerRegistry.builtIns.firstOrNull { it.id == languageServerId }
            ?: error("Language server registry entry disappeared: $languageServerId")
        val args = spec.commands.firstOrNull { it.firstOrNull() == plan.command }?.drop(1).orEmpty()
        val executable = record.commands[plan.command]
            ?: error("Installed package does not own expected command ${plan.command}")
        DeviceBridgeManager.requireSafeRemotePath(executable)
        val processHost = DeviceWorkstationProcessHost(bridge, development, workDir, scope)
        var rpc: JsonRpcProcess? = null

        val launched = checkStep("launch") {
            rpc = JsonRpcProcess(
                scope = scope,
                workDir = workDir,
                argv = listOf(executable) + args,
                environment = record.environment,
                processHost = processHost,
                inboundRequestHandler = { method, params ->
                    when (method) {
                        "workspace/configuration" -> {
                            val count = ((params as? kotlinx.serialization.json.JsonObject)?.get("items") as? kotlinx.serialization.json.JsonArray)?.size ?: 0
                            buildJsonArray { repeat(count) { add(JsonNull) } }
                        }
                        "workspace/workspaceFolders" -> buildJsonArray { }
                        "client/registerCapability", "client/unregisterCapability", "window/workDoneProgress/create" -> JsonNull
                        "workspace/applyEdit" -> buildJsonObject { put("applied", false); put("failureReason", "Certification does not permit server-driven edits") }
                        else -> JsonNull
                    }
                },
            ).also { it.start() }
            check(rpc?.isRunning == true) { "Language server exited during launch" }
            "${plan.command} ${args.joinToString(" ")}".trim()
        }
        if (!launched) {
            runCatching { rpc?.close() }
            skipRemaining("launch")
            return report(plan, record, artifactSha, abi, identity, checks)
        }

        val protocolPassed = checkStep("protocol") {
            val mapper = processHost.pathMapper()
            val rootUri = mapper.remoteUriFor(workDir)
            val liveRpc = rpc ?: error("RPC process is not running")
            val result = liveRpc.request(
                "initialize",
                buildJsonObject {
                    put("processId", JsonNull)
                    put("clientInfo", buildJsonObject { put("name", "Droide Certification"); put("version", "1") })
                    put("rootUri", rootUri)
                    put("workspaceFolders", buildJsonArray {
                        add(buildJsonObject { put("uri", rootUri); put("name", workDir.name.ifBlank { "workspace" }) })
                    })
                    put("capabilities", LspProtocolContract.clientCapabilities())
                    put("initializationOptions", buildJsonObject {})
                    put("trace", "off")
                },
                timeoutMs = 20_000,
            )
            check(result !is JsonNull) { "Language server returned a null initialize result" }
            val advertised = ManagedLanguageServerProbePolicy.validateInitialize(result)
            liveRpc.notify("initialized", buildJsonObject {})
            delay(80)
            check(liveRpc.isRunning) { "Language server exited immediately after initialized: ${liveRpc.stderr.take(1_500)}" }
            "initialize/initialized handshake completed; capabilities=${advertised.sorted().joinToString(",")}"
        }

        checkStep("shutdown") {
            try {
                if (protocolPassed && rpc?.isRunning == true) {
                    val liveRpc = rpc ?: error("RPC process is not running")
                    liveRpc.request("shutdown", timeoutMs = 10_000)
                    liveRpc.notify("exit")
                }
                "shutdown/exit sent"
            } finally {
                rpc?.close()
            }
        }
        if (!protocolPassed && checks.lastOrNull()?.id != "shutdown") skipRemaining("protocol")
        return report(plan, record, artifactSha, abi, identity, checks)
    }

    private fun report(
        plan: ManagedLanguageServerPlanEntry,
        record: ManagedPackageRecord,
        artifactSha: String,
        abi: String,
        identity: AndroidDeviceIdentitySnapshot,
        checks: List<ManagedPackageCertificationCheck>,
    ): ManagedLanguageServerCertificationReport = ManagedLanguageServerCertificationReport(
        generatedAtEpochMs = System.currentTimeMillis(),
        artifactSha256 = artifactSha,
        familyId = record.familyId,
        version = record.version,
        abi = abi,
        androidApi = identity.api,
        physicalDevice = identity.physical,
        deviceFingerprint = identity.fingerprint,
        languageServerId = plan.languageServerId,
        deviceIdentityPropertiesSha256 = identity.propertiesSha256,
        bridgeEndpoint = identity.bridgeEndpoint,
        checks = normalizeChecks(checks),
    ).withEvidenceDigest()

    private fun normalizeChecks(checks: List<ManagedPackageCertificationCheck>): List<ManagedPackageCertificationCheck> {
        val byId = checks.associateBy { it.id }
        return ManagedLanguageServerCertificationReport.REQUIRED_CHECKS.map { id ->
            byId[id] ?: ManagedPackageCertificationCheck(id, "SKIP", 0, "Certification did not reach this check")
        }
    }
}
