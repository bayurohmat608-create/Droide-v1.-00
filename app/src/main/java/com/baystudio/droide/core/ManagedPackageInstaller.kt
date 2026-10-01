package com.baystudio.droide.core

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private data class ManagedPackageInstallExpectation(
    val familyId: String,
    val version: String,
    val abi: String,
    val sha256: String,
    val dependencies: List<String>,
    val reviewedManifest: ManagedPackageManifest? = null,
)

class ManagedPackageInstaller(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val registry: ManagedPackageRegistry,
    private val downloader: UserInitiatedArtifactTransfer = UserInitiatedArtifactTransfer(context.applicationContext),
) {
    enum class Phase { IDLE, DOWNLOADING, VERIFYING, UPLOADING, ACTIVATING, HEALTH_CHECK, READY, CANCELED, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val message: String = "",
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long? = null,
        val completedItems: Int = 0,
        val totalItems: Int = 0,
    ) {
        val running: Boolean get() = phase in setOf(Phase.DOWNLOADING, Phase.VERIFYING, Phase.UPLOADING, Phase.ACTIVATING, Phase.HEALTH_CHECK)
        val fraction: Float? get() = when {
            totalBytes != null && totalBytes > 0 -> (bytesDownloaded.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
            totalItems > 0 -> (completedItems.toFloat() / totalItems).coerceIn(0f, 1f)
            else -> null
        }
    }

    val backendId: PackageBackendId = PackageBackendId.DEVICE_ADB
    private val appContext = context.applicationContext
    private val localRoot = appContext.filesDir.absolutePath

    internal fun owns(record: ManagedPackageRecord): Boolean =
        PackageBackendContract.recordOwner(record.installRoot, record.metadata, localRoot) == backendId

    private fun requireOwner(record: ManagedPackageRecord) =
        PackageBackendContract.requireOwner(backendId, record.installRoot, record.metadata, localRoot)

    private fun requireFamilyOwner(record: ManagedPackageRecord, records: List<ManagedPackageRecord>) {
        requireOwner(record)
        check(records.filter { it.familyId == record.familyId }.all(::owns)) {
            "Package family already belongs to another backend; explicit migration is required"
        }
    }
    private val json = Json { ignoreUnknownKeys = false }
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun install(entry: ManagedPackageCatalogEntry): ManagedPackageRecord =
        ManagedPackageMutationGate.mutex.withLock { installUnlocked(entry) }

    private suspend fun installUnlocked(entry: ManagedPackageCatalogEntry): ManagedPackageRecord = withContext(Dispatchers.IO) {
        entry.validate()
        check(registry.list().filter { it.familyId == entry.familyId }.all(::owns)) {
            "Package family already belongs to another backend; explicit migration is required"
        }
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        val expectation = ManagedPackageInstallExpectation(
            familyId = entry.familyId,
            version = entry.version,
            abi = entry.abi,
            sha256 = entry.sha256,
            dependencies = entry.dependencies,
        )
        try {
            _state.value = State(Phase.DOWNLOADING, "Downloading verified package…", totalBytes = entry.sizeBytes)
            val archive = downloader.download(entry.toArtifactSpec()) { progress ->
                _state.value = State(
                    phase = Phase.DOWNLOADING,
                    message = if (progress.fromCache) "Using verified package cache…" else "Downloading verified package…",
                    bytesDownloaded = progress.bytesDownloaded,
                    totalBytes = progress.totalBytes ?: entry.sizeBytes,
                )
            }
            val record = when (entry.distribution) {
                ManagedPackageDistribution.DROIDE_ZIP -> installArchive(archive, expectation)
                ManagedPackageDistribution.TERMUX_DEB_SINGLE_EXECUTABLE -> installTermuxExecutable(archive, entry, expectation)
            }
            _state.value = State(Phase.READY, "Installed, activated and health-checked.")
            record
        } catch (cancelled: CancellationException) {
            _state.value = State(Phase.CANCELED, "Installation canceled. No package was activated.")
            throw cancelled
        } catch (error: Throwable) {
            _state.value = State(Phase.FAILED, error.message ?: "Managed package installation failed")
            throw error
        }
    }


    private suspend fun installTermuxExecutable(
        artifact: File,
        entry: ManagedPackageCatalogEntry,
        expectation: ManagedPackageInstallExpectation,
    ): ManagedPackageRecord {
        require(entry.distribution == ManagedPackageDistribution.TERMUX_DEB_SINGLE_EXECUTABLE)
        require(ManagedPackageInstallerSupport.sha256File(artifact) == entry.sha256) { "Termux package artifact SHA-256 mismatch" }
        require(artifact.length() == entry.sizeBytes) { "Termux package artifact size mismatch" }
        val sourcePath = requireNotNull(entry.sourcePath)
        val executableName = requireNotNull(entry.executableName)

        val normalizeDir = File(appContext.cacheDir, "managed-package-normalize/${entry.sha256}")
        check(PathSecurity.deleteTreeNoFollow(normalizeDir)) { "Could not clear managed-package normalization directory" }
        require(normalizeDir.mkdirs() || normalizeDir.isDirectory) { "Could not create managed-package normalization directory" }
        try {
            val member = DebianArArchive.extractDataTar(
                deb = artifact,
                outputDir = normalizeDir,
                maxBytes = minOf(TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES, 512L * 1024L * 1024L),
            )
            val root = DeviceBridgeManager.remoteRoot()
            val safeFamily = expectation.familyId.replace('.', '_')
            val safeVersion = expectation.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
            val stage = "$root/packages/.staging/$safeFamily-$safeVersion-${expectation.abi}"
            val final = "$root/packages/${expectation.familyId}/${expectation.version}/${expectation.abi}"
            val previous = "$root/packages/.previous/$safeFamily-$safeVersion-${expectation.abi}"
            listOf(stage, final, previous).forEach(DeviceBridgeManager::requireSafeRemotePath)
            val sourceArchive = "$stage/.source/${member.name}"
            val extractRoot = "$stage/.extract"
            val stagedExecutable = "$stage/payload/bin/$executableName"
            listOf(sourceArchive, extractRoot, stagedExecutable).forEach(DeviceBridgeManager::requireSafeRemotePath)

            val prep = bridge.shell(
                "rm -rf ${DeviceBridgeManager.shellQuote(stage)} && " +
                    "mkdir -p ${DeviceBridgeManager.shellQuote("$stage/.source")} ${DeviceBridgeManager.shellQuote("$stage/payload/bin")} ${DeviceBridgeManager.shellQuote(extractRoot)}"
            )
            check(prep.exitCode == 0) { prep.combined }
            try {
                _state.value = State(Phase.UPLOADING, "Normalizing trusted Android-native language server…", totalItems = 4)
                member.file.inputStream().use { bridge.pushStream(it, sourceArchive, mode = 420) }
                _state.value = _state.value.copy(completedItems = 1)

                val compressionFlag = when (member.name) {
                    "data.tar.xz" -> "J"
                    "data.tar.gz" -> "z"
                    else -> error("Unsupported Debian data archive compression")
                }
                val listing = bridge.shellBounded(
                    "toybox tar -t${compressionFlag}f ${DeviceBridgeManager.shellQuote(sourceArchive)}",
                    maxOutputBytes = 512 * 1024,
                )
                check(listing.exitCode == 0) { "Could not inspect Debian data archive: ${listing.combined}" }
                val listedPath = listing.stdout.lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .onEach { path ->
                        val normalized = path.removePrefix("./")
                        require(!normalized.startsWith('/') && normalized.length <= 500) { "Unsafe Debian archive path" }
                        require(normalized.split('/').none { it == "." || it == ".." }) { "Unsafe Debian archive path" }
                    }
                    .firstOrNull { it.removePrefix("./") == sourcePath }
                    ?: error("Reviewed executable path is missing from Debian package: $sourcePath")

                val extraction = bridge.shellBounded(
                    "set -eu; " +
                        "toybox tar --restrict -x${compressionFlag}f ${DeviceBridgeManager.shellQuote(sourceArchive)} " +
                        "-C ${DeviceBridgeManager.shellQuote(extractRoot)} ${DeviceBridgeManager.shellQuote(listedPath)}; " +
                        "SRC=${DeviceBridgeManager.shellQuote("$extractRoot/$listedPath")}; " +
                        "test -f \"${'$'}SRC\"; test ! -L \"${'$'}SRC\"; " +
                        "mv \"${'$'}SRC\" ${DeviceBridgeManager.shellQuote(stagedExecutable)}; chmod 0755 ${DeviceBridgeManager.shellQuote(stagedExecutable)}; " +
                        "rm -rf ${DeviceBridgeManager.shellQuote(extractRoot)} ${DeviceBridgeManager.shellQuote("$stage/.source")}",
                    maxOutputBytes = 128 * 1024,
                )
                check(extraction.exitCode == 0) { "Could not extract reviewed Termux executable: ${extraction.combined}" }

                
                val elf = bridge.shellBounded(
                    "set -eu; F=${DeviceBridgeManager.shellQuote(stagedExecutable)}; " +
                        "test \"${'$'}(toybox od -An -tx1 -N5 \"${'$'}F\" | tr -d ' \\n')\" = 7f454c4602; " +
                        "test \"${'$'}(toybox od -An -tx1 -j18 -N2 \"${'$'}F\" | tr -d ' \\n')\" = b700",
                    maxOutputBytes = 32 * 1024,
                )
                check(elf.exitCode == 0) { "Termux executable is not a 64-bit AArch64 ELF" }
                _state.value = _state.value.copy(completedItems = 2)

                val manifest = ManagedPackageManifest(
                    schema = 2,
                    familyId = expectation.familyId,
                    version = expectation.version,
                    abi = expectation.abi,
                    dependencies = expectation.dependencies,
                    pathEntries = listOf("bin"),
                    executablePaths = listOf("bin/$executableName"),
                    healthChecks = listOf(ManagedPackageHealthCheck("bin/$executableName", listOf("--version"))),
                )
                validateManifest(manifest, expectation)
                val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                val manifestSha = MessageDigest.getInstance("SHA-256").digest(manifestBytes).joinToString("") { "%02x".format(it) }
                val provenance = ManagedPackageProvenanceLock(
                    schema = 1,
                    purpose = ManagedPackageProvenance.PURPOSE,
                    familyId = expectation.familyId,
                    version = expectation.version,
                    abi = expectation.abi,
                    manifestSha256 = manifestSha,
                    provenanceUrl = entry.provenanceUrl,
                    summary = entry.provenance,
                    sources = listOf(ManagedPackageSourcePin(entry.downloadUrl, entry.sha256, entry.sizeBytes)),
                )
                val provenanceBytes = json.encodeToString(provenance).toByteArray(Charsets.UTF_8)
                ManagedPackageProvenance.parseAndValidate(provenanceBytes, manifestBytes, manifest)
                ByteArrayInputStream(manifestBytes).use { bridge.pushStream(it, "$stage/package.json", mode = 420) }
                ByteArrayInputStream(provenanceBytes).use { bridge.pushStream(it, "$stage/${ManagedPackageProvenance.FILE_NAME}", mode = 420) }
                val checksums = bridge.shellBounded(
                    "cd ${DeviceBridgeManager.shellQuote(stage)} && " +
                        "toybox sha256sum package.json ${ManagedPackageProvenance.FILE_NAME} ${DeviceBridgeManager.shellQuote("payload/bin/$executableName")} > SHA256SUMS && " +
                        "toybox sha256sum -c SHA256SUMS",
                    maxOutputBytes = 128 * 1024,
                )
                check(checksums.exitCode == 0) { "Normalized managed package integrity check failed: ${checksums.combined}" }
                _state.value = _state.value.copy(completedItems = 3)

                _state.value = State(Phase.HEALTH_CHECK, "Running Android-native language-server health check…")
                healthCheck(stage, manifest)
                _state.value = State(Phase.ACTIVATING, "Activating Android-native language server…")
                val record = promoteStagedPackage(stage, final, previous, manifest, expectation)
                _state.value = State(Phase.READY, "Android-native language server installed and verified.", completedItems = 4, totalItems = 4)
                return record
            } catch (error: Throwable) {
                runSuspendCatching { bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(stage)}") }
                throw error
            }
        } finally {
            check(PathSecurity.deleteTreeNoFollow(normalizeDir)) { "Could not clear managed-package normalization directory" }
        }
    }

    // This never trusts or mutates the production catalog.
    internal suspend fun installCandidate(
        archive: File,
        expectedSha256: String,
        reviewedManifest: ManagedPackageManifest,
    ): ManagedPackageRecord = ManagedPackageMutationGate.mutex.withLock {
        withContext(Dispatchers.IO) {
            validateCandidateManifest(reviewedManifest)
            require(reviewedManifest.schema == 2) { "Managed package certification candidates must use schema 2" }
            require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid candidate artifact SHA-256" }
            require(archive.isFile && archive.length() in 1..TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES) {
                "Managed package candidate archive is missing or too large"
            }
            check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
            val installed = registry.list()
            reviewedManifest.dependencies.forEach { key ->
                val dependency = installed.firstOrNull { "${it.familyId}@${it.version}" == key }
                    ?: error("Managed package candidate dependency is not installed: $key")
                require(dependency.artifactSha256?.matches(Regex("[0-9a-f]{64}")) == true) {
                    "Managed package candidate dependency has no exact artifact receipt: $key"
                }
                val closure = dependencyClosure(dependency, installed)
                check((closure + dependency).all { verifyInstalledRecord(it) }) {
                    "Managed package candidate dependency failed integrity verification: $key"
                }
            }
            val expectation = ManagedPackageInstallExpectation(
                familyId = reviewedManifest.familyId,
                version = reviewedManifest.version,
                abi = reviewedManifest.abi,
                sha256 = expectedSha256,
                dependencies = reviewedManifest.dependencies,
                reviewedManifest = reviewedManifest,
            )
            try {
                _state.value = State(Phase.VERIFYING, "Verifying certification candidate…", totalBytes = archive.length())
                val record = installArchive(archive, expectation)
                _state.value = State(Phase.READY, "Certification candidate installed and health-checked.")
                record
            } catch (cancelled: CancellationException) {
                _state.value = State(Phase.CANCELED, "Candidate installation canceled. No package was activated.")
                throw cancelled
            } catch (error: Throwable) {
                _state.value = State(Phase.FAILED, error.message ?: "Managed package candidate installation failed")
                throw error
            }
        }
    }

    internal fun validateCandidateManifest(manifest: ManagedPackageManifest) {
        ManagedPackageInstallerSupport.validateManifestShape(manifest)
    }

    private suspend fun installArchive(archive: File, expectation: ManagedPackageInstallExpectation): ManagedPackageRecord {
        require(archive.isFile) { "Managed package archive is missing" }
        require(ManagedPackageInstallerSupport.sha256File(archive) == expectation.sha256) { "Managed package outer artifact SHA-256 mismatch" }
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 3..MAX_ENTRIES) { "Invalid managed package entry count" }
            require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate managed package archive path" }
            var uncompressed = 0L
            entries.forEach { z ->
                ManagedPackageInstallerSupport.validateArchivePath(z.name)
                require(z.size in -1L..MAX_FILE_BYTES) { "Managed package file is too large: ${z.name}" }
                if (z.size > 0) {
                    uncompressed += z.size
                    require(uncompressed <= MAX_UNCOMPRESSED_BYTES) { "Managed package expands beyond the safety limit" }
                }
            }

            val manifestEntry = zip.getEntry("package.json") ?: error("package.json is missing")
            val manifestBytes = zip.getInputStream(manifestEntry).use { input ->
                ManagedPackageInstallerSupport.readBytesBounded(input, 256_000, "package.json")
            }
            val manifest = json.decodeFromString<ManagedPackageManifest>(manifestBytes.decodeToString())
            validateManifest(manifest, expectation)

            val provenanceEntries = entries.filter { it.name == ManagedPackageProvenance.FILE_NAME }
            if (manifest.schema == 2) {
                require(provenanceEntries.size == 1) { "Schema-2 managed package must contain exactly one ${ManagedPackageProvenance.FILE_NAME}" }
                val provenanceBytes = zip.getInputStream(provenanceEntries.single()).use { input ->
                    ManagedPackageInstallerSupport.readBytesBounded(input, ManagedPackageProvenance.MAX_BYTES, ManagedPackageProvenance.FILE_NAME)
                }
                ManagedPackageProvenance.parseAndValidate(provenanceBytes, manifestBytes, manifest)
            } else {
                require(provenanceEntries.isEmpty()) { "Schema-1 managed package must not contain ${ManagedPackageProvenance.FILE_NAME}" }
            }
            require(entries.all { entry ->
                entry.isDirectory ||
                    entry.name == "package.json" ||
                    entry.name == manifest.checksumFile ||
                    (manifest.schema == 2 && entry.name == ManagedPackageProvenance.FILE_NAME) ||
                    entry.name.startsWith("payload/")
            }) { "Unexpected managed package archive path" }
            if (manifest.schema == 2) {
                require(entries.any { !it.isDirectory && it.name.startsWith("payload/") }) {
                    "Managed package schema-2 payload is empty"
                }
            }

            val checksumEntry = zip.getEntry(manifest.checksumFile) ?: error("${manifest.checksumFile} is missing")
            val checksumText = zip.getInputStream(checksumEntry).use { input ->
                ManagedPackageInstallerSupport.readUtf8Bounded(input, 8_000_000, manifest.checksumFile)
            }
            val expected = ManagedPackageInstallerSupport.parseChecksums(checksumText, manifest.checksumFile)
            val payload = entries.filterNot { it.isDirectory || it.name == manifest.checksumFile }
            require(expected.keys == payload.map { it.name }.toSet()) { "Managed package checksum manifest does not exactly match archive payload" }

            _state.value = State(Phase.VERIFYING, "Verifying package files…", totalItems = payload.size)
            var streamedUncompressed = 0L
            payload.forEachIndexed { index, z ->
                val digest = MessageDigest.getInstance("SHA-256")
                var streamedFile = 0L
                zip.getInputStream(z).buffered().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        streamedFile += count
                        streamedUncompressed += count
                        require(streamedFile <= MAX_FILE_BYTES) { "Managed package file expands beyond the safety limit: ${z.name}" }
                        require(streamedUncompressed <= MAX_UNCOMPRESSED_BYTES) { "Managed package expands beyond the safety limit" }
                        digest.update(buffer, 0, count)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expected.getValue(z.name)) { "Managed package payload hash mismatch: ${z.name}" }
                _state.value = State(Phase.VERIFYING, "Verifying package files…", completedItems = index + 1, totalItems = payload.size)
            }

            val managedPackageNeed = Math.addExact(streamedUncompressed, MANAGED_PACKAGE_TRANSACTION_OVERHEAD_BYTES)
            DeviceWorkstationStorageGuard.requireHeadroom(
                bridge = bridge,
                additionalBytes = managedPackageNeed,
                purpose = "install managed package ${expectation.familyId} ${expectation.version}",
            )

            val root = DeviceBridgeManager.remoteRoot()
            val safeFamily = expectation.familyId.replace('.', '_')
            val safeVersion = expectation.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
            val stage = "$root/packages/.staging/$safeFamily-$safeVersion-${expectation.abi}"
            val final = "$root/packages/${expectation.familyId}/${expectation.version}/${expectation.abi}"
            val previous = "$root/packages/.previous/$safeFamily-$safeVersion-${expectation.abi}"
            listOf(stage, final, previous).forEach(DeviceBridgeManager::requireSafeRemotePath)
            val prep = bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(stage)} && mkdir -p ${DeviceBridgeManager.shellQuote(stage)}")
            check(prep.exitCode == 0) { prep.combined }

            try {
                val upload = entries.filterNot { it.isDirectory }
                _state.value = State(Phase.UPLOADING, "Installing package in Device Workstation…", totalItems = upload.size)
                upload.forEachIndexed { index, z ->
                    val remote = "$stage/${z.name}"
                    DeviceBridgeManager.requireSafeRemotePath(remote)
                    val parent = remote.substringBeforeLast('/', stage)
                    val mk = bridge.shell("mkdir -p ${DeviceBridgeManager.shellQuote(parent)}")
                    check(mk.exitCode == 0) { mk.combined }
                    val mode = if (z.name.removePrefix("payload/") in manifest.executablePaths && z.name.startsWith("payload/")) 493 else 420
                    zip.getInputStream(z).use { input -> bridge.pushStream(input, remote, mode = mode) }
                    _state.value = State(Phase.UPLOADING, "Installing package in Device Workstation…", completedItems = index + 1, totalItems = upload.size)
                }

                _state.value = State(Phase.VERIFYING, "Verifying installed package…")
                val verify = bridge.shell("cd ${DeviceBridgeManager.shellQuote(stage)} && toybox sha256sum -c ${DeviceBridgeManager.shellQuote(manifest.checksumFile)}")
                check(verify.exitCode == 0) { "Installed package verification failed: ${verify.combined}" }

                _state.value = State(Phase.HEALTH_CHECK, "Running package health checks…")
                healthCheck(stage, manifest)

                _state.value = State(Phase.ACTIVATING, "Activating package…")
                return promoteStagedPackage(stage, final, previous, manifest, expectation)
            } catch (error: Throwable) {
                runSuspendCatching { bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(stage)}") }
                throw error
            }
        }
    }

    private suspend fun promoteStagedPackage(
        stage: String,
        final: String,
        previous: String,
        manifest: ManagedPackageManifest,
        expectation: ManagedPackageInstallExpectation,
    ): ManagedPackageRecord {
        val parent = final.substringBeforeLast('/')
        val promote = bridge.shell(
            "set -eu; mkdir -p ${DeviceBridgeManager.shellQuote(parent)} ${DeviceBridgeManager.shellQuote(previous.substringBeforeLast('/'))}; " +
                "rm -rf ${DeviceBridgeManager.shellQuote(previous)}; " +
                "if [ -d ${DeviceBridgeManager.shellQuote(final)} ]; then mv ${DeviceBridgeManager.shellQuote(final)} ${DeviceBridgeManager.shellQuote(previous)}; fi; " +
                "mv ${DeviceBridgeManager.shellQuote(stage)} ${DeviceBridgeManager.shellQuote(final)}"
        )
        if (promote.exitCode != 0) {
            val rollback = withContext(NonCancellable) {
                bridge.shell(
                    "set -eu; rm -rf ${DeviceBridgeManager.shellQuote(final)}; " +
                        "if [ -d ${DeviceBridgeManager.shellQuote(previous)} ]; then mv ${DeviceBridgeManager.shellQuote(previous)} ${DeviceBridgeManager.shellQuote(final)}; fi"
                )
            }
            error("Package activation failed: ${promote.combined}; rollback=${rollback.exitCode == 0}")
        }
        try {
            healthCheck(final, manifest)
        } catch (failure: Throwable) {
            val rollback = withContext(NonCancellable) {
                bridge.shell(
                    "set -eu; rm -rf ${DeviceBridgeManager.shellQuote(final)}; " +
                        "if [ -d ${DeviceBridgeManager.shellQuote(previous)} ]; then mv ${DeviceBridgeManager.shellQuote(previous)} ${DeviceBridgeManager.shellQuote(final)}; fi"
                )
            }
            error("Package post-activation health check failed: ${failure.message}; rollback=${rollback.exitCode == 0}")
        }

        val commands = manifest.executablePaths.associate { relative ->
            val name = relative.substringAfterLast('/')
            require(name.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid package command name: $name" }
            name to "$final/payload/$relative"
        }
        require(commands.size == manifest.executablePaths.size) { "Managed package executable command names must be unique" }
        val record = ManagedPackageRecord(
            familyId = expectation.familyId,
            version = expectation.version,
            scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
            installRoot = final,
            installedAtEpochMs = System.currentTimeMillis(),
            pathEntries = manifest.pathEntries.map { "$final/payload/$it" },
            environment = manifest.environment.mapValues { (_, value) -> ManagedPackageInstallerSupport.resolveTemplate(value, final) },
            commands = commands,
            dependencies = manifest.dependencies,
            healthChecks = manifest.healthChecks,
            artifactSha256 = expectation.sha256,
            checksumFile = manifest.checksumFile,
            abi = expectation.abi,
        )
        try {
            activateUnlocked(record)
        } catch (failure: Throwable) {
            val rollback = withContext(NonCancellable) {
                bridge.shell(
                    "set -eu; rm -rf ${DeviceBridgeManager.shellQuote(final)}; " +
                        "if [ -d ${DeviceBridgeManager.shellQuote(previous)} ]; then mv ${DeviceBridgeManager.shellQuote(previous)} ${DeviceBridgeManager.shellQuote(final)}; fi"
                )
            }
            error("Package registry/command activation failed: ${failure.message}; rollback=${rollback.exitCode == 0}")
        }
        withContext(NonCancellable) {
            bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(previous)}")
        }
        return record
    }

    suspend fun reconcile(): List<ManagedPackageRecord> =
        ManagedPackageMutationGate.mutex.withLock { reconcileUnlocked() }

    private suspend fun reconcileUnlocked(): List<ManagedPackageRecord> {
        if (bridge.state.value.connected == null) return registry.list()
        val root = DeviceBridgeManager.remoteRoot()
        val before = registry.list()
        reconcileInterruptedRemovals(before, root)
        val valid = mutableListOf<ManagedPackageRecord>()
        for (record in before.take(512)) {
            if (!owns(record)) {
                valid += record
                continue
            }
            if (!record.installRoot.startsWith("$root/") || runCatching { DeviceBridgeManager.requireSafeRemotePath(record.installRoot) }.isFailure) continue
            if (record.installRoot.startsWith("$root/packages/")) {
                if (reconcileInterruptedPromotion(record, root)) valid += record
            } else {
                val present = bridge.shell("test -d ${DeviceBridgeManager.shellQuote(record.installRoot)}")
                if (present.exitCode == 0) valid += record
            }
        }
        val projectable = valid.filter { record ->
            !owns(record) || runCatching { dependencyClosure(record, valid) }.isSuccess
        }
        rebuildManagedBin(projectable)
        // A failed probe or missing payload is repair state, not permission to erase its receipt.
        return before
    }

    private suspend fun reconcileInterruptedRemovals(records: List<ManagedPackageRecord>, root: String) {
        val trashRoot = DevicePackageRemovalJournal.trashRoot(root)
        DeviceBridgeManager.requireSafeRemotePath(trashRoot)
        val prepare = bridge.shell(
            "set -eu; mkdir -p ${DeviceBridgeManager.shellQuote(trashRoot)}; test ! -L ${DeviceBridgeManager.shellQuote(trashRoot)}",
        )
        check(prepare.exitCode == 0) { "Could not prepare managed package removal journal: ${prepare.combined.takeLast(4_000)}" }

        val ownedRecords = records.filter(::owns)
        val expectedEntries = ownedRecords
            .filter { it.installRoot.startsWith("$root/packages/") }
            .associateBy { DevicePackageRemovalJournal.entryName(it) }
        for (record in ownedRecords) {
            requireOwner(record)
            if (!record.installRoot.startsWith("$root/packages/")) continue
            DeviceBridgeManager.requireSafeRemotePath(record.installRoot)
            val trash = DevicePackageRemovalJournal.trashPath(root, record)
            DeviceBridgeManager.requireSafeRemotePath(trash)
            val sourcePresent = bridge.shell("test -d ${DeviceBridgeManager.shellQuote(record.installRoot)}").exitCode == 0
            val trashPresent = bridge.shell("test -d ${DeviceBridgeManager.shellQuote(trash)}").exitCode == 0
            if (!trashPresent) continue
            check(bridge.shell("test ! -L ${DeviceBridgeManager.shellQuote(trash)}").exitCode == 0) {
                "Unsafe symbolic-link managed package removal journal"
            }
            if (sourcePresent) {
                check(bridge.shell("test ! -L ${DeviceBridgeManager.shellQuote(record.installRoot)}").exitCode == 0) {
                    "Managed package root became a symbolic link during removal recovery"
                }
            }

            val sourceHealthy = sourcePresent && runCatching { verifyInstalledRecord(record) }.getOrDefault(false)
            val trashRecord = relocateRecord(record, trash)
            val trashHealthy = runCatching { verifyInstalledRecord(trashRecord) }.getOrDefault(false)
            when (DevicePackageRemovalJournal.recoveryAction(
                receiptPresent = true,
                sourcePresent = sourcePresent,
                sourceHealthy = sourceHealthy,
                trashPresent = true,
                trashHealthy = trashHealthy,
            )) {
                DevicePackageRemovalJournal.RecoveryAction.RESTORE -> {
                    val restore = bridge.shell(
                        "set -eu; test ! -e ${DeviceBridgeManager.shellQuote(record.installRoot)}; " +
                            "mv ${DeviceBridgeManager.shellQuote(trash)} ${DeviceBridgeManager.shellQuote(record.installRoot)}",
                    )
                    check(restore.exitCode == 0) { "Could not restore interrupted managed package removal: ${restore.combined.takeLast(4_000)}" }
                }
                DevicePackageRemovalJournal.RecoveryAction.REPLACE_SOURCE_FROM_TRASH -> {
                    val restore = bridge.shell(
                        "set -eu; rm -rf ${DeviceBridgeManager.shellQuote(record.installRoot)}; " +
                            "mv ${DeviceBridgeManager.shellQuote(trash)} ${DeviceBridgeManager.shellQuote(record.installRoot)}",
                    )
                    check(restore.exitCode == 0) { "Could not recover healthy managed package removal journal: ${restore.combined.takeLast(4_000)}" }
                }
                DevicePackageRemovalJournal.RecoveryAction.DISCARD_TRASH -> {
                    val cleanup = bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(trash)}")
                    check(cleanup.exitCode == 0) { "Could not clean stale managed package removal journal: ${cleanup.combined.takeLast(4_000)}" }
                }
                DevicePackageRemovalJournal.RecoveryAction.HOLD_FOR_REPAIR,
                DevicePackageRemovalJournal.RecoveryAction.NONE -> Unit
            }
        }

        val listing = bridge.shellBounded(
            "for p in ${DeviceBridgeManager.shellQuote(trashRoot)}/*; do " +
                "if [ -L \"${'$'}p\" ]; then printf 'UNSAFE:%s\\n' \"${'$'}(basename \"${'$'}p\")\"; " +
                "elif [ -d \"${'$'}p\" ]; then basename \"${'$'}p\"; fi; done",
            maxOutputBytes = 64_000,
        )
        check(listing.exitCode == 0) { "Could not inspect managed package removal journal: ${listing.combined.takeLast(4_000)}" }
        listing.stdout.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().take(512).forEach { entry ->
            require(entry.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Unsafe managed package removal journal entry" }
            if (entry !in expectedEntries) {
                val stale = "$trashRoot/$entry"
                DeviceBridgeManager.requireSafeRemotePath(stale)
                val cleanup = bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(stale)}")
                check(cleanup.exitCode == 0) { "Could not finalize committed managed package removal: ${cleanup.combined.takeLast(4_000)}" }
            }
        }
    }

    private suspend fun reconcileInterruptedPromotion(record: ManagedPackageRecord, root: String): Boolean {
        val safeFamily = record.familyId.replace('.', '_').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = record.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val previousRoot = "$root/packages/.previous"
        val stagingRoot = "$root/packages/.staging"
        listOf(previousRoot, stagingRoot).forEach(DeviceBridgeManager::requireSafeRemotePath)
        val patterns = buildList {
            if (record.artifactSha256 != null && !record.abi.isNullOrBlank()) {
                require(record.abi.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid managed package ABI" }
                add("$safeFamily-$safeVersion-${record.abi}")
            } else {
                add("$safeFamily-$safeVersion-guest")
                add("$safeFamily-$safeVersion-recipe-*")
            }
        }
        val previousProbe = bridge.shellBounded(
            buildString {
                append("set -eu; mkdir -p ").append(DeviceBridgeManager.shellQuote(previousRoot)).append(' ')
                    .append(DeviceBridgeManager.shellQuote(stagingRoot)).append("; ")
                patterns.forEach { pattern ->
                    append("for p in ").append(DeviceBridgeManager.shellQuote(previousRoot)).append('/').append(pattern).append("; do ")
                    append("[ -d \"${'$'}p\" ] && printf '%s\\n' \"${'$'}p\" || true; done; ")
                }
            },
            maxOutputBytes = 32_768,
        )
        if (previousProbe.exitCode != 0) return false
        val previousCandidates = previousProbe.stdout.lineSequence().map(String::trim).filter(String::isNotBlank)
            .distinct().take(8).filter { candidate ->
                candidate.startsWith("$previousRoot/") &&
                    runCatching { DeviceBridgeManager.requireSafeRemotePath(candidate) }.isSuccess
            }.toList()
        if (previousCandidates.isEmpty()) {
            return bridge.shell("test -d ${DeviceBridgeManager.shellQuote(record.installRoot)}").exitCode == 0
        }


        if (verifyInstalledRecord(record)) {
            withContext(NonCancellable) { cleanupInterruptedPackagePaths(previousCandidates, stagingRoot, patterns) }
            return true
        }
        val recoverable = previousCandidates.firstOrNull { previous ->
            verifyInstalledRecord(relocateRecord(record, previous))
        } ?: return false
        withContext(NonCancellable) {
            val restore = bridge.shell(
                "set -eu; rm -rf ${DeviceBridgeManager.shellQuote(record.installRoot)}; " +
                    "mv ${DeviceBridgeManager.shellQuote(recoverable)} ${DeviceBridgeManager.shellQuote(record.installRoot)}",
            )
            check(restore.exitCode == 0) { "Could not restore interrupted managed package activation: ${restore.combined.takeLast(4_000)}" }
            cleanupInterruptedPackagePaths(previousCandidates.filterNot { it == recoverable }, stagingRoot, patterns)
        }
        return verifyInstalledRecord(record)
    }

    private suspend fun cleanupInterruptedPackagePaths(previous: List<String>, stagingRoot: String, patterns: List<String>) {
        val command = buildString {
            if (previous.isNotEmpty()) {
                append("rm -rf ").append(previous.joinToString(" ") { DeviceBridgeManager.shellQuote(it) }).append("; ")
            }
            patterns.forEach { pattern ->
                append("for p in ").append(DeviceBridgeManager.shellQuote(stagingRoot)).append('/').append(pattern).append("; do ")
                append("[ -d \"${'$'}p\" ] && rm -rf \"${'$'}p\" || true; done; ")
            }
        }
        bridge.shell(command)
    }

    private fun relocateRecord(record: ManagedPackageRecord, newRoot: String): ManagedPackageRecord {
        DeviceBridgeManager.requireSafeRemotePath(newRoot)
        val oldRoot = record.installRoot
        fun relocate(value: String): String = if (value == oldRoot || value.startsWith("$oldRoot/")) newRoot + value.removePrefix(oldRoot) else value
        return record.copy(
            installRoot = newRoot,
            pathEntries = record.pathEntries.map(::relocate),
            environment = record.environment.mapValues { (_, value) -> relocate(value) },
            commands = record.commands.mapValues { (_, value) -> relocate(value) },
        )
    }
    // Admit a source-reviewed live-ecosystem install after its local integrity manifest and health checks pass.
    suspend fun adoptReviewedRecord(record: ManagedPackageRecord) =
        ManagedPackageMutationGate.mutex.withLock {
            requireOwner(record)
            requireFamilyOwner(record, registry.list())
            require(record.artifactSha256 == null) { "Reviewed live recipes must not masquerade as artifact-pinned packages" }
            val records = registry.list()
            val closure = dependencyClosure(record, records)
            closure.forEach(::requireOwner)
            require(closure.all { verifyInstalledRecord(it) }) { "Reviewed recipe dependency failed integrity or health verification" }
            require(verifyInstalledRecord(record)) { "Reviewed recipe failed integrity or health verification" }
            activateUnlocked(record)
        }

    // Admit a reviewed release artifact after exact download digest, local integrity and health checks pass.
    suspend fun adoptTrustedArtifactRecord(record: ManagedPackageRecord) = ManagedPackageMutationGate.mutex.withLock {
        requireOwner(record)
        requireFamilyOwner(record, registry.list())
        require(record.artifactSha256?.matches(Regex("[0-9a-f]{64}")) == true) { "Trusted release artifacts require an exact SHA-256 ownership record" }
        require(!record.abi.isNullOrBlank()) { "Trusted release artifacts require an explicit ABI" }
        val closure = dependencyClosure(record, registry.list())
        closure.forEach(::requireOwner)
        require(closure.all { verifyInstalledRecord(it) }) { "Trusted release dependency failed integrity or health verification" }
        require(verifyInstalledRecord(record)) { "Trusted release artifact failed integrity or health verification" }
        activateUnlocked(record)
    }

    // The wrapper projection and registry update are one rollback-safe mutation.


    suspend fun rebindDependencies(record: ManagedPackageRecord, dependencyKeys: List<String>): ManagedPackageRecord =
        ManagedPackageMutationGate.mutex.withLock {
            requireOwner(record)
            require(dependencyKeys.size <= 32) { "Too many managed package dependencies" }
            val normalized = dependencyKeys.distinct().sorted()
            val before = registry.list()
            val current = before.firstOrNull { it.familyId == record.familyId && it.version == record.version }
                ?: error("Managed package disappeared during dependency migration")
            requireFamilyOwner(current, before)
            val updated = current.copy(dependencies = normalized)
            val next = before.map { candidate ->
                if (candidate.familyId == current.familyId && candidate.version == current.version) updated else candidate
            }
            val closure = dependencyClosure(updated, next)
            closure.forEach(::requireOwner)
            require(closure.all { verifyInstalledRecord(it) }) { "Migrated package dependency failed integrity or health verification" }
            require(verifyInstalledRecord(updated)) { "Managed package failed health verification during dependency migration" }
            PackageProjectionCommit.commit(before, next, ::rebuildManagedBin, registry::replaceAll)
            updated
        }

    suspend fun activate(record: ManagedPackageRecord) =
        ManagedPackageMutationGate.mutex.withLock { activateUnlocked(record) }
    private suspend fun activateUnlocked(record: ManagedPackageRecord) {
        val before = registry.list()
        requireFamilyOwner(record, before)
        val closure = dependencyClosure(record, before)
        (closure + record).forEach(::requireOwner)
        val existing = before.filterNot { it.familyId == record.familyId && it.version == record.version }
        val next = existing.map { if (it.familyId == record.familyId) it.copy(active = false) else it } + record.copy(active = true, metadata = record.metadata + (PackageBackendContract.METADATA_KEY to backendId.name))
        PackageProjectionCommit.commit(before, next, ::rebuildManagedBin, registry::replaceAll)
    }

    suspend fun uninstall(record: ManagedPackageRecord): String =
        ManagedPackageMutationGate.mutex.withLock { uninstallUnlocked(record) }

    private suspend fun uninstallUnlocked(record: ManagedPackageRecord): String = withContext(Dispatchers.IO) {
        requireOwner(record)
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        require(record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name) { "Only Device Workstation managed packages can be uninstalled here" }
        DeviceBridgeManager.requireSafeRemotePath(record.installRoot)
        val root = DeviceBridgeManager.remoteRoot()
        require(record.installRoot.startsWith("$root/packages/") && !record.installRoot.contains("/.trash/")) {
            "Refusing to remove a package outside Droide managed package storage"
        }

        val before = registry.list()
        check(before.any { it.familyId == record.familyId && it.version == record.version }) { "Managed package receipt is missing" }
        requireFamilyOwner(record, before)
        val key = "${record.familyId}@${record.version}"
        val dependents = before.filter { it.familyId != record.familyId || it.version != record.version }
            .filter { key in it.dependencies }
        require(dependents.isEmpty()) {
            "Package is required by: " + dependents.joinToString { "${it.familyId}@${it.version}" }
        }

        val remaining = before.filterNot { it.familyId == record.familyId && it.version == record.version }.toMutableList()
        if (record.active) {
            val fallback = remaining.filter { it.familyId == record.familyId }.maxByOrNull { it.installedAtEpochMs }
            if (fallback != null) {
                val index = remaining.indexOfFirst { it.familyId == fallback.familyId && it.version == fallback.version }
                remaining[index] = fallback.copy(active = true)
            }
        }

        val trashRoot = DevicePackageRemovalJournal.trashRoot(root)
        val trash = DevicePackageRemovalJournal.trashPath(root, record)
        listOf(trashRoot, trash).forEach(DeviceBridgeManager::requireSafeRemotePath)
        val stage = bridge.shell(
            "set -eu; mkdir -p ${DeviceBridgeManager.shellQuote(trashRoot)}; test ! -L ${DeviceBridgeManager.shellQuote(trashRoot)}; " +
                "test -d ${DeviceBridgeManager.shellQuote(record.installRoot)}; test ! -L ${DeviceBridgeManager.shellQuote(record.installRoot)}; " +
                "test ! -e ${DeviceBridgeManager.shellQuote(trash)}; " +
                "mv ${DeviceBridgeManager.shellQuote(record.installRoot)} ${DeviceBridgeManager.shellQuote(trash)}",
        )
        check(stage.exitCode == 0) { "Could not stage managed package removal: ${stage.combined.takeLast(4_000)}" }

        try {
            PackageProjectionCommit.commit(before, remaining, ::rebuildManagedBin, registry::replaceAll)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                val restore = bridge.shell(
                    "set -eu; if [ -d ${DeviceBridgeManager.shellQuote(trash)} ] && [ ! -e ${DeviceBridgeManager.shellQuote(record.installRoot)} ]; then " +
                        "mv ${DeviceBridgeManager.shellQuote(trash)} ${DeviceBridgeManager.shellQuote(record.installRoot)}; fi",
                )
                if (restore.exitCode != 0) failure.addSuppressed(
                    IllegalStateException("Could not restore managed package removal journal: ${restore.combined.takeLast(4_000)}")
                )
            }
            throw failure
        }

        val cleanupOk = withContext(NonCancellable) {
            runCatching { bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(trash)}").exitCode == 0 }.getOrDefault(false)
        }
        if (cleanupOk) {
            "Uninstalled ${record.familyId} ${record.version}."
        } else {
            "Uninstalled ${record.familyId} ${record.version}; staged files will be reclaimed during package recovery."
        }
    }

    suspend fun verify(record: ManagedPackageRecord): Boolean =
        ManagedPackageMutationGate.mutex.withLock { verifyUnlocked(record) }

    private suspend fun verifyUnlocked(record: ManagedPackageRecord): Boolean = withContext(Dispatchers.IO) {
        if (!owns(record) || bridge.state.value.connected == null) return@withContext false
        val allRecords = registry.list()
        val closure = runCatching { dependencyClosure(record, allRecords) }.getOrElse { return@withContext false }
        (closure + record).all { candidate -> verifyInstalledRecord(candidate) }
    }

    private suspend fun verifyInstalledRecord(candidate: ManagedPackageRecord): Boolean {
        if (!owns(candidate)) return false
        if (runCatching { DeviceBridgeManager.requireSafeRemotePath(candidate.installRoot) }.isFailure) return false
        if (bridge.shell("test -d ${DeviceBridgeManager.shellQuote(candidate.installRoot)}").exitCode != 0) return false
        val root = DeviceBridgeManager.remoteRoot()
        if (candidate.installRoot.startsWith("$root/packages/")) {
            val integrity = bridge.shellBounded(
                "cd ${DeviceBridgeManager.shellQuote(candidate.installRoot)} && test -f SHA256SUMS && toybox sha256sum -c SHA256SUMS",
                maxOutputBytes = 128_000,
            )
            if (integrity.exitCode != 0) return false
        }
        if (!candidate.commands.values.all { target ->
                target.startsWith(candidate.installRoot + "/") &&
                    runCatching { DeviceBridgeManager.requireSafeRemotePath(target) }.isSuccess &&
                    bridge.shell("test -x ${DeviceBridgeManager.shellQuote(target)}").exitCode == 0
            }) return false

        // Never let an old, weaker health contract grandfather a guest runtime into READY.


        val currentGuestRecipe = WorkstationGuestPackageCatalog.find(candidate.familyId, candidate.version)
        if (currentGuestRecipe != null && candidate.artifactSha256 == null) {
            if (candidate.commands.keys != currentGuestRecipe.commands.keys) return false
            val requiredAdmissionContract = currentGuestRecipe.admissionContractSha256()
            if (requiredAdmissionContract != null &&
                candidate.metadata[WORKSTATION_GUEST_ADMISSION_CONTRACT_KEY] != requiredAdmissionContract
            ) return false
        }
        val effectiveHealthChecks = (candidate.healthChecks +
            currentGuestRecipe?.requiredManagedHealthChecks().orEmpty()).distinct()
        for (check in effectiveHealthChecks) {
            val executable = "${candidate.installRoot}/payload/${check.executable}"
            if (runCatching { DeviceBridgeManager.requireSafeRemotePath(executable) }.isFailure) return false
            val command = buildString {
                append(DeviceBridgeManager.shellQuote(executable))
                check.args.forEach { arg -> append(' ').append(DeviceBridgeManager.shellQuote(arg)) }
            }
            if (bridge.shellBounded(command, maxOutputBytes = 64_000).exitCode != 0) return false
        }
        return true
    }

    private suspend fun rebuildManagedBin(records: List<ManagedPackageRecord>) {
        val root = DeviceBridgeManager.remoteRoot()
        val managedBin = "$root/managed/bin"
        DeviceBridgeManager.requireSafeRemotePath(managedBin)
        val active = records.filter { it.active && owns(it) }.sortedBy { it.installedAtEpochMs }
        active.forEach { record -> (dependencyClosure(record, records) + record).forEach(::requireOwner) }
        val prepare = bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(managedBin)} && mkdir -p ${DeviceBridgeManager.shellQuote(managedBin)}")
        check(prepare.exitCode == 0) { prepare.combined }
        val commandOwners = linkedMapOf<String, String>()
        active.forEach { record ->
            record.commands.keys.forEach { command ->
                val owner = "${record.familyId}@${record.version}"
                val previous = commandOwners.putIfAbsent(command, owner)
                require(previous == null || previous == owner) {
                    "Managed command collision for '$command': $previous and $owner. Select one provider/version before activation."
                }
            }
        }
        for (record in active) {
            val dependencies = dependencyClosure(record, records)
            val wrapperEnvironment = linkedMapOf<String, String>().apply {
                dependencies.forEach { putAll(it.environment) }
                putAll(record.environment)
            }
            val exactDependencyPath = (dependencies + record).flatMap { candidate ->
                candidate.pathEntries + candidate.commands.values.mapNotNull { target ->
                    target.substringBeforeLast('/', missingDelimiterValue = "").takeIf(String::isNotBlank)
                }
            }.distinct().onEach(DeviceBridgeManager::requireSafeRemotePath)

            record.commands.forEach { (name, target) ->
                require(name.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid managed command name" }
                DeviceBridgeManager.requireSafeRemotePath(target)
                if (!target.startsWith(record.installRoot + "/")) return@forEach
                val wrapper = "$managedBin/$name"
                DeviceBridgeManager.requireSafeRemotePath(wrapper)
                val script = buildString {
                    append("#!/system/bin/sh\n")
                    if (exactDependencyPath.isNotEmpty()) {
                        append("export PATH=").append(DeviceBridgeManager.shellQuote(exactDependencyPath.joinToString(":")))
                            .append(":\"${'$'}{PATH:-/system/bin:/system/xbin}\"\n")
                    }
                    wrapperEnvironment.forEach { (key, value) ->
                        append("export ").append(key).append('=').append(DeviceBridgeManager.shellQuote(value)).append("\n")
                    }
                    append("exec ").append(DeviceBridgeManager.shellQuote(target)).append(" \"${'$'}@\"\n")
                }
                ByteArrayInputStream(script.toByteArray(Charsets.UTF_8)).use { input ->
                    bridge.pushStream(input, wrapper, mode = 493)
                }
            }
        }
        
        // Do not project the unauthenticated local CLI prototype onto an ADB target, even when it is the same phone.

        val droideWrapper = "$managedBin/droide"
        val droideScript = "#!/system/bin/sh\necho 'Package CLI unavailable on DEVICE_ADB; use Droide Extensions.' >&2\nexit 69\n"
        ByteArrayInputStream(droideScript.toByteArray(Charsets.UTF_8)).use { input ->
            bridge.pushStream(input, droideWrapper, mode = 493)
        }
    }

    private fun dependencyClosure(record: ManagedPackageRecord, records: List<ManagedPackageRecord>): List<ManagedPackageRecord> {
        val byKey = records.associateBy { "${it.familyId}@${it.version}" }
        val ordered = linkedMapOf<String, ManagedPackageRecord>()
        val visiting = mutableSetOf<String>()
        fun visit(current: ManagedPackageRecord) {
            require(ordered.size <= 128) { "Managed package dependency graph is too large" }
            current.dependencies.forEach { key ->
                require(visiting.add(key)) { "Managed package dependency cycle detected at $key" }
                val dependency = byKey[key] ?: error("Managed package dependency is missing: $key")
                visit(dependency)
                visiting.remove(key)
                ordered[key] = dependency
            }
        }
        visit(record)
        return ordered.values.toList()
    }

    private suspend fun healthCheck(root: String, manifest: ManagedPackageManifest) {
        manifest.executablePaths.forEach { relative ->
            val target = "$root/payload/$relative"
            DeviceBridgeManager.requireSafeRemotePath(target)
            val result = bridge.shell("test -x ${DeviceBridgeManager.shellQuote(target)}")
            check(result.exitCode == 0) { "Package executable is missing or not executable: $relative" }
        }
        manifest.healthChecks.forEach { check ->
            require(check.args.size <= 16 && check.args.all { it.length <= 200 }) { "Invalid package health-check arguments" }
            val executable = "$root/payload/${check.executable}"
            DeviceBridgeManager.requireSafeRemotePath(executable)
            val command = buildString {
                append(DeviceBridgeManager.shellQuote(executable))
                check.args.forEach { arg -> append(' ').append(DeviceBridgeManager.shellQuote(arg)) }
            }
            val result = bridge.shellBounded(command, maxOutputBytes = 64_000)
            check(result.exitCode == 0) { "Package health check failed for ${check.executable}: ${result.combined}" }
        }
    }

    private fun validateManifest(manifest: ManagedPackageManifest, expectation: ManagedPackageInstallExpectation) {
        ManagedPackageInstallerSupport.validateManifestShape(manifest)
        require(
            manifest.familyId == expectation.familyId &&
                manifest.version == expectation.version &&
                manifest.abi == expectation.abi
        ) { "Managed package manifest/install expectation mismatch" }
        require(manifest.dependencies == expectation.dependencies) {
            "Managed package dependency metadata does not match the install expectation"
        }
        expectation.reviewedManifest?.let { reviewed ->
            require(manifest == reviewed) { "Managed package manifest changed after candidate review" }
        }
    }


    companion object {
        private const val MAX_ENTRIES = 4096
        private const val MAX_FILE_BYTES = 512L * 1024L * 1024L
        private const val MAX_UNCOMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L
        private const val MANAGED_PACKAGE_TRANSACTION_OVERHEAD_BYTES = 64L * 1024L * 1024L
    }
}
