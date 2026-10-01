package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class LocalReviewedWholeTreeLock(
    val schema: Int = 1,
    val familyId: String,
    val version: String,
    val installer: String = "local-ubuntu-reviewed-tree-v1",
    val provider: String,
    val releaseTag: String,
    val command: String,
    val artifactFormat: String,
    val assetName: String,
    val assetUrl: String,
    val assetSha256: String,
    val assetSize: Long,
    val treeEntryPoint: String,
    val commands: Map<String, String>,
    val treeFiles: Int,
    val treeSymlinks: Int,
    val treeBytes: Long,
    val treeSha256: String,
    val treeLinksSha256: String,
    val runtimeDependencies: List<String>,
    val provenanceUrl: String,
    val guestProfile: String,
    val libcCompatibility: String,
    val integration: String,
    val compatibilityEvidenceUrl: String,
    val installedAtEpochMs: Long,
)

// Commit durable state only after verification succeeds.
class LocalReviewedWholeTreeArtifactInstaller(
    context: Context,
    private val localAuthority: LocalManagedPackageAuthority,
    private val ubuntuEnvironment: FoundryUbuntuGuestEnvironmentManager,
) {
    private val appContext = context.applicationContext
    private val downloader = UserInitiatedArtifactTransfer(appContext)
    private val json = Json { encodeDefaults = true }
    private val root = appContext.filesDir.canonicalPath

    fun supports(recipe: GitHubReleaseInstallRecipe): Boolean = LocalUbuntuReviewedWholeTreePolicy.supports(recipe)
    fun supports(recipe: VendorArtifactInstallRecipe): Boolean = LocalUbuntuReviewedWholeTreePolicy.supports(recipe)

    suspend fun install(recipe: GitHubReleaseInstallRecipe): ManagedPackageRecord {
        require(supports(recipe)) { "GitHub artifact is outside the certified local Ubuntu whole-tree cohort" }
        return install(
            Spec(
                familyId = recipe.familyId,
                version = recipe.version,
                provider = "GITHUB_RELEASE",
                releaseTag = recipe.releaseTag,
                command = recipe.command,
                artifactFormat = recipe.artifactFormat,
                assetName = recipe.assetName,
                assetUrl = recipe.assetUrl,
                assetSha256 = recipe.assetSha256,
                assetMaxBytes = recipe.assetSize,
                expectedAssetBytes = recipe.assetSize,
                treeEntryPoint = requireNotNull(recipe.treeEntryPoint),
                treeExecutablePaths = recipe.treeExecutablePaths,
                treeAuxCommands = emptyMap(),
                treeMaxFiles = requireNotNull(recipe.treeMaxFiles),
                treeMaxUnpackedBytes = requireNotNull(recipe.treeMaxUnpackedBytes),
                treeLinkPolicy = DeclarativeWholeTreeLinkPolicy.REJECT,
                treeModePolicy = DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST,
                provenanceUrl = recipe.provenanceUrl,
                healthArgs = recipe.healthArgs,
                runtimeCompatibility = recipe.runtimeCompatibility,
            ),
        )
    }

    suspend fun install(recipe: VendorArtifactInstallRecipe): ManagedPackageRecord {
        require(supports(recipe)) { "Vendor artifact is outside the certified local Ubuntu whole-tree cohort" }
        return install(
            Spec(
                familyId = recipe.familyId,
                version = recipe.version,
                provider = "VENDOR_OFFICIAL",
                releaseTag = recipe.releaseTag,
                command = recipe.command,
                artifactFormat = recipe.artifactFormat,
                assetName = recipe.assetName,
                assetUrl = recipe.assetUrl,
                assetSha256 = recipe.assetSha256,
                assetMaxBytes = recipe.assetMaxBytes,
                expectedAssetBytes = null,
                treeEntryPoint = requireNotNull(recipe.treeEntryPoint),
                treeExecutablePaths = recipe.treeExecutablePaths,
                treeAuxCommands = recipe.treeAuxCommands,
                treeMaxFiles = requireNotNull(recipe.treeMaxFiles),
                treeMaxUnpackedBytes = requireNotNull(recipe.treeMaxUnpackedBytes),
                treeLinkPolicy = recipe.treeLinkPolicy,
                treeModePolicy = recipe.treeModePolicy,
                provenanceUrl = recipe.provenanceUrl,
                healthArgs = recipe.healthArgs,
                runtimeCompatibility = recipe.runtimeCompatibility,
            ),
        )
    }

    private suspend fun install(spec: Spec): ManagedPackageRecord = withContext(Dispatchers.IO) {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) {
            "Local Ubuntu reviewed whole-tree artifacts currently require ARM64"
        }
        PackageBackendContract.requireSame(
            "Local reviewed Ubuntu whole-tree artifact",
            PackageBackendId.LOCAL_APP,
            localAuthority.backendId,
            ubuntuEnvironment.backendId,
        )

        ubuntuEnvironment.ensureInstalled()
        val layer = ubuntuEnvironment.ensurePackages(spec.runtimeCompatibility.requiredGuestPackages)
        val dependencyRecords = listOfNotNull(layer)
        dependencyRecords.forEach { dependency ->
            localAuthority.adopt(dependency)
            check(localAuthority.verify(dependency)) {
                "Ubuntu runtime dependency ${dependency.familyId}@${dependency.version} failed verification"
            }
        }
        PackageRuntimeCompatibilityPolicy.verifyGuestCommands(ubuntuEnvironment, spec.runtimeCompatibility)

        val artifactSpec = TrustedArtifactSpec(
            id = "local-ubuntu-tree-${safe(spec.familyId).take(40)}-${safe(spec.version).take(24)}",
            url = spec.assetUrl,
            sha256 = spec.assetSha256,
            fileName = spec.assetName,
            maxBytes = spec.assetMaxBytes,
            expectedBytes = spec.expectedAssetBytes,
        )
        val artifact = downloader.download(artifactSpec)
        val actualAssetSize = artifact.length()
        require(actualAssetSize in 1L..spec.assetMaxBytes) { "Whole-tree artifact size is outside its reviewed bound" }
        spec.expectedAssetBytes?.let { require(actualAssetSize == it) { "Whole-tree artifact size changed" } }

        val transactionNeed = Math.addExact(
            Math.addExact(spec.treeMaxUnpackedBytes, actualAssetSize),
            256L * 1024L * 1024L,
        )
        DeviceWorkstationStorageGuard.requireHeadroom(
            additionalBytes = transactionNeed,
            purpose = "install local Ubuntu whole-tree ${spec.familyId} ${spec.version}",
        )

        SafeWholeTreeArchive.project(
            archive = artifact,
            format = spec.artifactFormat,
            executablePaths = spec.treeExecutablePaths.toSet(),
            maxFiles = spec.treeMaxFiles,
            maxUnpackedBytes = spec.treeMaxUnpackedBytes,
            linkPolicy = spec.treeLinkPolicy,
            modePolicy = spec.treeModePolicy,
        ).use { projection ->
            val commands = linkedMapOf(spec.command to spec.treeEntryPoint).apply { putAll(spec.treeAuxCommands) }
            commands.forEach { (name, relative) ->
                require(name.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid whole-tree command" }
                val payload = resolvedProjectedFile(projection, relative)
                val elf = LinuxArm64ElfAdmission.requireUbuntuGlibc(payload.file)
                require(elf.interpreter == LinuxArm64ElfAdmission.AARCH64_GLIBC_INTERPRETER) {
                    "Whole-tree command '$name' does not use the certified Ubuntu/glibc interpreter"
                }
            }

            val safeFamily = safe(spec.familyId)
            val safeVersion = safe(spec.version)
            val transactionPaths = LocalPackageInstallJournal.paths(
                root, spec.familyId, spec.version, LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
            )
            val stage = transactionPaths.stage.absolutePath
            val final = transactionPaths.final.absolutePath
            val previous = transactionPaths.previous.absolutePath
            val stageTree = "$stage/payload/tree"
            val stageBin = "$stage/payload/bin"
            val stageHostBin = "$stage/payload/host-bin"
            val finalTree = "$final/payload/tree"
            val finalBin = "$final/payload/bin"
            val finalHostBin = "$final/payload/host-bin"
            listOf(stage, final, previous, stageTree, stageBin, stageHostBin, finalTree, finalBin, finalHostBin)
                .forEach(LocalExecutionSubstrate::requireSafeLocalPath)

            val stageDir = File(stage)
            val finalDir = File(final)
            val previousDir = File(previous)
            LocalPackageInstallJournal.transactionMutex.withLock {
                val existingReceipt = localAuthority.ownedReceipt(spec.familyId, spec.version)
                LocalPackageInstallJournal.recover(stageDir, finalDir, previousDir,
                    existingReceipt?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
                check(File(stageTree).mkdirs() && File(stageBin).mkdirs() && File(stageHostBin).mkdirs()) {
                    "Could not prepare local whole-tree transaction"
                }

                var activation: LocalPackageInstallJournal.Activation? = null
                try {
                    val activationId = LocalPackageInstallJournal.prepare(stageDir)
                    // The projection and staged tree live on the same app-owned filesystem. Release each
                    // projected regular file immediately after its verified copy is durable so large archives
                    // (for example the Swift toolchain) do not require space for two full unpacked trees.
                    // One source member is still duplicated while it is being copied, so reserve the largest
                    // reviewed member before staging begins.
                    val largestProjectedFile = projection.files.maxOfOrNull { it.size } ?: 0L
                    DeviceWorkstationStorageGuard.requireHeadroom(
                        additionalBytes = Math.addExact(largestProjectedFile, 64L * 1024L * 1024L),
                        purpose = "stage the largest member of local Ubuntu whole-tree ${spec.familyId} ${spec.version}",
                    )
                    projection.files.forEach { file ->
                        val destination = File(stageTree, file.relativePath)
                        LocalExecutionSubstrate.requireSafeLocalPath(destination.absolutePath)
                        file.file.inputStream().use { LocalExecutionSubstrate.pushStream(it, destination.absolutePath, file.mode) }
                        projection.releaseLocalPayload(file)
                    }
                    projection.symlinks.forEach { link ->
                        val destination = File(stageTree, link.relativePath)
                        LocalExecutionSubstrate.requireSafeLocalPath(destination.absolutePath)
                        check(destination.parentFile?.mkdirs() == true || destination.parentFile?.isDirectory == true) {
                            "Could not prepare local whole-tree symlink directory"
                        }
                        check(!destination.exists() && !PathSecurity.isSymbolicLink(destination)) { "Whole-tree symlink path already exists" }
                        Os.symlink(link.target, destination.absolutePath)
                    }

                    val treeManifest = projection.files.sortedBy { it.relativePath }.joinToString(separator = "\n", postfix = "\n") {
                        "${it.sha256}  ${it.relativePath}"
                    }
                    pushText("$stage/TREE_SHA256SUMS", treeManifest, 420)
                    val linksManifest = projection.symlinks.sortedBy { it.relativePath }.joinToString(separator = "\n", postfix = if (projection.symlinks.isEmpty()) "" else "\n") {
                        "${it.relativePath}\t${it.target}\t${it.resolvedPath}"
                    }
                    pushText("$stage/TREE_LINKS.tsv", linksManifest, 420)
                    val linksSha = sha256Bytes(linksManifest.toByteArray(Charsets.UTF_8))

                    val transfer = LocalExecutionSubstrate.shellBounded(
                        "set -eu; cd ${q(stageTree)}; toybox sha256sum -c ${q("$stage/TREE_SHA256SUMS")} >/dev/null; " +
                            "test \"${'$'}(find . -type f | wc -l | tr -d ' ')\" = ${q(projection.files.size.toString())}; " +
                            "test \"${'$'}(find . -type l | wc -l | tr -d ' ')\" = ${q(projection.symlinks.size.toString())}",
                        maxOutputBytes = 64_000,
                    )
                    check(transfer.exitCode == 0) { "Local whole-tree transfer integrity failed: ${transfer.output.takeLast(8_000)}" }
                    verifyStagedLinks(stageTree, projection.symlinks)

                    val health = ubuntuEnvironment.execute(listOf("$stageTree/${spec.treeEntryPoint}") + spec.healthArgs, maxOutputBytes = 128_000)
                    check(health.exitCode == 0) { "Local whole-tree health check failed: ${health.output.takeLast(8_000)}" }
                    val stagedEnvironment = LocalUbuntuJdkPolicy.environment(spec.familyId, stageTree, commands)
                    stagedEnvironment["JAVA_HOME"]?.let { home ->
                        val compilerHealth = ubuntuEnvironment.execute(
                            listOf("/bin/sh", "-c", LocalUbuntuJdkPolicy.healthScript(home, compileSample = true)),
                            maxOutputBytes = 128_000,
                        )
                        check(compilerHealth.exitCode == 0) { "Managed JDK compile/run admission failed: ${compilerHealth.output.takeLast(8_000)}" }
                    }

                    commands.forEach { (command, relative) ->
                        val guestWrapper = "#!/bin/sh\nset -eu\nexec ${q("$finalTree/$relative")} \"${'$'}@\"\n"
                        pushText("$stageBin/$command", guestWrapper, 493)
                        val hostWrapper = "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${q(ubuntuEnvironment.launcherPath())} ${q("$finalTree/$relative")} \"${'$'}@\"\n"
                        pushText("$stageHostBin/$command", hostWrapper, 493)
                    }

                    val commandsManifest = commands.toSortedMap().entries.joinToString(separator = "\n", postfix = "\n") { (name, relative) ->
                        "$name\t$relative"
                    }
                    pushText("$stage/TREE_COMMANDS.tsv", commandsManifest, 420)
                    val commandsSha = sha256Bytes(commandsManifest.toByteArray(Charsets.UTF_8))

                    val dependencyKeys = dependencyRecords.map { "${it.familyId}@${it.version}" }.distinct().sorted()
                    val lock = LocalReviewedWholeTreeLock(
                        familyId = spec.familyId,
                        version = spec.version,
                        provider = spec.provider,
                        releaseTag = spec.releaseTag,
                        command = spec.command,
                        artifactFormat = spec.artifactFormat.name,
                        assetName = spec.assetName,
                        assetUrl = spec.assetUrl,
                        assetSha256 = spec.assetSha256,
                        assetSize = actualAssetSize,
                        treeEntryPoint = spec.treeEntryPoint,
                        commands = commands.toSortedMap(),
                        treeFiles = projection.files.size,
                        treeSymlinks = projection.symlinks.size,
                        treeBytes = projection.totalBytes,
                        treeSha256 = projection.treeSha256,
                        treeLinksSha256 = linksSha,
                        runtimeDependencies = dependencyKeys,
                        provenanceUrl = spec.provenanceUrl,
                        guestProfile = spec.runtimeCompatibility.guestProfile.name,
                        libcCompatibility = spec.runtimeCompatibility.libc.name,
                        integration = spec.runtimeCompatibility.integration.name,
                        compatibilityEvidenceUrl = spec.runtimeCompatibility.evidenceUrl,
                        installedAtEpochMs = System.currentTimeMillis(),
                    )
                    pushText("$stage/RECIPE_LOCK.json", json.encodeToString(lock), 420)

                    val maxStageFiles = Math.addExact(spec.treeMaxFiles, commands.size * 2 + 8)
                    val maxStageKiB = Math.addExact((spec.treeMaxUnpackedBytes + 1023L) / 1024L, 8192L)
                    val bounds = LocalExecutionSubstrate.shellBounded(
                        "set -eu; C=${'$'}(find ${q(stage)} -type f | wc -l | tr -d ' '); " +
                            "B=${'$'}(du -sk ${q(stage)} | awk '{print ${'$'}1}'); " +
                            "test \"${'$'}C\" -le ${q(maxStageFiles.toString())}; test \"${'$'}B\" -le ${q(maxStageKiB.toString())}",
                        maxOutputBytes = 16_384,
                    )
                    check(bounds.exitCode == 0) { "Local whole-tree package exceeded reviewed final bounds" }

                    val seal = LocalExecutionSubstrate.shellBounded(
                        "set -eu; cd ${q(stage)}; find . -type f ! -name SHA256SUMS -print | sort | " +
                            "while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done > SHA256SUMS; toybox sha256sum -c SHA256SUMS >/dev/null",
                        maxOutputBytes = 128_000,
                    )
                    check(seal.exitCode == 0) { "Local whole-tree integrity manifest failed: ${seal.output.takeLast(8_000)}" }
                    val sealFile = File(stage, "SHA256SUMS")
                    check(sealFile.isFile && !PathSecurity.isSymbolicLink(sealFile)) { "Local whole-tree seal is missing" }
                    val sealSha = sha256File(sealFile)

                    activation = LocalPackageInstallJournal.activate(stageDir, finalDir, previousDir)

                    val record = ManagedPackageRecord(
                        familyId = spec.familyId,
                        version = spec.version,
                        scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                        installRoot = final,
                        installedAtEpochMs = System.currentTimeMillis(),
                        active = true,
                        pathEntries = listOf(finalHostBin),
                        commands = commands.keys.associateWith { "$finalHostBin/$it" },
                        environment = LocalUbuntuJdkPolicy.environment(spec.familyId, finalTree, commands),
                        dependencies = dependencyKeys,
                        healthChecks = listOf(ManagedPackageHealthCheck("host-bin/${spec.command}", spec.healthArgs)),
                        artifactSha256 = spec.assetSha256.lowercase(),
                        abi = "arm64-v8a",
                        metadata = mapOf(
                            LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activationId,
                            LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
                            LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                            LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name,
                            LocalManagedPackageMetadata.COMMAND_KEY to spec.command,
                            LocalManagedPackageMetadata.TREE_SHA256_KEY to projection.treeSha256,
                            LocalManagedPackageMetadata.TREE_LINKS_SHA256_KEY to linksSha,
                            LocalManagedPackageMetadata.TREE_COMMANDS_SHA256_KEY to commandsSha,
                            LocalManagedPackageMetadata.SEAL_SHA256_KEY to sealSha,
                        ),
                    )
                    localAuthority.adopt(record)
                    activation = null // The durable receipt is now the commit marker.
                    withContext(NonCancellable) { LocalPackageInstallJournal.commit(previousDir) }
                    if (spec.familyId == LocalUbuntuJdkPolicy.FAMILY || actualAssetSize >= LARGE_ARCHIVE_CACHE_BYTES) {
                        downloader.discard(artifactSpec)
                    }
                    record
                } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        activation?.let { committed ->
                            runCatching { LocalPackageInstallJournal.rollback(finalDir, previousDir, committed) }
                                .exceptionOrNull()?.let(failure::addSuppressed)
                        }
                        if (!PathSecurity.deleteTreeNoFollow(stageDir)) {
                            failure.addSuppressed(IllegalStateException("Could not clean failed whole-tree staging tree"))
                        }
                    }
                    throw failure
                }
            }
        }
    }

    private fun resolvedProjectedFile(projection: SafeTreeProjection, path: String): SafeTreeFile {
        val links = projection.symlinks.associateBy { it.relativePath }
        var current = path
        val seen = mutableSetOf<String>()
        while (true) {
            require(seen.add(current)) { "Whole-tree command link graph contains a cycle" }
            val link = links[current] ?: break
            current = link.resolvedPath
        }
        return projection.files.singleOrNull { it.relativePath == current }
            ?: error("Whole-tree command target '$path' does not resolve to a regular file")
    }

    private fun verifyStagedLinks(treeRoot: String, links: List<SafeTreeSymlink>) {
        val canonicalRoot = File(treeRoot).canonicalFile
        links.forEach { link ->
            val file = File(treeRoot, link.relativePath)
            LocalExecutionSubstrate.requireSafeLocalPath(file.absolutePath)
            check(PathSecurity.isSymbolicLink(file)) { "Whole-tree symlink was not preserved: ${link.relativePath}" }
            check(Os.readlink(file.absolutePath) == link.target) { "Whole-tree symlink target changed: ${link.relativePath}" }
            check(file.exists()) { "Whole-tree symlink target is missing after staging: ${link.relativePath}" }
            val canonicalTarget = file.canonicalFile
            check(canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
                "Whole-tree symlink escaped the staged tree: ${link.relativePath}"
            }
        }
    }

    private suspend fun pushText(path: String, text: String, mode: Int) {
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use { LocalExecutionSubstrate.pushStream(it, path, mode) }
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._+-]"), "_")
    private fun q(value: String): String = LocalExecutionSubstrate.shellQuote(value)
    private fun sha256Bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Spec(
        val familyId: String,
        val version: String,
        val provider: String,
        val releaseTag: String,
        val command: String,
        val artifactFormat: DeclarativeGitHubArtifactFormat,
        val assetName: String,
        val assetUrl: String,
        val assetSha256: String,
        val assetMaxBytes: Long,
        val expectedAssetBytes: Long?,
        val treeEntryPoint: String,
        val treeExecutablePaths: List<String>,
        val treeAuxCommands: Map<String, String>,
        val treeMaxFiles: Int,
        val treeMaxUnpackedBytes: Long,
        val treeLinkPolicy: DeclarativeWholeTreeLinkPolicy,
        val treeModePolicy: DeclarativeWholeTreeModePolicy,
        val provenanceUrl: String,
        val healthArgs: List<String>,
        val runtimeCompatibility: DeclarativeRuntimeCompatibility,
    )

    companion object {
        private const val LARGE_ARCHIVE_CACHE_BYTES = 128L * 1024L * 1024L
    }
}
