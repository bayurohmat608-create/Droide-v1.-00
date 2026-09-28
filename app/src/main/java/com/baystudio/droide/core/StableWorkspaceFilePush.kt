package com.baystudio.droide.core

import java.io.File

 
internal object StableWorkspaceFilePush {
    data class Projection(val sha256: String, val mode: Int)

    suspend fun push(
        projectRoot: File,
        bridge: DeviceBridgeManager,
        file: File,
        remote: String,
        initialSha256: String,
        initialMode: Int,
        modeResolver: (File) -> Int,
    ): Projection {
        require(initialSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid workspace content hash" }
        require(initialMode == WorkspaceExecutablePolicy.REGULAR_MODE || initialMode == WorkspaceExecutablePolicy.EXECUTABLE_MODE) {
            "Invalid workspace file mode"
        }
        val relative = runCatching { file.relativeTo(projectRoot).invariantSeparatorsPath }.getOrDefault(file.name)
        var beforeHash = initialSha256
        var beforeMode = initialMode
        repeat(MAX_ATTEMPTS) {
            requireSafeSource(projectRoot, file, relative)
            bridge.push(file, remote, beforeMode)
            requireSafeSource(projectRoot, file, relative)
            val afterHash = sha256NoFollow(file)
            val afterMode = modeResolver(file)
            if (afterHash == beforeHash && afterMode == beforeMode) return Projection(afterHash, afterMode)
            beforeHash = afterHash
            beforeMode = afterMode
        }
        error("Workspace file changed repeatedly during sync: $relative")
    }

    private fun requireSafeSource(projectRoot: File, file: File, relative: String) {
        require(file.isFile && !PathSecurity.isSymbolicLink(file) && PathSecurity.contains(projectRoot, file)) {
            "Workspace file became unsafe during sync: $relative"
        }
    }

    private fun sha256NoFollow(file: File): String = SafeFileDigest.sha256RegularNoFollow(file)

    private const val MAX_ATTEMPTS = 3
}
