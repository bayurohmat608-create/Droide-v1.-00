package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json








internal class ReviewedGitHubWholeTreeInstaller(
    private val bridge: DeviceBridgeManager,
    private val packageInstaller: ManagedPackageInstaller,
    private val environment: ReviewedGuestExecutionEnvironment,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun install(
        recipe: GitHubReleaseInstallRecipe,
        artifact: File,
        interpreter: GitHubArtifactInterpreterDependency? = null,
        runtimeDependencies: List<ManagedPackageRecord> = emptyList(),
    ): ManagedPackageRecord = withContext(Dispatchers.IO) {
        PackageBackendContract.requireSame("ReviewedGitHubWholeTreeInstaller", PackageBackendId.DEVICE_ADB, packageInstaller.backendId, environment.backendId)
        recipe.validate()
        require(recipe.artifactLayout == DeclarativeGitHubArtifactLayout.WHOLE_TREE) { "Whole-tree installer received a single-command recipe" }
        val entryPoint = expandTreePath(requireNotNull(recipe.treeEntryPoint), recipe.version)
        val executablePaths = recipe.treeExecutablePaths.map { expandTreePath(it, recipe.version) }
        val maxFiles = requireNotNull(recipe.treeMaxFiles)
        val maxBytes = requireNotNull(recipe.treeMaxUnpackedBytes)
        val interpretedTree = recipe.interpreterFamilyId != null
        val exactInterpreter = if (interpretedTree) {
            requireNotNull(interpreter) { "Reviewed interpreted whole-tree installation requires its managed interpreter dependency" }.also { dependency ->
                dependency.validate(recipe)
                require(packageInstaller.verify(dependency.record)) { "Managed whole-tree interpreter dependency failed integrity/health verification" }
            }
        } else {
            require(interpreter == null) { "Native whole-tree artifacts must not carry an interpreter dependency" }
            null
        }
        runtimeDependencies.forEach { dependency ->
            require(packageInstaller.verify(dependency)) { "Managed runtime dependency ${dependency.familyId}@${dependency.version} failed verification" }
        }
        environment.ensureInstalled()
        PackageRuntimeCompatibilityPolicy.verifyGuestCommands(environment, recipe.runtimeCompatibility)

        val root = DeviceBridgeManager.remoteRoot()
        val safeFamily = recipe.familyId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = recipe.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val stage = "$root/packages/.staging/$safeFamily-$safeVersion-arm64-v8a"
        val final = "$root/packages/${recipe.familyId}/$safeVersion/arm64-v8a"
        val previous = "$root/packages/.previous/$safeFamily-$safeVersion-arm64-v8a"
        val treeRoot = "$stage/payload/tree"
        val hostBinRoot = "$stage/payload/host-bin"
        val wrapper = "$hostBinRoot/${recipe.command}"
        listOf(stage, final, previous, treeRoot, hostBinRoot, wrapper).forEach(DeviceBridgeManager::requireSafeRemotePath)

        val remoteNeed = Math.addExact(maxBytes, WHOLE_TREE_TRANSACTION_OVERHEAD_BYTES)
        DeviceWorkstationStorageGuard.requireHeadroom(
            bridge,
            additionalBytes = remoteNeed,
            purpose = "install reviewed whole-tree GitHub artifact ${recipe.familyId} ${recipe.version}",
        )

        val projection = SafeWholeTreeArchive.project(
            archive = artifact,
            format = recipe.artifactFormat,
            executablePaths = executablePaths.toSet(),
            maxFiles = maxFiles,
            maxUnpackedBytes = maxBytes,
        )
        try {
            val largestProjectedFile = projection.files.maxOfOrNull { it.size } ?: 0L
            DeviceWorkstationStorageGuard.requireHeadroom(
                bridge = bridge,
                additionalBytes = largestProjectedFile,
                purpose = "transfer reviewed whole-tree GitHub artifact ${recipe.familyId} ${recipe.version}",
            )
            val entry = projection.files.singleOrNull { it.relativePath == entryPoint }
                ?: error("Reviewed whole-tree archive is missing entry point '$entryPoint'")
            if (!interpretedTree) require(entry.mode == EXECUTABLE_MODE) { "Reviewed native whole-tree entry point is not executable" }

            val prep = bridge.shell(
                "set -eu; rm -rf ${q(stage)} ${q(previous)}; mkdir -p ${q(treeRoot)} ${q(hostBinRoot)} ${q("$root/packages/.previous")}",
            )
            check(prep.exitCode == 0) { "Could not prepare whole-tree GitHub transaction: ${prep.combined.takeLast(8_000)}" }

            var activated = false
            try {
                createRemoteDirectories(treeRoot, projection.files)
                projection.files.forEach { file ->
                    val remote = "$treeRoot/${file.relativePath}"
                    DeviceBridgeManager.requireSafeRemotePath(remote)
                    bridge.push(file.file, remote, mode = file.mode)
                    projection.releaseLocalPayload(file)
                }

                val treeManifest = projection.files.sortedBy { it.relativePath }.joinToString(separator = "\n", postfix = "\n") {
                    "${it.sha256}  ${it.relativePath}"
                }
                ByteArrayInputStream(treeManifest.toByteArray(Charsets.UTF_8)).use {
                    bridge.pushStream(it, "$stage/TREE_SHA256SUMS", mode = 420)
                }
                val transferVerify = bridge.shellBounded(
                    "set -eu; cd ${q(treeRoot)}; toybox sha256sum -c ${q("$stage/TREE_SHA256SUMS")} >/dev/null; " +
                        "test \"${'$'}(find . -type f | wc -l | tr -d ' ')\" = ${q(projection.files.size.toString())}; " +
                        "test -z \"${'$'}(find . -type l -print -quit)\"",
                    maxOutputBytes = 32_768,
                )
                check(transferVerify.exitCode == 0) { "Whole-tree GitHub transfer integrity failed: ${transferVerify.combined.takeLast(8_000)}" }

                val stageEntryPoint = "$treeRoot/$entryPoint"
                DeviceBridgeManager.requireSafeRemotePath(stageEntryPoint)
                val health = if (interpretedTree) {
                    val dependency = requireNotNull(exactInterpreter)
                    val args = expandInterpreterArgs(recipe.interpreterArgs, treeRoot, stageEntryPoint, recipe.version)
                    environment.execute(listOf(dependency.guestExecutable) + args + recipe.healthArgs, maxOutputBytes = 128_000)
                } else {
                    verifyArm64Elf(stageEntryPoint)
                    environment.execute(listOf(stageEntryPoint) + recipe.healthArgs, maxOutputBytes = 128_000)
                }
                check(health.exitCode == 0) { "Whole-tree GitHub health check failed: ${health.output.takeLast(8_000)}" }

                val finalTreeRoot = "$final/payload/tree"
                val finalEntryPoint = "$finalTreeRoot/$entryPoint"
                val wrapperScript = if (interpretedTree) {
                    val dependency = requireNotNull(exactInterpreter)
                    val args = expandInterpreterArgs(recipe.interpreterArgs, finalTreeRoot, finalEntryPoint, recipe.version)
                    buildString {
                        append("#!/system/bin/sh\nset -eu\nexec ")
                        append(q(environment.launcherPath())).append(' ').append(q(dependency.guestExecutable))
                        args.forEach { argument -> append(' ').append(q(argument)) }
                        append(" \"${'$'}@\"\n")
                    }
                } else {
                    "#!/system/bin/sh\nset -eu\nexec ${q(environment.launcherPath())} ${q(finalEntryPoint)} \"${'$'}@\"\n"
                }
                ByteArrayInputStream(wrapperScript.toByteArray(Charsets.UTF_8)).use {
                    bridge.pushStream(it, wrapper, mode = EXECUTABLE_MODE)
                }

                val lock = ReviewedGitHubWholeTreeLock(
                    familyId = recipe.familyId,
                    version = recipe.version,
                    repository = recipe.repository,
                    releaseTag = recipe.releaseTag,
                    releaseImmutable = recipe.releaseImmutable,
                    command = recipe.command,
                    artifactFormat = recipe.artifactFormat.name,
                    assetName = recipe.assetName,
                    assetUrl = recipe.assetUrl,
                    assetSha256 = recipe.assetSha256,
                    assetSize = recipe.assetSize,
                    treeEntryPoint = entryPoint,
                    treeExecutablePaths = executablePaths.sorted(),
                    treeFiles = projection.files.size,
                    treeBytes = projection.totalBytes,
                    treeSha256 = projection.treeSha256,
                    provenanceUrl = recipe.provenanceUrl,
                    guestProfile = recipe.runtimeCompatibility.guestProfile.name,
                    libcCompatibility = recipe.runtimeCompatibility.libc.name,
                    integration = recipe.runtimeCompatibility.integration.name,
                    compatibilityEvidenceUrl = recipe.runtimeCompatibility.evidenceUrl,
                    interpreterDependency = exactInterpreter?.let { "${it.record.familyId}@${it.record.version}" },
                    interpreterGuestExecutable = exactInterpreter?.guestExecutable,
                    interpreterArgs = recipe.interpreterArgs,
                    runtimeDependencies = runtimeDependencies.map { "${it.familyId}@${it.version}" }.distinct().sorted(),
                    installedAtEpochMs = System.currentTimeMillis(),
                )
                ByteArrayInputStream(json.encodeToString(lock).toByteArray(Charsets.UTF_8)).use {
                    bridge.pushStream(it, "$stage/RECIPE_LOCK.json", mode = 420)
                }

                val maxStageFiles = Math.addExact(maxFiles, 4)
                val maxStageKiB = Math.addExact((maxBytes + 1023L) / 1024L, 4096L)
                val bounds = bridge.shellBounded(
                    "set -eu; C=${'$'}(find ${q(stage)} -type f | wc -l | tr -d ' '); B=${'$'}(du -sk ${q(stage)} | awk '{print ${'$'}1}'); " +
                        "test \"${'$'}C\" -le ${q(maxStageFiles.toString())}; test \"${'$'}B\" -le ${q(maxStageKiB.toString())}",
                    maxOutputBytes = 16_384,
                )
                check(bounds.exitCode == 0) { "Whole-tree GitHub package exceeded reviewed final bounds" }

                val checksums = bridge.shellBounded(
                    "set -eu; cd ${q(stage)}; find . -type f ! -name SHA256SUMS -print | sort | while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done > SHA256SUMS; toybox sha256sum -c SHA256SUMS >/dev/null",
                    maxOutputBytes = 32_768,
                )
                check(checksums.exitCode == 0) { "Whole-tree package integrity manifest failed: ${checksums.combined.takeLast(8_000)}" }

                val move = bridge.shell(
                    "set -eu; rm -rf ${q(previous)}; if [ -d ${q(final)} ]; then mv ${q(final)} ${q(previous)}; fi; " +
                        "mkdir -p ${q(final.substringBeforeLast('/'))}; mv ${q(stage)} ${q(final)}",
                )
                check(move.exitCode == 0) { "Could not activate whole-tree GitHub package: ${move.combined.takeLast(8_000)}" }
                activated = true

                val finalHostBinRoot = "$final/payload/host-bin"
                val dependencyKeys = buildList {
                    exactInterpreter?.let { add("${it.record.familyId}@${it.record.version}") }
                    addAll(runtimeDependencies.map { "${it.familyId}@${it.version}" })
                }.distinct().sorted()
                val record = ManagedPackageRecord(
                    familyId = recipe.familyId,
                    version = recipe.version,
                    scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                    installRoot = final,
                    installedAtEpochMs = System.currentTimeMillis(),
                    active = true,
                    pathEntries = listOf(finalHostBinRoot),
                    commands = mapOf(recipe.command to "$finalHostBinRoot/${recipe.command}"),
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
            val command = "mkdir -p " + chunk.joinToString(" ") { q("$treeRoot/$it") }
            val result = bridge.shellBounded(command, maxOutputBytes = 16_384)
            check(result.exitCode == 0) { "Could not create whole-tree package directories: ${result.combined.takeLast(4_000)}" }
        }
    }

    private fun expandTreePath(template: String, version: String): String {
        val expanded = template.replace(GitHubReleaseInstallRecipe.VERSION_PLACEHOLDER, version)
        require('{' !in expanded && '}' !in expanded) { "Unresolved whole-tree path placeholder" }
        require(!expanded.startsWith('/') && ".." !in expanded.split('/') && '\\' !in expanded) { "Expanded whole-tree path is unsafe" }
        return expanded
    }

    private fun expandInterpreterArgs(args: List<String>, treeRoot: String, entryPoint: String, version: String): List<String> {
        DeviceBridgeManager.requireSafeRemotePath(treeRoot)
        DeviceBridgeManager.requireSafeRemotePath(entryPoint)
        return args.map { argument ->
            val expanded = argument
                .replace(GitHubReleaseInstallRecipe.TREE_PLACEHOLDER, treeRoot)
                .replace(GitHubReleaseInstallRecipe.ENTRY_PLACEHOLDER, entryPoint)
                .replace(GitHubReleaseInstallRecipe.VERSION_PLACEHOLDER, version)
            require('{' !in expanded && '}' !in expanded) { "Unresolved whole-tree interpreter placeholder" }
            require(expanded.length <= 500 && '\u0000' !in expanded && '\n' !in expanded && '\r' !in expanded) {
                "Expanded whole-tree interpreter argument is unsafe"
            }
            expanded
        }
    }

    private suspend fun verifyArm64Elf(path: String) {
        val result = bridge.shellBounded(
            "set -eu; F=${q(path)}; test -f \"${'$'}F\"; test ! -L \"${'$'}F\"; " +
                "test \"${'$'}(toybox od -An -tx1 -N5 \"${'$'}F\" | tr -d ' \\n')\" = 7f454c4602; " +
                "test \"${'$'}(toybox od -An -tx1 -j18 -N2 \"${'$'}F\" | tr -d ' \\n')\" = b700",
            maxOutputBytes = 16_384,
        )
        check(result.exitCode == 0) { "Whole-tree entry point is not an ELF64 AArch64 executable" }
    }

    private fun q(value: String): String = DeviceBridgeManager.shellQuote(value)

    private companion object {
        const val EXECUTABLE_MODE = 493
        const val WHOLE_TREE_TRANSACTION_OVERHEAD_BYTES = 96L * 1024L * 1024L
    }
}

@Serializable
private data class ReviewedGitHubWholeTreeLock(
    val schema: Int = 4,
    val installer: String = "github-release-whole-tree-sha256",
    val familyId: String,
    val version: String,
    val repository: String,
    val releaseTag: String,
    val releaseImmutable: Boolean,
    val command: String,
    val platform: String = "linux-arm64",
    val artifactFormat: String,
    val assetName: String,
    val assetUrl: String,
    val assetSha256: String,
    val assetSize: Long,
    val treeEntryPoint: String,
    val treeExecutablePaths: List<String>,
    val treeFiles: Int,
    val treeBytes: Long,
    val treeSha256: String,
    val provenanceUrl: String,
    val guestProfile: String,
    val libcCompatibility: String,
    val integration: String,
    val compatibilityEvidenceUrl: String,
    val interpreterDependency: String? = null,
    val interpreterGuestExecutable: String? = null,
    val interpreterArgs: List<String> = emptyList(),
    val runtimeDependencies: List<String> = emptyList(),
    val installedAtEpochMs: Long,
)

internal data class SafeTreeFile(
    val relativePath: String,
    val file: File,
    val mode: Int,
    val size: Long,
    val sha256: String,
)

internal data class SafeTreeSymlink(
    val relativePath: String,
    val target: String,
    val resolvedPath: String,
)

internal class SafeTreeProjection(
    val root: File,
    val files: List<SafeTreeFile>,
    val symlinks: List<SafeTreeSymlink> = emptyList(),
    val totalBytes: Long,
    val treeSha256: String,
) : Closeable {
    





    fun releaseLocalPayload(file: SafeTreeFile) {
        val canonicalRoot = root.canonicalFile
        val canonicalFile = file.file.canonicalFile
        require(canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) { "Projected file escaped projection root" }
        if (canonicalFile.exists()) check(canonicalFile.delete()) { "Could not release projected payload ${file.relativePath}" }
    }

    override fun close() {
        PathSecurity.deleteTreeNoFollow(root)
    }
}

internal object SafeWholeTreeArchive {
    private const val TAR_BLOCK = 512
    private const val EXECUTABLE_MODE = 493
    private const val FILE_MODE = 420
    private const val MAX_COMPRESSION_RATIO = 200L
    private const val LOCAL_PROJECTION_RESERVE_BYTES = 256L * 1024L * 1024L

    fun project(
        archive: File,
        format: DeclarativeGitHubArtifactFormat,
        executablePaths: Set<String>,
        maxFiles: Int,
        maxUnpackedBytes: Long,
        linkPolicy: DeclarativeWholeTreeLinkPolicy = DeclarativeWholeTreeLinkPolicy.REJECT,
        modePolicy: DeclarativeWholeTreeModePolicy = DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST,
    ): SafeTreeProjection {
        require(archive.isFile && !archive.isDirectory) { "Whole-tree source artifact is missing" }
        val advancedTarPolicy = linkPolicy == DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE || modePolicy == DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE
        val maxAllowedBytes = if (advancedTarPolicy) 6_442_450_944L else 1_073_741_824L
        val maxAllowedFiles = if (advancedTarPolicy) 8_192 else 4_096
        require(maxFiles in 1..maxAllowedFiles && maxUnpackedBytes in 1L..maxAllowedBytes) { "Invalid whole-tree safety bounds" }
        require(executablePaths.size <= 64 && executablePaths.all(::safePath)) { "Invalid reviewed executable paths" }
        when (modePolicy) {
            DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST -> require(executablePaths.isNotEmpty()) { "Explicit whole-tree mode policy requires reviewed executable paths" }
            DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE -> {
                require(executablePaths.isEmpty()) { "Pinned archive mode policy must not duplicate executable paths" }
                require(format in setOf(DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ)) { "Pinned archive mode policy requires TAR/TGZ" }
            }
        }
        if (linkPolicy == DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE) {
            require(format in setOf(DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ)) { "Safe relative links require TAR/TGZ" }
        }
        val projectionParent = requireNotNull(archive.parentFile) { "Whole-tree artifact has no parent directory" }
        val projectionNeed = Math.addExact(maxUnpackedBytes, LOCAL_PROJECTION_RESERVE_BYTES)
        val localUsable = projectionParent.usableSpace.coerceAtLeast(0L)
        check(localUsable >= projectionNeed) {
            "Not enough local storage to safely project whole-tree artifact. Need " +
                "${DeviceWorkstationStorageGuard.formatBytes(projectionNeed)} free including projection reserve, but only " +
                "${DeviceWorkstationStorageGuard.formatBytes(localUsable)} is locally usable."
        }
        val root = File(projectionParent, ".tree-${System.nanoTime()}-${archive.nameWithoutExtension.take(32)}")
        check(root.mkdir()) { "Could not allocate whole-tree projection directory" }
        return try {
            val projected = when (format) {
                DeclarativeGitHubArtifactFormat.TAR_GZ, DeclarativeGitHubArtifactFormat.TGZ -> projectTarGz(
                    archive, root, executablePaths, maxFiles, maxUnpackedBytes, linkPolicy, modePolicy,
                )
                DeclarativeGitHubArtifactFormat.ZIP, DeclarativeGitHubArtifactFormat.VSIX -> TarProjection(
                    files = projectZip(archive, root, executablePaths, maxFiles, maxUnpackedBytes),
                    symlinks = emptyList(),
                )
                else -> error("Whole-tree projection requires TAR/TGZ/ZIP/VSIX")
            }
            val files = projected.files
            require(files.isNotEmpty()) { "Whole-tree archive contains no regular files" }
            val actualPaths = files.map { it.relativePath }.toSet()
            if (modePolicy == DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST) {
                require(executablePaths.all(actualPaths::contains)) { "Whole-tree archive is missing a reviewed executable path" }
            }
            val total = files.fold(0L) { acc, file -> Math.addExact(acc, file.size) }
            val digest = MessageDigest.getInstance("SHA-256")
            files.sortedBy { it.relativePath }.forEach { file ->
                digest.update(file.relativePath.toByteArray(Charsets.UTF_8)); digest.update(0)
                digest.update(file.mode.toString().toByteArray(Charsets.US_ASCII)); digest.update(0)
                digest.update(file.sha256.toByteArray(Charsets.US_ASCII)); digest.update('\n'.code.toByte())
            }
            projected.symlinks.sortedBy { it.relativePath }.forEach { link ->
                digest.update("LINK".toByteArray(Charsets.US_ASCII)); digest.update(0)
                digest.update(link.relativePath.toByteArray(Charsets.UTF_8)); digest.update(0)
                digest.update(link.target.toByteArray(Charsets.UTF_8)); digest.update(0)
                digest.update(link.resolvedPath.toByteArray(Charsets.UTF_8)); digest.update('\n'.code.toByte())
            }
            SafeTreeProjection(root, files, projected.symlinks, total, digest.digest().hex())
        } catch (failure: Throwable) {
            PathSecurity.deleteTreeNoFollow(root)
            throw failure
        }
    }

    private fun projectZip(
        archive: File,
        root: File,
        executablePaths: Set<String>,
        maxFiles: Int,
        maxBytes: Long,
    ): List<SafeTreeFile> = ZipFile(archive).use { zip ->
        val seen = linkedSetOf<String>()
        val files = mutableListOf<SafeTreeFile>()
        var total = 0L
        var entries = 0
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            entries++
            require(entries <= maxFiles * 2) { "Whole-tree ZIP/VSIX has too many entries" }
            val path = normalizePath(entry.name)
            require(seen.add(path)) { "Whole-tree ZIP/VSIX contains duplicate paths" }
            if (entry.isDirectory) continue
            val declaredSize = entry.size
            require(declaredSize in 0L..maxBytes) { "Whole-tree ZIP/VSIX member has an invalid size" }
            if (declaredSize > 0 && entry.compressedSize > 0) {
                require(declaredSize <= Math.multiplyExact(entry.compressedSize, MAX_COMPRESSION_RATIO)) { "Whole-tree ZIP/VSIX member has unsafe compression ratio" }
            }
            require(files.size < maxFiles) { "Whole-tree ZIP/VSIX exceeds reviewed file-count bound" }
            val out = child(root, path)
            out.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            zip.getInputStream(entry).use { input ->
                FileOutputStream(out).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size = Math.addExact(size, read.toLong())
                        total = Math.addExact(total, read.toLong())
                        require(total <= maxBytes) { "Whole-tree ZIP/VSIX exceeds reviewed expanded-byte bound" }
                        digest.update(buffer, 0, read); output.write(buffer, 0, read)
                    }
                }
            }
            if (declaredSize >= 0) require(size == declaredSize) { "Whole-tree ZIP/VSIX member size changed during extraction" }
            files += SafeTreeFile(path, out, if (path in executablePaths) EXECUTABLE_MODE else FILE_MODE, size, digest.digest().hex())
        }
        files
    }

    private data class TarProjection(val files: List<SafeTreeFile>, val symlinks: List<SafeTreeSymlink>)

    private fun projectTarGz(
        archive: File,
        root: File,
        executablePaths: Set<String>,
        maxFiles: Int,
        maxBytes: Long,
        linkPolicy: DeclarativeWholeTreeLinkPolicy,
        modePolicy: DeclarativeWholeTreeModePolicy,
    ): TarProjection {
        val files = mutableListOf<SafeTreeFile>()
        val symlinks = mutableListOf<SafeTreeSymlink>()
        val seen = linkedSetOf<String>()
        var total = 0L
        var executableCount = 0
        GZIPInputStream(FileInputStream(archive), 64 * 1024).use { input ->
            var entries = 0
            while (true) {
                val header = ByteArray(TAR_BLOCK)
                if (!readBlock(input, header)) break
                if (header.all { it == 0.toByte() }) break
                verifyTarChecksum(header)
                entries++
                require(entries <= maxFiles * 2) { "Whole-tree TAR has too many entries" }
                val name = tarString(header, 0, 100)
                val prefix = tarString(header, 345, 155)
                val path = normalizePath(if (prefix.isBlank()) name else "$prefix/$name")
                require(seen.add(path)) { "Whole-tree TAR contains duplicate paths" }
                val archiveMode = tarOctal(header, 100, 8, "mode")
                val size = tarOctal(header, 124, 12, "size")
                val type = header[156].toInt().toChar()
                when (type) {
                    '\u0000', '0' -> {
                        require(files.size < maxFiles) { "Whole-tree TAR exceeds reviewed file-count bound" }
                        total = Math.addExact(total, size)
                        require(total <= maxBytes) { "Whole-tree TAR exceeds reviewed expanded-byte bound" }
                        val out = child(root, path)
                        out.parentFile?.mkdirs()
                        val digest = MessageDigest.getInstance("SHA-256")
                        FileOutputStream(out).use { output -> copyExact(input, output, digest, size) }
                        skipFully(input, padding(size))
                        val executable = when (modePolicy) {
                            DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST -> path in executablePaths
                            DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE -> archiveMode and 0b001001001L != 0L
                        }
                        if (executable) {
                            executableCount++
                            require(executableCount <= 512) { "Whole-tree TAR exceeds pinned executable-count bound" }
                        }
                        files += SafeTreeFile(path, out, if (executable) EXECUTABLE_MODE else FILE_MODE, size, digest.digest().hex())
                    }
                    '5' -> {
                        require(size == 0L) { "Whole-tree TAR directory unexpectedly contains payload bytes" }
                        child(root, path).mkdirs()
                    }
                    '2' -> {
                        require(size == 0L) { "Whole-tree TAR symlink unexpectedly contains payload bytes" }
                        require(linkPolicy == DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE) { "Whole-tree TAR rejects symlinks without SAFE_RELATIVE policy" }
                        require(symlinks.size < 256) { "Whole-tree TAR exceeds safe relative-symlink bound" }
                        val target = tarString(header, 157, 100)
                        val resolved = resolveRelativeLink(path, target)
                        symlinks += SafeTreeSymlink(path, target, resolved)
                    }
                    else -> error("Whole-tree TAR rejects hard links, devices, FIFOs, sparse/PAX and special entry type '$type'")
                }
            }
        }
        if (symlinks.isNotEmpty()) {
            val knownPaths = seen.toSet()
            symlinks.forEach { require(it.resolvedPath in knownPaths) { "Whole-tree TAR symlink target is missing" } }
            val byPath = symlinks.associateBy { it.relativePath }
            fun resolve(path: String, chain: Set<String> = emptySet()): String {
                require(path !in chain) { "Whole-tree TAR symlink graph contains a cycle" }
                val link = byPath[path] ?: return path
                return resolve(link.resolvedPath, chain + path)
            }
            symlinks.forEach { resolve(it.relativePath) }
        }
        if (modePolicy == DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE) {
            require(executableCount in 1..512) { "Pinned archive contains no executable payloads" }
        }
        return TarProjection(files, symlinks)
    }

    private fun resolveRelativeLink(path: String, target: String): String {
        require(target.isNotBlank() && !target.startsWith('/') && '\\' !in target && '\u0000' !in target && '\n' !in target && '\r' !in target) {
            "Whole-tree TAR contains an unsafe symlink target"
        }
        val stack = path.substringBeforeLast('/', "").split('/').filter(String::isNotBlank).toMutableList()
        target.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> require(stack.isNotEmpty()) { "Whole-tree TAR symlink escapes the projection root" }.also { stack.removeAt(stack.lastIndex) }
                else -> {
                    require(segment.length <= 180 && segment.matches(Regex("[A-Za-z0-9._+@=~%\\[\\]-]{1,180}"))) { "Whole-tree TAR symlink target is unsafe" }
                    stack += segment
                }
            }
        }
        require(stack.isNotEmpty()) { "Whole-tree TAR symlink resolves to projection root" }
        return stack.joinToString("/")
    }

    private fun verifyTarChecksum(header: ByteArray) {
        val expected = tarOctal(header, 148, 8, "checksum")
        var sum = 0L
        header.indices.forEach { index -> sum += if (index in 148..155) 32 else header[index].toInt() and 0xff }
        require(sum == expected) { "Whole-tree TAR header checksum mismatch" }
    }

    private fun tarOctal(bytes: ByteArray, offset: Int, length: Int, label: String): Long {
        require(bytes[offset].toInt() and 0x80 == 0) { "Whole-tree TAR $label uses unsupported base-256 encoding" }
        val text = bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII).trim('\u0000', ' ')
        if (text.isBlank()) return 0L
        require(text.all { it in '0'..'7' }) { "Whole-tree TAR has invalid $label" }
        return text.toLong(8)
    }

    private fun tarString(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var offset = 0
        while (offset < block.size) {
            val read = input.read(block, offset, block.size - offset)
            if (read < 0) {
                require(offset == 0) { "Whole-tree TAR ended inside a header" }
                return false
            }
            offset += read
        }
        return true
    }

    private fun copyExact(input: InputStream, output: FileOutputStream, digest: MessageDigest, size: Long) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(read > 0) { "Whole-tree TAR ended inside a file" }
            output.write(buffer, 0, read); digest.update(buffer, 0, read); remaining -= read
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val scratch = ByteArray(4096)
        while (remaining > 0) {
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            require(read > 0) { "Whole-tree TAR ended inside alignment padding" }
            remaining -= read
        }
    }

    private fun padding(size: Long): Long = (TAR_BLOCK - (size % TAR_BLOCK)) % TAR_BLOCK

    private fun normalizePath(raw: String): String {
        require(raw.length in 1..500 && !raw.startsWith('/') && '\\' !in raw && '\u0000' !in raw && '\n' !in raw && '\r' !in raw) { "Unsafe whole-tree archive path" }
        val value = raw.removePrefix("./").removeSuffix("/")
        require(safePath(value)) { "Unsupported whole-tree archive path" }
        return value
    }

    private fun safePath(value: String): Boolean {
        if (value.length !in 1..500 || value.startsWith('/') || '\\' in value || '\u0000' in value || '\n' in value || '\r' in value) return false
        val segments = value.split('/')
        return segments.size in 1..16 && segments.all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." && segment.length <= 180 &&
                segment.matches(Regex("[A-Za-z0-9._+@=~%\\[\\]-]{1,180}"))
        }
    }

    private fun child(root: File, relativePath: String): File {
        val child = File(root, relativePath)
        require(child.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Whole-tree archive escaped projection root" }
        return child
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
