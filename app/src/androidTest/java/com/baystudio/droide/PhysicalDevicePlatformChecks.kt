package com.baystudio.droide

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

 
internal object PhysicalDevicePlatformChecks {
    fun verifyTargetContext(context: Context): String {
        check(context.packageName == APP_PACKAGE) { "Unexpected target package ${context.packageName}" }
        val packageInfo = context.packageManager.getPackageInfo(APP_PACKAGE, 0)
        return "package=${context.packageName}; version=${packageInfo.versionName.orEmpty()}; sdk=${Build.VERSION.SDK_INT}"
    }

    fun verifyPrivateStorage(context: Context, nonceSha256: String): String {
        val root = File(context.cacheDir, "device-cert-${nonceSha256.take(12)}")
        check(root.mkdirs() || root.isDirectory) { "Could not create app-private certification directory" }
        try {
            val probe = File(root, "roundtrip.txt")
            val value = "droide-storage-${nonceSha256.take(24)}"
            probe.writeText(value, Charsets.UTF_8)
            check(probe.readText(Charsets.UTF_8) == value) { "Private storage round-trip mismatch" }
            check(probe.delete()) { "Could not remove storage probe" }
        } finally {
            root.deleteRecursively()
        }
        return "app-private round-trip PASS"
    }

    fun verifyProcessShell(nonceSha256: String): String {
        val token = "DROIDE_SHELL_${nonceSha256.take(16)}"
        val process = ProcessBuilder("/system/bin/sh", "-c", "printf '$token'")
            .redirectErrorStream(true)
            .start()
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Shell process timed out" }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.exitValue() == 0) { "Shell process exited ${process.exitValue()}" }
            check(output == token) { "Shell process output mismatch" }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        return "system shell execution PASS"
    }

    fun launchMainActivity(instrumentation: Instrumentation, context: Context): MainActivity {
        val launch = context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)
            ?: error("Launcher intent is unavailable")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(launch)
        instrumentation.waitForIdleSync()
        check(activity is MainActivity) { "Launcher did not create MainActivity" }
        assertHealthyActivity(instrumentation, activity)
        return activity
    }

    fun recreateMainActivity(instrumentation: Instrumentation, current: MainActivity): MainActivity {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync { current.recreate() }
            val recreated = instrumentation.waitForMonitorWithTimeout(monitor, ACTIVITY_TIMEOUT_MS)
                ?: error("MainActivity recreation timed out")
            instrumentation.waitForIdleSync()
            check(recreated is MainActivity) { "Recreation did not produce MainActivity" }
            check(recreated !== current) { "Activity recreation reused the same instance" }
            assertHealthyActivity(instrumentation, recreated)
            return recreated
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun assertHealthyActivity(instrumentation: Instrumentation, activity: MainActivity) {
        var healthy = false
        instrumentation.runOnMainSync {
            healthy = !activity.isFinishing && !activity.isDestroyed && activity.window.decorView.isAttachedToWindow
        }
        check(healthy) { "MainActivity did not reach an attached foreground window" }
    }

    private const val APP_PACKAGE = "com.baystudio.droide"
    private const val ACTIVITY_TIMEOUT_MS = 8_000L
}
