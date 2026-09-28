package com.baystudio.droide.core

 
enum class PackageBackendId { LOCAL_APP, DEVICE_ADB }

object PackageBackendContract {
    const val METADATA_KEY = "droide.backend"
    private const val DEVICE_ROOT = "/data/local/tmp/droide"

    // No I/O: admission must precede downloads, guest provisioning and dependency mutations.
    fun requireSame(operation: String, vararg owners: PackageBackendId) {
        require(owners.isNotEmpty()) { "Package transaction has no backend owner" }
        check(owners.all { it == owners.first() }) {
            "$operation unavailable: mixed package backends ${owners.distinct().joinToString()}. " +
                "Local guest package transactions require a local package authority; ADB cannot substitute."
        }
    }

    // Never infer ownership from that label.
    fun recordOwner(installRoot: String, metadata: Map<String, String>, localRoot: String): PackageBackendId? {
        fun safeAbsolute(path: String): Boolean = path.startsWith('/') && path.length > 1 &&
            path.none { it == '\u0000' || it == '\n' || it == '\r' || it == '\\' } &&
            path.drop(1).split('/').none { it.isBlank() || it == "." || it == ".." }
        if (!safeAbsolute(installRoot)) return null
        val inferred = when {
            installRoot.startsWith("$DEVICE_ROOT/") -> PackageBackendId.DEVICE_ADB
            safeAbsolute(localRoot) && installRoot.startsWith("$localRoot/") -> PackageBackendId.LOCAL_APP
            else -> return null
        }
        val declared = metadata[METADATA_KEY] ?: return inferred
        return inferred.takeIf { declared == it.name }
    }

    fun requireOwner(expected: PackageBackendId, installRoot: String, metadata: Map<String, String>, localRoot: String) {
        check(recordOwner(installRoot, metadata, localRoot) == expected) {
            "Package record is not owned by $expected; refusing cross-backend health, activation or removal"
        }
    }
}
