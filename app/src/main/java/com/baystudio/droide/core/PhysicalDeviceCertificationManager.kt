package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PhysicalDeviceCertificationStep(
    val id: String,
    val outcome: String,
    val durationMs: Long,
    val detail: String,
)








@Serializable
data class PhysicalDeviceCertificationReport(
    val schema: Int = 1,
    val generatedAtEpochMs: Long,
    val projectId: String,
    val packageName: String,
    val activeFile: String,
    val activeFileSha256: String,
    val breakpointLine: Int,
    val repeatedDebugApkSha256: String,
    val preflightDevice: AndroidDeviceIdentitySnapshot,
    val postflightDevice: AndroidDeviceIdentitySnapshot,
    val toolchainReport: AndroidToolchainCertificationManager.Report,
    val debugReport: DebugCertificationReport,
    val steps: List<PhysicalDeviceCertificationStep>,
    val evidenceDigestSha256: String = "",
) {
    fun validate(requireDigest: Boolean = true) {
        require(schema == 1) { "Unsupported physical-device certification schema" }
        require(generatedAtEpochMs > 0) { "Invalid physical-device certification timestamp" }
        require(projectId.isNotBlank() && projectId.length <= 255 && projectId.none { it.code < 0x20 || it == '\u007f' }) {
            "Invalid project id"
        }
        DeviceBridgeManager.requirePackageName(packageName)
        require(activeFile.isNotBlank() && activeFile.length <= 1_000 && '\u0000' !in activeFile) { "Invalid active file" }
        require(activeFileSha256.matches(HEX64)) { "Invalid active-file digest" }
        require(breakpointLine >= 1) { "Invalid breakpoint line" }
        require(repeatedDebugApkSha256.matches(HEX64)) { "Invalid repeated debug APK digest" }

        preflightDevice.validate(requirePhysical = true)
        postflightDevice.validate(requirePhysical = true)
        require(preflightDevice.samePhysicalIdentity(postflightDevice)) {
            "Physical Android identity changed during certification"
        }

        PhysicalDeviceCertificationRules.validateFullToolchainReport(toolchainReport)
        debugReport.validate()

        val toolchainDevice = PhysicalDeviceCertificationRules.toolchainDevice(toolchainReport)
        require(preflightDevice.samePhysicalIdentity(toolchainDevice)) {
            "Toolchain certification ran against a different physical Android identity"
        }
        require(postflightDevice.samePhysicalIdentity(toolchainDevice)) {
            "Toolchain evidence does not match the postflight physical Android identity"
        }
        require(preflightDevice.samePhysicalIdentity(debugReport.device)) {
            "Debugger certification ran against a different physical Android identity"
        }
        require(packageName == toolchainReport.projectPackage) { "Toolchain report package does not match the certified project" }
        require(packageName == debugReport.packageName) { "Debugger report package does not match the certified project" }
        require(activeFile == debugReport.activeFile && breakpointLine == debugReport.breakpointLine) {
            "Debugger evidence is not bound to the requested source breakpoint"
        }
        require(repeatedDebugApkSha256 == debugReport.apkSha256) {
            "Debugger evidence does not match the repeated debug-build APK"
        }
        require(generatedAtEpochMs >= toolchainReport.generatedAtEpochMs && generatedAtEpochMs >= debugReport.generatedAtEpochMs) {
            "Aggregate evidence predates child certification evidence"
        }

        require(steps.map { it.id } == REQUIRED_STEPS) { "Physical-device certification steps are incomplete or out of order" }
        require(steps.all { it.outcome == "PASS" }) { "Physical-device certification contains a non-PASS step" }
        require(steps.all { it.durationMs in 0..MAX_STEP_DURATION_MS && it.detail.length <= MAX_DETAIL_CHARS }) {
            "Invalid physical-device certification step"
        }

        if (requireDigest) {
            require(evidenceDigestSha256.matches(HEX64)) { "Invalid physical-device evidence digest" }
            require(evidenceDigestSha256 == PhysicalDeviceCertificationRules.evidenceDigest(this)) {
                "Physical-device evidence digest mismatch"
            }
        }
    }

    fun withEvidenceDigest(): PhysicalDeviceCertificationReport {
        validate(requireDigest = false)
        return copy(evidenceDigestSha256 = PhysicalDeviceCertificationRules.evidenceDigest(this)).also { it.validate() }
    }

    companion object {
        val REQUIRED_STEPS = listOf(
            "physical-preflight",
            "debug-provider-preflight",
            "toolchain-full",
            "repeat-debug-build",
            "debug-certification",
            "postflight-stability",
        )
        internal const val MAX_DETAIL_CHARS = 4_000
        internal const val MAX_STEP_DURATION_MS = 6L * 60L * 60L * 1_000L
        internal val HEX64 = Regex("[0-9a-f]{64}")
    }
}

internal object PhysicalDeviceCertificationRules {
    private val fullToolchainSteps = listOf(
        "workstation-health",
        "java",
        "aapt2",
        "sdk-platform",
        "compat-runtime",
        "assemble-debug",
        "unit-tests",
        "lint",
        "release",
        "install-run-logcat",
    )

    fun toolchainDevice(report: AndroidToolchainCertificationManager.Report): AndroidDeviceIdentitySnapshot {
        val snapshot = AndroidDeviceIdentitySnapshot.fromProperties(report.deviceProperties, report.bridgeEndpoint)
        snapshot.validate(requirePhysical = true)
        require(report.devicePhysical) { "Toolchain report is not physical-device evidence" }
        require(report.deviceIdentitySource == "remote-getprop") { "Toolchain report identity source is not remote getprop" }
        require(report.identityPropertiesSha256 == snapshot.propertiesSha256) { "Toolchain identity digest mismatch" }
        require(report.deviceManufacturer == snapshot.manufacturer.take(120)) { "Toolchain manufacturer mismatch" }
        require(report.deviceModel == snapshot.model.take(120)) { "Toolchain model mismatch" }
        require(report.deviceName == snapshot.device.take(120)) { "Toolchain device-name mismatch" }
        require(report.androidApi == snapshot.api) { "Toolchain Android API mismatch" }
        require(report.deviceAbis == snapshot.abis) { "Toolchain ABI evidence mismatch" }
        require(report.deviceFingerprint == snapshot.fingerprint.take(300)) { "Toolchain fingerprint mismatch" }
        return snapshot
    }

    fun validateFullToolchainReport(report: AndroidToolchainCertificationManager.Report) {
        require(report.schema == 3 && report.level == AndroidToolchainCertificationManager.Level.FULL.name && report.passed) {
            "Physical-device suite requires a schema-3 FULL PASS toolchain report"
        }
        require(report.generatedAtEpochMs > 0) { "Invalid toolchain certification timestamp" }
        require(report.packSha256.matches(PhysicalDeviceCertificationReport.HEX64)) { "Invalid toolchain pack digest" }
        require(report.toolchainVersion.isNotBlank() && report.toolchainVersion.length <= 120) { "Invalid toolchain version" }
        report.sourceLockSha256?.let {
            require(it.matches(PhysicalDeviceCertificationReport.HEX64)) { "Invalid toolchain source-lock digest" }
        }
        require(report.compileSdk in 1..999 && report.javaVersion in 8..99) { "Invalid toolchain SDK/JDK evidence" }
        require(report.projectPackage != null && report.projectCompileSdk != null) { "FULL toolchain report is not bound to an Android project" }
        DeviceBridgeManager.requirePackageName(report.projectPackage)
        require(report.projectCompileSdk in 1..999) { "Invalid project compileSdk evidence" }
        require(report.steps.map { it.id } == fullToolchainSteps) { "FULL toolchain steps are incomplete or out of order" }
        require(report.steps.all { it.outcome == AndroidToolchainCertificationManager.Outcome.PASS.name }) {
            "FULL toolchain report contains a non-PASS step"
        }
        require(report.steps.all { it.durationMs in 0..PhysicalDeviceCertificationReport.MAX_STEP_DURATION_MS && it.detail.length <= PhysicalDeviceCertificationReport.MAX_DETAIL_CHARS }) {
            "Invalid FULL toolchain step"
        }
        toolchainDevice(report)
        require(report.evidenceDigestSha256.matches(PhysicalDeviceCertificationReport.HEX64)) { "Invalid toolchain evidence digest" }
        require(report.evidenceDigestSha256 == toolchainEvidenceDigest(report)) { "Toolchain evidence digest mismatch" }
    }

    fun evidenceDigest(report: PhysicalDeviceCertificationReport): String {
        val canonical = buildString {
            append("schema=").append(report.schema).append('\n')
            append("generatedAtEpochMs=").append(report.generatedAtEpochMs).append('\n')
            append("projectId=").append(report.projectId).append('\n')
            append("packageName=").append(report.packageName).append('\n')
            append("activeFile=").append(report.activeFile).append('\n')
            append("activeFileSha256=").append(report.activeFileSha256).append('\n')
            append("breakpointLine=").append(report.breakpointLine).append('\n')
            append("repeatedDebugApkSha256=").append(report.repeatedDebugApkSha256).append('\n')
            append("preflight.propertiesSha256=").append(report.preflightDevice.propertiesSha256).append('\n')
            append("preflight.bridgeEndpoint=").append(report.preflightDevice.bridgeEndpoint).append('\n')
            append("postflight.propertiesSha256=").append(report.postflightDevice.propertiesSha256).append('\n')
            append("postflight.bridgeEndpoint=").append(report.postflightDevice.bridgeEndpoint).append('\n')
            append("toolchainEvidenceDigestSha256=").append(report.toolchainReport.evidenceDigestSha256).append('\n')
            append("debugEvidenceDigestSha256=").append(report.debugReport.evidenceDigestSha256).append('\n')
            report.steps.forEachIndexed { index, step ->
                append("step[").append(index).append("]=")
                    .append(step.id).append('|')
                    .append(step.outcome).append('|')
                    .append(step.durationMs).append('|')
                    .append(sha256Text(step.detail)).append('\n')
            }
        }
        return sha256Text(canonical)
    }

    private fun toolchainEvidenceDigest(report: AndroidToolchainCertificationManager.Report): String {
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
            field("steps[$index].detailSha256", sha256Text(step.detail))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

 
class PhysicalDeviceCertificationEvidenceStore private constructor(
    private val root: File,
    @Suppress("UNUSED_PARAMETER") directRoot: Boolean,
) {
    constructor(context: Context, projectId: String) : this(
        File(File(context.applicationContext.filesDir, "physical-device-certification"), PathSecurity.safeLeafName(projectId)),
        true,
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

    init {
        check(root.isDirectory || root.mkdirs()) { "Could not create physical-device certification evidence directory" }
    }

    fun persist(report: PhysicalDeviceCertificationReport) {
        report.validate()
        val bytes = (json.encodeToString(report) + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_REPORT_BYTES) { "Physical-device certification report is too large" }
        atomicWrite(File(root, LATEST_FILE), bytes)
        atomicWrite(
            File(root, "${report.generatedAtEpochMs}-${report.repeatedDebugApkSha256.take(12)}-${report.evidenceDigestSha256.take(12)}.json"),
            bytes,
        )
        pruneHistory()
    }

    fun latest(): PhysicalDeviceCertificationReport? = runCatching {
        val file = File(root, LATEST_FILE)
        if (!file.isFile || file.length() !in 1..MAX_REPORT_BYTES.toLong()) return@runCatching null
        json.decodeFromString<PhysicalDeviceCertificationReport>(file.readText(Charsets.UTF_8)).also(PhysicalDeviceCertificationReport::validate)
    }.getOrNull()

    fun exportLatest(output: OutputStream): PhysicalDeviceCertificationReport {
        val report = latest() ?: error("No validated physical-device certification evidence is available")
        val bytes = (json.encodeToString(report) + "\n").toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.flush()
        return report
    }

    private fun pruneHistory() {
        root.listFiles { file -> file.isFile && file.extension == "json" && file.name != LATEST_FILE }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            ?.drop(MAX_HISTORY_REPORTS)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File(root, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            temp.outputStream().buffered().use { output -> output.write(bytes); output.flush() }
            runCatching {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        internal fun forTesting(root: File): PhysicalDeviceCertificationEvidenceStore =
            PhysicalDeviceCertificationEvidenceStore(root, directRoot = true)

        private const val LATEST_FILE = "latest.json"
        private const val MAX_REPORT_BYTES = 4 * 1024 * 1024
        private const val MAX_HISTORY_REPORTS = 20
    }
}





class PhysicalDeviceCertificationManager(
    context: Context,
    private val projectId: String,
    private val projectRoot: File,
    private val development: AndroidDevelopmentManager,
    private val debugger: DebugManager,
    private val bridge: DeviceBridgeManager,
) {
    private val toolchainCertification = AndroidToolchainCertificationManager(context.applicationContext, development, bridge)
    private val debugCertification = DebugCertificationManager(
        context.applicationContext,
        projectId,
        development,
        debugger,
        bridge,
    )
    private val evidence = PhysicalDeviceCertificationEvidenceStore(context.applicationContext, projectId)
    private val identityCollector = AndroidDeviceIdentityCollector(bridge)
    private val certificationMutex = Mutex()

    suspend fun certify(
        activeFile: String,
        breakpointLine: Int,
        waitForBreakpointMs: Long = 30_000,
    ): PhysicalDeviceCertificationReport = certificationMutex.withLock {
        require(waitForBreakpointMs in 5_000..120_000) { "Invalid breakpoint wait timeout" }
        require(breakpointLine >= 1) { "Breakpoint line must be >= 1" }
        val source = PathSecurity.resolveWithin(projectRoot, activeFile)
        require(source.isFile && !PathSecurity.isSymbolicLink(source)) { "Certification source file is missing or symbolic: $activeFile" }
        require(lineExists(source, breakpointLine)) { "Breakpoint line $breakpointLine is outside $activeFile" }
        val sourceSha256 = PhysicalDeviceCertificationRules.sha256File(source)
        val steps = mutableListOf<PhysicalDeviceCertificationStep>()

        suspend fun <T> step(id: String, block: suspend () -> T): T {
            val started = System.currentTimeMillis()
            return try {
                val value = block()
                steps += PhysicalDeviceCertificationStep(
                    id = id,
                    outcome = "PASS",
                    durationMs = System.currentTimeMillis() - started,
                    detail = when (value) {
                        is String -> value.take(PhysicalDeviceCertificationReport.MAX_DETAIL_CHARS)
                        else -> "PASS"
                    },
                )
                value
            } catch (error: Throwable) {
                throw PhysicalDeviceCertificationException(id, steps.toList(), error)
            }
        }

        val preflight = step("physical-preflight") {
            val health = bridge.ensureHealthyConnection()
            val identity = identityCollector.collect(requirePhysical = true)
            identity to "$health · ${identity.manufacturer} ${identity.model} · API ${identity.api} · ${identity.abis.joinToString()}"
        }
        val preflightDevice = preflight.first
        
        steps[steps.lastIndex] = steps.last().copy(detail = preflight.second.take(PhysicalDeviceCertificationReport.MAX_DETAIL_CHARS))

        step("debug-provider-preflight") {
            check(debugger.hasAndroidAttachProvider(activeFile)) {
                "No Android JDWP-capable debugger provider is installed for $activeFile"
            }
            "Android JDWP provider available before long-running build certification"
        }

        val toolchainReport = step("toolchain-full") {
            toolchainCertification.certify(AndroidToolchainCertificationManager.Level.FULL).also {
                PhysicalDeviceCertificationRules.validateFullToolchainReport(it)
                val childDevice = PhysicalDeviceCertificationRules.toolchainDevice(it)
                check(preflightDevice.samePhysicalIdentity(childDevice)) {
                    "FULL toolchain certification switched to a different physical Android device"
                }
            }
        }
        steps[steps.lastIndex] = steps.last().copy(
            detail = "FULL PASS · ${toolchainReport.executionMode} · SDK ${toolchainReport.compileSdk} · JDK ${toolchainReport.javaVersion} · ${toolchainReport.evidenceDigestSha256}"
                .take(PhysicalDeviceCertificationReport.MAX_DETAIL_CHARS),
        )

        val status = development.refresh()
        val packageName = status.packageName ?: error("Android project package id is unavailable after FULL certification")
        DeviceBridgeManager.requirePackageName(packageName)
        check(toolchainReport.projectPackage == packageName) { "Project package changed after FULL toolchain certification" }

        val repeatedBuild = step("repeat-debug-build") {
            development.buildDebug().also { result ->
                check(result.success) { result.output }
                check(result.localArtifacts.any { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }) {
                    "Repeated assembleDebug produced no APK artifact"
                }
            }
        }
        val apkCandidates = repeatedBuild.localArtifacts.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
        

        val debugNamed = apkCandidates.filter { it.name.contains("debug", ignoreCase = true) }
        val debugApk = when {
            debugNamed.size == 1 -> debugNamed.single()
            apkCandidates.size == 1 -> apkCandidates.single()
            else -> error("assembleDebug produced ${apkCandidates.size} APKs and Droide cannot safely guess which split/module APK to certify")
        }
        val debugApkSha256 = PhysicalDeviceCertificationRules.sha256File(debugApk)
        steps[steps.lastIndex] = steps.last().copy(
            detail = "${repeatedBuild.durationMs}ms · ${debugApk.name} · sha256=$debugApkSha256",
        )

        val debugReport = step("debug-certification") {
            debugCertification.certify(
                apk = debugApk,
                packageName = packageName,
                activeFile = activeFile,
                breakpointLine = breakpointLine,
                waitForBreakpointMs = waitForBreakpointMs,
            ).also {
                it.validate()
                check(it.apkSha256 == debugApkSha256) { "Debugger evidence does not match the repeated build artifact" }
                check(preflightDevice.samePhysicalIdentity(it.device)) { "Debugger certification switched physical Android devices" }
            }
        }
        steps[steps.lastIndex] = steps.last().copy(
            detail = "${debugReport.adapterName} · APK ${debugReport.apkSha256} · evidence ${debugReport.evidenceDigestSha256}",
        )

        val postflight = step("postflight-stability") {
            val health = bridge.ensureHealthyConnection()
            val identity = identityCollector.collect(requirePhysical = true)
            check(preflightDevice.samePhysicalIdentity(identity)) {
                "Physical Android identity changed during the certification suite"
            }
            val finalSourceSha256 = PhysicalDeviceCertificationRules.sha256File(source)
            check(finalSourceSha256 == sourceSha256) { "Breakpoint source changed during certification" }
            identity to "$health · same physical identity · source unchanged"
        }
        val postflightDevice = postflight.first
        steps[steps.lastIndex] = steps.last().copy(detail = postflight.second)

        PhysicalDeviceCertificationReport(
            generatedAtEpochMs = System.currentTimeMillis(),
            projectId = projectId,
            packageName = packageName,
            activeFile = activeFile,
            activeFileSha256 = sourceSha256,
            breakpointLine = breakpointLine,
            repeatedDebugApkSha256 = debugApkSha256,
            preflightDevice = preflightDevice,
            postflightDevice = postflightDevice,
            toolchainReport = toolchainReport,
            debugReport = debugReport,
            steps = steps,
        ).withEvidenceDigest().also(evidence::persist)
    }

    fun latestReport(): PhysicalDeviceCertificationReport? = evidence.latest()

    fun exportLatest(output: OutputStream): PhysicalDeviceCertificationReport = evidence.exportLatest(output)

    private fun lineExists(file: File, line: Int): Boolean {
        var count = 0
        file.bufferedReader().useLines { lines ->
            val iterator = lines.iterator()
            while (iterator.hasNext() && count < line) {
                iterator.next()
                count++
            }
        }
        return count >= line
    }
}

class PhysicalDeviceCertificationException(
    val failedStep: String,
    val completedSteps: List<PhysicalDeviceCertificationStep>,
    cause: Throwable,
) : IllegalStateException("Physical-device certification failed at $failedStep: ${cause.message}", cause)
