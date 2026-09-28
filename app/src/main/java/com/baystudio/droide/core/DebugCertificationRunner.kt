package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

 
@Serializable
data class DebugCertificationStep(
    val id: String,
    val outcome: String,
    val durationMs: Long,
    val detail: String,
)

@Serializable
data class DebugCertificationReport(
    val schema: Int = 2,
    val generatedAtEpochMs: Long,
    val packageName: String,
    val apkSha256: String,
    val pid: Int,
    val activeFile: String,
    val breakpointLine: Int,
    val adapterName: String,
    val device: AndroidDeviceIdentitySnapshot,
    val steps: List<DebugCertificationStep>,
    val evidenceDigestSha256: String = "",
) {
    fun validate(requireDigest: Boolean = true) {
        require(schema == 2) { "Unsupported debug certification schema" }
        require(generatedAtEpochMs > 0) { "Invalid debug certification timestamp" }
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_.]{1,240}"))) { "Invalid package name" }
        require(apkSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid debug APK digest" }
        require(pid > 0) { "Invalid debug process pid" }
        require(activeFile.isNotBlank() && activeFile.length <= 1_000) { "Invalid active file" }
        require(breakpointLine >= 1) { "Invalid breakpoint line" }
        require(adapterName.isNotBlank() && adapterName.length <= 200) { "Invalid adapter name" }
        device.validate(requirePhysical = true)
        require(steps.map { it.id } == REQUIRED_STEPS) { "Debug certification steps are incomplete or out of order" }
        require(steps.all { it.outcome == "PASS" }) { "Debug certification contains a non-PASS step" }
        require(steps.all { it.durationMs in 0..120_000 && it.detail.length <= 4_000 }) { "Invalid debug certification step" }
        if (requireDigest) {
            require(evidenceDigestSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid debug evidence digest" }
            require(evidenceDigestSha256 == evidenceDigest(this)) { "Debug evidence digest mismatch" }
        }
    }

    fun withEvidenceDigest(): DebugCertificationReport {
        validate(requireDigest = false)
        return copy(evidenceDigestSha256 = evidenceDigest(this)).also { it.validate() }
    }

    companion object {
        val REQUIRED_STEPS = listOf(
            "install-launch-suspended",
            "jdwp-discovery",
            "dap-attach",
            "breakpoint-hit",
            "stack-variables",
            "step",
            "terminate-clean",
        )

         
        fun computeEvidenceDigest(report: DebugCertificationReport): String = evidenceDigest(report)

        fun evidenceDigest(report: DebugCertificationReport): String {
            val canonical = buildString {
                append("schema=").append(report.schema).append('\n')
                append("generatedAtEpochMs=").append(report.generatedAtEpochMs).append('\n')
                append("packageName=").append(report.packageName).append('\n')
                append("apkSha256=").append(report.apkSha256).append('\n')
                append("pid=").append(report.pid).append('\n')
                append("activeFile=").append(report.activeFile).append('\n')
                append("breakpointLine=").append(report.breakpointLine).append('\n')
                append("adapterName=").append(report.adapterName).append('\n')
                append("device.propertiesSha256=").append(report.device.propertiesSha256).append('\n')
                append("device.bridgeEndpoint=").append(report.device.bridgeEndpoint).append('\n')
                append("device.physical=").append(report.device.physical).append('\n')
                report.steps.forEachIndexed { index, step ->
                    append("step[").append(index).append("]=")
                        .append(step.id).append('|')
                        .append(step.outcome).append('|')
                        .append(step.durationMs).append('|')
                        .append(AndroidDeviceIdentityRules.sha256(step.detail)).append('\n')
                }
            }
            return AndroidDeviceIdentityRules.sha256(canonical)
        }
    }
}








class DebugCertificationRunner(
    private val androidDevelopment: AndroidDevelopmentManager,
    private val debugger: DebugManager,
    private val bridge: DeviceBridgeManager,
) {
    suspend fun run(
        apk: File,
        packageName: String,
        activeFile: String,
        breakpointLine: Int,
        waitForBreakpointMs: Long = 30_000,
    ): DebugCertificationReport {
        require(apk.isFile && apk.length() > 0) { "Debug APK is missing" }
        require(waitForBreakpointMs in 5_000..120_000) { "Invalid breakpoint wait timeout" }
        require(breakpointLine >= 1) { "Breakpoint line must be >= 1" }

        val identityCollector = AndroidDeviceIdentityCollector(bridge)
        val identity = identityCollector.collect(requirePhysical = true)
        val apkSha256 = sha256(apk)
        val steps = mutableListOf<DebugCertificationStep>()

        suspend fun <T> step(id: String, block: suspend () -> T): T {
            val started = System.currentTimeMillis()
            return try {
                val value = block()
                steps += DebugCertificationStep(
                    id = id,
                    outcome = "PASS",
                    durationMs = System.currentTimeMillis() - started,
                    detail = when (value) {
                        is String -> value.take(4_000)
                        else -> "PASS"
                    },
                )
                value
            } catch (t: Throwable) {
                steps += DebugCertificationStep(
                    id = id,
                    outcome = "FAIL",
                    durationMs = System.currentTimeMillis() - started,
                    detail = (t.message ?: t::class.java.simpleName).take(4_000),
                )
                throw DebugCertificationException(id, steps.toList(), t)
            }
        }

        val breakpointAlreadyPresent = debugger.breakpointForRequestedLine(activeFile, breakpointLine) != null
        var launchedPackage = false
        var debugSessionStarted = false
        try {
            val launch = step("install-launch-suspended") {
                androidDevelopment.installAndLaunchForDebug(apk, packageName).also { launchedPackage = true }
            }
            step("jdwp-discovery") {
                val processes = debugger.androidJdwpProcesses()
                check(processes.any { it.pid == launch.pid }) { "Launched PID ${launch.pid} is not present in JDWP discovery" }
                "JDWP PID ${launch.pid} discovered"
            }

            val config = debugger.androidAttachConfigurations(activeFile, launch.pid).firstOrNull()
                ?: throw DebugCertificationException(
                    "dap-attach",
                    steps + DebugCertificationStep("dap-attach", "FAIL", 0, "No compatible Android JDWP DAP/JDI provider is installed"),
                    IllegalStateException("No compatible Android JDWP DAP/JDI provider is installed"),
                )

            debugger.addBreakpoint(activeFile, breakpointLine)
            step("dap-attach") {
                debugger.start(config)
                debugSessionStarted = true
                check(debugger.state.value in setOf(DebugState.RUNNING, DebugState.STOPPED)) {
                    "DAP attach did not enter an active state"
                }
                "Attached with ${config.name}"
            }
            step("breakpoint-hit") {
                var stop = debugger.lastStop.value
                var proof = stop?.let {
                    runSuspendCatching { proveTargetBreakpoint(activeFile, breakpointLine, it) }.getOrNull()
                }
                if (proof == null) {
                    val generation = stop?.generation ?: 0L
                    when (debugger.state.value) {
                        DebugState.STOPPED -> debugger.continueExecution()
                        DebugState.RUNNING -> Unit
                        else -> error("Debugger is not runnable while waiting for the target breakpoint")
                    }
                    stop = awaitStopAfter(generation, waitForBreakpointMs)
                    proof = proveTargetBreakpoint(activeFile, breakpointLine, stop)
                }
                proof
            }
            step("stack-variables") {
                val stop = requireNotNull(debugger.lastStop.value) { "No stopped-event evidence is available" }
                val threadId = stop.threadId ?: debugger.refreshThreads().firstOrNull()?.id
                    ?: error("Debugger returned no threads")
                val threads = debugger.refreshThreads()
                check(threads.any { it.id == threadId }) { "Stopped thread $threadId is missing from debugger thread list" }
                debugger.selectThread(threadId)
                val frames = debugger.refreshStack(threadId)
                check(frames.isNotEmpty()) { "Debugger returned no stack frames" }
                debugger.selectFrame(frames.first().id)
                val variables = debugger.refreshFrameVariables(frames.first().id)
                val concrete = variables.count { !it.name.startsWith("[") }
                check(concrete > 0) { "Debugger returned scopes but no inspectable variables" }
                "threads=${threads.size}, frames=${frames.size}, inspectableVariables=$concrete"
            }
            step("step") {
                val priorStop = requireNotNull(debugger.lastStop.value) { "No breakpoint stop exists before step" }
                val before = debugger.frames.value.firstOrNull()?.line
                debugger.next(priorStop.threadId)
                val afterStop = awaitStopAfter(priorStop.generation, 15_000)
                check(DebugCertificationContract.provesStep(afterStop)) {
                    "Expected a fresh step stop, got '${afterStop.reason}'"
                }
                val threadId = afterStop.threadId ?: debugger.refreshThreads().firstOrNull()?.id
                    ?: error("No thread after step")
                val frames = debugger.refreshStack(threadId)
                check(frames.isNotEmpty()) { "Debugger returned no stack after step" }
                val after = frames.first().line
                "freshStopGeneration=${afterStop.generation}, line ${before ?: "?"} -> $after"
            }
            step("terminate-clean") {
                debugger.stop(terminateDebuggee = true)
                debugSessionStarted = false
                androidDevelopment.stopApp(packageName)
                awaitProcessGone(packageName, launch.pid)
                launchedPackage = false
                check(debugger.state.value == DebugState.IDLE) { "Debugger did not return to IDLE" }
                val finalIdentity = identityCollector.collect(requirePhysical = true)
                check(identity.sameTarget(finalIdentity)) { "Connected Android target changed during debug certification" }
                "DAP session closed; package process and JDWP PID disappeared; physical device identity remained stable"
            }

            return DebugCertificationReport(
                generatedAtEpochMs = System.currentTimeMillis(),
                packageName = packageName,
                apkSha256 = apkSha256,
                pid = launch.pid,
                activeFile = activeFile,
                breakpointLine = breakpointLine,
                adapterName = config.name,
                device = identity,
                steps = steps,
            ).withEvidenceDigest()
        } catch (t: Throwable) {
            if (debugSessionStarted || debugger.state.value != DebugState.IDLE) {
                runCatching { debugger.stop(terminateDebuggee = true) }
            }
            if (launchedPackage) runCatching { androidDevelopment.stopApp(packageName) }
            throw t
        } finally {
            if (!breakpointAlreadyPresent) debugger.clearBreakpoint(activeFile, breakpointLine)
        }
    }

    private suspend fun awaitStopAfter(generation: Long, timeoutMs: Long): DebugStopSnapshot =
        withTimeout(timeoutMs) {
            debugger.lastStop.first { it != null && it.generation > generation }!!
        }

    private suspend fun proveTargetBreakpoint(
        activeFile: String,
        requestedLine: Int,
        stop: DebugStopSnapshot,
    ): String {
        val breakpoint = debugger.breakpointForRequestedLine(activeFile, requestedLine)
            ?: error("Requested breakpoint disappeared from debugger state")
        check(breakpoint.verified) { "Breakpoint $activeFile:$requestedLine was not verified by the adapter" }
        check(DebugCertificationContract.provesBreakpoint(stop, breakpoint.id)) {
            "Stopped event did not prove the requested breakpoint (reason='${stop.reason}', id=${breakpoint.id})"
        }
        val threadId = stop.threadId ?: debugger.refreshThreads().firstOrNull()?.id
            ?: error("Breakpoint stop did not identify a thread")
        val frames = debugger.refreshStack(threadId)
        val frame = frames.firstOrNull { DebugCertificationContract.frameMatches(it, activeFile, breakpoint.line) }
            ?: error("Breakpoint stop stack does not contain $activeFile:${breakpoint.line}")
        return "reason=${stop.reason}, generation=${stop.generation}, breakpointId=${breakpoint.id ?: -1}, frame=${frame.path}:${frame.line}"
    }

    private suspend fun awaitProcessGone(packageName: String, pid: Int) {
        withTimeout(8_000) {
            while (true) {
                val runningPid = runSuspendCatching { bridge.pidOf(packageName) }.getOrNull()
                val jdwpPids = runSuspendCatching { bridge.jdwpPids(timeoutMs = 1_000) }.getOrDefault(emptyList())
                if (runningPid == null && pid !in jdwpPids) return@withTimeout
                delay(100)
            }
        }
    }

    private fun sha256(file: File): String {
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
}

class DebugCertificationException(
    val failedStep: String,
    val completedSteps: List<DebugCertificationStep>,
    cause: Throwable,
) : IllegalStateException("Debug certification failed at $failedStep: ${cause.message}", cause)
