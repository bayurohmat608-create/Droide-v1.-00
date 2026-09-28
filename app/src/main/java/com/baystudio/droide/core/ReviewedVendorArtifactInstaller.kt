package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json








data class VendorArtifactInstallRecipe(
    val familyId: String,
    val version: String,
    val command: String,
    val versionAuthority: String,
    val releaseTag: String,
    val artifactFormat: DeclarativeGitHubArtifactFormat,
    val artifactLayout: DeclarativeGitHubArtifactLayout = DeclarativeGitHubArtifactLayout.SINGLE_COMMAND,
    val executableBasename: String,
    val interpreterFamilyId: String? = null,
    val interpreterCommand: String? = null,
    val archiveMemberPath: String? = null,
    val archiveMaxFiles: Int? = null,
    val archiveMaxUnpackedBytes: Long? = null,
    val treeEntryPoint: String? = null,
    val treeExecutablePaths: List<String> = emptyList(),
    val treeAuxCommands: Map<String, String> = emptyMap(),
    val treeMaxFiles: Int? = null,
    val treeMaxUnpackedBytes: Long? = null,
    val treeLinkPolicy: DeclarativeWholeTreeLinkPolicy = DeclarativeWholeTreeLinkPolicy.REJECT,
    val treeModePolicy: DeclarativeWholeTreeModePolicy = DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST,
    val assetName: String,
    val assetUrl: String,
    val assetSha256: String,
    val assetMaxBytes: Long,
    val provenanceUrl: String,
    val healthArgs: List<String> = listOf("--version"),
    val runtimeCompatibility: DeclarativeRuntimeCompatibility,
) {
    fun validate() {
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid vendor recipe family" }
        require(version.matches(Regex("[0-9][A-Za-z0-9._+-]{0,79}"))) { "Invalid vendor release version" }
        require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid vendor command" }
        require(versionAuthority.startsWith("https://") && versionAuthority.length <= 500) { "Invalid vendor version authority" }
        require(releaseTag.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,95}"))) { "Invalid vendor release tag" }
        require(executableBasename.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Invalid vendor executable basename" }
        when (artifactLayout) {
            DeclarativeGitHubArtifactLayout.SINGLE_COMMAND -> {
                require(treeEntryPoint == null && treeExecutablePaths.isEmpty() && treeAuxCommands.isEmpty() && treeMaxFiles == null && treeMaxUnpackedBytes == null && treeLinkPolicy == DeclarativeWholeTreeLinkPolicy.REJECT && treeModePolicy == DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST) {
                    "Single-command vendor artifact must not declare whole-tree metadata"
                }
                require(artifactFormat in setOf(DeclarativeGitHubArtifactFormat.RAW, DeclarativeGitHubArtifactFormat.PHAR, DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ)) {
                    "Vendor single-command transaction certifies RAW/PHAR/TAR_GZ/TGZ artifacts"
                }
                when (artifactFormat) {
                    DeclarativeGitHubArtifactFormat.PHAR -> {
                        require(archiveMemberPath == null && archiveMaxFiles == null && archiveMaxUnpackedBytes == null) {
                            "PHAR vendor artifact must not declare archive projection metadata"
                        }
                        require(interpreterFamilyId?.matches(Regex("[A-Za-z0-9._-]{1,120}")) == true) { "PHAR vendor artifact requires an interpreter family" }
                        require(interpreterCommand?.matches(Regex("[A-Za-z0-9._+-]{1,80}")) == true) { "PHAR vendor artifact requires an interpreter command" }
                    }
                    DeclarativeGitHubArtifactFormat.RAW -> {
                        require(archiveMemberPath == null && archiveMaxFiles == null && archiveMaxUnpackedBytes == null) {
                            "Raw vendor artifact must not declare archive projection metadata"
                        }
                        require(interpreterFamilyId == null && interpreterCommand == null) { "Raw vendor artifact must not declare an interpreter" }
                    }
                    DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ -> {
                        require(interpreterFamilyId == null && interpreterCommand == null) { "Native vendor archive must not declare an interpreter" }
                        val member = requireNotNull(archiveMemberPath) { "Vendor archive requires a reviewed member path" }
                        require(safeArchivePath(member)) { "Invalid reviewed vendor archive member path" }
                        require(member.substringAfterLast('/') == executableBasename) { "Vendor archive member basename mismatch" }
                        require(archiveMaxFiles != null && archiveMaxFiles in 1..4_096) { "Invalid vendor archive file-count bound" }
                        require(archiveMaxUnpackedBytes != null && archiveMaxUnpackedBytes in 1L..1_073_741_824L) { "Invalid vendor archive expanded-byte bound" }
                    }
                    else -> error("Unsupported single-command vendor artifact format")
                }
            }
            DeclarativeGitHubArtifactLayout.WHOLE_TREE -> {
                require(artifactFormat in setOf(DeclarativeGitHubArtifactFormat.ZIP, DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ)) {
                    "Vendor whole-tree transaction requires ZIP/TAR_GZ/TGZ"
                }
                require(interpreterFamilyId == null && interpreterCommand == null) { "Vendor native whole-tree artifact must not declare an interpreter" }
                require(archiveMemberPath == null && archiveMaxFiles == null && archiveMaxUnpackedBytes == null) { "Vendor whole-tree artifact must not declare single-member metadata" }
                val entry = requireNotNull(treeEntryPoint) { "Vendor whole-tree artifact requires a reviewed entry point" }
                require(safeArchivePath(entry)) { "Invalid vendor whole-tree entry point" }
                require(treeExecutablePaths.distinct().size == treeExecutablePaths.size && treeExecutablePaths.all(::safeArchivePath)) {
                    "Invalid vendor whole-tree executable paths"
                }
                when (treeModePolicy) {
                    DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST -> {
                        require(treeExecutablePaths.size in 1..64) { "Explicit vendor executable allowlist is invalid" }
                        require(entry in treeExecutablePaths) { "Vendor whole-tree entry point must be executable" }
                        require(treeAuxCommands.values.all { it in treeExecutablePaths }) { "Vendor auxiliary commands must point at reviewed executable paths" }
                    }
                    DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE -> {
                        require(treeExecutablePaths.isEmpty()) { "Pinned-archive mode policy must not duplicate executable metadata" }
                        require(artifactFormat in setOf(DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ)) {
                            "Pinned archive mode policy currently requires TAR_GZ/TGZ"
                        }
                    }
                }
                require(treeAuxCommands.size <= 16 && treeAuxCommands.keys.all { it.matches(Regex("[A-Za-z0-9._+-]{1,80}")) } && treeAuxCommands.values.all(::safeArchivePath)) {
                    "Invalid vendor whole-tree auxiliary commands"
                }
                if (treeLinkPolicy == DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE) {
                    require(artifactFormat in setOf(DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ)) {
                        "Safe relative-link projection currently requires TAR_GZ/TGZ"
                    }
                }
                require(treeMaxFiles != null && treeMaxFiles in 1..4_096) { "Invalid vendor whole-tree file-count bound" }
                require(treeMaxUnpackedBytes != null && treeMaxUnpackedBytes in 1L..6_442_450_944L) { "Invalid vendor whole-tree expanded-byte bound" }
            }
        }
        require(assetName.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "Invalid vendor artifact filename" }
        require(GitHubReleaseInstallRecipe.assetMatchesFormat(assetName, artifactFormat)) { "Vendor artifact format mismatch" }
        NetworkSecurity.validatePublicHttpsTarget(assetUrl)
        require(assetSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid vendor artifact SHA-256" }
        require(assetMaxBytes in 1L..2L * 1024L * 1024L * 1024L) { "Invalid vendor artifact size bound" }
        require(provenanceUrl.startsWith("https://") && provenanceUrl.length <= 500) { "Invalid vendor provenance" }
        require(healthArgs.size <= 8 && healthArgs.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }) {
            "Invalid vendor health arguments"
        }
        require(runtimeCompatibility.integration == DeclarativeIntegrationKind.CLI) { "Vendor artifact backend currently supports CLI integration" }
        PackageRuntimeCompatibilityPolicy.requireGenericInstallerCompatible(runtimeCompatibility)
    }

    private fun safeArchivePath(value: String): Boolean {
        if (value.length !in 1..500 || value.startsWith('/') || '\\' in value || '\u0000' in value || '\n' in value || '\r' in value) return false
        val segments = value.removePrefix("./").split('/')
        return segments.size in 1..16 && segments.all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." && segment.length <= 180 &&
                segment.matches(Regex("[A-Za-z0-9._+@=~%\\[\\]-]{1,180}"))
        }
    }
}

@Serializable
private data class ReviewedVendorArtifactLock(
    val schema: Int = 2,
    val familyId: String,
    val version: String,
    val installer: String = "vendor-official-sha256-v2",
    val command: String,
    val versionAuthority: String,
    val releaseTag: String,
    val platform: String = "linux-arm64",
    val artifactFormat: String,
    val executableBasename: String,
    val interpreterFamilyId: String? = null,
    val interpreterCommand: String? = null,
    val archiveMemberPath: String? = null,
    val assetName: String,
    val assetUrl: String,
    val assetSha256: String,
    val assetSize: Long,
    val projectedMember: String,
    val projectedSha256: String,
    val projectedSize: Long,
    val runtimeDependencies: List<String>,
    val provenanceUrl: String,
    val guestProfile: String,
    val libcCompatibility: String,
    val compatibilityEvidenceUrl: String,
    val installedAtEpochMs: Long,
)

class ReviewedVendorArtifactInstaller(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val packageInstaller: ManagedPackageInstaller,
    private val downloader: TrustedArtifactDownloader = TrustedArtifactDownloader(context.applicationContext),
    private val environment: ReviewedGuestExecutionEnvironment = AlpineReviewedGuestEnvironment(context.applicationContext),
) {
    private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }

    suspend fun install(
        recipe: VendorArtifactInstallRecipe,
        interpreter: GitHubArtifactInterpreterDependency? = null,
        runtimeDependencies: List<ManagedPackageRecord> = emptyList(),
    ): ManagedPackageRecord = withContext(Dispatchers.IO) {
        PackageBackendContract.requireSame("ReviewedVendorArtifactInstaller", PackageBackendId.DEVICE_ADB, packageInstaller.backendId, environment.backendId)
        recipe.validate()
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) { "Reviewed vendor artifacts currently require ARM64" }
        if (recipe.artifactFormat == DeclarativeGitHubArtifactFormat.PHAR) {
            requireNotNull(interpreter) { "Reviewed vendor PHAR installation requires its managed interpreter dependency" }.let { dependency ->
                require(dependency.record.familyId == recipe.interpreterFamilyId) { "Vendor PHAR interpreter family mismatch" }
                require(dependency.record.commands.containsKey(requireNotNull(recipe.interpreterCommand))) { "Vendor PHAR interpreter command mismatch" }
                require(packageInstaller.verify(dependency.record)) { "Managed vendor PHAR interpreter dependency failed integrity/health verification" }
            }
        } else {
            require(interpreter == null) { "Native vendor artifacts must not carry an interpreter dependency" }
        }
        runtimeDependencies.forEach { dependency ->
            require(packageInstaller.verify(dependency)) { "Managed runtime dependency ${dependency.familyId}@${dependency.version} failed verification" }
        }
        environment.ensureInstalled()
        PackageRuntimeCompatibilityPolicy.verifyGuestCommands(environment, recipe.runtimeCompatibility)

        val artifactIdFamily = recipe.familyId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val artifactIdVersion = recipe.version.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32)
        val artifactSpec = TrustedArtifactSpec(
            id = "vendor-$artifactIdFamily-$artifactIdVersion",
            url = recipe.assetUrl,
            sha256 = recipe.assetSha256,
            fileName = recipe.assetName,
            maxBytes = recipe.assetMaxBytes,
        )
        val artifact = downloader.download(artifactSpec)
        val assetSize = artifact.length()
        require(assetSize in 1L..recipe.assetMaxBytes) { "Vendor artifact size is outside its reviewed bound" }
        if (recipe.artifactLayout == DeclarativeGitHubArtifactLayout.WHOLE_TREE) {
            require(interpreter == null) { "Vendor native whole-tree artifacts must not carry an interpreter dependency" }
            val record = ReviewedVendorWholeTreeInstaller(bridge, packageInstaller, environment).install(recipe, artifact, runtimeDependencies)
            if (artifact.length() >= LARGE_WHOLE_TREE_SOURCE_CACHE_BYTES) downloader.discard(artifactSpec)
            return@withContext record
        }

        val root = DeviceBridgeManager.remoteRoot()
        val safeFamily = recipe.familyId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = recipe.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val stage = "$root/packages/.staging/$safeFamily-$safeVersion-arm64-v8a"
        val final = "$root/packages/${recipe.familyId}/$safeVersion/arm64-v8a"
        val previous = "$root/packages/.previous/$safeFamily-$safeVersion-arm64-v8a"
        val binRoot = "$stage/payload/bin"
        val libRoot = "$stage/payload/lib"
        val hostBinRoot = "$stage/payload/host-bin"
        val binary = "$binRoot/${recipe.command}"
        val interpretedPayload = "$libRoot/${recipe.executableBasename}"
        val projectedPayload = if (recipe.artifactFormat == DeclarativeGitHubArtifactFormat.PHAR) interpretedPayload else binary
        val wrapper = "$hostBinRoot/${recipe.command}"
        listOf(stage, final, previous, binRoot, libRoot, hostBinRoot, binary, interpretedPayload, projectedPayload, wrapper).forEach(DeviceBridgeManager::requireSafeRemotePath)

        var activated = false
        var localProjection: SafeTreeProjection? = null
        try {
            val localPayload: File
            val projectedMember: String
            val projectedSha256: String
            val projectedSize: Long
            when (recipe.artifactFormat) {
                DeclarativeGitHubArtifactFormat.RAW, DeclarativeGitHubArtifactFormat.PHAR -> {
                    localPayload = artifact
                    projectedMember = recipe.assetName
                    projectedSha256 = recipe.assetSha256
                    projectedSize = assetSize
                }
                DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ -> {
                    val member = requireNotNull(recipe.archiveMemberPath)
                    val projection = SafeWholeTreeArchive.project(
                        archive = artifact,
                        format = recipe.artifactFormat,
                        executablePaths = setOf(member),
                        maxFiles = requireNotNull(recipe.archiveMaxFiles),
                        maxUnpackedBytes = requireNotNull(recipe.archiveMaxUnpackedBytes),
                    )
                    localProjection = projection
                    val selected = projection.files.singleOrNull { it.relativePath == member }
                        ?: error("Vendor archive is missing reviewed member '$member'")
                    localPayload = selected.file
                    projectedMember = selected.relativePath
                    projectedSha256 = selected.sha256
                    projectedSize = selected.size
                }
                else -> error("Unsupported reviewed vendor artifact format")
            }

            val headroom = Math.addExact(Math.multiplyExact(projectedSize, 2L), TRANSACTION_HEADROOM_BYTES)
            DeviceWorkstationStorageGuard.requireHeadroom(bridge, headroom, "install official vendor artifact ${recipe.familyId} ${recipe.version}")
            val prep = bridge.shell(
                "rm -rf ${q(stage)} ${q(previous)} && mkdir -p ${q(binRoot)} ${q(libRoot)} ${q(hostBinRoot)} ${q("$root/packages/.previous")}",
            )
            check(prep.exitCode == 0) { "Could not prepare vendor artifact transaction: ${prep.combined}" }

            val payloadMode = if (recipe.artifactFormat == DeclarativeGitHubArtifactFormat.PHAR) 420 else 493
            localPayload.inputStream().use { bridge.pushStream(it, projectedPayload, mode = payloadMode) }
            verifyRemoteBytes(projectedPayload, projectedSha256, projectedSize, "Projected vendor payload changed during transfer")
            if (recipe.artifactFormat == DeclarativeGitHubArtifactFormat.PHAR) {
                val exactInterpreter = requireNotNull(interpreter)
                val health = environment.execute(
                    listOf(exactInterpreter.guestExecutable, interpretedPayload) + recipe.healthArgs,
                    maxOutputBytes = 128_000,
                )
                check(health.exitCode == 0) { "Vendor PHAR health check failed: ${health.output.takeLast(8_000)}" }
            } else {
                verifyArm64Elf(binary)
                val health = environment.execute(listOf(binary) + recipe.healthArgs, maxOutputBytes = 128_000)
                check(health.exitCode == 0) { "Vendor command health check failed: ${health.output.takeLast(8_000)}" }
            }

            val finalPayload = if (recipe.artifactFormat == DeclarativeGitHubArtifactFormat.PHAR) {
                "$final/payload/lib/${recipe.executableBasename}"
            } else {
                "$final/payload/bin/${recipe.command}"
            }
            val wrapperScript = buildString {
                append("#!/system/bin/sh\nset -eu\nexec ")
                append(q(environment.launcherPath())).append(' ')
                if (recipe.artifactFormat == DeclarativeGitHubArtifactFormat.PHAR) {
                    append(q(requireNotNull(interpreter).guestExecutable)).append(' ')
                }
                append(q(finalPayload)).append(" \"${'$'}@\"\n")
            }
            ByteArrayInputStream(wrapperScript.toByteArray(Charsets.UTF_8)).use { bridge.pushStream(it, wrapper, mode = 493) }

            val interpreterDependencyKey = interpreter?.record?.let { "${it.familyId}@${it.version}" }
            val runtimeDependencyKeys = runtimeDependencies.map { "${it.familyId}@${it.version}" }.distinct().sorted()
            val allDependencyKeys = (runtimeDependencyKeys + listOfNotNull(interpreterDependencyKey)).distinct().sorted()
            val lock = ReviewedVendorArtifactLock(
                familyId = recipe.familyId,
                version = recipe.version,
                command = recipe.command,
                versionAuthority = recipe.versionAuthority,
                releaseTag = recipe.releaseTag,
                artifactFormat = recipe.artifactFormat.name,
                executableBasename = recipe.executableBasename,
                interpreterFamilyId = recipe.interpreterFamilyId,
                interpreterCommand = recipe.interpreterCommand,
                archiveMemberPath = recipe.archiveMemberPath,
                assetName = recipe.assetName,
                assetUrl = recipe.assetUrl,
                assetSha256 = recipe.assetSha256,
                assetSize = assetSize,
                projectedMember = projectedMember,
                projectedSha256 = projectedSha256,
                projectedSize = projectedSize,
                runtimeDependencies = allDependencyKeys,
                provenanceUrl = recipe.provenanceUrl,
                guestProfile = recipe.runtimeCompatibility.guestProfile.name,
                libcCompatibility = recipe.runtimeCompatibility.libc.name,
                compatibilityEvidenceUrl = recipe.runtimeCompatibility.evidenceUrl,
                installedAtEpochMs = System.currentTimeMillis(),
            )
            ByteArrayInputStream(json.encodeToString(lock).toByteArray(Charsets.UTF_8)).use {
                bridge.pushStream(it, "$stage/RECIPE_LOCK.json", mode = 420)
            }

            val bounds = bridge.shellBounded(
                "set -eu; C=${'$'}(find ${q(stage)} -type f | wc -l); B=${'$'}(du -sk ${q(stage)} | awk '{print ${'$'}1}'); " +
                    "test \"${'$'}C\" -le 24; test \"${'$'}B\" -le 1048576; printf '%s %s\\n' \"${'$'}C\" \"${'$'}B\"",
                maxOutputBytes = 16_384,
            )
            check(bounds.exitCode == 0) { "Vendor package exceeded final package safety bounds" }
            val checksums = bridge.shellBounded(
                "set -eu; cd ${q(stage)}; find . -type f ! -name SHA256SUMS -print | sort | while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done > SHA256SUMS; toybox sha256sum -c SHA256SUMS",
                maxOutputBytes = 128_000,
            )
            check(checksums.exitCode == 0) { "Vendor package integrity manifest failed: ${checksums.combined}" }

            val move = bridge.shell(
                "set -eu; rm -rf ${q(previous)}; if [ -d ${q(final)} ]; then mv ${q(final)} ${q(previous)}; fi; " +
                    "mkdir -p ${q(final.substringBeforeLast('/'))}; mv ${q(stage)} ${q(final)}",
            )
            check(move.exitCode == 0) { "Could not activate vendor package: ${move.combined}" }
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
                commands = mapOf(recipe.command to "$finalHostBinRoot/${recipe.command}"),
                dependencies = allDependencyKeys,
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
        } finally {
            localProjection?.close()
        }
    }

    private suspend fun verifyRemoteBytes(path: String, sha256: String, size: Long, message: String) {
        val result = bridge.shellBounded(
            "set -eu; test \"${'$'}(toybox sha256sum ${q(path)} | awk '{print ${'$'}1}')\" = ${q(sha256)}; " +
                "test \"${'$'}(toybox wc -c < ${q(path)} | tr -d ' ')\" = ${q(size.toString())}",
            maxOutputBytes = 16_384,
        )
        check(result.exitCode == 0) { message }
    }

    private suspend fun verifyArm64Elf(path: String) {
        val elf = bridge.shellBounded(
            "set -eu; F=${q(path)}; " +
                "test \"${'$'}(toybox od -An -tx1 -N5 \"${'$'}F\" | tr -d ' \\n')\" = 7f454c4602; " +
                "test \"${'$'}(toybox od -An -tx1 -j18 -N2 \"${'$'}F\" | tr -d ' \\n')\" = b700",
            maxOutputBytes = 16_384,
        )
        check(elf.exitCode == 0) { "Vendor payload is not an ELF64 AArch64 executable" }
    }

    private fun q(value: String) = DeviceBridgeManager.shellQuote(value)

    companion object {
        private const val TRANSACTION_HEADROOM_BYTES = 64L * 1024L * 1024L
        private const val LARGE_WHOLE_TREE_SOURCE_CACHE_BYTES = 128L * 1024L * 1024L
    }
}
