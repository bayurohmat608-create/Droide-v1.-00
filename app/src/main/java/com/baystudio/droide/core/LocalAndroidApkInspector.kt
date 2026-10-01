package com.baystudio.droide.core

import java.io.File


internal class LocalAndroidApkInspector(
    private val projectRoot: File,
    private val runtimeTemporaryRoot: File,
) {
    suspend fun inspect(snapshot: File): String? {
        if (!LocalExecutionSubstrate.inspectLinuxState().ready) return null
        val guestPath = AndroidApkInspectionPolicy.guestSnapshotPath(runtimeTemporaryRoot, snapshot)
        val launch = LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
        val lease = RemoteProcessLease.createLocal("apk-inspection")
        val argv = launch.shellCommand(LocalAndroidApkInspectionCommand.create(guestPath), lease.environment)
        val result = LocalProcessSupervisor.capture(
            argv = listOf("/system/bin/sh", "-c", lease.wrap(argv.joinToString(" ", transform = LocalExecutionSubstrate::shellQuote))),
            cwd = projectRoot,
            environment = launch.environment + lease.environment,
            maxOutputBytes = 8192,
            timeoutMs = 30_000,
            cleanup = { LocalExecutionSubstrate.terminateLease(lease) },
        )
        check(!result.timedOut) { "Local APK inspection timed out" }
        if (result.exitCode == LocalAndroidApkInspectionCommand.UNAVAILABLE_EXIT) return null
        check(result.exitCode == 0) { "Local AAPT2 could not inspect the APK: ${result.output.takeLast(4000)}" }
        return AndroidApkInspectionPolicy.packageName(result.output)
    }
}
