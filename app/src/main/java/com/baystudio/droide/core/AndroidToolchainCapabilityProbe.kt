package com.baystudio.droide.core

 
internal class AndroidToolchainCapabilityProbe(
    private val bridge: DeviceBridgeManager,
) {
    suspend fun verify(
        manifest: AndroidDevelopmentManager.ToolchainManifest,
        requirements: AndroidToolchainRequirements,
    ): String? = runSuspendCatching {
        val requiredSdks = requirements.effectiveCompileSdks.ifEmpty { setOf(manifest.compileSdk) }
        val requiredBuildTools = requirements.effectiveBuildToolsVersions
        val requiredNdks = requirements.effectiveNdkVersions
        val requiredCmakes = requirements.effectiveCmakeVersions
        if (manifest.runtime.mode != AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU && requiredBuildTools.size > 1) {
            error(
                "Native/ARM64 Android execution uses one global AAPT2 override and cannot safely honor multiple explicit " +
                    "Build Tools revisions (${requiredBuildTools.sorted().joinToString()}); use a compatible x86_64/QEMU profile"
            )
        }
        val java = manifest.javaHome.trimEnd('/') + "/bin/java"
        val sdkRoot = manifest.sdkRoot.trimEnd('/')
        val androidJars = requiredSdks.map { "$sdkRoot/platforms/android-$it/android.jar" }
        val aapt2Paths = requiredBuildTools.map { "$sdkRoot/build-tools/$it/aapt2" }
            .ifEmpty { listOf(manifest.aapt2Path) }
        (listOf(java) + androidJars + aapt2Paths).forEach(DeviceBridgeManager::requireSafeRemotePath)

        val runtimeChecks = buildString {
            if (AndroidToolchainRuntime.isCompatibility(manifest.runtime)) {
                listOfNotNull(
                    manifest.runtime.launcherPath,
                    manifest.runtime.rootfsPath,
                    manifest.runtime.hostBinPath,
                ).forEach(DeviceBridgeManager::requireSafeRemotePath)
                append("test -x ").append(DeviceBridgeManager.shellQuote(requireNotNull(manifest.runtime.launcherPath))).append("; ")
                append("test -d ").append(DeviceBridgeManager.shellQuote(requireNotNull(manifest.runtime.rootfsPath))).append("; ")
                append("test -d ").append(DeviceBridgeManager.shellQuote(requireNotNull(manifest.runtime.hostBinPath))).append("; ")
                manifest.runtime.qemuPath?.let { qemu ->
                    DeviceBridgeManager.requireSafeRemotePath(qemu)
                    append("test -x ").append(DeviceBridgeManager.shellQuote(qemu)).append("; ")
                }
            }
        }
        val componentChecks = componentChecks(sdkRoot, androidJars, aapt2Paths, requiredNdks, requiredCmakes, requirements)
        val guestJava = manifest.runtime.guestJavaHome
            ?.takeIf { AndroidToolchainRuntime.isCompatibility(manifest.runtime) }
            ?.trimEnd('/')
            ?.plus("/bin/java")
            ?: java
        val guestProbe = executableProbe(guestJava, aapt2Paths, sdkRoot, requiredCmakes)
        val executableProbe = AndroidToolchainRuntime.guestCommand(manifest.runtime, guestProbe)
        val check = bridge.shell(
            "set -eu; " + runtimeChecks +
                "test -x ${DeviceBridgeManager.shellQuote(java)}; " + componentChecks + executableProbe
        )
        check(check.exitCode == 0) { check.combined.ifBlank { "JDK/SDK/build-tool capability probe failed" } }
        null
    }.getOrElse { error ->
        val requested = buildList {
            requirements.effectiveCompileSdks.sorted().takeIf { it.isNotEmpty() }?.let { add("SDK ${it.joinToString()}") }
            requirements.effectiveBuildToolsVersions.sorted().takeIf { it.isNotEmpty() }?.let { add("Build Tools ${it.joinToString()}") }
            requirements.effectiveNdkVersions.sorted().takeIf { it.isNotEmpty() }?.let { add("NDK ${it.joinToString()}") }
            requirements.effectiveCmakeVersions.sorted().takeIf { it.isNotEmpty() }?.let { add("CMake ${it.joinToString()}") }
            if (requirements.requiresNativeToolchain && requirements.effectiveNdkVersions.isEmpty()) add("an installed NDK")
            if (requirements.requiresCmake && requirements.effectiveCmakeVersions.isEmpty()) add("an installed CMake")
        }.joinToString(", ").ifBlank { "the project requirements" }
        "Toolchain does not satisfy $requested: ${error.message ?: "verification failed"}"
    }

    private fun componentChecks(
        sdkRoot: String,
        androidJars: List<String>,
        aapt2Paths: List<String>,
        ndkVersions: Set<String>,
        cmakeVersions: Set<String>,
        requirements: AndroidToolchainRequirements,
    ): String = buildString {
        androidJars.forEach { androidJar ->
            append("test -f ").append(DeviceBridgeManager.shellQuote(androidJar)).append("; ")
        }
        aapt2Paths.forEach { aapt2 ->
            append("test -x ").append(DeviceBridgeManager.shellQuote(aapt2)).append("; ")
        }
        ndkVersions.forEach { version ->
            val ndk = "$sdkRoot/ndk/$version"
            DeviceBridgeManager.requireSafeRemotePath(ndk)
            append("test -d ").append(DeviceBridgeManager.shellQuote(ndk)).append("; ")
            append("test -f ").append(DeviceBridgeManager.shellQuote("$ndk/source.properties")).append("; ")
        }
        cmakeVersions.forEach { version ->
            val cmake = "$sdkRoot/cmake/$version/bin/cmake"
            DeviceBridgeManager.requireSafeRemotePath(cmake)
            append("test -x ").append(DeviceBridgeManager.shellQuote(cmake)).append("; ")
        }
        if (requirements.requiresNativeToolchain && ndkVersions.isEmpty()) {
            val ndkRoot = "$sdkRoot/ndk"
            DeviceBridgeManager.requireSafeRemotePath(ndkRoot)
            append("find ").append(DeviceBridgeManager.shellQuote(ndkRoot))
                .append(" -mindepth 2 -maxdepth 2 -name source.properties -type f -print -quit | grep -q .; ")
        }
        if (requirements.requiresCmake && cmakeVersions.isEmpty()) {
            val cmakeRoot = "$sdkRoot/cmake"
            DeviceBridgeManager.requireSafeRemotePath(cmakeRoot)
            append("find ").append(DeviceBridgeManager.shellQuote(cmakeRoot))
                .append(" -mindepth 3 -maxdepth 3 -path '*/bin/cmake' -type f -print -quit | grep -q .; ")
        }
    }

    private fun executableProbe(
        java: String,
        aapt2Paths: List<String>,
        sdkRoot: String,
        cmakeVersions: Set<String>,
    ): String = buildString {
        append(DeviceBridgeManager.shellQuote(java)).append(" -version >/dev/null 2>&1; ")
        aapt2Paths.forEach { aapt2 ->
            append(DeviceBridgeManager.shellQuote(aapt2)).append(" version >/dev/null 2>&1; ")
        }
        cmakeVersions.forEach { version ->
            val cmake = "$sdkRoot/cmake/$version/bin/cmake"
            append(DeviceBridgeManager.shellQuote(cmake)).append(" --version >/dev/null 2>&1; ")
        }
    }
}
