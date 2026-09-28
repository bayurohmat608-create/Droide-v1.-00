package com.baystudio.droide.core

 
internal object GradleBuildCommand {
    fun create(
        manifest: AndroidDevelopmentManager.ToolchainManifest,
        remoteWorkspace: String,
        task: String,
        maxWorkers: Int,
        useBuildCache: Boolean,
        useLowProcessPriority: Boolean,
        daemonIdleMillis: Long,
        allowPersistentDaemon: Boolean,
        readOnlyDependencyCache: GradleReadOnlyDependencyCache?,
    ): String {
        DeviceBridgeManager.requireSafeRemotePath(remoteWorkspace)
        GradleTaskPath.parse(task)
        readOnlyDependencyCache?.let { DeviceBridgeManager.requireSafeRemotePath(it.root) }
        val root = DeviceBridgeManager.remoteRoot()
        val home = "$root/home"
        val gradleHome = "$root/gradle-cache"
        val tempDir = "$root/tmp"
        val path = "${manifest.javaHome}/bin:/system/bin:/system/xbin"
        val compatibilityRuntime = AndroidToolchainRuntime.isCompatibility(manifest.runtime)
        val commonArgs = buildString {
            append("--console=plain ")
            



            if (!allowPersistentDaemon || compatibilityRuntime) append("--no-daemon ")
            append("--max-workers=").append(maxWorkers).append(' ')
            if (useBuildCache) append("--build-cache ")
            if (useLowProcessPriority) append("-Dorg.gradle.priority=low ")
            append("-Dorg.gradle.daemon.idletimeout=").append(daemonIdleMillis).append(' ')
            append("-Dorg.gradle.vfs.watch=false ")
            if (manifest.runtime.mode != AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU) {
                append("-Pandroid.aapt2FromMavenOverride=")
                append(DeviceBridgeManager.shellQuote(manifest.aapt2Path)).append(' ')
            }
            append(DeviceBridgeManager.shellQuote(task))
        }
        return buildString {
            append("set -eu; ")
            append("export HOME=").append(DeviceBridgeManager.shellQuote(home)).append("; ")
            append("export JAVA_HOME=").append(DeviceBridgeManager.shellQuote(manifest.javaHome)).append("; ")
            append("export ANDROID_HOME=").append(DeviceBridgeManager.shellQuote(manifest.sdkRoot)).append("; ")
            append("export ANDROID_SDK_ROOT=").append(DeviceBridgeManager.shellQuote(manifest.sdkRoot)).append("; ")
            append("export GRADLE_USER_HOME=").append(DeviceBridgeManager.shellQuote(gradleHome)).append("; ")
            readOnlyDependencyCache?.let {
                append("export GRADLE_RO_DEP_CACHE=").append(DeviceBridgeManager.shellQuote(it.root)).append("; ")
            }
            append("export TMPDIR=").append(DeviceBridgeManager.shellQuote(tempDir)).append("; ")
            append("export PATH=").append(DeviceBridgeManager.shellQuote(path)).append("; ")
            append("mkdir -p ").append(DeviceBridgeManager.shellQuote(home)).append(' ')
                .append(DeviceBridgeManager.shellQuote(gradleHome)).append(' ')
                .append(DeviceBridgeManager.shellQuote(tempDir)).append("; ")
            append("cd ").append(DeviceBridgeManager.shellQuote(remoteWorkspace)).append("; ")
            append(DeviceBridgeManager.shellQuote(manifest.javaHome + "/bin/java")).append(" -version >/dev/null; ")
            append("toybox tr -d '\\015' < ./gradlew > ./.droide-gradlew; chmod 700 ./.droide-gradlew; ")
            if (compatibilityRuntime) {
                append(AndroidToolchainRuntime.guestCommand(manifest.runtime, "/bin/sh ./.droide-gradlew $commonArgs", remoteWorkspace))
            } else {
                append("/system/bin/sh ./.droide-gradlew ").append(commonArgs)
            }
        }
    }
}
