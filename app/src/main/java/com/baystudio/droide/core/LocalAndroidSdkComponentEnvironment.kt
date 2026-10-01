package com.baystudio.droide.core

import java.io.File

internal object LocalAndroidSdkComponentEnvironment {
    const val GUEST_SDK_ROOT = "/opt/droide/android-sdk"

    data class Binding(val hostPath: String, val guestPath: String)

    fun bindings(
        records: List<ManagedPackageRecord>,
        workspaceSelections: Map<String, String>,
        appRoot: String,
    ): List<Binding> {
        val selected = LocalLinuxPackageEnvironment.selectedRecords(records, workspaceSelections, appRoot)
            .filter { it.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT }
        val seenTargets = linkedSetOf<String>()
        return selected.map { record ->
            val target = record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_GUEST_TARGET_KEY]
                ?: error("Android SDK component receipt is missing its guest target")
            requireSafeTarget(target)
            check(seenTargets.add(target)) { "Multiple active Android SDK components target $target" }
            val host = File(record.installRoot, "payload/component")
            LocalExecutionSubstrate.requireSafeLocalPath(host.absolutePath)
            check(host.isDirectory && !PathSecurity.isSymbolicLink(host)) { "Android SDK component payload is unavailable" }
            Binding(host.canonicalPath, "$GUEST_SDK_ROOT/$target")
        }.take(64)
    }

    fun environment(bindings: List<Binding>): Map<String, String> =
        if (bindings.isEmpty()) emptyMap() else mapOf(
            "ANDROID_HOME" to GUEST_SDK_ROOT,
            "ANDROID_SDK_ROOT" to GUEST_SDK_ROOT,
        )

    fun prepareGuestTargets(rootfs: File, bindings: List<Binding>) {
        val sdkRoot = File(rootfs, GUEST_SDK_ROOT.removePrefix("/"))
        check(sdkRoot.mkdirs() || sdkRoot.isDirectory) { "Cannot prepare managed Android SDK root in Ubuntu" }
        val canonicalRootfs = rootfs.canonicalFile
        bindings.forEach { binding ->
            require(binding.guestPath.startsWith("$GUEST_SDK_ROOT/"))
            val target = File(rootfs, binding.guestPath.removePrefix("/"))
            val canonical = target.canonicalFile
            require(canonical.path.startsWith(canonicalRootfs.path + File.separator)) { "Android SDK guest bind escaped Ubuntu rootfs" }
            check(target.mkdirs() || target.isDirectory) { "Cannot prepare Android SDK guest bind target" }
        }
    }

    private fun requireSafeTarget(target: String) {
        require(target.length in 3..200 && !target.startsWith('/') && '\\' !in target) { "Invalid Android SDK component guest target" }
        val parts = target.split('/')
        require(parts.size in 2..4 && parts.all { part ->
            part.isNotBlank() && part != "." && part != ".." && part.matches(Regex("[A-Za-z0-9._+-]{1,80}"))
        }) { "Unsafe Android SDK component guest target" }
        when (parts.first()) {
            "platforms" -> require(parts.size == 2 && parts[1].matches(Regex("android-[0-9]{1,3}")))
            "build-tools", "ndk", "cmake" -> require(parts.size == 2)
            else -> error("Unsupported Android SDK component guest target")
        }
    }
}
