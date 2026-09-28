package com.baystudio.droide.core

 
internal object AndroidToolchainProjectBinding {
    fun bind(
        manifest: AndroidDevelopmentManager.ToolchainManifest,
        requirements: AndroidToolchainRequirements,
    ): AndroidDevelopmentManager.ToolchainManifest {
        



        if (manifest.runtime.mode == AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU) return manifest
        val buildTools = requirements.effectiveBuildToolsVersions
        if (buildTools.isEmpty()) return manifest
        check(buildTools.size == 1) {
            "Native/ARM64 Android execution cannot bind multiple explicit Build Tools revisions: ${buildTools.sorted().joinToString()}"
        }
        val path = manifest.sdkRoot.trimEnd('/') + "/build-tools/${buildTools.single()}/aapt2"
        DeviceBridgeManager.requireSafeRemotePath(path)
        return manifest.copy(aapt2Path = path)
    }
}
