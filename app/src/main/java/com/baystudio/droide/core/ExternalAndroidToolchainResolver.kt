package com.baystudio.droide.core

import kotlinx.serialization.json.Json









internal class ExternalAndroidToolchainResolver(
    private val bridge: DeviceBridgeManager,
) {
    data class Candidate(
        val manifest: AndroidDevelopmentManager.ToolchainManifest,
        val markerPath: String,
    )

    private val json = Json { ignoreUnknownKeys = false }

     
    suspend fun read(): Candidate? = readAll().firstOrNull()

    // The legacy marker is first for compatibility.





    suspend fun readAll(): List<Candidate> {
        val result = mutableListOf<Candidate>()
        readMarker(markerPath())?.let(result::add)

        val directory = profileDirectory()
        DeviceBridgeManager.requireSafeRemotePath(directory)
        val listing = bridge.shellBounded(
            "if [ -d ${DeviceBridgeManager.shellQuote(directory)} ]; then " +
                "for f in ${DeviceBridgeManager.shellQuote(directory)}/*.json; do " +
                "[ -f \"\$f\" ] || continue; basename \"\$f\"; done; fi",
            maxOutputBytes = MAX_PROFILE_LIST_BYTES,
        )
        require(!listing.truncated) { "User Android toolchain profile listing was truncated" }
        require(listing.stdout.toByteArray(Charsets.UTF_8).size <= MAX_PROFILE_LIST_BYTES) {
            "User Android toolchain profile listing is too large"
        }
        val names = listing.stdout.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .onEach { require(PROFILE_FILE.matches(it)) { "Invalid user Android toolchain profile name: $it" } }
            .distinct()
            .sorted()
            .take(MAX_PROFILES + 1)
            .toList()
        require(names.size <= MAX_PROFILES) { "Too many user Android toolchain profiles" }
        for (name in names) {
            val path = "$directory/$name"
            readMarker(path)?.let(result::add)
        }
        return result.distinctBy { candidate ->
            val manifest = candidate.manifest
            listOf(
                manifest.version,
                manifest.javaHome,
                manifest.sdkRoot,
                manifest.runtime.mode.name,
                manifest.runtime.rootfsPath.orEmpty(),
            ).joinToString("\u0000")
        }
    }

    private suspend fun readMarker(marker: String): Candidate? {
        DeviceBridgeManager.requireSafeRemotePath(marker)
        val result = bridge.shellBounded(
            "test -f ${DeviceBridgeManager.shellQuote(marker)} && " +
                "cat ${DeviceBridgeManager.shellQuote(marker)} || true",
            maxOutputBytes = MAX_MARKER_BYTES,
        )
        if (result.exitCode != 0 || result.stdout.isBlank()) return null
        require(!result.truncated) { "User Android toolchain marker output was truncated" }
        require(result.stdout.toByteArray(Charsets.UTF_8).size <= MAX_MARKER_BYTES) {
            "User Android toolchain marker is too large"
        }
        val manifest = runCatching {
            json.decodeFromString<AndroidDevelopmentManager.ToolchainManifest>(result.stdout)
        }.getOrElse { error ->
            throw IllegalArgumentException("Invalid user Android toolchain marker at $marker: ${error.message}", error)
        }
        validateExplicitUserManifest(manifest)
        return Candidate(manifest, marker)
    }

    private fun validateExplicitUserManifest(manifest: AndroidDevelopmentManager.ToolchainManifest) {
        // A user marker describes an already-installed workstation toolchain and must not impersonate release evidence.

        require(manifest.schema in 1..2) { "User Android toolchain marker must use schema 1 or 2" }
        require(manifest.candidateId == null && manifest.sourceLockSha256 == null && manifest.buildRecipeVersion == null) {
            "User Android toolchain marker must not contain Droide candidate-certification identity"
        }
        val userRoot = userToolchainRoot()
        listOf(manifest.aapt2Path, manifest.javaHome, manifest.sdkRoot).forEach { path ->
            requireInsideUserRoot(path, userRoot)
        }
        manifest.runtime.launcherPath?.let { requireInsideUserRoot(it, userRoot) }
        manifest.runtime.rootfsPath?.let { requireInsideUserRoot(it, userRoot) }
        manifest.runtime.hostBinPath?.let { requireInsideUserRoot(it, userRoot) }
        manifest.runtime.qemuPath?.let { requireInsideUserRoot(it, userRoot) }
    }

    private fun requireInsideUserRoot(path: String, userRoot: String) {
        DeviceBridgeManager.requireSafeRemotePath(path)
        require(path == userRoot || path.startsWith("$userRoot/")) {
            "User Android toolchain paths must stay inside $userRoot"
        }
    }

    companion object {
        private const val MAX_MARKER_BYTES = 64 * 1024
        private const val MAX_PROFILE_LIST_BYTES = 16 * 1024
        private const val MAX_PROFILES = 24
        private val PROFILE_FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}\\.json")

        fun userToolchainRoot(): String = DeviceBridgeManager.remoteRoot() + "/user/toolchains/android"
        fun markerPath(): String = userToolchainRoot() + "/toolchain.json"
        fun profileDirectory(): String = userToolchainRoot() + "/profiles"
    }
}
