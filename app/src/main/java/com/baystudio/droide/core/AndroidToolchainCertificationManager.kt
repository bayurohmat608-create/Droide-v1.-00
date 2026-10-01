package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json








class AndroidToolchainCertificationManager(
    context: Context,
    private val development: AndroidDevelopmentManager,
    private val bridge: DeviceBridgeManager,
) {
    enum class Level { SMOKE, FULL }
    enum class Outcome { PASS, FAIL, SKIP }

    @Serializable
    data class StepResult(
        val id: String,
        val title: String,
        val outcome: String,
        val durationMs: Long,
        val detail: String = "",
    )

    @Serializable
    data class Report(
        val schema: Int = 3,
        val generatedAtEpochMs: Long,
        val level: String,
        val passed: Boolean,
        val packSha256: String,
        val toolchainVersion: String,
        val candidateId: String? = null,
        val sourceLockSha256: String? = null,
        val executionMode: String,
        val abi: String,
        val compileSdk: Int,
        val javaVersion: Int,
        val deviceManufacturer: String,
        val deviceModel: String,
        val deviceName: String,
        val androidApi: Int,
        val deviceAbis: List<String>,
        val devicePhysical: Boolean,
        val deviceFingerprint: String,
        val deviceIdentitySource: String = "",
        val bridgeEndpoint: String = "",
        val identityPropertiesSha256: String = "",
        val deviceProperties: Map<String, String> = emptyMap(),
        val evidenceDigestSha256: String = "",
        val projectPackage: String? = null,
        val projectCompileSdk: Int? = null,
        val steps: List<StepResult>,
    )

    data class State(
        val running: Boolean = false,
        val currentStep: String = "",
        val completed: Int = 0,
        val total: Int = 0,
        val latest: Report? = null,
        val message: String = "",
    ) {
        val progress: Float?
            get() = total.takeIf { it > 0 }?.let { (completed.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    }

    private val appContext = context.applicationContext
    private val reportRoot = File(appContext.filesDir, "toolchain-certification").apply { mkdirs() }
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
    private val _state = MutableStateFlow(State(latest = readLatest()))
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun certify(level: Level): Report = withContext(Dispatchers.IO) {
        check(!_state.value.running) { "A toolchain certification run is already active" }
        val status = development.refresh()
        check(status.readiness == AndroidDevelopmentManager.Readiness.READY) { status.message }
        val info = development.workstationInfo() ?: error("Verified workstation toolchain is unavailable")
        val receipt = development.installedToolchainReceipt() ?: error("Installed toolchain has no Droide install receipt")
        check(receipt.packSha256 == info.packSha256) { "Toolchain receipt does not match workstation state" }
        val manifest = development.installedToolchainManifest() ?: error("Installed toolchain manifest is missing")
        if (manifest.schema >= 3) {
            check(receipt.candidateId == manifest.candidateId) { "Candidate id changed after installation" }
            check(receipt.sourceLockSha256 == manifest.sourceLockSha256) { "Source-lock binding changed after installation" }
        }

        val steps = mutableListOf<StepResult>()
        val total = if (level == Level.FULL) 10 else 5
        _state.value = State(running = true, total = total, message = "Starting ${level.name.lowercase()} certification…")

        suspend fun step(id: String, title: String, required: Boolean = true, block: suspend () -> String) {
            val start = System.currentTimeMillis()
            _state.value = _state.value.copy(currentStep = title)
            try {
                val detail = block().take(MAX_STEP_DETAIL_CHARS)
                steps += StepResult(id, title, Outcome.PASS.name, System.currentTimeMillis() - start, detail)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                steps += StepResult(
                    id = id,
                    title = title,
                    outcome = if (required) Outcome.FAIL.name else Outcome.SKIP.name,
                    durationMs = System.currentTimeMillis() - start,
                    detail = (error.message ?: error::class.java.simpleName).take(MAX_STEP_DETAIL_CHARS),
                )
                if (required) throw CertificationStepFailure(id, error)
            } finally {
                _state.value = _state.value.copy(completed = steps.size)
            }
        }

        var failure: Throwable? = null
        try {
            val shell = development.prepareInteractiveShell(syncProject = false)
            step("workstation-health", "Workstation health") {
                check(info.packSha256 == receipt.packSha256) { "Pack SHA-256 mismatch" }
                "${info.version} · ${info.abi} · SDK ${info.compileSdk} · ${info.executionMode}"
            }
            step("java", "JDK ${info.javaVersion} execution") {
                val result = bridge.shell(shell.shellPrefix() + "java -version")
                check(result.exitCode == 0) { result.combined }
                result.combined
            }
            step("aapt2", "AAPT2 execution") {
                val result = bridge.shell(shell.shellPrefix() + "aapt2 version")
                check(result.exitCode == 0) { result.combined }
                result.combined
            }
            step("sdk-platform", "Android SDK platform") {
                val androidJar = "${info.sdkRoot.trimEnd('/')}/platforms/android-${info.compileSdk}/android.jar"
                DeviceBridgeManager.requireSafeRemotePath(androidJar)
                val result = bridge.shell("test -s ${DeviceBridgeManager.shellQuote(androidJar)} && echo android.jar:ok")
                check(result.exitCode == 0) { "android.jar for API ${info.compileSdk} is missing" }
                result.stdout
            }
            step("compat-runtime", "Execution runtime") {
                if (AndroidToolchainRuntime.isCompatibility(manifest.runtime)) {
                    val guestJava = requireNotNull(manifest.runtime.guestJavaHome).trimEnd('/') + "/bin/java"
                    val command = AndroidToolchainRuntime.guestCommand(
                        manifest.runtime,
                        "uname -m; ${DeviceBridgeManager.shellQuote(guestJava)} -version",
                        shell.remoteWorkspace,
                    )
                    val result = bridge.shell(command)
                    check(result.exitCode == 0) { result.combined }
                    when (info.executionMode) {
                        AndroidToolchainExecutionMode.LINUX_ARM64_PROOT -> check(
                            result.combined.contains("aarch64", ignoreCase = true) || result.combined.contains("arm64", ignoreCase = true)
                        ) { "Linux ARM64 compatibility guest did not report ARM64" }
                        AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU -> check(
                            result.combined.contains("x86_64", ignoreCase = true) || result.combined.contains("amd64", ignoreCase = true)
                        ) { "Linux x86_64/QEMU compatibility guest did not report x86_64" }
                        AndroidToolchainExecutionMode.ANDROID_NATIVE -> Unit
                    }
                    result.combined
                } else {
                    "android-native"
                }
            }

            if (level == Level.FULL) {
                step("assemble-debug", "Gradle assembleDebug") {
                    val result = development.buildDebug()
                    check(result.success) { result.output }
                    "${result.durationMs}ms · ${result.localArtifacts.size} artifact(s)"
                }
                step("unit-tests", "Gradle test") {
                    val result = development.test()
                    check(result.success) { result.output }
                    "${result.durationMs}ms"
                }
                step("lint", "Gradle lintDebug") {
                    val result = development.lint()
                    check(result.success) { result.output }
                    "${result.durationMs}ms"
                }
                step("release", "Release APK + AAB") {
                    val apk = development.buildReleaseApk()
                    check(apk.success) { apk.output }
                    val bundle = development.buildReleaseBundle()
                    check(bundle.success) { bundle.output }
                    "APK ${apk.durationMs}ms · AAB ${bundle.durationMs}ms"
                }
                step("install-run-logcat", "Install, launch and logcat") {
                    val debug = development.buildDebug()
                    check(debug.success) { debug.output }
                    val apk = debug.localArtifacts
                        .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                        .sortedByDescending { if (it.name.contains("debug", ignoreCase = true)) 1 else 0 }
                        .firstOrNull() ?: error("assembleDebug produced no pulled APK")
                    val launch = development.installAndRun(apk)
                    val logs = development.logs()
                    "$launch\nlogcat-bytes=${logs.toByteArray().size}"
                }
            }
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(running = false, currentStep = "", message = "Certification canceled.")
            throw cancelled
        } catch (error: Throwable) {
            failure = error
        }

        val identity = try {
            AndroidDeviceIdentityCollector(bridge).collect(requirePhysical = false)
        } catch (error: Throwable) {
            if (failure == null) failure = error
            null
        }

        // Keep the collected identity in a failed report for diagnostics, but never let an emulator-shaped target become PASS and rely on the later offline promotion.


        if (failure == null && identity == null) {
            failure = IllegalStateException("Connected device identity evidence is unavailable")
        }
        if (failure == null && identity != null && !identity.physical) {
            failure = IllegalStateException("Connected target appears to be an emulator/non-physical Android device")
        }
        if (failure == null && identity != null && info.abi !in identity.abis) {
            failure = IllegalStateException("Connected target does not advertise required toolchain ABI ${info.abi}")
        }

        val passed = failure == null && steps.none { it.outcome == Outcome.FAIL.name }
        val unsignedReport = Report(
            generatedAtEpochMs = System.currentTimeMillis(),
            level = level.name,
            passed = passed,
            packSha256 = receipt.packSha256,
            toolchainVersion = info.version,
            candidateId = info.candidateId,
            sourceLockSha256 = info.sourceLockSha256,
            executionMode = info.executionMode.name,
            abi = info.abi,
            compileSdk = info.compileSdk,
            javaVersion = info.javaVersion,
            deviceManufacturer = identity?.manufacturer?.take(120).orEmpty(),
            deviceModel = identity?.model?.take(120).orEmpty(),
            deviceName = identity?.device?.take(120).orEmpty(),
            androidApi = identity?.api ?: -1,
            deviceAbis = identity?.abis.orEmpty(),
            devicePhysical = identity?.physical == true,
            deviceFingerprint = identity?.fingerprint?.take(300).orEmpty(),
            deviceIdentitySource = identity?.source.orEmpty(),
            bridgeEndpoint = identity?.bridgeEndpoint.orEmpty(),
            identityPropertiesSha256 = identity?.propertiesSha256.orEmpty(),
            deviceProperties = identity?.properties.orEmpty(),
            projectPackage = status.packageName,
            projectCompileSdk = status.compileSdk,
            steps = steps,
        )
        val report = unsignedReport.copy(evidenceDigestSha256 = evidenceDigest(unsignedReport))
        persist(report)
        _state.value = State(
            running = false,
            completed = steps.size,
            total = total,
            latest = report,
            message = if (passed) {
                "${level.name.lowercase().replaceFirstChar { it.uppercase() }} certification passed."
            } else {
                val failedStep = steps.lastOrNull { it.outcome == Outcome.FAIL.name }?.title
                val reason = failedStep ?: failure?.message?.take(MAX_STEP_DETAIL_CHARS) ?: "identity validation"
                "Certification failed: $reason."
            },
        )
        if (!passed) throw IllegalStateException(_state.value.message, failure)
        report
    }

    suspend fun exportLatest(output: OutputStream) = withContext(Dispatchers.IO) {
        val report = _state.value.latest ?: readLatest() ?: error("No certification report is available")
        val bytes = (json.encodeToString(report) + "\n").toByteArray(Charsets.UTF_8)
        output.use { it.write(bytes); it.flush() }
    }

    fun latestReport(): Report? = _state.value.latest

    private fun evidenceDigest(report: Report): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(name: String, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update('='.code.toByte())
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update('\n'.code.toByte())
        }
        field("schema", report.schema.toString())
        field("generatedAtEpochMs", report.generatedAtEpochMs.toString())
        field("level", report.level)
        field("passed", report.passed.toString())
        field("packSha256", report.packSha256)
        field("toolchainVersion", report.toolchainVersion)
        field("candidateId", report.candidateId.orEmpty())
        field("sourceLockSha256", report.sourceLockSha256.orEmpty())
        field("executionMode", report.executionMode)
        field("abi", report.abi)
        field("compileSdk", report.compileSdk.toString())
        field("javaVersion", report.javaVersion.toString())
        field("deviceManufacturer", report.deviceManufacturer)
        field("deviceModel", report.deviceModel)
        field("deviceName", report.deviceName)
        field("androidApi", report.androidApi.toString())
        report.deviceAbis.forEachIndexed { index, value -> field("deviceAbis[$index]", value) }
        field("devicePhysical", report.devicePhysical.toString())
        field("deviceFingerprint", report.deviceFingerprint)
        field("deviceIdentitySource", report.deviceIdentitySource)
        field("bridgeEndpoint", report.bridgeEndpoint)
        field("identityPropertiesSha256", report.identityPropertiesSha256)
        report.deviceProperties.toSortedMap().forEach { (key, value) -> field("deviceProperties[$key]", value) }
        field("projectPackage", report.projectPackage.orEmpty())
        field("projectCompileSdk", report.projectCompileSdk?.toString().orEmpty())
        report.steps.forEachIndexed { index, step ->
            field("steps[$index].id", step.id)
            field("steps[$index].title", step.title)
            field("steps[$index].outcome", step.outcome)
            field("steps[$index].durationMs", step.durationMs.toString())
            field("steps[$index].detailSha256", sha256(step.detail))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun persist(report: Report) {
        val text = json.encodeToString(report) + "\n"
        val latest = File(reportRoot, "latest.json")
        val temp = File(reportRoot, ".latest.tmp")
        temp.writeText(text, Charsets.UTF_8)
        check(temp.renameTo(latest) || run { latest.delete(); temp.renameTo(latest) }) { "Could not publish certification report" }
        val historyName = "${report.generatedAtEpochMs}-${report.packSha256.take(12)}-${report.level.lowercase()}.json"
        File(reportRoot, historyName).writeText(text, Charsets.UTF_8)
        reportRoot.listFiles { file -> file.name.endsWith(".json") && file.name != "latest.json" }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_HISTORY_REPORTS)
            ?.forEach(File::delete)
    }

    private fun readLatest(): Report? = runCatching {
        val file = File(reportRoot, "latest.json")
        if (!file.isFile || file.length() !in 1..MAX_REPORT_BYTES) return@runCatching null
        json.decodeFromString<Report>(file.readText(Charsets.UTF_8))
    }.getOrNull()

    private class CertificationStepFailure(val stepId: String, cause: Throwable) : RuntimeException(cause)

    companion object {
        private const val MAX_STEP_DETAIL_CHARS = 4000
        private const val MAX_REPORT_BYTES = 1024L * 1024L
        private const val MAX_HISTORY_REPORTS = 20
    }
}
