package com.baystudio.droide.core

import kotlinx.serialization.Serializable









@Serializable
enum class AndroidToolchainExecutionMode {
    ANDROID_NATIVE,
    LINUX_ARM64_PROOT,
    LINUX_X86_64_PROOT_QEMU,
}

@Serializable
data class AndroidToolchainRuntimeSpec(
    val mode: AndroidToolchainExecutionMode = AndroidToolchainExecutionMode.ANDROID_NATIVE,
    val launcherPath: String? = null,
    val rootfsPath: String? = null,
    val hostBinPath: String? = null,
    val guestJavaHome: String? = null,
    val qemuPath: String? = null,
)

object AndroidToolchainRuntime {
    fun validate(spec: AndroidToolchainRuntimeSpec, abi: String) {
        when (spec.mode) {
            AndroidToolchainExecutionMode.ANDROID_NATIVE -> Unit
            AndroidToolchainExecutionMode.LINUX_ARM64_PROOT -> {
                require(abi == "arm64-v8a") {
                    "Linux ARM64 compatibility runtime currently requires an arm64-v8a device"
                }
                validateLinuxRuntime(spec, requireQemu = false)
            }
            AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU -> {
                require(abi == "arm64-v8a") {
                    "Linux x86_64/QEMU compatibility runtime currently requires an arm64-v8a device"
                }
                validateLinuxRuntime(spec, requireQemu = true)
            }
        }
    }

    private fun validateLinuxRuntime(spec: AndroidToolchainRuntimeSpec, requireQemu: Boolean) {
        val launcher = requireNotNull(spec.launcherPath) { "Compatibility launcher path is missing" }
        val rootfs = requireNotNull(spec.rootfsPath) { "Compatibility rootfs path is missing" }
        val hostBin = requireNotNull(spec.hostBinPath) { "Compatibility host-bin path is missing" }
        val guestJavaHome = requireNotNull(spec.guestJavaHome) { "Compatibility guest JAVA_HOME is missing" }
        listOf(launcher, rootfs, hostBin).forEach(DeviceBridgeManager::requireSafeRemotePath)
        requireGuestAbsolutePath(guestJavaHome)
        if (requireQemu) {
            val qemu = requireNotNull(spec.qemuPath) { "x86_64 compatibility runtime requires qemuPath" }
            DeviceBridgeManager.requireSafeRemotePath(qemu)
        } else {
            require(spec.qemuPath == null) { "ARM64 PRoot runtime must not declare qemuPath" }
        }
    }

    fun isCompatibility(spec: AndroidToolchainRuntimeSpec): Boolean =
        spec.mode != AndroidToolchainExecutionMode.ANDROID_NATIVE

    fun runtimePriority(spec: AndroidToolchainRuntimeSpec): Int = when (spec.mode) {
        AndroidToolchainExecutionMode.ANDROID_NATIVE -> 0
        AndroidToolchainExecutionMode.LINUX_ARM64_PROOT -> 1
        AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU -> 2
    }

    fun canRunDesktopHostTools(spec: AndroidToolchainRuntimeSpec): Boolean = when (spec.mode) {
        AndroidToolchainExecutionMode.ANDROID_NATIVE -> false
        AndroidToolchainExecutionMode.LINUX_ARM64_PROOT,
        AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU -> true
    }

    




    fun guestCommand(
        spec: AndroidToolchainRuntimeSpec,
        command: String,
        workingDirectory: String? = null,
    ): String {
        if (!isCompatibility(spec)) return command
        val launcher = requireNotNull(spec.launcherPath)
        val rootfs = requireNotNull(spec.rootfsPath)
        val guestJavaHome = requireNotNull(spec.guestJavaHome)
        listOf(launcher, rootfs).forEach(DeviceBridgeManager::requireSafeRemotePath)
        workingDirectory?.let(DeviceBridgeManager::requireSafeRemotePath)
        requireGuestAbsolutePath(guestJavaHome)

        val guestScript = buildString {
            append("export DROIDE_COMPAT_ACTIVE=1; ")
            append("export JAVA_HOME=").append(DeviceBridgeManager.shellQuote(guestJavaHome)).append("; ")
            append("export PATH=\"\$JAVA_HOME/bin:\$PATH\"; ")
            append(command)
        }
        return buildString {
            append(DeviceBridgeManager.shellQuote(launcher))
            append(" -0 -r ").append(DeviceBridgeManager.shellQuote(rootfs))
            if (spec.mode == AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU) {
                val qemu = requireNotNull(spec.qemuPath)
                DeviceBridgeManager.requireSafeRemotePath(qemu)
                append(" -q ").append(DeviceBridgeManager.shellQuote(qemu))
            }
            

            append(" -b /dev -b /proc -b /sys -b /system -b /apex -b /data/local/tmp")
            if (workingDirectory != null) {
                append(" -w ").append(DeviceBridgeManager.shellQuote(workingDirectory))
            }
            append(" /bin/sh -lc ").append(DeviceBridgeManager.shellQuote(guestScript))
        }
    }

    fun additionalInteractivePathEntries(spec: AndroidToolchainRuntimeSpec): List<String> =
        spec.hostBinPath?.takeIf { isCompatibility(spec) }?.let(::listOf).orEmpty()

    fun additionalEnvironment(spec: AndroidToolchainRuntimeSpec): Map<String, String> = when (spec.mode) {
        AndroidToolchainExecutionMode.ANDROID_NATIVE -> mapOf("DROIDE_TOOLCHAIN_EXECUTION" to "android-native")
        AndroidToolchainExecutionMode.LINUX_ARM64_PROOT -> mapOf(
            "DROIDE_TOOLCHAIN_EXECUTION" to "linux-arm64-proot",
            "DROIDE_GUEST_JAVA_HOME" to requireNotNull(spec.guestJavaHome),
        )
        AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU -> mapOf(
            "DROIDE_TOOLCHAIN_EXECUTION" to "linux-x86_64-proot-qemu",
            "DROIDE_GUEST_JAVA_HOME" to requireNotNull(spec.guestJavaHome),
            "DROIDE_QEMU_USER" to requireNotNull(spec.qemuPath),
        )
    }

    private fun requireGuestAbsolutePath(path: String) {
        require(path.startsWith('/') && path.length in 2..400) { "Invalid guest path" }
        require(path.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Invalid guest path" }
        val parts = path.split('/').filter(String::isNotBlank)
        require(parts.none { it == "." || it == ".." }) { "Unsafe guest path" }
    }
}
