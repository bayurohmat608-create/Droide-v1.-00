package com.baystudio.droide.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext








internal class AndroidAppRuntimeController(
    private val bridge: DeviceBridgeManager,
    private val manifestProvider: suspend () -> AndroidDevelopmentManager.ToolchainManifest?,
) {
    @Volatile private var lastRuntimePackage: String? = null

    suspend fun installAndRun(apk: File, expectedPackageName: String? = null): String {
        val pkg = resolveAndValidate(apk, expectedPackageName)
        bridge.install(apk, replace = true)
        val launch = bridge.launch(pkg)
        check(launch.exitCode == 0) { launch.combined }
        lastRuntimePackage = pkg
        return "Installed and launched $pkg"
    }

    suspend fun installAndLaunchForDebug(
        apk: File,
        expectedPackageName: String? = null,
        waitTimeoutMs: Long = 12_000,
    ): AndroidDevelopmentManager.DebugLaunchResult {
        val pkg = resolveAndValidate(apk, expectedPackageName)
        require(waitTimeoutMs in 2_000..30_000) { "Invalid debugger wait timeout" }
        bridge.install(apk, replace = true)
        runSuspendCatching { bridge.forceStop(pkg) }
        val launch = bridge.launchDebuggable(pkg, suspendAtStart = true)
        check(launch.exitCode == 0) { launch.combined }
        lastRuntimePackage = pkg
        val deadline = System.currentTimeMillis() + waitTimeoutMs
        var lastPid: Int? = null
        while (System.currentTimeMillis() < deadline) {
            val pid = bridge.pidOf(pkg)
            if (pid != null) {
                lastPid = pid
                val debuggable = runSuspendCatching { bridge.jdwpPids(timeoutMs = 1_500) }.getOrDefault(emptyList())
                if (pid in debuggable) {
                    return AndroidDevelopmentManager.DebugLaunchResult(
                        pkg,
                        pid,
                        "Launched $pkg suspended for debugger attach (PID $pid)",
                    )
                }
            }
            delay(150)
        }
        runSuspendCatching { bridge.forceStop(pkg) }
        error("$pkg launched but did not expose JDWP within ${waitTimeoutMs}ms" + (lastPid?.let { " (PID $it)" } ?: ""))
    }

    suspend fun stopApp(packageName: String? = null) {
        val pkg = requireKnownPackage(packageName)
        val result = bridge.forceStop(pkg)
        check(result.exitCode == 0) { result.combined }
    }

    suspend fun logs(packageName: String? = null): String = bridge.logcat(requireKnownPackage(packageName)).combined

    private fun requireKnownPackage(explicit: String?): String {
        explicit?.let {
            DeviceBridgeManager.requirePackageName(it)
            return it
        }
        return lastRuntimePackage ?: error(
            "Runtime package is unknown. Install/run an APK first or provide an explicit package id."
        )
    }

    private suspend fun resolveAndValidate(apk: File, expected: String?): String {
        val actual = resolvePackageName(apk)
        expected?.let {
            DeviceBridgeManager.requirePackageName(it)
            require(actual == it) {
                "APK package '$actual' does not match the requested package '$it'. Refusing to target a different app."
            }
        }
        return actual
    }

    private suspend fun resolvePackageName(apk: File): String {
        require(apk.isFile && apk.extension.equals("apk", ignoreCase = true)) { "APK not found: ${apk.path}" }
        val manifest = manifestProvider() ?: error("Verified Android toolchain is unavailable")
        val remoteDir = "${DeviceBridgeManager.remoteRoot()}/tmp/apk-inspect/${UUID.randomUUID()}"
        val remoteApk = "$remoteDir/artifact.apk"
        DeviceBridgeManager.requireSafeRemotePath(remoteDir)
        DeviceBridgeManager.requireSafeRemotePath(remoteApk)
        val prepare = bridge.shell("mkdir -p ${DeviceBridgeManager.shellQuote(remoteDir)}")
        check(prepare.exitCode == 0) { "Could not prepare APK inspection workspace: ${prepare.combined}" }
        try {
            bridge.push(apk, remoteApk)
            val aapt2 = DeviceBridgeManager.shellQuote(manifest.aapt2Path)
            val inspect = AndroidToolchainRuntime.guestCommand(
                manifest.runtime,
                "$aapt2 dump packagename ${DeviceBridgeManager.shellQuote(remoteApk)}",
            )
            val result = bridge.shellBounded(inspect, maxOutputBytes = 128 * 1024)
            check(result.exitCode == 0 && !result.truncated) {
                "AAPT2 could not inspect the built APK: ${result.combined}"
            }
            val lines = result.stdout.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            require(lines.size == 1) { "AAPT2 returned an ambiguous package-name response" }
            val pkg = lines.single()
            DeviceBridgeManager.requirePackageName(pkg)
            return pkg
        } finally {
            withContext(NonCancellable) {
                runSuspendCatching {
                    bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(remoteDir)}")
                }
            }
        }
    }

}
