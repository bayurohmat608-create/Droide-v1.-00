package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest

 
internal class AndroidBuildArtifactCollector internal constructor(
    private val cacheDir: File,
    private val shellBounded: suspend (String, Int) -> BridgeShellResult,
    private val pull: suspend (String, File) -> Unit,
) {
    constructor(cacheDir: File, bridge: DeviceBridgeManager) : this(
        cacheDir = cacheDir,
        shellBounded = { command, cap -> bridge.shellBounded(command, cap) },
        pull = { remote, local -> bridge.pull(remote, local) },
    )

    suspend fun collect(remoteWorkspace: String, task: String, buildStartedAtMs: Long): List<File> {
        val query = AndroidBuildArtifactPolicy.queryFor(task) ?: return emptyList()
        val searchRoot = query.modulePath?.let { "$remoteWorkspace/$it" } ?: remoteWorkspace
        DeviceBridgeManager.requireSafeRemotePath(searchRoot)
        val extension = query.kind.extension
        val outputDirectory = query.kind.outputDirectory
        val command = "find ${DeviceBridgeManager.shellQuote(searchRoot)} -type f -name '*.$extension' -path '*/build/outputs/$outputDirectory/*' | head -${MAX_ARTIFACTS + 1}"
        val list = shellBounded(command, 256 * 1024)
        check(list.exitCode == 0) { "Could not enumerate build artifacts: ${list.combined}" }
        check(!list.truncated) { "Build artifact listing exceeded the safety limit" }

        val workspacePrefix = remoteWorkspace.trimEnd('/') + "/"
        val relativeArtifacts = list.stdout.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { remote ->
                DeviceBridgeManager.requireSafeRemotePath(remote)
                require(remote.startsWith(workspacePrefix)) { "Artifact escaped remote workspace" }
                remote.removePrefix(workspacePrefix).also(WorkspaceSyncManifest::validateRelativePath)
            }
            .filter { AndroidBuildArtifactPolicy.matchesRelativePath(query, it) }
            .distinct()
            .toList()
        require(relativeArtifacts.size <= MAX_ARTIFACTS) { "Too many build artifacts (${relativeArtifacts.size}); narrow the Gradle task" }
        if (relativeArtifacts.isEmpty()) return emptyList()

        val buildId = "$buildStartedAtMs-${shortTaskHash(task)}"
        val outDir = File(cacheDir, "android-build-artifacts/$buildId").apply { mkdirs() }
        val outRoot = outDir.canonicalFile
        val pulled = mutableListOf<File>()
        try {
            for (relative in relativeArtifacts) {
                val remote = "$workspacePrefix$relative"
                val target = File(outDir, relative).canonicalFile
                require(target.path.startsWith(outRoot.path + File.separator)) { "Artifact escaped local build cache" }
                target.parentFile?.mkdirs()
                pull(remote, target)
                require(target.isFile && target.length() > 0L) { "Pulled artifact is empty: $relative" }
                pulled += target
            }
            return pulled
        } catch (error: Throwable) {
            // Never leave or expose a partial set after one pull fails; a two-APK build must not degrade into a false single-APK result.

            PathSecurity.deleteTreeNoFollow(outDir)
            throw error
        }
    }

    private fun shortTaskHash(task: String): String = MessageDigest.getInstance("SHA-256")
        .digest(task.toByteArray())
        .take(6)
        .joinToString("") { "%02x".format(it) }

    private companion object { const val MAX_ARTIFACTS = 40 }
}
