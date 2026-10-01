package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json








data class GitHubReleaseInstallRecipe(
    val familyId: String,
    val version: String,
    val repository: String,
    val releaseTag: String,
    val releaseImmutable: Boolean,
    val command: String,
    val artifactFormat: DeclarativeGitHubArtifactFormat,
    val artifactLayout: DeclarativeGitHubArtifactLayout = DeclarativeGitHubArtifactLayout.SINGLE_COMMAND,
    val executableBasename: String,
    val treeEntryPoint: String? = null,
    val treeExecutablePaths: List<String> = emptyList(),
    val treeMaxFiles: Int? = null,
    val treeMaxUnpackedBytes: Long? = null,
    val interpreterFamilyId: String? = null,
    val interpreterCommand: String? = null,
    val interpreterArgs: List<String> = emptyList(),
    val assetName: String,
    val assetUrl: String,
    val assetSha256: String,
    val assetSize: Long,
    val provenanceUrl: String,
    val healthArgs: List<String> = listOf("--version"),
    val runtimeCompatibility: DeclarativeRuntimeCompatibility,
) {
    fun validate() {
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid GitHub recipe family" }
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}"))) { "Invalid GitHub release version" }
        require(repository.matches(Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))) { "Invalid GitHub repository" }
        require(releaseTag.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,95}"))) { "Invalid GitHub release tag" }
        require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid GitHub release command" }
        require(executableBasename.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Invalid GitHub projected executable basename" }
        require(assetName.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "Invalid GitHub release asset" }
        require(assetMatchesFormat(assetName, artifactFormat)) { "GitHub release asset does not match its reviewed format" }
        require(assetUrl.startsWith("https://github.com/")) { "GitHub asset URL must use github.com HTTPS" }
        require(assetSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid GitHub release SHA-256" }
        require(assetSize in 1L..MAX_ASSET_BYTES) { "GitHub release asset size is outside the reviewed bound" }
        require(provenanceUrl.startsWith("https://github.com/$repository/")) { "GitHub provenance escaped reviewed repository" }
        require(healthArgs.size <= 8 && healthArgs.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }) {
            "Invalid GitHub release health arguments"
        }
        PackageRuntimeCompatibilityPolicy.requireGenericInstallerCompatible(runtimeCompatibility)
        when (artifactLayout) {
            DeclarativeGitHubArtifactLayout.SINGLE_COMMAND -> {
                require(treeEntryPoint == null && treeExecutablePaths.isEmpty() && treeMaxFiles == null && treeMaxUnpackedBytes == null) {
                    "Single-command GitHub artifact must not declare whole-tree metadata"
                }
                val interpreted = artifactFormat == DeclarativeGitHubArtifactFormat.PHAR || interpreterFamilyId != null || interpreterCommand != null || interpreterArgs.isNotEmpty()
                if (interpreted) {
                    require(artifactFormat in setOf(DeclarativeGitHubArtifactFormat.PHAR, DeclarativeGitHubArtifactFormat.RAW)) { "Only PHAR/RAW artifacts may declare an interpreter" }
                    require(interpreterFamilyId?.matches(Regex("[A-Za-z0-9._-]{1,120}")) == true) { "Interpreted artifact requires an interpreter family" }
                    require(interpreterCommand?.matches(Regex("[A-Za-z0-9._+-]{1,80}")) == true) { "Interpreted artifact requires an interpreter command" }
                    require(interpreterArgs.size <= 12 && interpreterArgs.all { safeInterpreterArg(it, allowTreePlaceholders = false) }) { "Invalid interpreted-artifact arguments" }
                    require(runtimeCompatibility.libc == DeclarativeLibcCompatibility.INTERPRETED) { "Interpreted artifact requires INTERPRETED runtime compatibility" }
                } else {
                    require(interpreterFamilyId == null && interpreterCommand == null && interpreterArgs.isEmpty()) { "Native artifact must not declare an interpreter" }
                    require(runtimeCompatibility.libc in setOf(DeclarativeLibcCompatibility.STATIC_OR_MUSL, DeclarativeLibcCompatibility.GLIBC)) { "Native artifact requires a reviewed native libc contract" }
                }
            }
            DeclarativeGitHubArtifactLayout.WHOLE_TREE -> {
                require(artifactFormat in setOf(DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ, DeclarativeGitHubArtifactFormat.ZIP, DeclarativeGitHubArtifactFormat.VSIX)) {
                    "Whole-tree GitHub artifact requires an archive format"
                }
                val interpreted = interpreterFamilyId != null || interpreterCommand != null || interpreterArgs.isNotEmpty()
                if (interpreted) {
                    require(interpreterFamilyId?.matches(Regex("[A-Za-z0-9._-]{1,120}")) == true) { "Interpreted whole-tree artifact requires an interpreter family" }
                    require(interpreterCommand?.matches(Regex("[A-Za-z0-9._+-]{1,80}")) == true) { "Interpreted whole-tree artifact requires an interpreter command" }
                    require(interpreterArgs.size <= 12 && interpreterArgs.all { safeInterpreterArg(it, allowTreePlaceholders = true) }) { "Invalid interpreted whole-tree arguments" }
                    require(interpreterArgs.any { ENTRY_PLACEHOLDER in it }) { "Interpreted whole-tree arguments must reference {entry}" }
                    require(runtimeCompatibility.libc == DeclarativeLibcCompatibility.INTERPRETED) { "Interpreted whole-tree artifact requires INTERPRETED runtime compatibility" }
                } else {
                    require(interpreterFamilyId == null && interpreterCommand == null && interpreterArgs.isEmpty()) { "Native whole-tree artifact must not declare an interpreter" }
                    require(runtimeCompatibility.libc in setOf(DeclarativeLibcCompatibility.STATIC_OR_MUSL, DeclarativeLibcCompatibility.GLIBC)) { "Native whole-tree artifact requires a reviewed native libc contract" }
                }
                require(treeEntryPoint?.let(::safeTreePath) == true) { "Whole-tree GitHub artifact requires a safe entry point" }
                require(treeExecutablePaths.size in 1..64 && treeExecutablePaths.distinct().size == treeExecutablePaths.size && treeExecutablePaths.all(::safeTreePath)) {
                    "Whole-tree GitHub artifact requires bounded safe executable paths"
                }
                if (!interpreted) require(treeEntryPoint in treeExecutablePaths) { "Native whole-tree entry point must be declared executable" }
                require(treeMaxFiles != null && treeMaxFiles in 1..4_096) { "Whole-tree file-count bound is invalid" }
                require(treeMaxUnpackedBytes != null && treeMaxUnpackedBytes in 1L..1_073_741_824L) { "Whole-tree unpacked-byte bound is invalid" }
            }
        }
    }

    companion object {
        private const val MAX_ASSET_BYTES = 512L * 1024L * 1024L
        internal const val TREE_PLACEHOLDER = "{tree}"
        internal const val ENTRY_PLACEHOLDER = "{entry}"
        internal const val VERSION_PLACEHOLDER = "{version}"

        private fun safeInterpreterArg(value: String, allowTreePlaceholders: Boolean): Boolean {
            if (value.length > 120 || '\u0000' in value || '\n' in value || '\r' in value) return false
            val stripped = if (allowTreePlaceholders) {
                value.replace(TREE_PLACEHOLDER, "").replace(ENTRY_PLACEHOLDER, "").replace(VERSION_PLACEHOLDER, "")
            } else {
                value
            }
            return '{' !in stripped && '}' !in stripped
        }

        private fun safeTreePath(value: String): Boolean {
            if (value.length !in 1..500 || value.startsWith('/') || '\\' in value || '\u0000' in value || '\n' in value || '\r' in value) return false
            val stripped = value.replace(VERSION_PLACEHOLDER, "1.2.3")
            if ('{' in stripped || '}' in stripped) return false
            val segments = stripped.removePrefix("./").split('/')
            return segments.size in 1..16 && segments.all { it.isNotBlank() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9._+@=~%\\[\\]-]{1,180}")) }
        }

        fun assetMatchesFormat(name: String, format: DeclarativeGitHubArtifactFormat): Boolean {
            val lower = name.lowercase()
            return when (format) {
                DeclarativeGitHubArtifactFormat.TAR_GZ -> lower.endsWith(".tar.gz")
                DeclarativeGitHubArtifactFormat.TGZ -> lower.endsWith(".tgz")
                DeclarativeGitHubArtifactFormat.ZIP -> lower.endsWith(".zip")
                DeclarativeGitHubArtifactFormat.VSIX -> lower.endsWith(".vsix")
                DeclarativeGitHubArtifactFormat.PHAR -> lower.endsWith(".phar")
                DeclarativeGitHubArtifactFormat.RAW -> !listOf(".tar.gz", ".tgz", ".zip", ".vsix", ".phar").any(lower::endsWith)
            }
        }
    }
}

data class GitHubArtifactInterpreterDependency(
    val record: ManagedPackageRecord,
    val guestExecutable: String,
) {
    fun validate(recipe: GitHubReleaseInstallRecipe) {
        require(recipe.interpreterFamilyId != null) { "Interpreter dependency requires an interpreted GitHub artifact" }
        require(record.familyId == recipe.interpreterFamilyId) { "Interpreter dependency family mismatch" }
        require(record.commands.containsKey(requireNotNull(recipe.interpreterCommand))) { "Interpreter dependency does not export the required command" }
        require(guestExecutable.startsWith('/') && guestExecutable.length <= 240 && guestExecutable.split('/').none { it == "." || it == ".." }) {
            "Unsafe guest interpreter path"
        }
    }
}

@Serializable
private data class ReviewedGitHubReleaseLock(
    val schema: Int = 4,
    val familyId: String,
    val version: String,
    val installer: String = "github-release-generic-sha256",
    val repository: String,
    val releaseTag: String,
    val releaseImmutable: Boolean,
    val command: String,
    val platform: String = "linux-arm64",
    val artifactFormat: String,
    val artifactLayout: String,
    val executableBasename: String,
    val assetName: String,
    val assetUrl: String,
    val assetSha256: String,
    val assetSize: Long,
    val projectedMember: String,
    val projectedSha256: String,
    val interpreterDependency: String? = null,
    val interpreterGuestExecutable: String? = null,
    val interpreterArgs: List<String> = emptyList(),
    val runtimeDependencies: List<String> = emptyList(),
    val provenanceUrl: String,
    val guestProfile: String,
    val libcCompatibility: String,
    val integration: String,
    val compatibilityEvidenceUrl: String,
    val installedAtEpochMs: Long,
)

private data class LocalProjection(
    val file: File,
    val member: String,
    val sha256: String,
    val size: Long,
    val temporary: Boolean,
)

class ReviewedGitHubReleaseInstaller(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val packageInstaller: ManagedPackageInstaller,
    private val downloader: TrustedArtifactDownloader = TrustedArtifactDownloader(context.applicationContext),
    private val environment: ReviewedGuestExecutionEnvironment = AlpineReviewedGuestEnvironment(context.applicationContext),
) {
    private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }

    suspend fun install(
        recipe: GitHubReleaseInstallRecipe,
        interpreter: GitHubArtifactInterpreterDependency? = null,
        runtimeDependencies: List<ManagedPackageRecord> = emptyList(),
    ): ManagedPackageRecord = withContext(Dispatchers.IO) {
        PackageBackendContract.requireSame("ReviewedGitHubReleaseInstaller", PackageBackendId.DEVICE_ADB, packageInstaller.backendId, environment.backendId)
        recipe.validate()
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) {
            "Reviewed GitHub release artifacts currently require ARM64"
        }
        val interpretedArtifact = recipe.interpreterFamilyId != null
        if (interpretedArtifact) {
            requireNotNull(interpreter) { "Reviewed interpreted installation requires its managed interpreter dependency" }.validate(recipe)
            require(packageInstaller.verify(interpreter.record)) { "Managed interpreter dependency failed integrity/health verification" }
        } else {
            require(interpreter == null) { "Native GitHub artifacts must not carry an interpreter dependency" }
        }
        runtimeDependencies.forEach { dependency ->
            require(packageInstaller.verify(dependency)) { "Managed runtime dependency ${dependency.familyId}@${dependency.version} failed verification" }
        }
        environment.ensureInstalled()
        PackageRuntimeCompatibilityPolicy.verifyGuestCommands(environment, recipe.runtimeCompatibility)

        val artifactIdFamily = recipe.familyId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val artifactIdVersion = recipe.version.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32)
        val artifactSpec = TrustedArtifactSpec(
            id = "github-$artifactIdFamily-$artifactIdVersion",
            url = recipe.assetUrl,
            sha256 = recipe.assetSha256,
            fileName = recipe.assetName,
            maxBytes = recipe.assetSize,
            expectedBytes = recipe.assetSize,
        )
        val artifact = downloader.download(artifactSpec)
        if (recipe.artifactLayout == DeclarativeGitHubArtifactLayout.WHOLE_TREE) {
            val record = ReviewedGitHubWholeTreeInstaller(bridge, packageInstaller, environment).install(recipe, artifact, interpreter, runtimeDependencies)
            if (artifact.length() >= LARGE_WHOLE_TREE_SOURCE_CACHE_BYTES) downloader.discard(artifactSpec)
            return@withContext record
        }
        var localProjection: LocalProjection? = null

        val root = DeviceBridgeManager.remoteRoot()
        val safeFamily = recipe.familyId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = recipe.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val stage = "$root/packages/.staging/$safeFamily-$safeVersion-arm64-v8a"
        val final = "$root/packages/${recipe.familyId}/$safeVersion/arm64-v8a"
        val previous = "$root/packages/.previous/$safeFamily-$safeVersion-arm64-v8a"
        val sourceRoot = "$stage/.source"
        val archive = "$sourceRoot/${recipe.assetName}"
        val extractRoot = "$stage/.extract"
        val binRoot = "$stage/payload/bin"
        val libRoot = "$stage/payload/lib"
        val hostBinRoot = "$stage/payload/host-bin"
        val binary = "$binRoot/${recipe.command}"
        val interpretedPayload = "$libRoot/${recipe.executableBasename}"
        val wrapper = "$hostBinRoot/${recipe.command}"
        listOf(stage, final, previous, sourceRoot, archive, extractRoot, binRoot, libRoot, hostBinRoot, binary, interpretedPayload, wrapper)
            .forEach(DeviceBridgeManager::requireSafeRemotePath)

        val headroom = Math.addExact(Math.multiplyExact(recipe.assetSize, 2L), EXTRACTION_HEADROOM_BYTES)
        DeviceWorkstationStorageGuard.requireHeadroom(
            bridge,
            additionalBytes = headroom,
            purpose = "install reviewed GitHub artifact ${recipe.familyId} ${recipe.version}",
        )
        val prep = bridge.shell(
            "rm -rf ${q(stage)} ${q(previous)} && " +
                "mkdir -p ${q(sourceRoot)} ${q(extractRoot)} ${q(binRoot)} ${q(libRoot)} ${q(hostBinRoot)} ${q("$root/packages/.previous")}",
        )
        check(prep.exitCode == 0) { "Could not prepare GitHub artifact transaction: ${prep.combined}" }

        var projectionActivated = false
        try {
            localProjection = when (recipe.artifactFormat) {
                DeclarativeGitHubArtifactFormat.ZIP, DeclarativeGitHubArtifactFormat.VSIX ->
                    extractSingleZipMember(artifact, recipe.executableBasename)
                DeclarativeGitHubArtifactFormat.RAW, DeclarativeGitHubArtifactFormat.PHAR ->
                    LocalProjection(artifact, recipe.assetName, recipe.assetSha256, recipe.assetSize, temporary = false)
                DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ -> null
            }
            val projectedMember: String
            val projectedSha256: String
            when (recipe.artifactFormat) {
                DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ -> {
                    artifact.inputStream().use { bridge.pushStream(it, archive, mode = 420) }
                    verifyRemoteBytes(archive, recipe.assetSha256, recipe.assetSize, "GitHub release artifact changed during transfer")
                    val tarFlag = if (recipe.artifactFormat == DeclarativeGitHubArtifactFormat.TGZ) "-tzf" else "-tzf"
                    val listing = bridge.shellBounded("toybox tar $tarFlag ${q(archive)}", maxOutputBytes = MAX_ARCHIVE_LISTING_BYTES)
                    check(listing.exitCode == 0) { "Could not inspect GitHub release archive: ${listing.combined.takeLast(8_000)}" }
                    val members = parseSafeArchiveMembers(listing.stdout)
                    projectedMember = members.filter { it.substringAfterLast('/') == recipe.executableBasename }.singleOrNull()
                        ?: error("GitHub release archive must contain exactly one '${recipe.executableBasename}' payload")
                    val extracted = "$extractRoot/$projectedMember"
                    DeviceBridgeManager.requireSafeRemotePath(extracted)
                    val extraction = bridge.shellBounded(
                        "set -eu; toybox tar --restrict -xzf ${q(archive)} -C ${q(extractRoot)} ${q(projectedMember)}; " +
                            "SRC=${q(extracted)}; test -f \"${'$'}SRC\"; test ! -L \"${'$'}SRC\"; " +
                            "mv \"${'$'}SRC\" ${q(binary)}; chmod 0755 ${q(binary)}; rm -rf ${q(extractRoot)} ${q(sourceRoot)}",
                        maxOutputBytes = 64_000,
                    )
                    check(extraction.exitCode == 0) { "Could not extract reviewed GitHub payload: ${extraction.combined.takeLast(8_000)}" }
                    projectedSha256 = remoteSha256(binary)
                }
                DeclarativeGitHubArtifactFormat.ZIP, DeclarativeGitHubArtifactFormat.VSIX -> {
                    val projection = requireNotNull(localProjection)
                    projectedMember = projection.member
                    projection.file.inputStream().use { bridge.pushStream(it, binary, mode = 493) }
                    verifyRemoteBytes(binary, projection.sha256, projection.size, "Projected ZIP/VSIX payload changed during transfer")
                    bridge.shell("rm -rf ${q(extractRoot)} ${q(sourceRoot)}")
                    projectedSha256 = projection.sha256
                }
                DeclarativeGitHubArtifactFormat.RAW -> {
                    val projection = requireNotNull(localProjection)
                    projectedMember = projection.member
                    val destination = if (interpretedArtifact) interpretedPayload else binary
                    val mode = if (interpretedArtifact) 420 else 493
                    projection.file.inputStream().use { bridge.pushStream(it, destination, mode = mode) }
                    verifyRemoteBytes(destination, recipe.assetSha256, recipe.assetSize, "Raw GitHub payload changed during transfer")
                    bridge.shell("rm -rf ${q(extractRoot)} ${q(sourceRoot)}")
                    projectedSha256 = recipe.assetSha256
                }
                DeclarativeGitHubArtifactFormat.PHAR -> {
                    val projection = requireNotNull(localProjection)
                    projectedMember = projection.member
                    projection.file.inputStream().use { bridge.pushStream(it, interpretedPayload, mode = 420) }
                    verifyRemoteBytes(interpretedPayload, recipe.assetSha256, recipe.assetSize, "PHAR payload changed during transfer")
                    bridge.shell("rm -rf ${q(extractRoot)} ${q(sourceRoot)}")
                    projectedSha256 = recipe.assetSha256
                }
            }

            if (interpretedArtifact) {
                val exactInterpreter = requireNotNull(interpreter)
                val health = environment.execute(
                    listOf(exactInterpreter.guestExecutable) + recipe.interpreterArgs + listOf(interpretedPayload) + recipe.healthArgs,
                    maxOutputBytes = 128_000,
                )
                check(health.exitCode == 0) { "GitHub interpreted-artifact health check failed: ${health.output.takeLast(8_000)}" }
            } else {
                verifyArm64Elf(binary)
                val health = environment.execute(listOf(binary) + recipe.healthArgs, maxOutputBytes = 128_000)
                check(health.exitCode == 0) { "GitHub release command health check failed: ${health.output.takeLast(8_000)}" }
            }

            val finalPayload = if (interpretedArtifact) {
                "$final/payload/lib/${recipe.executableBasename}"
            } else {
                "$final/payload/bin/${recipe.command}"
            }
            val wrapperScript = buildString {
                append("#!/system/bin/sh\nset -eu\nexec ")
                append(q(environment.launcherPath())).append(' ')
                if (interpretedArtifact) {
                    append(q(requireNotNull(interpreter).guestExecutable)).append(' ')
                    recipe.interpreterArgs.forEach { argument -> append(q(argument)).append(' ') }
                }
                append(q(finalPayload)).append(" \"${'$'}@\"\n")
            }
            ByteArrayInputStream(wrapperScript.toByteArray(Charsets.UTF_8)).use { bridge.pushStream(it, wrapper, mode = 493) }

            val dependencyKey = interpreter?.record?.let { "${it.familyId}@${it.version}" }
            val runtimeDependencyKeys = runtimeDependencies.map { "${it.familyId}@${it.version}" }.distinct().sorted()
            val allDependencyKeys = (runtimeDependencyKeys + listOfNotNull(dependencyKey)).distinct().sorted()
            val lock = ReviewedGitHubReleaseLock(
                familyId = recipe.familyId,
                version = recipe.version,
                repository = recipe.repository,
                releaseTag = recipe.releaseTag,
                releaseImmutable = recipe.releaseImmutable,
                command = recipe.command,
                artifactFormat = recipe.artifactFormat.name,
                artifactLayout = recipe.artifactLayout.name,
                executableBasename = recipe.executableBasename,
                assetName = recipe.assetName,
                assetUrl = recipe.assetUrl,
                assetSha256 = recipe.assetSha256,
                assetSize = recipe.assetSize,
                projectedMember = projectedMember,
                projectedSha256 = projectedSha256,
                interpreterDependency = dependencyKey,
                interpreterGuestExecutable = interpreter?.guestExecutable,
                interpreterArgs = recipe.interpreterArgs,
                runtimeDependencies = runtimeDependencyKeys,
                provenanceUrl = recipe.provenanceUrl,
                guestProfile = recipe.runtimeCompatibility.guestProfile.name,
                libcCompatibility = recipe.runtimeCompatibility.libc.name,
                integration = recipe.runtimeCompatibility.integration.name,
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
            check(bounds.exitCode == 0) { "GitHub release package exceeded final package safety bounds" }
            val checksums = bridge.shellBounded(
                "set -eu; cd ${q(stage)}; find . -type f ! -name SHA256SUMS -print | sort | while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done > SHA256SUMS; toybox sha256sum -c SHA256SUMS",
                maxOutputBytes = 128_000,
            )
            check(checksums.exitCode == 0) { "GitHub release package integrity manifest failed: ${checksums.combined}" }

            val move = bridge.shell(
                "set -eu; rm -rf ${q(previous)}; if [ -d ${q(final)} ]; then mv ${q(final)} ${q(previous)}; fi; " +
                    "mkdir -p ${q(final.substringBeforeLast('/'))}; mv ${q(stage)} ${q(final)}",
            )
            check(move.exitCode == 0) { "Could not activate GitHub release package: ${move.combined}" }
            projectionActivated = true

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
            projectionActivated = false
            record
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (projectionActivated) {
                    bridge.shell("rm -rf ${q(final)}; if [ -d ${q(previous)} ]; then mv ${q(previous)} ${q(final)}; fi")
                }
                bridge.shell("rm -rf ${q(stage)} ${q(previous)}")
            }
            throw failure
        } finally {
            localProjection?.takeIf { it.temporary }?.file?.delete()
        }
    }

    private fun extractSingleZipMember(archive: File, basename: String): LocalProjection {
        ZipFile(archive).use { zip ->
            val seen = linkedSetOf<String>()
            val candidates = mutableListOf<ZipEntry>()
            var count = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                require(count <= MAX_ARCHIVE_MEMBERS) { "ZIP/VSIX archive contains too many entries" }
                val normalized = normalizeArchivePath(entry.name)
                require(seen.add(normalized)) { "ZIP/VSIX archive contains duplicate paths" }
                if (!entry.isDirectory && normalized.substringAfterLast('/') == basename) candidates += entry
            }
            require(candidates.size == 1) { "ZIP/VSIX archive must contain exactly one '$basename' payload" }
            val selected = candidates.single()
            if (selected.size >= 0) require(selected.size in 1L..MAX_PROJECTED_BYTES) { "Projected ZIP/VSIX member is outside safety bounds" }
            if (selected.size > 0 && selected.compressedSize > 0) {
                require(selected.size <= Math.multiplyExact(selected.compressedSize, MAX_COMPRESSION_RATIO)) {
                    "Projected ZIP/VSIX member has an unsafe compression ratio"
                }
            }
            val temp = File.createTempFile("droide-github-projection-", ".bin", archive.parentFile)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                zip.getInputStream(selected).use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total = Math.addExact(total, read.toLong())
                            require(total <= MAX_PROJECTED_BYTES) { "Projected ZIP/VSIX member exceeded safety bound" }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                require(total > 0) { "Projected ZIP/VSIX member is empty" }
                if (selected.size >= 0) require(total == selected.size) { "Projected ZIP/VSIX member size changed during extraction" }
                return LocalProjection(
                    file = temp,
                    member = normalizeArchivePath(selected.name),
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                    size = total,
                    temporary = true,
                )
            } catch (failure: Throwable) {
                temp.delete()
                throw failure
            }
        }
    }

    private fun normalizeArchivePath(raw: String): String {
        require(raw.length in 1..500 && '\u0000' !in raw && '\n' !in raw && '\r' !in raw) { "Unsafe archive path" }
        val normalized = raw.replace('\\', '/').removePrefix("./").removeSuffix("/")
        require(normalized.isNotBlank() && !normalized.startsWith('/')) { "Unsafe archive path" }
        val segments = normalized.split('/')
        require(segments.size in 1..12 && segments.none { it == "." || it == ".." || it.isBlank() }) { "Unsafe archive path" }
        require(segments.all { it.length <= 180 && it.matches(Regex("[A-Za-z0-9._+@=~%\\[\\] -]{1,180}")) }) { "Unsupported archive path characters" }
        return normalized
    }

    private fun parseSafeArchiveMembers(output: String): List<String> {
        require(output.length <= MAX_ARCHIVE_LISTING_BYTES) { "GitHub archive listing exceeded safety bound" }
        val entries = output.lineSequence().map(String::trim).filter(String::isNotBlank).map { raw ->
            val directory = raw.endsWith('/')
            normalizeArchivePath(raw) to directory
        }.toList()
        require(entries.isNotEmpty() && entries.size <= MAX_ARCHIVE_MEMBERS) { "GitHub archive member count is outside safety bounds" }
        require(entries.map { it.first }.distinct().size == entries.size) { "GitHub archive contains duplicate paths" }
        return entries.filterNot { it.second }.map { it.first }
    }

    private suspend fun verifyRemoteBytes(path: String, sha256: String, size: Long, message: String) {
        val transferred = bridge.shellBounded(
            "set -eu; test \"${'$'}(toybox sha256sum ${q(path)} | awk '{print ${'$'}1}')\" = ${q(sha256)}; " +
                "test \"${'$'}(toybox wc -c < ${q(path)} | tr -d ' ')\" = ${q(size.toString())}",
            maxOutputBytes = 16_384,
        )
        check(transferred.exitCode == 0) { message }
    }

    private suspend fun remoteSha256(path: String): String {
        val result = bridge.shellBounded("toybox sha256sum ${q(path)}", maxOutputBytes = 8_192)
        val sha = result.stdout.trim().substringBefore(' ').lowercase()
        check(result.exitCode == 0 && sha.matches(Regex("[0-9a-f]{64}"))) { "Could not hash projected GitHub payload" }
        return sha
    }

    private suspend fun verifyArm64Elf(path: String) {
        val elf = bridge.shellBounded(
            "set -eu; F=${q(path)}; " +
                "test \"${'$'}(toybox od -An -tx1 -N5 \"${'$'}F\" | tr -d ' \\n')\" = 7f454c4602; " +
                "test \"${'$'}(toybox od -An -tx1 -j18 -N2 \"${'$'}F\" | tr -d ' \\n')\" = b700",
            maxOutputBytes = 16_384,
        )
        check(elf.exitCode == 0) { "GitHub release payload is not an ELF64 AArch64 executable" }
    }

    private fun q(value: String) = DeviceBridgeManager.shellQuote(value)

    companion object {
        private const val MAX_ARCHIVE_LISTING_BYTES = 512_000
        private const val MAX_ARCHIVE_MEMBERS = 4_096
        private const val MAX_PROJECTED_BYTES = 256L * 1024L * 1024L
        private const val MAX_COMPRESSION_RATIO = 200L
        private const val EXTRACTION_HEADROOM_BYTES = 64L * 1024L * 1024L
        private const val LARGE_WHOLE_TREE_SOURCE_CACHE_BYTES = 128L * 1024L * 1024L
    }
}
