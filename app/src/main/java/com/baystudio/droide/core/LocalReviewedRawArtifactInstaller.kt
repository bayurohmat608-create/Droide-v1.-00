package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class LocalReviewedRawLock(
    val schema: Int = 1,
    val familyId: String,
    val version: String,
    val installer: String = "local-ubuntu-reviewed-raw-v1",
    val repository: String,
    val releaseTag: String,
    val command: String,
    val assetName: String,
    val assetUrl: String,
    val assetSha256: String,
    val assetSize: Long,
    val provenanceUrl: String,
    val guestProfile: String,
    val libcCompatibility: String,
    val elfInterpreter: String,
    val runtimeDependencies: List<String>,
    val compatibilityEvidenceUrl: String,
    val installedAtEpochMs: Long,
)


class LocalReviewedRawArtifactInstaller(
    context: Context,
    private val localAuthority: LocalManagedPackageAuthority,
    private val ubuntuEnvironment: FoundryUbuntuGuestEnvironmentManager,
) {
    private val appContext = context.applicationContext
    private val downloader = UserInitiatedArtifactTransfer(appContext)
    private val json = Json { encodeDefaults = true }
    private val root = appContext.filesDir.canonicalPath

    fun supports(recipe: GitHubReleaseInstallRecipe): Boolean = LocalUbuntuReviewedRawPolicy.supports(recipe)

    suspend fun install(recipe: GitHubReleaseInstallRecipe): ManagedPackageRecord = withContext(Dispatchers.IO) {
        require(supports(recipe)) {
            "This reviewed artifact is outside the certified app-local Ubuntu RAW/glibc cohort"
        }
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) {
            "Local Ubuntu reviewed artifacts currently require ARM64"
        }
        PackageBackendContract.requireSame(
            "Local reviewed Ubuntu artifact",
            PackageBackendId.LOCAL_APP,
            localAuthority.backendId,
            ubuntuEnvironment.backendId,
        )

        ubuntuEnvironment.ensureInstalled()
        val layer = ubuntuEnvironment.ensurePackages(recipe.runtimeCompatibility.requiredGuestPackages)
        val dependencyRecords = listOfNotNull(layer)
        dependencyRecords.forEach { dependency ->
            localAuthority.adopt(dependency)
            check(localAuthority.verify(dependency)) {
                "Ubuntu runtime dependency ${dependency.familyId}@${dependency.version} failed verification"
            }
        }
        PackageRuntimeCompatibilityPolicy.verifyGuestCommands(ubuntuEnvironment, recipe.runtimeCompatibility)

        val safeFamily = recipe.familyId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = recipe.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val transactionPaths = LocalPackageInstallJournal.paths(
            root, recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW,
        )
        val stage = transactionPaths.stage.absolutePath
        val final = transactionPaths.final.absolutePath
        val previous = transactionPaths.previous.absolutePath
        val stageBin = "$stage/payload/bin/${recipe.command}"
        val stageHostBin = "$stage/payload/host-bin/${recipe.command}"
        val finalBin = "$final/payload/bin/${recipe.command}"
        val finalHostBin = "$final/payload/host-bin/${recipe.command}"
        listOf(stage, final, previous, stageBin, stageHostBin, finalBin, finalHostBin)
            .forEach(LocalExecutionSubstrate::requireSafeLocalPath)

        val artifactSpec = TrustedArtifactSpec(
            id = "local-ubuntu-${safeFamily.take(40)}-${safeVersion.take(24)}",
            url = recipe.assetUrl,
            sha256 = recipe.assetSha256,
            fileName = recipe.assetName,
            maxBytes = recipe.assetSize,
            expectedBytes = recipe.assetSize,
        )
        val artifact = downloader.download(artifactSpec)
        val headroom = Math.addExact(Math.multiplyExact(recipe.assetSize, 2L), 64L * 1024L * 1024L)
        DeviceWorkstationStorageGuard.requireHeadroom(
            additionalBytes = headroom,
            purpose = "install local Ubuntu artifact ${recipe.familyId} ${recipe.version}",
        )

        val stageDir = File(stage)
        val finalDir = File(final)
        val previousDir = File(previous)
        LocalPackageInstallJournal.transactionMutex.withLock {
            val existingReceipt = localAuthority.ownedReceipt(recipe.familyId, recipe.version)
            LocalPackageInstallJournal.recover(stageDir, finalDir, previousDir,
                existingReceipt?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
            check(File(stage, "payload/bin").mkdirs() && File(stage, "payload/host-bin").mkdirs()) {
                "Could not prepare local Ubuntu package transaction"
            }

            var activation: LocalPackageInstallJournal.Activation? = null
            try {
                val activationId = LocalPackageInstallJournal.prepare(stageDir)
                artifact.inputStream().use { LocalExecutionSubstrate.pushStream(it, stageBin, mode = 493) }
                val transferred = File(stageBin)
                check(transferred.length() == recipe.assetSize) { "Local Ubuntu artifact size changed during staging" }
                check(sha256(transferred) == recipe.assetSha256.lowercase()) { "Local Ubuntu artifact SHA-256 changed during staging" }

                val elf = LinuxArm64ElfAdmission.requireUbuntuGlibc(transferred)
                val health = ubuntuEnvironment.execute(listOf(stageBin) + recipe.healthArgs, maxOutputBytes = 128_000)
                check(health.exitCode == 0) {
                    "Local Ubuntu artifact health check failed: ${health.output.takeLast(8_000)}"
                }

                val wrapperScript = "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${q(ubuntuEnvironment.launcherPath())} ${q(finalBin)} \"${'$'}@\"\n"
                ByteArrayInputStream(wrapperScript.toByteArray(Charsets.UTF_8)).use {
                    LocalExecutionSubstrate.pushStream(it, stageHostBin, mode = 493)
                }

                val dependencyKeys = dependencyRecords.map { "${it.familyId}@${it.version}" }.distinct().sorted()
                val lock = LocalReviewedRawLock(
                    familyId = recipe.familyId,
                    version = recipe.version,
                    repository = recipe.repository,
                    releaseTag = recipe.releaseTag,
                    command = recipe.command,
                    assetName = recipe.assetName,
                    assetUrl = recipe.assetUrl,
                    assetSha256 = recipe.assetSha256,
                    assetSize = recipe.assetSize,
                    provenanceUrl = recipe.provenanceUrl,
                    guestProfile = recipe.runtimeCompatibility.guestProfile.name,
                    libcCompatibility = recipe.runtimeCompatibility.libc.name,
                    elfInterpreter = elf.interpreter,
                    runtimeDependencies = dependencyKeys,
                    compatibilityEvidenceUrl = recipe.runtimeCompatibility.evidenceUrl,
                    installedAtEpochMs = System.currentTimeMillis(),
                )
                ByteArrayInputStream(json.encodeToString(lock).toByteArray(Charsets.UTF_8)).use {
                    LocalExecutionSubstrate.pushStream(it, "$stage/RECIPE_LOCK.json", mode = 420)
                }

                val seal = LocalExecutionSubstrate.shellBounded(
                    "set -eu; cd ${q(stage)}; " +
                        "find . -type f ! -name SHA256SUMS -print | sort | while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done > SHA256SUMS; " +
                        "toybox sha256sum -c SHA256SUMS",
                    maxOutputBytes = 128_000,
                )
                check(seal.exitCode == 0) { "Local Ubuntu package integrity manifest failed: ${seal.output.takeLast(8_000)}" }

                activation = LocalPackageInstallJournal.activate(stageDir, finalDir, previousDir)

                val record = ManagedPackageRecord(
                    familyId = recipe.familyId,
                    version = recipe.version,
                    scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                    installRoot = final,
                    installedAtEpochMs = System.currentTimeMillis(),
                    active = true,
                    pathEntries = listOf("$final/payload/host-bin"),
                    commands = mapOf(recipe.command to finalHostBin),
                    dependencies = dependencyKeys,
                    healthChecks = listOf(ManagedPackageHealthCheck("host-bin/${recipe.command}", recipe.healthArgs)),
                    artifactSha256 = recipe.assetSha256.lowercase(),
                    abi = "arm64-v8a",
                    metadata = mapOf(
                        LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activationId,
                        LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW,
                        LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                        LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name,
                        LocalManagedPackageMetadata.COMMAND_KEY to recipe.command,
                        LocalManagedPackageMetadata.ELF_INTERPRETER_KEY to elf.interpreter,
                    ),
                )
                localAuthority.adopt(record)
                activation = null // The durable receipt is now the commit marker.
                withContext(NonCancellable) { LocalPackageInstallJournal.commit(previousDir) }
                record
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    activation?.let { committed ->
                        runCatching { LocalPackageInstallJournal.rollback(finalDir, previousDir, committed) }
                            .exceptionOrNull()?.let(failure::addSuppressed)
                    }
                    if (!PathSecurity.deleteTreeNoFollow(stageDir)) {
                        failure.addSuppressed(IllegalStateException("Could not clean failed package staging tree"))
                    }
                }
                throw failure
            } finally {
                if (artifact.length() >= 96L * 1024L * 1024L) runCatching { downloader.discard(artifactSpec) }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
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

    private fun q(value: String): String = LocalExecutionSubstrate.shellQuote(value)
}
