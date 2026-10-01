package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


class AndroidDevelopmentManager(
    private val context: Context,
    private val projectRoot: File,
    private val bridge: DeviceBridgeManager,
) {
    // Cache content identities while preserving a forced full-hash path for build reconciliation.
    private val syncFingerprintCache = WorkspaceSyncFingerprintCache()
    private val artifactCollector = AndroidBuildArtifactCollector(context.cacheDir, bridge)
    private val buildResourceGovernor = MobileBuildResourceGovernor(context)
    private val localToolchainDiscovery = LocalAndroidToolchainDiscovery(context.applicationContext, projectRoot)
    private val localBuildRunner = LocalAndroidBuildRunner(context.applicationContext, projectRoot)
    private val longOperationJournal = LongRunningOperationJournal(
        File(context.applicationContext.filesDir, "operation-journal"),
        stableProjectId(projectRoot),
        android.os.Process.myPid(),
        currentProcessIdentity = LocalExecutionSubstrate.processIdentity(),
    )
    private val gradleWrapperProbe = GradleWrapperRuntimeProbe(bridge)
    private val gradleReadOnlyCacheResolver = GradleReadOnlyDependencyCacheResolver(bridge)
    private val gradleDependencyProbe = GradleDependencyRuntimeProbe(bridge)
     
    private val workstationMutationMutex = Mutex()
    enum class Readiness { READY, BRIDGE_REQUIRED, TOOLCHAIN_REQUIRED, UNSUPPORTED }
    enum class BuildBackend(val label: String) { LOCAL_UBUNTU("Local Ubuntu"), DEVICE_WORKSTATION("Device Workstation") }

    enum class OperationPhase {
        IDLE,
        VERIFYING_PACK,
        UPLOADING,
        VERIFYING_REMOTE,
        ACTIVATING,
        REPAIRING,
        REMOVING,
        BUILDING,
        READY,
        CANCELED,
        FAILED,
    }

    data class OperationState(
        val phase: OperationPhase = OperationPhase.IDLE,
        val message: String = "",
        val completedItems: Int = 0,
        val totalItems: Int = 0,
    ) {
        val progress: Float?
            get() = totalItems.takeIf { it > 0 }?.let { (completedItems.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
        val running: Boolean
            get() = phase in setOf(
                OperationPhase.VERIFYING_PACK,
                OperationPhase.UPLOADING,
                OperationPhase.VERIFYING_REMOTE,
                OperationPhase.ACTIVATING,
                OperationPhase.REPAIRING,
                OperationPhase.REMOVING,
                OperationPhase.BUILDING,
            )
    }

    data class Status(
        val androidProject: Boolean = false,
        val readiness: Readiness = Readiness.BRIDGE_REQUIRED,
        val packageName: String? = null,
        val compileSdk: Int? = null,
        val targetSdk: Int? = null,
        val minSdk: Int? = null,
        val toolchainVersion: String? = null,
        val message: String = "Checking Android development environment…",
        val buildBackend: BuildBackend? = null,
    )

    @Serializable
    data class ToolchainManifest(
        val schema: Int = 2,
        val version: String,
        val abi: String,
        val compileSdk: Int,
        val javaVersion: Int,
        val gradleVersion: String,
        val aapt2Path: String,
        val javaHome: String,
        val sdkRoot: String,
        val executablePaths: List<String> = emptyList(),
        val executableFile: String? = null,
        val symlinkFile: String? = null,
        val checksumFile: String = "SHA256SUMS",
        val runtime: AndroidToolchainRuntimeSpec = AndroidToolchainRuntimeSpec(),
        val candidateId: String? = null,
        val sourceLockSha256: String? = null,
        val buildRecipeVersion: String? = null,
        val sdkLicenseSha256: String? = null,
        val supportedCompileSdks: List<Int> = emptyList(),
        val buildToolsVersions: List<String> = emptyList(),
        val ndkVersions: List<String> = emptyList(),
        val cmakeVersions: List<String> = emptyList(),
    ) {
        val compileSdks: Set<Int> get() = (supportedCompileSdks + compileSdk).toSet()
    }

    @Serializable
    data class ToolchainInstallReceipt(
        val schema: Int = 1,
        val packSha256: String,
        val installedAtEpochMs: Long,
        val manifestVersion: String,
        val candidateId: String? = null,
        val sourceLockSha256: String? = null,
        val executionMode: AndroidToolchainExecutionMode,
    )

    data class BuildResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val localArtifacts: List<File>,
        val durationMs: Long,
        val diagnostics: List<BuildDiagnostic>,
    )

    data class DebugLaunchResult(
        val packageName: String,
        val pid: Int,
        val message: String,
    )

    data class RemoteStorageUsage(
        val freeBytes: Long,
        val toolchainsBytes: Long,
        val gradleCacheBytes: Long,
        val workspacesBytes: Long,
    )

    data class WorkstationInfo(
        val version: String,
        val abi: String,
        val javaVersion: Int,
        val compileSdk: Int,
        val sdkPlatforms: Set<Int>,
        val buildToolsVersions: Set<String>,
        val ndkVersions: Set<String>,
        val cmakeVersions: Set<String>,
        val javaHome: String,
        val sdkRoot: String,
        val aapt2Path: String,
        val executionMode: AndroidToolchainExecutionMode,
        val candidateId: String? = null,
        val sourceLockSha256: String? = null,
        val packSha256: String? = null,
    )

    data class InteractiveShellConfig(
        val remoteWorkspace: String,
        val environment: Map<String, String>,
        val pathEntries: List<String>,
    ) {
        fun shellPrefix(): String = buildString {
            append("set -eu; ")
            environment.forEach { (key, value) ->
                require(key.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) { "Invalid environment key" }
                append("export ").append(key).append('=').append(DeviceBridgeManager.shellQuote(value)).append("; ")
            }
            append("cd ").append(DeviceBridgeManager.shellQuote(remoteWorkspace)).append("; ")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _operation = MutableStateFlow(OperationState())
    val operation: StateFlow<OperationState> = _operation.asStateFlow()
    private val externalToolchainResolver = ExternalAndroidToolchainResolver(bridge)
    private val toolchainCapabilityProbe = AndroidToolchainCapabilityProbe(bridge)
    private val toolchainSelector = AndroidToolchainSelector(
        ::managedToolchainManifest,
        { externalToolchainResolver.read()?.manifest },
        ::verifyRemoteToolchain,
        ::verifyRemoteToolchainRequirements,
        userCandidatesProvider = { externalToolchainResolver.readAll().map { it.manifest } },
    )
    private val projectToolchainAuthority = AndroidProjectToolchainAuthority(projectRoot, toolchainSelector, ::verifyRemoteToolchainRequirements)
    private val runtimeTemporaryRoot = File(context.applicationContext.filesDir, "runtime-tmp")
    private val localApkInspector = LocalAndroidApkInspector(projectRoot, runtimeTemporaryRoot)
    private val appRuntime = AndroidAppRuntimeController(
        bridge = bridge,
        manifestProvider = { projectToolchainAuthority.resolve().resolved?.manifest },
        snapshots = AndroidRuntimeApkSnapshot(runtimeTemporaryRoot),
        localPackageInspector = localApkInspector::inspect,
    )

    suspend fun remoteStorageUsage(): RemoteStorageUsage = withContext(Dispatchers.IO) {
        check(bridge.state.value.connected != null) { "Device bridge is not connected" }
        val root = DeviceBridgeManager.remoteRoot()
        val prepare = bridge.shell("mkdir -p ${DeviceBridgeManager.shellQuote(root)}")
        check(prepare.exitCode == 0) { prepare.combined }
        val df = bridge.shell("df -Pk ${DeviceBridgeManager.shellQuote(root)} | tail -1")
        check(df.exitCode == 0) { "Could not read device storage: ${df.combined}" }
        val fields = df.stdout.trim().split(Regex("\\s+"))
        require(fields.size >= 4) { "Unexpected device storage report" }
        val freeBytes = fields[3].toLongOrNull()?.times(1024L) ?: error("Invalid free-space value")
        suspend fun directoryBytes(path: String): Long {
            DeviceBridgeManager.requireSafeRemotePath(path)
            val result = bridge.shell("du -sk ${DeviceBridgeManager.shellQuote(path)} 2>/dev/null | head -1")
            if (result.exitCode != 0 || result.stdout.isBlank()) return 0L
            return result.stdout.trim().split(Regex("\\s+"), limit = 2).firstOrNull()?.toLongOrNull()?.times(1024L) ?: 0L
        }
        RemoteStorageUsage(
            freeBytes = freeBytes,
            toolchainsBytes = directoryBytes("$root/toolchains"),
            gradleCacheBytes = directoryBytes("$root/gradle-cache"),
            workspacesBytes = directoryBytes("$root/workspaces"),
        )
    }

    suspend fun clearRemoteBuildCaches(includeGradleCache: Boolean = true): String = workstationMutationMutex.withLock {
        withContext(Dispatchers.IO) {
        check(bridge.state.value.connected != null) { "Device bridge is not connected" }
        val root = DeviceBridgeManager.remoteRoot()
        val workspaces = "$root/workspaces"
        val gradleCache = "$root/gradle-cache"
        listOf(workspaces, gradleCache).forEach(DeviceBridgeManager::requireSafeRemotePath)
        val command = buildString {
            append("rm -rf ").append(DeviceBridgeManager.shellQuote(workspaces)).append("; mkdir -p ").append(DeviceBridgeManager.shellQuote(workspaces))
            if (includeGradleCache) {
                append("; rm -rf ").append(DeviceBridgeManager.shellQuote(gradleCache)).append("; mkdir -p ").append(DeviceBridgeManager.shellQuote(gradleCache))
            }
        }
        val result = bridge.shell(command)
        check(result.exitCode == 0) { "Could not clear remote build caches: ${result.combined}" }
        if (includeGradleCache) "Remote workspace and Gradle cache cleared."
        else "Remote workspace cache cleared."
        }
    }

    suspend fun refresh(): Status = try {
        refreshInternal()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Status(
            androidProject = false,
            readiness = Readiness.UNSUPPORTED,
            message = "Android setup check failed: ${error.message?.take(160) ?: error::class.java.simpleName}",
        ).also { _status.value = it }
    }

    private suspend fun refreshInternal(): Status = withContext(Dispatchers.IO) {
        val interrupted = longOperationJournal.reconcileInterrupted()
        if (interrupted.any { it.kind == LongRunningOperationJournal.Kind.BUILD }) {
            _operation.value = OperationState(
                OperationPhase.FAILED,
                "Previous Gradle build was interrupted when the Android process ended; no build is being claimed as still running.",
            )
        }
        val model = AndroidProjectDetector.detect(projectRoot)
        if (model == null) {
            return@withContext Status(androidProject = false, readiness = Readiness.UNSUPPORTED, message = "Not an Android Gradle project.")
                .also { _status.value = it }
        }
        val requirements = model.toolchainRequirements()
        val local = localToolchainDiscovery.resolve(requirements, model.androidGradlePluginVersions)
        local.snapshot?.let { snapshot ->
            return@withContext Status(
                androidProject = true,
                readiness = Readiness.READY,
                packageName = model.packageName,
                compileSdk = model.compileSdk,
                targetSdk = model.targetSdk,
                minSdk = model.minSdk,
                toolchainVersion = snapshot.versionLabel,
                message = "Ready · Local Ubuntu · JDK ${snapshot.javaVersion} · SDK ${snapshot.compileSdks.sorted().joinToString(",")}",
                buildBackend = BuildBackend.LOCAL_UBUNTU,
            ).also { _status.value = it }
        }

        val remoteUnavailable = when {
            Build.VERSION.SDK_INT < 30 -> "Device Workstation requires Android 11+ Wireless Debugging."
            bridge.state.value.connected == null -> "Device Workstation is not connected."
            else -> null
        }
        if (remoteUnavailable != null) {
            return@withContext Status(
                androidProject = true,
                readiness = Readiness.TOOLCHAIN_REQUIRED,
                packageName = model.packageName,
                compileSdk = model.compileSdk,
                targetSdk = model.targetSdk,
                minSdk = model.minSdk,
                message = "Local Ubuntu: ${local.failure ?: "Android toolchain unavailable"} $remoteUnavailable",
            ).also { _status.value = it }
        }

        val resolution = projectToolchainAuthority.resolve(requirements)
        val manifest = resolution.resolved?.manifest
        val next = if (manifest == null) {
            Status(
                androidProject = true, readiness = Readiness.TOOLCHAIN_REQUIRED, packageName = model.packageName,
                compileSdk = model.compileSdk, targetSdk = model.targetSdk, minSdk = model.minSdk,
                message = "Local Ubuntu: ${local.failure ?: "unavailable"}. Device Workstation: ${resolution.failure ?: "install or select a compatible Android toolchain"}.",
            )
        } else {
            Status(
                androidProject = true, readiness = Readiness.READY, packageName = model.packageName,
                compileSdk = model.compileSdk, targetSdk = model.targetSdk, minSdk = model.minSdk,
                toolchainVersion = manifest.version,
                message = "Ready · Device Workstation · JDK ${manifest.javaVersion} · SDK ${manifest.compileSdks.sorted().joinToString(",")}",
                buildBackend = BuildBackend.DEVICE_WORKSTATION,
            )
        }
        _status.value = next
        next
    }


    suspend fun provision(pack: File, expectedSha256: String): String = workstationMutationMutex.withLock {
        _operation.value = OperationState(OperationPhase.VERIFYING_PACK, "Verifying Android toolchain pack…")
        try {
            provisionInternal(pack, expectedSha256).also {
                _operation.value = OperationState(OperationPhase.READY, it)
            }
        } catch (cancelled: CancellationException) {
            _operation.value = OperationState(OperationPhase.CANCELED, "Android toolchain installation canceled.")
            throw cancelled
        } catch (error: Throwable) {
            _operation.value = OperationState(OperationPhase.FAILED, error.message ?: "Android toolchain installation failed")
            throw error
        }
    }

    private suspend fun provisionInternal(pack: File, expectedSha256: String): String = withContext(Dispatchers.IO) {
        val verifiedPack = VerifiedToolchainPack.create(context.cacheDir, pack, expectedSha256, MAX_TOOLCHAIN_ARCHIVE_BYTES)
        val actual = verifiedPack.sha256
        var provisionFailure: Throwable? = null
        try {
        ZipFile(verifiedPack.file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 2..MAX_TOOLCHAIN_ENTRIES) { "Invalid toolchain entry count: ${entries.size}" }
            var total = 0L
            entries.forEach { entry ->
                validatePackPath(entry.name)
                require(entry.size in -1L..MAX_TOOLCHAIN_FILE_BYTES) { "Toolchain entry too large: ${entry.name}" }
                if (entry.size > 0) {
                    total += entry.size
                    require(total <= MAX_TOOLCHAIN_UNCOMPRESSED_BYTES) { "Toolchain pack is too large" }
                }
            }

            ensureRemoteFreeSpace(total + TOOLCHAIN_FREE_SPACE_RESERVE_BYTES, "install the Android toolchain")

            val manifestEntry = zip.getEntry("toolchain.json") ?: error("toolchain.json is missing")
            val manifestText = readBoundedZipText(zip, manifestEntry, MAX_TOOLCHAIN_MANIFEST_BYTES, "toolchain.json")
            val manifest = json.decodeFromString<ToolchainManifest>(manifestText)
            validateManifest(manifest)
            val checksumEntry = zip.getEntry(manifest.checksumFile) ?: error("${manifest.checksumFile} is missing")
            validatePackPath(manifest.checksumFile)
            val checksumText = readBoundedZipText(zip, checksumEntry, MAX_CHECKSUM_MANIFEST_BYTES, manifest.checksumFile)
            val expectedHashes = parseChecksumManifest(checksumText, manifest.checksumFile)
            val payloadEntries = entries.filterNot { it.isDirectory || it.name == manifest.checksumFile }
            val payloadPaths = payloadEntries.map { it.name }.toSet()
            val executablePaths = effectiveExecutablePaths(zip, manifest, payloadPaths)
            val symlinks = effectiveSymlinks(zip, manifest, payloadPaths)
            _operation.value = OperationState(
                OperationPhase.VERIFYING_PACK,
                "Verifying ${payloadEntries.size} toolchain files…",
                totalItems = payloadEntries.size,
            )
            require(expectedHashes.keys == payloadPaths) { "Toolchain checksum manifest does not exactly match payload entries" }
            payloadEntries.forEachIndexed { index, entry ->
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).buffered().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                val actualEntry = digest.digest().joinToString("") { "%02x".format(it) }
                check(actualEntry == expectedHashes.getValue(entry.name)) { "Toolchain payload hash mismatch: ${entry.name}" }
                _operation.value = OperationState(
                    OperationPhase.VERIFYING_PACK,
                    "Verifying toolchain pack…",
                    completedItems = index + 1,
                    totalItems = payloadEntries.size,
                )
            }

            val root = DeviceBridgeManager.remoteRoot()
            val stage = "$root/toolchains/.staging"
            val prep = bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(stage)} && mkdir -p ${DeviceBridgeManager.shellQuote(stage)}")
            check(prep.exitCode == 0) { prep.combined }

            try {
                val uploadEntries = entries.filterNot { it.isDirectory }
                _operation.value = OperationState(OperationPhase.UPLOADING, "Installing toolchain on this device…", totalItems = uploadEntries.size)
                uploadEntries.forEachIndexed { index, entry ->
                    val remote = "$stage/${entry.name}"
                    DeviceBridgeManager.requireSafeRemotePath(remote)
                    val parent = remote.substringBeforeLast('/', stage)
                    val mk = bridge.shell("mkdir -p ${DeviceBridgeManager.shellQuote(parent)}")
                    check(mk.exitCode == 0) { mk.combined }
                    val executable = entry.name in executablePaths
                    zip.getInputStream(entry).use { input ->
                        bridge.pushStream(input, remote, mode = if (executable) 493 else 420)
                    }
                    _operation.value = OperationState(
                        OperationPhase.UPLOADING,
                        "Installing toolchain on this device…",
                        completedItems = index + 1,
                        totalItems = uploadEntries.size,
                    )
                }

                _operation.value = OperationState(OperationPhase.VERIFYING_REMOTE, "Verifying installed toolchain files…")
                val verify = bridge.shell(
                    "cd ${DeviceBridgeManager.shellQuote(stage)} && toybox sha256sum -c ${DeviceBridgeManager.shellQuote(manifest.checksumFile)}"
                )
                check(verify.exitCode == 0) { "Toolchain file verification failed: ${verify.combined}" }

                if (symlinks.isNotEmpty()) {
                    _operation.value = OperationState(OperationPhase.VERIFYING_REMOTE, "Restoring verified toolchain links…", totalItems = symlinks.size)
                    createRemoteSymlinks(stage, symlinks)
                }

                val toolchains = "$root/toolchains"
                _operation.value = OperationState(OperationPhase.ACTIVATING, "Activating verified toolchain…")
                val promote = bridge.shell(
                    "set -eu; " +
                        "rm -rf ${DeviceBridgeManager.shellQuote("$toolchains/previous")}; " +
                        "if [ -d ${DeviceBridgeManager.shellQuote("$toolchains/current")} ]; then " +
                        "mv ${DeviceBridgeManager.shellQuote("$toolchains/current")} ${DeviceBridgeManager.shellQuote("$toolchains/previous")}; fi; " +
                        "mv ${DeviceBridgeManager.shellQuote("$toolchains/.staging")} ${DeviceBridgeManager.shellQuote("$toolchains/current")}"
                )
                check(promote.exitCode == 0) { "Toolchain activation failed: ${promote.combined}" }
            } catch (t: Throwable) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    runSuspendCatching { bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(stage)}") }
                }
                throw t
            }
        }

        val manifest = managedToolchainManifest() ?: run {
            rollbackProvisionedToolchain()
            error("Toolchain manifest missing after install")
        }
        val model = AndroidProjectDetector.detect(projectRoot)
        val verificationError = verifyRemoteToolchainRequirements(manifest, model?.toolchainRequirements() ?: AndroidToolchainRequirements())
        if (verificationError != null) {
            rollbackProvisionedToolchain()
            error("Toolchain verification failed: $verificationError")
        }
        try {
            writeInstallReceipt(
                ToolchainInstallReceipt(
                    packSha256 = actual.lowercase(),
                    installedAtEpochMs = System.currentTimeMillis(),
                    manifestVersion = manifest.version,
                    candidateId = manifest.candidateId,
                    sourceLockSha256 = manifest.sourceLockSha256,
                    executionMode = manifest.runtime.mode,
                )
            )
        } catch (error: Throwable) {
            rollbackProvisionedToolchain()
            throw IllegalStateException("Toolchain was healthy but its install receipt could not be committed", error)
        }
        refresh()
        "Installed and verified Android toolchain ${manifest.version}"
        } catch (failure: Throwable) {
            provisionFailure = failure
            throw failure
        } finally {
            val cleanupFailure = runCatching { verifiedPack.close() }.exceptionOrNull()
            if (cleanupFailure != null) {
                if (provisionFailure != null) provisionFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    suspend fun repairToolchain(): String = workstationMutationMutex.withLock {
        _operation.value = OperationState(OperationPhase.REPAIRING, "Repairing Android toolchain…")
        val root = DeviceBridgeManager.remoteRoot() + "/toolchains"
        val currentPath = "$root/current"
        val previousPath = "$root/previous"
        val failedPath = "$root/.repair-failed"
        listOf(currentPath, previousPath, failedPath).forEach(DeviceBridgeManager::requireSafeRemotePath)
        var swapped = false
        suspend fun restoreOriginal() {
            val restore = bridge.shell(
                "set -eu; " +
                    "rm -rf ${DeviceBridgeManager.shellQuote(previousPath)}; " +
                    "if [ -d ${DeviceBridgeManager.shellQuote(currentPath)} ]; then mv ${DeviceBridgeManager.shellQuote(currentPath)} ${DeviceBridgeManager.shellQuote(previousPath)}; fi; " +
                    "if [ -d ${DeviceBridgeManager.shellQuote(failedPath)} ]; then mv ${DeviceBridgeManager.shellQuote(failedPath)} ${DeviceBridgeManager.shellQuote(currentPath)}; fi"
            )
            check(restore.exitCode == 0) { "Toolchain repair rollback failed: ${restore.combined}" }
            swapped = false
        }
        return try {
            withContext(Dispatchers.IO) {
                check(bridge.state.value.connected != null) { "Device bridge is not connected" }
                val model = AndroidProjectDetector.detect(projectRoot)
                val current = managedToolchainManifest()
                if (current != null && verifyRemoteToolchainRequirements(current, model?.toolchainRequirements() ?: AndroidToolchainRequirements()) == null) {
                    refresh()
                    return@withContext "Android toolchain ${current.version} is already healthy."
                }
                val swap = bridge.shell(
                    "set -eu; " +
                        "test -d ${DeviceBridgeManager.shellQuote(previousPath)}; " +
                        "rm -rf ${DeviceBridgeManager.shellQuote(failedPath)}; " +
                        "if [ -d ${DeviceBridgeManager.shellQuote(currentPath)} ]; then mv ${DeviceBridgeManager.shellQuote(currentPath)} ${DeviceBridgeManager.shellQuote(failedPath)}; fi; " +
                        "mv ${DeviceBridgeManager.shellQuote(previousPath)} ${DeviceBridgeManager.shellQuote(currentPath)}"
                )
                check(swap.exitCode == 0) { "No previous toolchain is available for repair: ${swap.combined}" }
                swapped = true
                val candidate = managedToolchainManifest()
                val verificationError = candidate?.let { verifyRemoteToolchainRequirements(it, model?.toolchainRequirements() ?: AndroidToolchainRequirements()) }
                    ?: "Recovered toolchain manifest is missing"
                if (verificationError != null) {
                    withContext(kotlinx.coroutines.NonCancellable) { restoreOriginal() }
                    error("Previous toolchain failed verification: $verificationError")
                }
                val cleanup = bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(failedPath)}")
                check(cleanup.exitCode == 0) { "Recovered toolchain is healthy but cleanup failed: ${cleanup.combined}" }
                swapped = false
                refresh()
                "Recovered Android toolchain ${requireNotNull(candidate).version}."
            }.also { _operation.value = OperationState(OperationPhase.READY, it) }
        } catch (cancelled: CancellationException) {
            if (swapped) withContext(kotlinx.coroutines.NonCancellable) { restoreOriginal() }
            _operation.value = OperationState(OperationPhase.CANCELED, "Android toolchain repair canceled; original toolchain restored.")
            throw cancelled
        } catch (error: Throwable) {
            if (swapped) {
                runSuspendCatching { withContext(kotlinx.coroutines.NonCancellable) { restoreOriginal() } }
            }
            _operation.value = OperationState(OperationPhase.FAILED, error.message ?: "Android toolchain repair failed")
            throw error
        }
    }

    suspend fun removeToolchain(): String = workstationMutationMutex.withLock {
        _operation.value = OperationState(OperationPhase.REMOVING, "Removing Android toolchain…")
        try {
            withContext(Dispatchers.IO) {
                check(bridge.state.value.connected != null) { "Device bridge is not connected" }
                val root = DeviceBridgeManager.remoteRoot() + "/toolchains"
                DeviceBridgeManager.requireSafeRemotePath(root)
                val result = bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(root)} && mkdir -p ${DeviceBridgeManager.shellQuote(root)}")
                check(result.exitCode == 0) { "Could not remove Android toolchain: ${result.combined}" }
                refresh()
                "Android toolchain removed. Project files were not touched."
            }.also { _operation.value = OperationState(OperationPhase.IDLE, it) }
        } catch (cancelled: CancellationException) {
            _operation.value = OperationState(OperationPhase.CANCELED, "Android toolchain removal canceled.")
            throw cancelled
        } catch (error: Throwable) {
            _operation.value = OperationState(OperationPhase.FAILED, error.message ?: "Android toolchain removal failed")
            throw error
        }
    }


    suspend fun workstationInfo(): WorkstationInfo? = withContext(Dispatchers.IO) {
        if (bridge.state.value.connected == null) return@withContext null
        val resolved = projectToolchainAuthority.resolve().resolved ?: return@withContext null
        val manifest = resolved.manifest
        val platformRoot = manifest.sdkRoot.trimEnd('/') + "/platforms"
        DeviceBridgeManager.requireSafeRemotePath(platformRoot)
        val listed = bridge.shellBounded(
            "find ${DeviceBridgeManager.shellQuote(platformRoot)} -maxdepth 1 -type d -name 'android-*' 2>/dev/null | sed 's#.*/android-##'",
            maxOutputBytes = 128 * 1024,
        )
        require(!listed.truncated) { "Android platform listing exceeded the safety limit" }
        val platforms = if (listed.exitCode == 0) {
            listed.stdout.lineSequence().mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..999 }.toSet()
        } else emptySet()
        suspend fun versionDirs(relative: String): Set<String> {
            val root = manifest.sdkRoot.trimEnd('/') + "/" + relative
            DeviceBridgeManager.requireSafeRemotePath(root)
            val result = bridge.shellBounded(
                "find ${DeviceBridgeManager.shellQuote(root)} -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sed 's#.*/##' || true",
                maxOutputBytes = 128 * 1024,
            )
            require(!result.truncated) { "Android component version listing exceeded the safety limit" }
            return result.stdout.lineSequence().map(String::trim)
                .filter { it.matches(Regex("[0-9][A-Za-z0-9+_.-]{0,79}")) }.take(128).toSet()
        }
        val buildTools = versionDirs("build-tools")
        val ndks = versionDirs("ndk")
        val cmakes = versionDirs("cmake")
        val receipt = if (resolved.source == AndroidToolchainSelector.Source.MANAGED) installedToolchainReceipt() else null
        WorkstationInfo(
            version = manifest.version,
            abi = manifest.abi,
            javaVersion = manifest.javaVersion,
            compileSdk = manifest.compileSdk,
            sdkPlatforms = platforms,
            buildToolsVersions = buildTools,
            ndkVersions = ndks,
            cmakeVersions = cmakes,
            javaHome = manifest.javaHome,
            sdkRoot = manifest.sdkRoot,
            aapt2Path = manifest.aapt2Path,
            executionMode = manifest.runtime.mode,
            candidateId = manifest.candidateId,
            sourceLockSha256 = manifest.sourceLockSha256,
            packSha256 = receipt?.packSha256,
        )
    }


    suspend fun installedToolchainReceipt(): ToolchainInstallReceipt? = withContext(Dispatchers.IO) {
        check(bridge.state.value.connected != null) { "Device bridge is not connected" }
        val path = DeviceBridgeManager.remoteRoot() + "/toolchains/current/.droide-install.json"
        DeviceBridgeManager.requireSafeRemotePath(path)
        val result = bridge.shellBounded(
            "test -f ${DeviceBridgeManager.shellQuote(path)} && cat ${DeviceBridgeManager.shellQuote(path)} || true",
            maxOutputBytes = MAX_INSTALL_RECEIPT_BYTES,
        )
        if (result.exitCode != 0 || result.stdout.isBlank()) return@withContext null
        require(!result.truncated) { "Toolchain install receipt is too large" }
        require(result.stdout.toByteArray(Charsets.UTF_8).size <= MAX_INSTALL_RECEIPT_BYTES) { "Toolchain install receipt is too large" }
        runCatching { json.decodeFromString<ToolchainInstallReceipt>(result.stdout) }.getOrNull()?.also { validateInstallReceipt(it) }
    }

    suspend fun installedToolchainManifest(): ToolchainManifest? = withContext(Dispatchers.IO) { managedToolchainManifest() }
    suspend fun prepareInteractiveShell(syncProject: Boolean = true): InteractiveShellConfig =
        if (syncProject) workstationMutationMutex.withLock { prepareInteractiveShellInternal(syncProject = true) }
        else prepareInteractiveShellInternal(syncProject = false)

    private suspend fun prepareInteractiveShellInternal(syncProject: Boolean): InteractiveShellConfig = withContext(Dispatchers.IO) {
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        val root = DeviceBridgeManager.remoteRoot()
        val remoteWorkspace = "$root/workspaces/${stableProjectId(projectRoot)}"
        val home = "$root/home"
        val gradleHome = "$root/gradle-cache"
        val tempDir = "$root/tmp"
        val userBins = listOf("$root/user/bin", "$home/.local/bin", "$home/bin", "$root/user/local/bin")
        val managedBin = "$root/managed/bin"
        (listOf(home, gradleHome, tempDir, managedBin, remoteWorkspace) + userBins).forEach(DeviceBridgeManager::requireSafeRemotePath)

        val manifest = projectToolchainAuthority.resolve().resolved?.manifest
        if (syncProject) syncWorkspace(remoteWorkspace, manifest, forceContentHash = true)
        else {
            val prepareWorkspace = bridge.shell("mkdir -p ${DeviceBridgeManager.shellQuote(remoteWorkspace)}")
            check(prepareWorkspace.exitCode == 0) { prepareWorkspace.combined }
        }

        
        val pathEntries = userBins.toMutableList()
        val environment = linkedMapOf(
            "HOME" to home,
            "GRADLE_USER_HOME" to gradleHome,
            "TMPDIR" to tempDir,
            "DROIDE_WORKSPACE" to remoteWorkspace,
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "en_US.UTF-8",
        )


        val packageRegistry = ManagedPackageRegistry(context)
        runSuspendCatching { ManagedPackageInstaller(context, bridge, packageRegistry).reconcile() }
        val managedRecords = packageRegistry.list()
            .filter { it.scope == ExecutionScope.LOCAL_LINUX_ARM64.name }
        val workspaceToolchains = WorkspaceToolchainPreferences(context, projectRoot)
        workspaceToolchains.prune(managedRecords.map { it.familyId to it.version }.toSet())
        val selections = workspaceToolchains.selections()

        suspend fun applyManagedRecord(record: ManagedPackageRecord, overrideEnvironment: Boolean) {
            if (!record.installRoot.startsWith("$root/")) return
            if (runCatching { DeviceBridgeManager.requireSafeRemotePath(record.installRoot) }.isFailure) return
            val present = bridge.shell("test -d ${DeviceBridgeManager.shellQuote(record.installRoot)}")
            if (present.exitCode != 0) {
                return
            }
            val candidatePaths = record.pathEntries + record.commands.values.mapNotNull {
                it.substringBeforeLast('/', missingDelimiterValue = "").takeIf(String::isNotBlank)
            }
            candidatePaths.forEach { entry ->
                if (entry.startsWith(record.installRoot + "/") && runCatching { DeviceBridgeManager.requireSafeRemotePath(entry) }.isSuccess) {
                    pathEntries += entry
                }
            }
            record.environment.forEach { (key, value) ->
                if (value.startsWith(record.installRoot) && runCatching { DeviceBridgeManager.requireSafeRemotePath(value) }.isSuccess) {
                    if (overrideEnvironment) environment[key] = value else environment.putIfAbsent(key, value)
                }
            }
        }

        selections.forEach { (familyId, version) ->
            managedRecords.firstOrNull { it.familyId == familyId && it.version == version }?.let {
                applyManagedRecord(it, overrideEnvironment = true)
            }
        }
        pathEntries += managedBin
        managedRecords.filter { it.active && it.familyId !in selections.keys }.forEach {
            applyManagedRecord(it, overrideEnvironment = false)
        }

        if (manifest != null) {
            val buildToolsDir = manifest.aapt2Path.substringBeforeLast('/', missingDelimiterValue = manifest.sdkRoot)
            listOf(manifest.javaHome, manifest.sdkRoot, buildToolsDir).forEach(DeviceBridgeManager::requireSafeRemotePath)
            val compatibility = AndroidToolchainRuntime.isCompatibility(manifest.runtime)
            val extraExecutableDirs = manifest.executablePaths.mapNotNull { executable ->
                runCatching {
                    DeviceBridgeManager.requireSafeRemotePath(executable)
                    executable.substringBeforeLast('/', missingDelimiterValue = "")
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            pathEntries += manifest.javaHome.trimEnd('/') + "/bin"
            if (compatibility) {
                

                pathEntries += AndroidToolchainRuntime.additionalInteractivePathEntries(manifest.runtime)
            } else {
                pathEntries += buildToolsDir
                pathEntries += manifest.sdkRoot.trimEnd('/') + "/platform-tools"
                pathEntries += extraExecutableDirs
            }


            environment.putIfAbsent("JAVA_HOME", manifest.javaHome)
            environment.putIfAbsent("ANDROID_HOME", manifest.sdkRoot)
            environment.putIfAbsent("ANDROID_SDK_ROOT", manifest.sdkRoot)
            AndroidToolchainRuntime.additionalEnvironment(manifest.runtime).forEach { (key, value) ->
                environment.putIfAbsent(key, value)
            }
        }
        pathEntries += listOf("/system/bin", "/system/xbin")
        val normalizedPath = pathEntries.distinct()
        environment["PATH"] = normalizedPath.joinToString(":")

        val preparePaths = listOf(home, gradleHome, tempDir, managedBin) + userBins
        val prepare = bridge.shell("mkdir -p " + preparePaths.joinToString(" ", transform = DeviceBridgeManager::shellQuote))
        check(prepare.exitCode == 0) { prepare.combined }
        InteractiveShellConfig(
            remoteWorkspace = remoteWorkspace,
            environment = environment,
            pathEntries = normalizedPath,
        )
    }

    suspend fun buildDebug(): BuildResult = build("assembleDebug")
    suspend fun buildReleaseApk(): BuildResult = build("assembleRelease")
    suspend fun buildReleaseBundle(): BuildResult = build("bundleRelease")
    suspend fun test(): BuildResult = build("test")
    suspend fun lint(): BuildResult = build("lintDebug")

    suspend fun build(task: String, requiredBackend: BuildBackend? = null): BuildResult = workstationMutationMutex.withLock {
        withContext(Dispatchers.IO) {
            GradleTaskPath.parse(task)
            val model = AndroidProjectDetector.detect(projectRoot) ?: error("Android project model disappeared")
            val operationLease = longOperationJournal.begin(LongRunningOperationJournal.Kind.BUILD, "Gradle $task")
            try {
                var localFailure: String? = null
                val result = AndroidBuildRouting.execute(
                    requiredBackend = requiredBackend,
                    resolveLocal = {
                        localToolchainDiscovery.resolve(model.toolchainRequirements(), model.androidGradlePluginVersions)
                            .also { localFailure = it.failure }.snapshot
                    },
                    runLocal = { snapshot ->
                        _status.value = Status(
                            androidProject = true,
                            readiness = Readiness.READY,
                            packageName = model.packageName,
                            compileSdk = model.compileSdk,
                            targetSdk = model.targetSdk,
                            minSdk = model.minSdk,
                            toolchainVersion = snapshot.versionLabel,
                            message = "Ready · Local Ubuntu · JDK ${snapshot.javaVersion} · SDK ${snapshot.compileSdks.sorted().joinToString(",")}",
                            buildBackend = BuildBackend.LOCAL_UBUNTU,
                        )
                        val localResult = localBuildRunner.build(task, snapshot) { message ->
                            _operation.value = OperationState(OperationPhase.BUILDING, message)
                        }
                        BuildResult(
                            success = localResult.success,
                            exitCode = localResult.exitCode,
                            output = localResult.output,
                            localArtifacts = localResult.artifacts,
                            durationMs = localResult.durationMs,
                            diagnostics = localResult.diagnostics,
                        )
                    },
                    runDevice = {
                        val localDetail = if (requiredBackend == BuildBackend.DEVICE_WORKSTATION) ""
                            else "Local Ubuntu: ${localFailure ?: "Android toolchain unavailable"}. "
                        check(Build.VERSION.SDK_INT >= 30) {
                            "${localDetail}Device Workstation requires Android 11+."
                        }
                        check(bridge.state.value.connected != null) {
                            "${localDetail}Device Workstation is not connected."
                        }
                        buildRemoteLocked(task, model)
                    },
                )
                if (result.success) operationLease.complete("Gradle $task completed")
                else operationLease.fail("Gradle $task exited ${result.exitCode}")
                _operation.value = OperationState(
                    if (result.success) OperationPhase.READY else OperationPhase.FAILED,
                    if (result.success) "Gradle task $task completed." else "Gradle task $task failed (exit ${result.exitCode}).",
                )
                result
            } catch (cancelled: CancellationException) {
                operationLease.cancel("Gradle $task canceled")
                _operation.value = OperationState(OperationPhase.CANCELED, "Gradle operation $task canceled.")
                throw cancelled
            } catch (error: Throwable) {
                operationLease.fail((error.message ?: "Gradle operation failed").take(300))
                _operation.value = OperationState(OperationPhase.FAILED, error.message ?: "Gradle operation failed")
                throw error
            }
        }
    }

    private suspend fun buildRemoteLocked(task: String, projectModel: AndroidProjectModel): BuildResult {
        val remoteWorkspace = "${DeviceBridgeManager.remoteRoot()}/workspaces/${stableProjectId(projectRoot)}"
        return AndroidEphemeralBuildInputs.withSession(projectRoot, bridge, remoteWorkspace) { sensitiveInputs ->
            var activeBuildLease: RemoteProcessLease? = null
            try {
                val wrapperDescriptor = GradleWrapperInspector.inspect(projectRoot)
                bridge.ensureHealthyConnection()
                ensureRemoteFreeSpace(MIN_BUILD_FREE_SPACE_BYTES, "build this project")
                val requirements = projectModel.toolchainRequirements()
                val gradleConfigurationFingerprint = GradleProjectConfigurationFingerprint.compute(projectRoot)
                val candidates = toolchainSelector.resolveAll(requirements)
                val selection = AndroidBuildToolchainChooser.select(candidates, onAttempt = { candidate ->
                    _operation.value = OperationState(
                        OperationPhase.BUILDING,
                        "Device Workstation · checking ${candidate.source.label} ${candidate.manifest.version} · JDK ${candidate.manifest.javaVersion}…",
                    )
                }) { candidate ->
                    val bound = AndroidToolchainProjectBinding.bind(candidate.manifest, requirements)
                    syncWorkspace(remoteWorkspace, bound, forceContentHash = true)
                    sensitiveInputs.push()
                    val version = gradleWrapperProbe.ensureReady(remoteWorkspace, bound, wrapperDescriptor)
                    val cache = gradleReadOnlyCacheResolver.resolve(version)
                    _operation.value = OperationState(
                        OperationPhase.BUILDING,
                        "Device Workstation · resolving Gradle project with ${candidate.source.label} ${candidate.manifest.version}…",
                    )
                    gradleDependencyProbe.ensureReady(remoteWorkspace, bound, wrapperDescriptor, gradleConfigurationFingerprint, cache)
                    version
                }
                val manifest = AndroidToolchainProjectBinding.bind(selection.manifest, requirements)
                projectToolchainAuthority.bind(
                    manifest,
                    selection.manifest,
                    selection.source,
                    wrapperDescriptor.fingerprintSha256,
                    gradleConfigurationFingerprint,
                )
                val runtimeGradleVersion = selection.runtimeGradleVersion
                val readOnlyDependencyCache = gradleReadOnlyCacheResolver.resolve(runtimeGradleVersion)
                val resourcePlan = buildResourceGovernor.plan()
                val projectHints = GradleProjectPerformanceHints.read(projectRoot)
                val maxWorkers = minOf(resourcePlan.maxWorkers, projectHints.maxWorkers ?: resourcePlan.maxWorkers).coerceAtLeast(1)
                val daemonIdleMillis = minOf(resourcePlan.daemonIdleMillis, projectHints.daemonIdleMillis ?: resourcePlan.daemonIdleMillis)
                val useBuildCache = resourcePlan.useBuildCache && projectHints.buildCacheEnabled != false
                val useLowProcessPriority = projectHints.priority != "normal"
                val allowPersistentGradleDaemon = resourcePlan.pressure == MobileBuildResourcePolicy.Pressure.NORMAL && projectHints.daemonEnabled == true
                val start = System.currentTimeMillis()
                val command = GradleBuildCommand.create(
                    manifest = manifest,
                    remoteWorkspace = remoteWorkspace,
                    task = task,
                    maxWorkers = maxWorkers,
                    useBuildCache = useBuildCache,
                    useLowProcessPriority = useLowProcessPriority,
                    daemonIdleMillis = daemonIdleMillis,
                    allowPersistentDaemon = allowPersistentGradleDaemon,
                    readOnlyDependencyCache = readOnlyDependencyCache,
                )
                _operation.value = OperationState(
                    OperationPhase.BUILDING,
                    "Device Workstation · Gradle: $task · $maxWorkers worker${if (maxWorkers == 1) "" else "s"} · ${resourcePlan.pressure.name.lowercase()}${if (useBuildCache) " · cache" else ""}",
                )
                val lineBuffer = StringBuilder()
                val buildLease = RemoteProcessLease.create("gradle-build")
                activeBuildLease = buildLease
                val r = bridge.shellStreaming(buildLease.wrap(command), maxOutputBytes = 1_500_000) { text, _ ->
                    synchronized(lineBuffer) {
                        lineBuffer.append(text)
                        if (lineBuffer.length > 32_000) lineBuffer.delete(0, lineBuffer.length - 16_000)
                        val latest = lineBuffer.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
                        latest.takeIf { it.startsWith("> Task ") }?.let { taskLine ->
                            _operation.value = OperationState(OperationPhase.BUILDING, "Device Workstation · ${taskLine.removePrefix("> ").take(220)}")
                        }
                    }
                }
                withContext(kotlinx.coroutines.NonCancellable) {
                    runSuspendCatching { bridge.shellBounded(buildLease.cleanupCommand(), maxOutputBytes = 8_192) }
                }
                activeBuildLease = null
                val output = r.combined
                val artifacts = if (r.exitCode == 0) artifactCollector.collect(remoteWorkspace, task, start) else emptyList()
                val diagnostics = GradleProblemParser.parse(output, remoteWorkspace)
                _status.value = _status.value.copy(buildBackend = BuildBackend.DEVICE_WORKSTATION)
                BuildResult(
                    success = r.exitCode == 0,
                    exitCode = r.exitCode,
                    output = output,
                    localArtifacts = artifacts,
                    durationMs = System.currentTimeMillis() - start,
                    diagnostics = diagnostics,
                )
            } catch (cancelled: CancellationException) {
                activeBuildLease?.let { terminateBuildLease(it) }
                activeBuildLease = null
                throw cancelled
            } catch (error: Throwable) {
                activeBuildLease?.let { terminateBuildLease(it) }
                activeBuildLease = null
                throw error
            }
        }
    }

    private suspend fun terminateBuildLease(lease: RemoteProcessLease) = withContext(kotlinx.coroutines.NonCancellable) {
        

        runSuspendCatching { bridge.ensureHealthyConnection() }
        if (bridge.state.value.connected != null) {
            runSuspendCatching { bridge.shellBounded(lease.terminateCommand(), maxOutputBytes = 16_384) }
        }
    }

    suspend fun installAndRun(apk: File, packageName: String? = null): String =
        appRuntime.installAndRun(apk, packageName)

    internal fun requireRuntimeConnection() {
        check(Build.VERSION.SDK_INT >= 30) { "Android install/run/debug requires Wireless Debugging on Android 11+" }
        check(bridge.state.value.connected != null) { "Pair and connect Device Workstation before Android install/run/debug" }
    }

     
    suspend fun installAndLaunchForDebug(
        apk: File,
        packageName: String? = null,
        waitTimeoutMs: Long = 12_000,
    ): DebugLaunchResult = appRuntime.installAndLaunchForDebug(apk, packageName, waitTimeoutMs)

    suspend fun stopApp(packageName: String? = null) = appRuntime.stopApp(packageName)

    suspend fun logs(packageName: String? = null): String = appRuntime.logs(packageName)

    private suspend fun ensureRemoteFreeSpace(requiredBytes: Long, purpose: String) {
        require(requiredBytes > 0L) { "Invalid storage requirement" }
        val root = DeviceBridgeManager.remoteRoot()
        val prepare = bridge.shell("mkdir -p ${DeviceBridgeManager.shellQuote(root)}")
        check(prepare.exitCode == 0) { prepare.combined }
        val df = bridge.shell("df -Pk ${DeviceBridgeManager.shellQuote(root)} | tail -1")
        check(df.exitCode == 0) { "Could not check device free space: ${df.combined}" }
        val fields = df.stdout.trim().split(Regex("\\s+"))
        require(fields.size >= 4) { "Unexpected device storage report" }
        val available = fields[3].toLongOrNull()?.times(1024L) ?: error("Invalid device free-space value")
        check(available >= requiredBytes) {
            "Not enough device storage to $purpose. Need ${formatBytes(requiredBytes)} free; ${formatBytes(available)} available."
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mib < 1024.0) String.format(java.util.Locale.US, "%.0f MiB", mib)
        else String.format(java.util.Locale.US, "%.1f GiB", mib / 1024.0)
    }

    private suspend fun managedToolchainManifest(): ToolchainManifest? {
        val path = "${DeviceBridgeManager.remoteRoot()}/toolchains/current/toolchain.json"
        val r = bridge.shellBounded(
            "test -f ${DeviceBridgeManager.shellQuote(path)} && cat ${DeviceBridgeManager.shellQuote(path)}",
            maxOutputBytes = 64_000,
        )
        if (r.exitCode != 0 || r.stdout.isBlank() || r.truncated) return null
        return runCatching {
            json.decodeFromString<ToolchainManifest>(r.stdout).also(::validateManifest)
        }.getOrNull()
    }

    private suspend fun verifyRemoteToolchain(manifest: ToolchainManifest, requiredCompileSdk: Int?): String? =
        verifyRemoteToolchainRequirements(manifest, AndroidToolchainRequirements(compileSdk = requiredCompileSdk))

    private suspend fun verifyRemoteToolchainRequirements(
        manifest: ToolchainManifest,
        requirements: AndroidToolchainRequirements,
    ): String? = runSuspendCatching {
        validateManifest(manifest)
        toolchainCapabilityProbe.verify(manifest, requirements)?.let(::error)
        null
    }.getOrElse { it.message ?: "Android toolchain verification failed" }

    private suspend fun rollbackProvisionedToolchain() {
        val root = DeviceBridgeManager.remoteRoot()
        val current = "$root/toolchains/current"
        val previous = "$root/toolchains/previous"
        val result = bridge.shell(
            "set -eu; " +
                "rm -rf ${DeviceBridgeManager.shellQuote(current)}; " +
                "if [ -d ${DeviceBridgeManager.shellQuote(previous)} ]; then " +
                "mv ${DeviceBridgeManager.shellQuote(previous)} ${DeviceBridgeManager.shellQuote(current)}; fi"
        )
        check(result.exitCode == 0) { "Failed to roll back Android toolchain: ${result.combined}" }
    }

    private suspend fun syncWorkspace(
        remoteWorkspace: String,
        manifest: ToolchainManifest? = null,
        forceContentHash: Boolean = false,
    ) {
        DeviceBridgeManager.requireSafeRemotePath(remoteWorkspace)
        val remoteManifestPath = "$remoteWorkspace/.droide-sync-manifest.tsv"
        val previousResult = bridge.shellBounded(
            "test -f ${DeviceBridgeManager.shellQuote(remoteManifestPath)} && cat ${DeviceBridgeManager.shellQuote(remoteManifestPath)}",
            maxOutputBytes = MAX_SYNC_MANIFEST_BYTES,
        )
        val previous = if (previousResult.exitCode == 0 && !previousResult.truncated) {
            WorkspaceSyncManifest.parseTrustedOrEmpty(previousResult.stdout, MAX_SYNC_FILES)
        } else emptyMap()

        if (previous.isEmpty()) {
            val prepare = bridge.shell(
                "rm -rf ${DeviceBridgeManager.shellQuote(remoteWorkspace)} && mkdir -p ${DeviceBridgeManager.shellQuote(remoteWorkspace)}"
            )
            check(prepare.exitCode == 0) { prepare.combined }
        } else {
            val prepare = bridge.shell("mkdir -p ${DeviceBridgeManager.shellQuote(remoteWorkspace)}")
            check(prepare.exitCode == 0) { prepare.combined }
        }

        val current = linkedMapOf<String, WorkspaceSyncManifest.Entry>()
        var count = 0
        var totalBytes = 0L
        projectRoot.walkTopDown().onEnter { dir ->
            !PathSecurity.isSymbolicLink(dir) &&
                !dir.name.let { it == ".git" || it == ".gradle" || it == "build" || it == ".droide" }
        }.filter { it.isFile && !PathSecurity.isSymbolicLink(it) }.forEach { file ->
            val rel = file.relativeTo(projectRoot).invariantSeparatorsPath
            if (SensitivePathPolicy.isSensitive(rel)) return@forEach
            validateSyncRelativePath(rel)
            val identity = syncFingerprintCache.identity(rel, file, forceContentHash) { SafeFileDigest.sha256RegularNoFollow(it, projectRoot) }
            require(identity.sizeBytes <= MAX_SYNC_FILE_BYTES) { "Refusing to sync file >128 MiB: $rel" }
            count++
            require(count <= MAX_SYNC_FILES) { "Workspace has too many files to sync" }
            totalBytes += identity.sizeBytes
            require(totalBytes <= MAX_SYNC_TOTAL_BYTES) { "Workspace sync exceeds 2 GiB" }
            current[rel] = WorkspaceSyncManifest.Entry(identity.sha256, WorkspaceExecutablePolicy.projectedMode(projectRoot, file))
        }


        syncFingerprintCache.retainOnly(current.keys)


        val stale = previous.keys - current.keys
        stale.chunked(100).forEach { batch ->
            val targets = batch.joinToString(" ") { rel ->
                validateSyncRelativePath(rel)
                DeviceBridgeManager.shellQuote("$remoteWorkspace/$rel")
            }
            if (targets.isNotBlank()) {
                val removed = bridge.shell("rm -f $targets")
                check(removed.exitCode == 0) { removed.combined }
            }
        }

        current.forEach { (rel, entry) ->
            if (previous[rel] == entry) return@forEach
            val file = File(projectRoot, rel)
            val remote = "$remoteWorkspace/$rel"
            DeviceBridgeManager.requireSafeRemotePath(remote)
            val parent = remote.substringBeforeLast('/', remoteWorkspace)
            val mk = bridge.shell("mkdir -p ${DeviceBridgeManager.shellQuote(parent)}")
            check(mk.exitCode == 0) { mk.combined }
            val projection = StableWorkspaceFilePush.push(projectRoot, bridge, file, remote, entry.sha256, requireNotNull(entry.mode),
                modeResolver = { WorkspaceExecutablePolicy.projectedMode(projectRoot, it) })
            current[rel] = WorkspaceSyncManifest.Entry(projection.sha256, projection.mode)
        }


        if (manifest != null) {
            val localPropertiesFile = File(context.cacheDir, "droide-remote-local-${stableProjectId(projectRoot)}.properties")
            localPropertiesFile.writeText("sdk.dir=${manifest.sdkRoot}\n")
            try {
                bridge.push(localPropertiesFile, "$remoteWorkspace/local.properties", mode = 420)
            } finally {
                localPropertiesFile.delete()
            }
        } else {
            val removeGeneratedSdkPointer = bridge.shell("rm -f ${DeviceBridgeManager.shellQuote("$remoteWorkspace/local.properties")}")
            check(removeGeneratedSdkPointer.exitCode == 0) { removeGeneratedSdkPointer.combined }
        }

        val manifestFile = File(context.cacheDir, "droide-sync-${stableProjectId(projectRoot)}.tsv")
        manifestFile.writeText(WorkspaceSyncManifest.serialize(current))
        try {
            bridge.push(manifestFile, remoteManifestPath, mode = 420)
        } finally {
            manifestFile.delete()
        }
    }

    private fun validateSyncRelativePath(path: String) = WorkspaceSyncManifest.validateRelativePath(path)

    private fun parseChecksumManifest(text: String, checksumFile: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lineSequence().filter { it.isNotBlank() }.forEachIndexed { index, line ->
            val match = Regex("^([0-9a-fA-F]{64})  ([^\r\n]+)$").matchEntire(line)
                ?: error("Invalid checksum line ${index + 1}")
            val path = match.groupValues[2]
            validatePackPath(path)
            require(path != checksumFile) { "Checksum file must not checksum itself" }
            require(result.put(path, match.groupValues[1].lowercase()) == null) { "Duplicate checksum path: $path" }
        }
        require(result.isNotEmpty()) { "Empty toolchain checksum manifest" }
        return result
    }

    private fun validateManifest(manifest: ToolchainManifest) {
        require(manifest.schema in 1..4) { "Unsupported toolchain schema ${manifest.schema}" }
        require(manifest.version.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid toolchain version" }
        require(manifest.abi in Build.SUPPORTED_ABIS) { "Toolchain ABI ${manifest.abi} is not supported by this device" }
        require(manifest.compileSdk in 1..999) { "Invalid toolchain SDK ${manifest.compileSdk}" }
        require(manifest.javaVersion in 8..99) { "Invalid JDK major ${manifest.javaVersion}" }
        require(manifest.gradleVersion.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+._A-Za-z0-9]*)?"))) { "Invalid Gradle version" }
        listOf(manifest.aapt2Path, manifest.javaHome, manifest.sdkRoot).forEach { path ->
            DeviceBridgeManager.requireSafeRemotePath(path)
        }
        require(manifest.compileSdks.all { it in 1..999 } && manifest.compileSdks.size <= 128) { "Invalid SDK platform capability set" }
        listOf(manifest.buildToolsVersions, manifest.ndkVersions, manifest.cmakeVersions).forEach { versions ->
            require(versions.size <= 128 && versions.distinct().size == versions.size) { "Invalid component version capability list" }
            versions.forEach { version ->
                require(version.matches(Regex("[0-9][A-Za-z0-9+_.-]{0,79}"))) { "Invalid toolchain component version: $version" }
            }
        }
        AndroidToolchainRuntime.validate(manifest.runtime, manifest.abi)
        validatePackPath(manifest.checksumFile)
        require(manifest.checksumFile != "toolchain.json") { "toolchain.json cannot be the checksum manifest" }
        manifest.executablePaths.forEach(::validatePackPath)
        manifest.executableFile?.let {
            require(manifest.schema >= 2) { "Executable manifest requires toolchain schema 2" }
            validatePackPath(it)
            require(it != manifest.checksumFile && it != "toolchain.json") { "Invalid executable manifest path" }
        }
        manifest.symlinkFile?.let {
            require(manifest.schema >= 2) { "Symlink manifest requires toolchain schema 2" }
            validatePackPath(it)
            require(it != manifest.checksumFile && it != "toolchain.json") { "Invalid symlink manifest path" }
        }
        if (AndroidToolchainRuntime.isCompatibility(manifest.runtime)) {
            require(manifest.schema >= 2) { "Linux compatibility toolchains require schema 2" }
            require(manifest.executableFile != null) { "Linux compatibility toolchains require an executable manifest" }
        }
        if (manifest.schema >= 3) {
            require(manifest.candidateId?.matches(Regex("[A-Za-z0-9._+-]{1,120}")) == true) { "Schema 3 toolchain candidate id is missing/invalid" }
            require(manifest.sourceLockSha256?.matches(Regex("[0-9a-f]{64}")) == true) { "Schema 3 source-lock SHA-256 is missing/invalid" }
            require(manifest.buildRecipeVersion?.matches(Regex("[A-Za-z0-9._+-]{1,120}")) == true) { "Schema 3 build recipe version is missing/invalid" }
            require(manifest.sdkLicenseSha256?.matches(Regex("[0-9a-f]{64}")) == true) { "Schema 3 SDK license SHA-256 is missing/invalid" }
        }
    }

    private fun validateInstallReceipt(receipt: ToolchainInstallReceipt) {
        require(receipt.schema == 1) { "Unsupported toolchain receipt schema" }
        require(receipt.packSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid toolchain receipt SHA-256" }
        require(receipt.installedAtEpochMs > 0L) { "Invalid toolchain install timestamp" }
        require(receipt.manifestVersion.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid toolchain receipt version" }
        receipt.candidateId?.let { require(it.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Invalid toolchain receipt candidate id" } }
        receipt.sourceLockSha256?.let { require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid toolchain receipt source-lock SHA-256" } }
    }

    private suspend fun writeInstallReceipt(receipt: ToolchainInstallReceipt) {
        validateInstallReceipt(receipt)
        val path = DeviceBridgeManager.remoteRoot() + "/toolchains/current/.droide-install.json"
        DeviceBridgeManager.requireSafeRemotePath(path)
        val jsonText = json.encodeToString(receipt)
        require(jsonText.toByteArray(Charsets.UTF_8).size <= MAX_INSTALL_RECEIPT_BYTES) { "Toolchain install receipt is too large" }
        val result = bridge.shell(
            "umask 077; printf %s ${DeviceBridgeManager.shellQuote(jsonText)} > ${DeviceBridgeManager.shellQuote(path)}"
        )
        check(result.exitCode == 0) { "Could not persist toolchain install receipt: ${result.combined}" }
    }

    private fun validatePackPath(path: String) {
        require(path.isNotBlank() && path.length <= 600) { "Invalid toolchain path" }
        require(!path.startsWith('/') && !path.startsWith('\\') && !path.contains('\u0000')) { "Absolute/invalid toolchain path: $path" }
        val normalized = path.replace('\\', '/').trimEnd('/')
        require(normalized.isNotBlank()) { "Invalid toolchain path" }
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe toolchain path: $path" }
    }

    private data class ToolchainSymlink(val linkPath: String, val target: String)

    private fun readBoundedZipText(zip: ZipFile, entry: java.util.zip.ZipEntry, maxBytes: Int, label: String): String {
        require(entry.size <= maxBytes.toLong() || entry.size == -1L) { "$label is too large" }
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        zip.getInputStream(entry).buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                total += n
                require(total <= maxBytes) { "$label is too large" }
                output.write(buffer, 0, n)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun effectiveExecutablePaths(
        zip: ZipFile,
        manifest: ToolchainManifest,
        payloadPaths: Set<String>,
    ): Set<String> {
        val result = linkedSetOf<String>()
        result += manifest.executablePaths
        manifest.executableFile?.let { file ->
            val entry = zip.getEntry(file) ?: error("$file is missing")
            val text = readBoundedZipText(zip, entry, MAX_EXECUTABLE_MANIFEST_BYTES, file)
            text.lineSequence().filter { it.isNotBlank() }.forEachIndexed { index, raw ->
                require(index < MAX_TOOLCHAIN_EXECUTABLES) { "Too many executable entries" }
                val path = raw.trimEnd('\r')
                validatePackPath(path)
                require(result.add(path)) { "Duplicate executable path: $path" }
            }
        }
        require(result.all { it in payloadPaths }) { "Executable manifest refers to a non-payload path" }
        require(manifest.checksumFile !in result) { "Checksum manifest cannot be executable" }
        return result
    }

    private fun effectiveSymlinks(
        zip: ZipFile,
        manifest: ToolchainManifest,
        payloadPaths: Set<String>,
    ): List<ToolchainSymlink> {
        val file = manifest.symlinkFile ?: return emptyList()
        val entry = zip.getEntry(file) ?: error("$file is missing")
        val text = readBoundedZipText(zip, entry, MAX_SYMLINK_MANIFEST_BYTES, file)
        val links = mutableListOf<ToolchainSymlink>()
        val seen = linkedSetOf<String>()
        text.lineSequence().filter { it.isNotBlank() }.forEachIndexed { index, rawLine ->
            require(index < MAX_TOOLCHAIN_SYMLINKS) { "Too many toolchain symlinks" }
            val line = rawLine.trimEnd('\r')
            val tab = line.indexOf('\t')
            require(tab in 1 until line.lastIndex && line.indexOf('\t', tab + 1) == -1) { "Invalid symlink line ${index + 1}" }
            val link = line.substring(0, tab)
            val target = line.substring(tab + 1)
            validatePackPath(link)
            validateSymlinkTarget(link, target)
            require(link !in payloadPaths) { "Symlink collides with payload path: $link" }
            require(seen.add(link)) { "Duplicate symlink path: $link" }
            links += ToolchainSymlink(link, target)
        }
        val allPaths = payloadPaths + seen
        for (link in seen) {
            require(allPaths.none { it != link && it.startsWith("$link/") }) {
                "Symlink cannot be an ancestor of another pack path: $link"
            }
        }
        return links
    }

    private fun validateSymlinkTarget(linkPath: String, target: String) {
        require(target.isNotBlank() && target.length <= 600) { "Invalid symlink target" }
        require(target.none { it == '\u0000' || it == '\n' || it == '\r' || it == '\t' }) { "Invalid symlink target" }
        if (target.startsWith('/')) {
            require(linkPath.startsWith("compat/rootfs/")) { "Absolute symlink targets are allowed only inside the compatibility rootfs" }
            validateGuestAbsoluteSymlinkTarget(target)
            return
        }
        val base = linkPath.substringBeforeLast('/', "").split('/').filter { it.isNotBlank() }.toMutableList()
        target.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> require(base.isNotEmpty()) { "Symlink escapes toolchain root: $linkPath -> $target" }.also { base.removeAt(base.lastIndex) }
                else -> {
                    require(part.none { it == '\u0000' || it == '\n' || it == '\r' || it == '\t' }) { "Invalid symlink target" }
                    base += part
                }
            }
        }
        val resolved = base.joinToString("/")
        require(resolved.isNotBlank()) { "Symlink resolves outside toolchain root" }
        if (linkPath.startsWith("compat/rootfs/")) {
            require(resolved == "compat/rootfs" || resolved.startsWith("compat/rootfs/")) {
                "Rootfs symlink escapes compatibility rootfs: $linkPath -> $target"
            }
        }
    }

    private fun validateGuestAbsoluteSymlinkTarget(path: String) {
        require(path.startsWith('/') && path.length in 2..600) { "Invalid absolute rootfs symlink target" }
        val parts = path.split('/').filter { it.isNotBlank() }
        require(parts.none { it == "." || it == ".." }) { "Unsafe absolute rootfs symlink target" }
    }

    private suspend fun createRemoteSymlinks(stage: String, symlinks: List<ToolchainSymlink>) {
        DeviceBridgeManager.requireSafeRemotePath(stage)
        symlinks.chunked(40).forEachIndexed { batchIndex, batch ->
            val command = buildString {
                append("set -eu; cd ").append(DeviceBridgeManager.shellQuote(stage)).append("; ")
                batch.forEach { link ->
                    val parent = link.linkPath.substringBeforeLast('/', "")
                    if (parent.isNotBlank()) {
                        append("mkdir -p ").append(DeviceBridgeManager.shellQuote(parent)).append("; ")
                    }
                    val targetArg = if (link.target.startsWith('-')) "./${link.target}" else link.target
                    append("ln -s ").append(DeviceBridgeManager.shellQuote(targetArg)).append(' ')
                        .append(DeviceBridgeManager.shellQuote("./${link.linkPath}")).append("; ")
                }
            }
            val result = bridge.shell(command)
            check(result.exitCode == 0) { "Could not restore toolchain symlinks: ${result.combined}" }
            _operation.value = OperationState(
                OperationPhase.VERIFYING_REMOTE,
                "Restoring verified toolchain links…",
                completedItems = minOf((batchIndex + 1) * 40, symlinks.size),
                totalItems = symlinks.size,
            )
        }
    }

    companion object {
        private const val MAX_INSTALL_RECEIPT_BYTES = 16 * 1024
        private const val TOOLCHAIN_FREE_SPACE_RESERVE_BYTES = 256L * 1024L * 1024L
        private const val MIN_BUILD_FREE_SPACE_BYTES = 512L * 1024L * 1024L
        private const val MAX_TOOLCHAIN_ENTRIES = 50_000
        private const val MAX_TOOLCHAIN_FILE_BYTES = 1L * 1024L * 1024L * 1024L
        private const val MAX_TOOLCHAIN_UNCOMPRESSED_BYTES = 3L * 1024L * 1024L * 1024L
        private const val MAX_TOOLCHAIN_ARCHIVE_BYTES = 3L * 1024L * 1024L * 1024L
        private const val MAX_TOOLCHAIN_MANIFEST_BYTES = 256_000
        private const val MAX_CHECKSUM_MANIFEST_BYTES = 8_000_000
        private const val MAX_EXECUTABLE_MANIFEST_BYTES = 2_000_000
        private const val MAX_SYMLINK_MANIFEST_BYTES = 2_000_000
        private const val MAX_TOOLCHAIN_EXECUTABLES = 20_000
        private const val MAX_TOOLCHAIN_SYMLINKS = 20_000
        private const val MAX_SYNC_FILES = 50_000
        private const val MAX_SYNC_FILE_BYTES = 128L * 1024L * 1024L
        private const val MAX_SYNC_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_SYNC_MANIFEST_BYTES = 8_000_000

        fun stableProjectId(root: File): String = MessageDigest.getInstance("SHA-256")
            .digest(root.canonicalPath.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
    }
}
