package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Only catalog-reviewed entry points receive executable permission.




internal class ReviewedVendorWholeTreeInstaller(
    private val bridge: DeviceBridgeManager,
    private val packageInstaller: ManagedPackageInstaller,
    private val environment: ReviewedGuestExecutionEnvironment,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun install(
        recipe: VendorArtifactInstallRecipe,
        artifact: File,
        runtimeDependencies: List<ManagedPackageRecord> = emptyList(),
    ): ManagedPackageRecord = withContext(Dispatchers.IO) {
        PackageBackendContract.requireSame("ReviewedVendorWholeTreeInstaller", PackageBackendId.DEVICE_ADB, packageInstaller.backendId, environment.backendId)
        recipe.validate()
        require(recipe.artifactLayout == DeclarativeGitHubArtifactLayout.WHOLE_TREE) { "Vendor whole-tree installer received a single-command recipe" }
        runtimeDependencies.forEach { dependency ->
            require(packageInstaller.verify(dependency)) { "Managed runtime dependency ${dependency.familyId}@${dependency.version} failed verification" }
        }
        environment.ensureInstalled()
        PackageRuntimeCompatibilityPolicy.verifyGuestCommands(environment, recipe.runtimeCompatibility)

        val entryPoint = requireNotNull(recipe.treeEntryPoint)
        val executablePaths = recipe.treeExecutablePaths
        val maxFiles = requireNotNull(recipe.treeMaxFiles)
        val maxBytes = requireNotNull(recipe.treeMaxUnpackedBytes)
        val root = DeviceBridgeManager.remoteRoot()
        val safeFamily = recipe.familyId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = recipe.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val stage = "$root/packages/.staging/$safeFamily-$safeVersion-arm64-v8a"
        val final = "$root/packages/${recipe.familyId}/$safeVersion/arm64-v8a"
        val previous = "$root/packages/.previous/$safeFamily-$safeVersion-arm64-v8a"
        val treeRoot = "$stage/payload/tree"
        val hostBinRoot = "$stage/payload/host-bin"
        listOf(stage, final, previous, treeRoot, hostBinRoot).forEach(DeviceBridgeManager::requireSafeRemotePath)

        val remoteNeed = Math.addExact(maxBytes, WHOLE_TREE_TRANSACTION_OVERHEAD_BYTES)
        DeviceWorkstationStorageGuard.requireHeadroom(bridge, remoteNeed, "install official vendor whole-tree ${recipe.familyId} ${recipe.version}")
        val projection = SafeWholeTreeArchive.project(
            archive = artifact,
            format = recipe.artifactFormat,
            executablePaths = executablePaths.toSet(),
            maxFiles = maxFiles,
            maxUnpackedBytes = maxBytes,
            linkPolicy = recipe.treeLinkPolicy,
            modePolicy = recipe.treeModePolicy,
        )
        try {
            val largestProjectedFile = projection.files.maxOfOrNull { it.size } ?: 0L
            DeviceWorkstationStorageGuard.requireHeadroom(
                bridge = bridge,
                additionalBytes = largestProjectedFile,
                purpose = "transfer official vendor whole-tree ${recipe.familyId} ${recipe.version}",
            )
            val entryFile = projection.files.singleOrNull { it.relativePath == entryPoint }
            val entryLink = projection.symlinks.singleOrNull { it.relativePath == entryPoint }
            require(entryFile != null || entryLink != null) { "Reviewed vendor tree is missing entry point '$entryPoint'" }
            if (entryFile != null) require(entryFile.mode == EXECUTABLE_MODE) { "Reviewed vendor tree entry point is not executable" }

            val prep = bridge.shell("set -eu; rm -rf ${q(stage)} ${q(previous)}; mkdir -p ${q(treeRoot)} ${q(hostBinRoot)} ${q("$root/packages/.previous")}")
            check(prep.exitCode == 0) { "Could not prepare vendor whole-tree transaction: ${prep.combined.takeLast(8_000)}" }

            var activated = false
            try {
                createRemoteDirectories(treeRoot, projection.files)
                projection.files.forEach { file ->
                    val remote = "$treeRoot/${file.relativePath}"
                    DeviceBridgeManager.requireSafeRemotePath(remote)
                    bridge.push(file.file, remote, mode = file.mode)
                    projection.releaseLocalPayload(file)
                }
                createRemoteSymlinks(treeRoot, projection.symlinks)
                val treeManifest = projection.files.sortedBy { it.relativePath }.joinToString(separator = "\n", postfix = "\n") {
                    "${it.sha256}  ${it.relativePath}"
                }
                ByteArrayInputStream(treeManifest.toByteArray(Charsets.UTF_8)).use { bridge.pushStream(it, "$stage/TREE_SHA256SUMS", mode = 420) }
                val transfer = bridge.shellBounded(
                    "set -eu; cd ${q(treeRoot)}; toybox sha256sum -c ${q("$stage/TREE_SHA256SUMS")} >/dev/null; " +
                        "test \"${'$'}(find . -type f | wc -l | tr -d ' ')\" = ${q(projection.files.size.toString())}; " +
                        "test \"${'$'}(find . -type l | wc -l | tr -d ' ')\" = ${q(projection.symlinks.size.toString())}",
                    maxOutputBytes = 32_768,
                )
                check(transfer.exitCode == 0) { "Vendor whole-tree transfer integrity failed: ${transfer.combined.takeLast(8_000)}" }
                verifyRemoteSymlinks(treeRoot, projection.symlinks)

                val commandPaths = linkedMapOf(recipe.command to entryPoint).apply { putAll(recipe.treeAuxCommands) }
                commandPaths.values.distinct().forEach { relative ->
                    val staged = "$treeRoot/$relative"
                    DeviceBridgeManager.requireSafeRemotePath(staged)
                    verifyArm64Elf(staged, allowSymlink = recipe.treeLinkPolicy == DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE)
                }
                val health = environment.execute(listOf("$treeRoot/$entryPoint") + recipe.healthArgs, maxOutputBytes = 128_000)
                check(health.exitCode == 0) { "Vendor whole-tree health check failed: ${health.output.takeLast(8_000)}" }

                val finalTreeRoot = "$final/payload/tree"
                commandPaths.forEach { (command, relative) ->
                    val wrapper = "$hostBinRoot/$command"
                    DeviceBridgeManager.requireSafeRemotePath(wrapper)
                    val finalTarget = "$finalTreeRoot/$relative"
                    val script = "#!/system/bin/sh\nset -eu\nexec ${q(environment.launcherPath())} ${q(finalTarget)} \"${'$'}@\"\n"
                    ByteArrayInputStream(script.toByteArray(Charsets.UTF_8)).use { bridge.pushStream(it, wrapper, mode = EXECUTABLE_MODE) }
                }

                val dependencyKeys = runtimeDependencies.map { "${it.familyId}@${it.version}" }.distinct().sorted()
                val lock = ReviewedVendorWholeTreeLock(
                    familyId = recipe.familyId,
                    version = recipe.version,
                    command = recipe.command,
                    versionAuthority = recipe.versionAuthority,
                    releaseTag = recipe.releaseTag,
                    artifactFormat = recipe.artifactFormat.name,
                    assetName = recipe.assetName,
                    assetUrl = recipe.assetUrl,
                    assetSha256 = recipe.assetSha256,
                    assetSize = artifact.length(),
                    treeEntryPoint = entryPoint,
                    treeExecutablePaths = executablePaths.sorted(),
                    treeAuxCommands = recipe.treeAuxCommands.toSortedMap(),
                    treeFiles = projection.files.size,
                    treeSymlinks = projection.symlinks.size,
                    treeBytes = projection.totalBytes,
                    treeSha256 = projection.treeSha256,
                    runtimeDependencies = dependencyKeys,
                    provenanceUrl = recipe.provenanceUrl,
                    guestProfile = recipe.runtimeCompatibility.guestProfile.name,
                    libcCompatibility = recipe.runtimeCompatibility.libc.name,
                    compatibilityEvidenceUrl = recipe.runtimeCompatibility.evidenceUrl,
                    installedAtEpochMs = System.currentTimeMillis(),
                )
                ByteArrayInputStream(json.encodeToString(lock).toByteArray(Charsets.UTF_8)).use { bridge.pushStream(it, "$stage/RECIPE_LOCK.json", mode = 420) }

                val maxStageFiles = Math.addExact(maxFiles, commandPaths.size + 4)
                val maxStageKiB = Math.addExact((maxBytes + 1023L) / 1024L, 4096L)
                val bounds = bridge.shellBounded(
                    "set -eu; C=${'$'}(find ${q(stage)} -type f | wc -l | tr -d ' '); B=${'$'}(du -sk ${q(stage)} | awk '{print ${'$'}1}'); " +
                        "test \"${'$'}C\" -le ${q(maxStageFiles.toString())}; test \"${'$'}B\" -le ${q(maxStageKiB.toString())}",
                    maxOutputBytes = 16_384,
                )
                check(bounds.exitCode == 0) { "Vendor whole-tree package exceeded reviewed final bounds" }
                val checksums = bridge.shellBounded(
                    "set -eu; cd ${q(stage)}; find . -type f ! -name SHA256SUMS -print | sort | while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done > SHA256SUMS; toybox sha256sum -c SHA256SUMS >/dev/null",
                    maxOutputBytes = 32_768,
                )
                check(checksums.exitCode == 0) { "Vendor whole-tree integrity manifest failed: ${checksums.combined.takeLast(8_000)}" }
                val move = bridge.shell(
                    "set -eu; rm -rf ${q(previous)}; if [ -d ${q(final)} ]; then mv ${q(final)} ${q(previous)}; fi; " +
                        "mkdir -p ${q(final.substringBeforeLast('/'))}; mv ${q(stage)} ${q(final)}",
                )
                check(move.exitCode == 0) { "Could not activate vendor whole-tree package: ${move.combined.takeLast(8_000)}" }
                activated = true

                val finalHostBinRoot = "$final/payload/host-bin"
                val record = ManagedPackageRecord(
                    familyId = recipe.familyId,
                    version = recipe.version,
                    scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                    installRoot = final,
                    installedAtEpochMs = System.currentTimeMillis(),
                    active = true,
                    pathEntries = listOf(finalHostBinRoot),
                    commands = commandPaths.keys.associateWith { "$finalHostBinRoot/$it" },
                    dependencies = dependencyKeys,
                    healthChecks = listOf(ManagedPackageHealthCheck("host-bin/${recipe.command}", recipe.healthArgs)),
                    artifactSha256 = recipe.assetSha256,
                    abi = "arm64-v8a",
                )
                packageInstaller.adoptTrustedArtifactRecord(record)
                withContext(NonCancellable) { bridge.shell("rm -rf ${q(previous)}") }
                activated = false
                record
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    if (activated) bridge.shell("rm -rf ${q(final)}; if [ -d ${q(previous)} ]; then mv ${q(previous)} ${q(final)}; fi")
                    bridge.shell("rm -rf ${q(stage)} ${q(previous)}")
                }
                throw failure
            }
        } finally {
            projection.close()
        }
    }

    private suspend fun createRemoteDirectories(treeRoot: String, files: List<SafeTreeFile>) {
        val dirs = files.mapNotNull { it.relativePath.substringBeforeLast('/', "").takeIf(String::isNotBlank) }
            .distinct().sortedWith(compareBy<String> { it.count { ch -> ch == '/' } }.thenBy { it })
        dirs.chunked(40).forEach { chunk ->
            val result = bridge.shellBounded("mkdir -p " + chunk.joinToString(" ") { q("$treeRoot/$it") }, maxOutputBytes = 16_384)
            check(result.exitCode == 0) { "Could not create vendor whole-tree directories: ${result.combined.takeLast(4_000)}" }
        }
    }

    private suspend fun createRemoteSymlinks(treeRoot: String, links: List<SafeTreeSymlink>) {
        links.sortedBy { it.relativePath }.forEach { link ->
            val remote = "$treeRoot/${link.relativePath}"
            DeviceBridgeManager.requireSafeRemotePath(remote)
            val parent = remote.substringBeforeLast('/', treeRoot)
            val result = bridge.shellBounded(
                "set -eu; mkdir -p ${q(parent)}; rm -f ${q(remote)}; ln -s ${q(link.target)} ${q(remote)}",
                maxOutputBytes = 8_192,
            )
            check(result.exitCode == 0) { "Could not create reviewed vendor tree symlink: ${result.combined.takeLast(4_000)}" }
        }
    }

    private suspend fun verifyRemoteSymlinks(treeRoot: String, links: List<SafeTreeSymlink>) {
        links.chunked(24).forEach { chunk ->
            val checks = chunk.joinToString("; ") { link ->
                val remote = "$treeRoot/${link.relativePath}"
                "test -L ${q(remote)}; test \"${'$'}(toybox readlink ${q(remote)})\" = ${q(link.target)}; test -e ${q(remote)}"
            }
            val result = bridge.shellBounded("set -eu; $checks", maxOutputBytes = 16_384)
            check(result.exitCode == 0) { "Reviewed vendor symlink graph failed remote verification: ${result.combined.takeLast(4_000)}" }
        }
    }

    private suspend fun verifyArm64Elf(path: String, allowSymlink: Boolean = false) {
        val resolve = if (allowSymlink) "if [ -L \"${'$'}F\" ]; then F=${'$'}(toybox readlink -f \"${'$'}F\"); fi; " else "test ! -L \"${'$'}F\"; "
        val result = bridge.shellBounded(
            "set -eu; F=${q(path)}; $resolve test -f \"${'$'}F\"; " +
                "test \"${'$'}(toybox od -An -tx1 -N5 \"${'$'}F\" | tr -d ' \\n')\" = 7f454c4602; " +
                "test \"${'$'}(toybox od -An -tx1 -j18 -N2 \"${'$'}F\" | tr -d ' \\n')\" = b700",
            maxOutputBytes = 16_384,
        )
        check(result.exitCode == 0) { "Vendor whole-tree command is not an ELF64 AArch64 executable" }
    }

    private fun q(value: String): String = DeviceBridgeManager.shellQuote(value)

    private companion object {
        const val EXECUTABLE_MODE = 493
        const val WHOLE_TREE_TRANSACTION_OVERHEAD_BYTES = 96L * 1024L * 1024L
    }
}

@Serializable
private data class ReviewedVendorWholeTreeLock(
    val schema: Int = 1,
    val installer: String = "vendor-official-whole-tree-sha256",
    val familyId: String,
    val version: String,
    val command: String,
    val versionAuthority: String,
    val releaseTag: String,
    val platform: String = "linux-arm64",
    val artifactFormat: String,
    val assetName: String,
    val assetUrl: String,
    val assetSha256: String,
    val assetSize: Long,
    val treeEntryPoint: String,
    val treeExecutablePaths: List<String>,
    val treeAuxCommands: Map<String, String>,
    val treeFiles: Int,
    val treeSymlinks: Int = 0,
    val treeBytes: Long,
    val treeSha256: String,
    val runtimeDependencies: List<String>,
    val provenanceUrl: String,
    val guestProfile: String,
    val libcCompatibility: String,
    val compatibilityEvidenceUrl: String,
    val installedAtEpochMs: Long,
)
