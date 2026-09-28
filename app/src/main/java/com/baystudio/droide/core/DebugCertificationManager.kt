package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock





class DebugCertificationManager(
    context: Context,
    projectId: String,
    androidDevelopment: AndroidDevelopmentManager,
    debugger: DebugManager,
    bridge: DeviceBridgeManager,
) {
    private val runner = DebugCertificationRunner(androidDevelopment, debugger, bridge)
    private val evidence = DebugCertificationEvidenceStore(context.applicationContext, projectId)
    private val certificationMutex = Mutex()

    suspend fun certify(
        apk: File,
        packageName: String,
        activeFile: String,
        breakpointLine: Int,
        waitForBreakpointMs: Long = 30_000,
    ): DebugCertificationReport = certificationMutex.withLock {
        val report = runner.run(
            apk = apk,
            packageName = packageName,
            activeFile = activeFile,
            breakpointLine = breakpointLine,
            waitForBreakpointMs = waitForBreakpointMs,
        )
        report.validate()
        evidence.persist(report)
        report
    }

    fun latestReport(): DebugCertificationReport? = evidence.latest()

    fun exportLatest(output: OutputStream): DebugCertificationReport = evidence.exportLatest(output)
}
