package com.baystudio.droide.core

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json






class LocalLlamaRuntimeManager(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val downloader: TrustedArtifactDownloader = TrustedArtifactDownloader(context.applicationContext),
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
    private val remoteBase = "${DeviceBridgeManager.remoteRoot()}/local-model-runtimes/llama.cpp"
    private val finalRoot = "$remoteBase/${LocalLlamaRuntimeContract.VERSION}/arm64-v8a"
    private val treeRoot = "$finalRoot/runtime"
    private val lockPath = "$finalRoot/RUNTIME_LOCK.json"

    data class TargetIdentity(val abi: String, val api: Int) {
        init {
            require(abi.length in 1..64 && '\u0000' !in abi && '\n' !in abi && '\r' !in abi) { "Invalid target ABI" }
            require(api in 1..10_000) { "Invalid target API level" }
        }
    }

    data class Status(
        val version: String,
        val cliPath: String,
        val serverPath: String,
        val target: TargetIdentity,
         
        val runtimeSmokeCertified: Boolean,
        val inferenceCertified: Boolean = false,
    )

    suspend fun ensureInstalled(
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
    ): Status = withContext(Dispatchers.IO) {
        LocalLlamaRuntimeContract.validate()
        bridge.ensurePackageBackend()
        val target = targetIdentity()
        LocalLlamaRuntimeContract.requireCompatibleTarget(target.abi, target.api)
        reconcileInterruptedInstall()
        healthyStatus(target)?.let { return@withContext it }

        val spec = artifactSpec()
        val artifact = downloader.download(spec, onProgress)
        val projection = SafeWholeTreeArchive.project(
            archive = artifact,
            format = DeclarativeGitHubArtifactFormat.TAR_GZ,
            executablePaths = emptySet(),
            maxFiles = LocalLlamaRuntimeContract.MAX_TREE_FILES,
            maxUnpackedBytes = LocalLlamaRuntimeContract.MAX_TREE_BYTES,
            linkPolicy = DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE,
            modePolicy = DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE,
        )
        try {
            validateProjection(projection)
            val remoteNeed = Math.addExact(projection.totalBytes, LocalLlamaRuntimeContract.INSTALL_OVERHEAD_BYTES)
            DeviceWorkstationStorageGuard.requireHeadroom(
                bridge = bridge,
                additionalBytes = remoteNeed,
                purpose = "install the local llama.cpp runtime",
            )
            installProjection(projection, target)
        } finally {
            projection.close()
            if (artifact.length() >= 32L * 1024L * 1024L) {
                try { downloader.discard(spec) } catch (_: Throwable) {   }
            }
        }
        healthyStatus(target) ?: error("Activated local llama.cpp runtime did not pass final health verification")
    }

    suspend fun status(): Status? = withContext(Dispatchers.IO) {
        if (bridge.state.value.connected == null) return@withContext null
        val target = runCatching { targetIdentity() }.getOrNull() ?: return@withContext null
        runCatching { LocalLlamaRuntimeContract.requireCompatibleTarget(target.abi, target.api) }.getOrNull() ?: return@withContext null
        healthyStatus(target)
    }

    private suspend fun installProjection(projection: SafeTreeProjection, target: TargetIdentity) {
        val safeVersion = LocalLlamaRuntimeContract.VERSION
        val stage = "$remoteBase/.staging/$safeVersion-arm64-v8a"
        val previous = "$remoteBase/.previous/$safeVersion-arm64-v8a"
        val stageTree = "$stage/runtime"
        listOf(remoteBase, stage, previous, stageTree, finalRoot).forEach(DeviceBridgeManager::requireSafeRemotePath)

        val prep = bridge.shell(
            "set -eu; rm -rf ${q(stage)} ${q(previous)}; mkdir -p ${q(stageTree)} ${q("$remoteBase/.previous")}",
        )
        check(prep.exitCode == 0) { "Could not prepare local llama.cpp runtime transaction: ${prep.combined.takeLast(8_000)}" }

        var activated = false
        try {
            createRemoteDirectories(stageTree, projection.files)
            projection.files.forEach { file ->
                LocalLlamaRuntimeContract.requireArchivePath(file.relativePath)
                val remote = "$stageTree/${file.relativePath}"
                DeviceBridgeManager.requireSafeRemotePath(remote)
                bridge.push(file.file, remote, mode = file.mode)
                projection.releaseLocalPayload(file)
            }
            createRemoteSymlinks(stageTree, projection.symlinks)

            val manifest = projection.files.sortedBy { it.relativePath }.joinToString(separator = "\n", postfix = "\n") {
                "${it.sha256}  ${it.relativePath}"
            }
            ByteArrayInputStream(manifest.toByteArray(Charsets.UTF_8)).use {
                bridge.pushStream(it, "$stage/TREE_SHA256SUMS", mode = 420)
            }
            verifyRemoteTree(stage, stageTree, projection)
            smokeRuntime(stageTree)

            val lock = RuntimeLock(
                version = LocalLlamaRuntimeContract.VERSION,
                stableRelease = LocalLlamaRuntimeContract.STABLE_RELEASE,
                upstreamCommit = LocalLlamaRuntimeContract.UPSTREAM_COMMIT,
                targetAbi = target.abi,
                targetApi = target.api,
                assetName = LocalLlamaRuntimeContract.ASSET_NAME,
                assetUrl = LocalLlamaRuntimeContract.ASSET_URL,
                assetSha256 = LocalLlamaRuntimeContract.ASSET_SHA256,
                assetBytes = LocalLlamaRuntimeContract.ASSET_BYTES,
                provenanceUrl = LocalLlamaRuntimeContract.PROVENANCE_URL,
                attestationUrl = LocalLlamaRuntimeContract.ATTESTATION_URL,
                treeFiles = projection.files.size,
                treeSymlinks = projection.symlinks.size,
                treeBytes = projection.totalBytes,
                treeSha256 = projection.treeSha256,
                runtimeSmokeCertified = true,
                inferenceCertified = false,
                installedAtEpochMs = System.currentTimeMillis(),
            )
            ByteArrayInputStream(json.encodeToString(lock).toByteArray(Charsets.UTF_8)).use {
                bridge.pushStream(it, "$stage/RUNTIME_LOCK.json", mode = 420)
            }
            val stageIntegrity = bridge.shellBounded(
                "set -eu; cd ${q(stage)}; toybox sha256sum ${q("RUNTIME_LOCK.json")} >/dev/null; " +
                    "test -f ${q("TREE_SHA256SUMS")}; test -f ${q("runtime/${LocalLlamaRuntimeContract.CLI_RELATIVE_PATH}")}",
                maxOutputBytes = 16_384,
            )
            check(stageIntegrity.exitCode == 0) { "Local llama.cpp runtime lock staging failed" }

            val activate = bridge.shell(
                "set -eu; rm -rf ${q(previous)}; if [ -d ${q(finalRoot)} ]; then mv ${q(finalRoot)} ${q(previous)}; fi; " +
                    "mkdir -p ${q(finalRoot.substringBeforeLast('/'))}; mv ${q(stage)} ${q(finalRoot)}",
            )
            check(activate.exitCode == 0) { "Could not activate local llama.cpp runtime: ${activate.combined.takeLast(8_000)}" }
            activated = true

            verifyRemoteTree(finalRoot, treeRoot, projection)
            smokeRuntime(treeRoot)
            withContext(NonCancellable) { bridge.shell("rm -rf ${q(previous)}") }
            activated = false
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (activated) {
                    bridge.shell("rm -rf ${q(finalRoot)}; if [ -d ${q(previous)} ]; then mv ${q(previous)} ${q(finalRoot)}; fi")
                }
                bridge.shell("rm -rf ${q(stage)} ${q(previous)}")
            }
            throw failure
        }
    }

    private fun validateProjection(projection: SafeTreeProjection) {
        require(projection.files.isNotEmpty()) { "llama.cpp Android archive is empty" }
        require(projection.files.size <= LocalLlamaRuntimeContract.MAX_TREE_FILES) { "llama.cpp archive has too many files" }
        require(projection.totalBytes in 1L..LocalLlamaRuntimeContract.MAX_TREE_BYTES) { "llama.cpp archive exceeds expanded size bound" }
        projection.files.forEach { LocalLlamaRuntimeContract.requireArchivePath(it.relativePath) }
        projection.symlinks.forEach {
            LocalLlamaRuntimeContract.requireArchivePath(it.relativePath)
            LocalLlamaRuntimeContract.requireArchivePath(it.resolvedPath)
        }
        val regular = projection.files.associateBy { it.relativePath }
        LocalLlamaRuntimeContract.requiredRegularFiles.forEach { path ->
            val file = regular[path] ?: error("Pinned llama.cpp Android archive is missing '$path'")
            if (path != LocalLlamaRuntimeContract.LICENSE_RELATIVE_PATH) {
                require(file.mode == EXECUTABLE_MODE) { "Pinned llama.cpp command '$path' is not executable" }
            }
        }
    }

    private suspend fun targetIdentity(): TargetIdentity {
        val result = bridge.shellBounded(
            "set -eu; printf '%s\\n' \"${'$'}(getprop ro.product.cpu.abi)\" \"${'$'}(getprop ro.build.version.sdk)\"",
            maxOutputBytes = 8_192,
        )
        check(result.exitCode == 0) { "Could not inspect Device Workstation Android identity" }
        val lines = result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.size >= 2) { "Incomplete Device Workstation Android identity" }
        val abi = lines[0]
        val api = lines[1].toIntOrNull() ?: error("Invalid Device Workstation Android API level")
        return TargetIdentity(abi, api)
    }

    private suspend fun healthyStatus(target: TargetIdentity): Status? {
        val lock = readLock() ?: return null
        if (!lock.matchesSource(target)) return null
        val cli = "$treeRoot/${LocalLlamaRuntimeContract.CLI_RELATIVE_PATH}"
        val server = "$treeRoot/${LocalLlamaRuntimeContract.SERVER_RELATIVE_PATH}"
        listOf(cli, server).forEach {
            DeviceBridgeManager.requireSafeRemotePath(it)
            if (!ProcessSecurityPolicy.isAllowedRemoteExecutable(it, DeviceBridgeManager.remoteRoot())) return null
        }
        return runCatching {
            smokeRuntime(treeRoot)
            Status(
                version = lock.version,
                cliPath = cli,
                serverPath = server,
                target = target,
                runtimeSmokeCertified = lock.runtimeSmokeCertified,
                inferenceCertified = false,
            )
        }.getOrNull()
    }

    private suspend fun readLock(): RuntimeLock? {
        DeviceBridgeManager.requireSafeRemotePath(lockPath)
        val result = bridge.shellBounded("test -f ${q(lockPath)} && cat ${q(lockPath)}", maxOutputBytes = 64_000)
        if (result.exitCode != 0 || result.stdout.isBlank()) return null
        return runCatching { json.decodeFromString<RuntimeLock>(result.stdout) }.getOrNull()
    }

    private suspend fun reconcileInterruptedInstall() {
        val safeVersion = LocalLlamaRuntimeContract.VERSION
        val stage = "$remoteBase/.staging/$safeVersion-arm64-v8a"
        val previous = "$remoteBase/.previous/$safeVersion-arm64-v8a"
        listOf(stage, previous, finalRoot).forEach(DeviceBridgeManager::requireSafeRemotePath)
        val flags = bridge.shellBounded(
            "for p in ${q(previous)} ${q(stage)} ${q(finalRoot)}; do if [ -d \"${'$'}p\" ]; then printf '1 '; else printf '0 '; fi; done",
            maxOutputBytes = 8_192,
        )
        check(flags.exitCode == 0) { "Could not reconcile local llama.cpp runtime transaction" }
        val state = flags.stdout.trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
        if (state.size < 3) error("Invalid local llama.cpp runtime transaction state")
        val previousExists = state[0] == 1
        val stageExists = state[1] == 1
        if (!previousExists) {
            if (stageExists) withContext(NonCancellable) { bridge.shell("rm -rf ${q(stage)}") }
            return
        }
        val target = runCatching { targetIdentity() }.getOrNull()
        if (target != null && healthyStatus(target) != null) {
            withContext(NonCancellable) { bridge.shell("rm -rf ${q(previous)} ${q(stage)}") }
        } else {
            withContext(NonCancellable) {
                val restore = bridge.shell("set -eu; rm -rf ${q(finalRoot)}; mv ${q(previous)} ${q(finalRoot)}; rm -rf ${q(stage)}")
                check(restore.exitCode == 0) { "Could not restore previous local llama.cpp runtime" }
            }
        }
    }

    private suspend fun smokeRuntime(root: String) {
        val cli = "$root/${LocalLlamaRuntimeContract.CLI_RELATIVE_PATH}"
        val server = "$root/${LocalLlamaRuntimeContract.SERVER_RELATIVE_PATH}"
        listOf(cli, server).forEach { path ->
            DeviceBridgeManager.requireSafeRemotePath(path)
            require(ProcessSecurityPolicy.isAllowedRemoteExecutable(path, DeviceBridgeManager.remoteRoot())) {
                "Local llama.cpp command escaped Device Workstation"
            }
            verifyArm64Elf(path)
        }
        val result = bridge.shellBounded(
            "set -eu; cd ${q("$root/${LocalLlamaRuntimeContract.ARCHIVE_ROOT}")}; ./llama-cli --version; ./llama-server --version",
            maxOutputBytes = 128_000,
        )
        check(result.exitCode == 0 && !result.truncated) { "Local llama.cpp runtime smoke failed: ${result.combined.takeLast(8_000)}" }
        val text = result.combined.lowercase()
        check("llama" in text || LocalLlamaRuntimeContract.VERSION.removePrefix("b") in text) {
            "Local llama.cpp runtime returned no recognizable version evidence"
        }
    }

    private suspend fun verifyRemoteTree(base: String, root: String, projection: SafeTreeProjection) {
        val manifest = "$base/TREE_SHA256SUMS"
        listOf(base, root, manifest).forEach(DeviceBridgeManager::requireSafeRemotePath)
        val result = bridge.shellBounded(
            "set -eu; cd ${q(root)}; toybox sha256sum -c ${q(manifest)} >/dev/null; " +
                "test \"${'$'}(find . -type f | wc -l | tr -d ' ')\" = ${q(projection.files.size.toString())}; " +
                "test \"${'$'}(find . -type l | wc -l | tr -d ' ')\" = ${q(projection.symlinks.size.toString())}",
            maxOutputBytes = 32_768,
        )
        check(result.exitCode == 0) { "Local llama.cpp runtime tree integrity failed: ${result.combined.takeLast(8_000)}" }
        verifyRemoteSymlinks(root, projection.symlinks)
    }

    private suspend fun createRemoteDirectories(root: String, files: List<SafeTreeFile>) {
        val dirs = files.mapNotNull { it.relativePath.substringBeforeLast('/', "").takeIf(String::isNotBlank) }
            .distinct().sortedWith(compareBy<String> { it.count { ch -> ch == '/' } }.thenBy { it })
        dirs.chunked(40).forEach { chunk ->
            val result = bridge.shellBounded("mkdir -p " + chunk.joinToString(" ") { q("$root/$it") }, maxOutputBytes = 16_384)
            check(result.exitCode == 0) { "Could not create local llama.cpp runtime directories" }
        }
    }

    private suspend fun createRemoteSymlinks(root: String, links: List<SafeTreeSymlink>) {
        links.sortedBy { it.relativePath }.forEach { link ->
            val remote = "$root/${link.relativePath}"
            DeviceBridgeManager.requireSafeRemotePath(remote)
            val parent = remote.substringBeforeLast('/', root)
            val result = bridge.shellBounded(
                "set -eu; mkdir -p ${q(parent)}; rm -f ${q(remote)}; ln -s ${q(link.target)} ${q(remote)}",
                maxOutputBytes = 8_192,
            )
            check(result.exitCode == 0) { "Could not create local llama.cpp runtime symlink" }
        }
    }

    private suspend fun verifyRemoteSymlinks(root: String, links: List<SafeTreeSymlink>) {
        links.chunked(24).forEach { chunk ->
            val checks = chunk.joinToString("; ") { link ->
                val remote = "$root/${link.relativePath}"
                "test -L ${q(remote)}; test \"${'$'}(toybox readlink ${q(remote)})\" = ${q(link.target)}; test -e ${q(remote)}"
            }
            val result = bridge.shellBounded("set -eu; $checks", maxOutputBytes = 16_384)
            check(result.exitCode == 0) { "Local llama.cpp runtime symlink verification failed" }
        }
    }

    private suspend fun verifyArm64Elf(path: String) {
        val result = bridge.shellBounded(
            "set -eu; F=${q(path)}; test -f \"${'$'}F\"; test ! -L \"${'$'}F\"; " +
                "test \"${'$'}(toybox od -An -tx1 -N5 \"${'$'}F\" | tr -d ' \\n')\" = 7f454c4602; " +
                "test \"${'$'}(toybox od -An -tx1 -j18 -N2 \"${'$'}F\" | tr -d ' \\n')\" = b700",
            maxOutputBytes = 16_384,
        )
        check(result.exitCode == 0) { "Local llama.cpp command is not an ELF64 AArch64 executable" }
    }

    private fun artifactSpec() = TrustedArtifactSpec(
        id = "llama-cpp-${LocalLlamaRuntimeContract.VERSION}-android-arm64",
        url = LocalLlamaRuntimeContract.ASSET_URL,
        sha256 = LocalLlamaRuntimeContract.ASSET_SHA256,
        fileName = LocalLlamaRuntimeContract.ASSET_NAME,
        maxBytes = LocalLlamaRuntimeContract.ASSET_MAX_BYTES,
        expectedBytes = LocalLlamaRuntimeContract.ASSET_BYTES,
    )

    private fun q(value: String) = DeviceBridgeManager.shellQuote(value)

    private companion object {
        const val EXECUTABLE_MODE = 493
    }

    @Serializable
    private data class RuntimeLock(
        val schema: Int = 1,
        val runtimeId: String = LocalLlamaRuntimeContract.RUNTIME_ID,
        val version: String,
        val stableRelease: String,
        val upstreamCommit: String,
        val targetAbi: String,
        val targetApi: Int,
        val assetName: String,
        val assetUrl: String,
        val assetSha256: String,
        val assetBytes: Long,
        val provenanceUrl: String,
        val attestationUrl: String,
        val treeFiles: Int,
        val treeSymlinks: Int,
        val treeBytes: Long,
        val treeSha256: String,
        val runtimeSmokeCertified: Boolean,
        val inferenceCertified: Boolean,
        val installedAtEpochMs: Long,
    ) {
        fun matchesSource(target: TargetIdentity): Boolean =
            schema == 1 && runtimeId == LocalLlamaRuntimeContract.RUNTIME_ID &&
                version == LocalLlamaRuntimeContract.VERSION && stableRelease == LocalLlamaRuntimeContract.STABLE_RELEASE &&
                upstreamCommit == LocalLlamaRuntimeContract.UPSTREAM_COMMIT && targetAbi == target.abi && targetApi == target.api &&
                assetName == LocalLlamaRuntimeContract.ASSET_NAME && assetUrl == LocalLlamaRuntimeContract.ASSET_URL &&
                assetSha256 == LocalLlamaRuntimeContract.ASSET_SHA256 && assetBytes == LocalLlamaRuntimeContract.ASSET_BYTES &&
                provenanceUrl == LocalLlamaRuntimeContract.PROVENANCE_URL && attestationUrl == LocalLlamaRuntimeContract.ATTESTATION_URL &&
                treeFiles in 1..LocalLlamaRuntimeContract.MAX_TREE_FILES && treeSymlinks in 0..256 &&
                treeBytes in 1L..LocalLlamaRuntimeContract.MAX_TREE_BYTES && treeSha256.matches(Regex("[0-9a-f]{64}")) &&
                runtimeSmokeCertified && !inferenceCertified
    }
}
