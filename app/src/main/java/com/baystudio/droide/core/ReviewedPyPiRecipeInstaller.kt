package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

// The installer then asks pip for a dry-run installation report, rejects direct/yanked/non-PyPI artifacts.


data class PyPiInstallRecipe(
    val familyId: String,
    val version: String,
    val packageName: String,
    val packageVersion: String,
    val command: String,
    val minimumPythonMajor: Int,
    val minimumPythonMinor: Int,
    val provenanceUrl: String,
    val requiresPython: String? = null,
    val releaseWheelSha256: Set<String>,
    val healthArgs: List<String> = listOf("--version"),
) {
    fun validate() {
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid PyPI recipe family" }
        require(packageName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}"))) { "Invalid PyPI package name" }
        require(packageVersion.matches(Regex("[0-9A-Za-z][0-9A-Za-z._+!-]{0,79}"))) { "Invalid PyPI version" }
        require(version == packageVersion) { "PyPI ownership version must be exact" }
        require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid PyPI command" }
        require(minimumPythonMajor == 3 && minimumPythonMinor in 8..20) { "Invalid Python minimum" }
        require(provenanceUrl.startsWith("https://")) { "PyPI provenance must use HTTPS" }
        requiresPython?.let { require(it.length <= 200 && '\u0000' !in it && '\n' !in it && '\r' !in it) { "Invalid Requires-Python metadata" } }
        require(releaseWheelSha256.isNotEmpty() && releaseWheelSha256.size <= 128) { "PyPI release must expose bounded wheel hashes" }
        require(releaseWheelSha256.all { it.matches(Regex("[0-9a-f]{64}")) }) { "Invalid PyPI wheel SHA-256" }
        require(healthArgs.size <= 8 && healthArgs.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }) { "Invalid PyPI health arguments" }
    }
}

@Serializable
private data class PipArchiveInfo(
    val hashes: Map<String, String> = emptyMap(),
)

@Serializable
private data class PipDownloadInfo(
    val url: String,
    @SerialName("archive_info") val archiveInfo: PipArchiveInfo = PipArchiveInfo(),
)

@Serializable
private data class PipMetadata(
    val name: String,
    val version: String,
    @SerialName("requires_python") val requiresPython: String? = null,
)

@Serializable
private data class PipInstallItem(
    @SerialName("download_info") val downloadInfo: PipDownloadInfo,
    @SerialName("is_direct") val isDirect: Boolean = false,
    @SerialName("is_yanked") val isYanked: Boolean = false,
    val requested: Boolean = false,
    val metadata: PipMetadata,
)

@Serializable
private data class PipInstallReport(
    val version: String,
    @SerialName("pip_version") val pipVersion: String,
    val install: List<PipInstallItem> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
)

@Serializable
private data class PyPiClosureArtifact(
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
)

@Serializable
private data class ReviewedPyPiRecipeLock(
    val schema: Int = 1,
    val familyId: String,
    val version: String,
    val installer: String = "pypi-pip-wheel-closure",
    val packageName: String,
    val packageVersion: String,
    val command: String,
    val provenanceUrl: String,
    val requiresPython: String? = null,
    val pythonVersion: String,
    val pythonDependency: String,
    val pipVersion: String,
    val artifacts: List<PyPiClosureArtifact>,
    val installedAtEpochMs: Long,
)

class ReviewedPyPiRecipeInstaller(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val packageInstaller: ManagedPackageInstaller,
    private val environment: WorkstationGuestEnvironmentManager = WorkstationGuestEnvironmentManager(context.applicationContext),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun probePythonVersion(pythonDependency: ManagedPackageRecord): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        PackageBackendContract.requireSame("ReviewedPyPiRecipeInstaller", PackageBackendId.DEVICE_ADB, packageInstaller.backendId, environment.backendId)
        if (bridge.state.value.connected == null || pythonDependency.familyId != "runtime.python") return@withContext null
        val python = pythonDependency.commands["python3"] ?: pythonDependency.commands["python"] ?: return@withContext null
        if (runCatching { DeviceBridgeManager.requireSafeRemotePath(python) }.isFailure) return@withContext null
        val result = bridge.shellBounded(
            DeviceBridgeManager.shellQuote(python) + " -c " + DeviceBridgeManager.shellQuote("import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"),
            maxOutputBytes = 8_192,
        )
        if (result.exitCode != 0) return@withContext null
        val parts = result.stdout.trim().lineSequence().lastOrNull().orEmpty().split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return@withContext null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return@withContext null
        major to minor
    }

    suspend fun install(recipe: PyPiInstallRecipe, pythonDependency: ManagedPackageRecord): ManagedPackageRecord = withContext(Dispatchers.IO) {
        PackageBackendContract.requireSame("ReviewedPyPiRecipeInstaller", PackageBackendId.DEVICE_ADB, packageInstaller.backendId, environment.backendId)
        recipe.validate()
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) { "Reviewed PyPI recipes currently require ARM64" }
        require(pythonDependency.familyId == "runtime.python") { "Reviewed PyPI recipe requires a managed Python dependency" }
        require(packageInstaller.verify(pythonDependency)) { "Managed Python dependency failed integrity or health verification" }
        val pythonVersionPair = probePythonVersion(pythonDependency) ?: error("Managed Python dependency is not executable")
        require(pythonVersionPair.first > recipe.minimumPythonMajor ||
            pythonVersionPair.first == recipe.minimumPythonMajor && pythonVersionPair.second >= recipe.minimumPythonMinor) {
            "${recipe.familyId} requires Python ${recipe.minimumPythonMajor}.${recipe.minimumPythonMinor}+; managed dependency has ${pythonVersionPair.first}.${pythonVersionPair.second}"
        }
        val pythonVersion = "${pythonVersionPair.first}.${pythonVersionPair.second}"
        val pythonCommand = pythonDependency.commands["python3"] ?: pythonDependency.commands["python"] ?: error("Managed Python dependency does not export python3")
        DeviceBridgeManager.requireSafeRemotePath(pythonCommand)
        val pipProbe = bridge.shellBounded(DeviceBridgeManager.shellQuote(pythonCommand) + " -m pip --version", maxOutputBytes = 16_384)
        check(pipProbe.exitCode == 0) { "Managed Python dependency does not expose pip" }
        val pipVersion = Regex("pip\\s+([0-9]+(?:\\.[0-9A-Za-z-]+){1,4})").find(pipProbe.stdout)?.groupValues?.getOrNull(1)
            ?: error("Could not verify pip version")
        val pipMajor = pipVersion.substringBefore('.').toIntOrNull() ?: 0
        require(pipMajor >= 23) { "Reviewed PyPI transactions require pip 23+ installation-report format" }

        environment.ensureInstalled()
        val root = DeviceBridgeManager.remoteRoot()
        val safeFamily = recipe.familyId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = recipe.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val transactionId = UUID.randomUUID().toString().replace("-", "").take(16)
        val stage = "$root/packages/.staging/$safeFamily-$safeVersion-pypi-$transactionId"
        val final = "$root/packages/${recipe.familyId}/$safeVersion/arm64-v8a"
        val previous = "$root/packages/.previous/$safeFamily-$safeVersion-pypi"
        val resolveRoot = "$stage/.resolve"
        val reportPath = "$resolveRoot/pip-report.json"
        val wheelRoot = "$resolveRoot/wheels"
        val requirementsPath = "$resolveRoot/locked-requirements.txt"
        val siteRoot = "$stage/payload/python"
        val binRoot = "$stage/payload/bin"
        listOf(stage, final, previous, resolveRoot, reportPath, wheelRoot, requirementsPath, siteRoot, binRoot).forEach(DeviceBridgeManager::requireSafeRemotePath)

        DeviceWorkstationStorageGuard.requireHeadroom(
            bridge,
            additionalBytes = PYPI_PACKAGE_MAX_BYTES + PYPI_TRANSIENT_HEADROOM_BYTES,
            purpose = "install reviewed PyPI recipe ${recipe.familyId} ${recipe.version}",
        )
        val prep = bridge.shell(
            "rm -rf ${q(stage)} ${q(previous)} && mkdir -p ${q(resolveRoot)} ${q(wheelRoot)} ${q(siteRoot)} ${q(binRoot)} ${q("$root/packages/.previous")}",
        )
        check(prep.exitCode == 0) { "Could not prepare PyPI transaction: ${prep.combined}" }

        var projectionActivated = false
        try {
            val exact = "${recipe.packageName}==${recipe.packageVersion}"
            val resolverCommand = pipEnvironment() +
                " /usr/bin/python3 -m pip --isolated install --dry-run --ignore-installed --no-cache-dir --disable-pip-version-check --no-input" +
                " --report ${guestQ(reportPath)} --only-binary=:all: --index-url https://pypi.org/simple ${guestQ(exact)}"
            val resolve = runLeasedGuestShell("pypi-resolve", resolverCommand, 1_500_000)
            check(resolve.exitCode == 0) { "PyPI dependency resolution failed: ${resolve.combined.takeLast(12_000)}" }

            val reportRead = bridge.shellBounded("cat ${q(reportPath)}", maxOutputBytes = 1_000_000)
            check(reportRead.exitCode == 0) { "Could not read pip installation report" }
            val report = json.decodeFromString<PipInstallReport>(reportRead.stdout)
            val artifacts = validateReport(recipe, report, pipVersion, pythonVersion)
            val requirements = artifacts.sortedWith(compareBy<PyPiClosureArtifact> { normalizeProjectName(it.name) }.thenBy { it.version })
                .joinToString(separator = "\n", postfix = "\n") { artifact ->
                    "${artifact.name}==${artifact.version} --hash=sha256:${artifact.sha256}"
                }
            ByteArrayInputStream(requirements.toByteArray(Charsets.UTF_8)).use { bridge.pushStream(it, requirementsPath, mode = 420) }

            val downloadCommand = pipEnvironment() +
                " /usr/bin/python3 -m pip --isolated download --require-hashes --no-cache-dir --disable-pip-version-check --no-input" +
                " --only-binary=:all: --index-url https://pypi.org/simple --dest ${guestQ(wheelRoot)} -r ${guestQ(requirementsPath)}"
            val download = runLeasedGuestShell("pypi-download", downloadCommand, 1_500_000)
            check(download.exitCode == 0) { "PyPI wheel-closure download failed: ${download.combined.takeLast(12_000)}" }

            val wheelAudit = bridge.shellBounded(
                "set -eu; C=${'$'}(find ${q(wheelRoot)} -type f -name '*.whl' | wc -l); " +
                    "test \"${'$'}C\" -eq ${artifacts.size}; find ${q(wheelRoot)} -type f -name '*.whl' -print | sort | while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done",
                maxOutputBytes = 256_000,
            )
            check(wheelAudit.exitCode == 0) { "Downloaded PyPI closure is incomplete or contains non-wheel candidates" }
            val downloadedHashes = wheelAudit.stdout.lineSequence().mapNotNull { line ->
                line.trim().substringBefore(' ').takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            }.toSet()
            require(downloadedHashes == artifacts.map { it.sha256 }.toSet()) { "Downloaded PyPI closure does not match the resolver report" }

            val installCommand = pipEnvironment() +
                " /usr/bin/python3 -m pip --isolated install --require-hashes --no-index --no-cache-dir --disable-pip-version-check --no-input" +
                " --only-binary=:all: --find-links ${guestQ(wheelRoot)} --target ${guestQ(siteRoot)} -r ${guestQ(requirementsPath)}"
            val install = runLeasedGuestShell("pypi-install", installCommand, 1_500_000)
            check(install.exitCode == 0) { "Offline PyPI wheel install failed: ${install.combined.takeLast(12_000)}" }

            val guestLauncher = "$siteRoot/bin/${recipe.command}"
            val launcherPresent = environment.execute(listOf("/bin/sh", "-lc", "test -x ${guestQ(guestLauncher)}"), maxOutputBytes = 16_384)
            check(launcherPresent.exitCode == 0) { "Installed PyPI package did not expose '${recipe.command}'" }

            val finalSite = "$final/payload/python"
            val finalGuestLauncher = "$finalSite/bin/${recipe.command}"
            val wrapper = "$binRoot/${recipe.command}"
            val wrapperScript = buildString {
                append("#!/system/bin/sh\nset -eu\n")
                append("GUEST=").append(q(environment.launcherPath())).append("\n")
                append("SITE=").append(q(finalSite)).append("\n")
                append("TARGET=").append(q(finalGuestLauncher)).append("\n")
                append("exec \"${'$'}GUEST\" /usr/bin/env \"PYTHONPATH=${'$'}SITE\" \"${'$'}TARGET\" \"${'$'}@\"\n")
            }
            ByteArrayInputStream(wrapperScript.toByteArray(Charsets.UTF_8)).use { bridge.pushStream(it, wrapper, mode = 493) }

            val lock = ReviewedPyPiRecipeLock(
                familyId = recipe.familyId,
                version = recipe.version,
                packageName = recipe.packageName,
                packageVersion = recipe.packageVersion,
                command = recipe.command,
                provenanceUrl = recipe.provenanceUrl,
                requiresPython = recipe.requiresPython,
                pythonVersion = pythonVersion,
                pythonDependency = "${pythonDependency.familyId}@${pythonDependency.version}",
                pipVersion = pipVersion,
                artifacts = artifacts.sortedBy { normalizeProjectName(it.name) },
                installedAtEpochMs = System.currentTimeMillis(),
            )
            ByteArrayInputStream(json.encodeToString(lock).toByteArray(Charsets.UTF_8)).use { bridge.pushStream(it, "$stage/RECIPE_LOCK.json", mode = 420) }

            val cleanup = bridge.shell("rm -rf ${q(resolveRoot)}")
            check(cleanup.exitCode == 0) { "Could not reclaim PyPI transaction files" }
            val bounds = bridge.shellBounded(
                "set -eu; C=${'$'}(find ${q(stage)} -type f | wc -l); B=${'$'}(du -sk ${q(stage)} | awk '{print ${'$'}1}'); " +
                    "test \"${'$'}C\" -le 40000; test \"${'$'}B\" -le 1048576; printf '%s %s\\n' \"${'$'}C\" \"${'$'}B\"",
                maxOutputBytes = 16_384,
            )
            check(bounds.exitCode == 0) { "Reviewed PyPI recipe exceeded package safety bounds" }
            val checksums = bridge.shellBounded(
                "set -eu; cd ${q(stage)}; find . -type f ! -name SHA256SUMS -print | sort | while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done > SHA256SUMS; toybox sha256sum -c SHA256SUMS",
                maxOutputBytes = 256_000,
            )
            check(checksums.exitCode == 0) { "Reviewed PyPI recipe integrity manifest failed: ${checksums.combined}" }

            val move = bridge.shell(
                "set -eu; rm -rf ${q(previous)}; if [ -d ${q(final)} ]; then mv ${q(final)} ${q(previous)}; fi; " +
                    "mkdir -p ${q(final.substringBeforeLast('/'))}; mv ${q(stage)} ${q(final)}",
            )
            check(move.exitCode == 0) { "Could not activate reviewed PyPI recipe: ${move.combined}" }
            projectionActivated = true

            val record = ManagedPackageRecord(
                familyId = recipe.familyId,
                version = recipe.version,
                scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                installRoot = final,
                installedAtEpochMs = System.currentTimeMillis(),
                active = true,
                pathEntries = listOf("$final/payload/bin"),
                commands = mapOf(recipe.command to "$final/payload/bin/${recipe.command}"),
                dependencies = listOf("${pythonDependency.familyId}@${pythonDependency.version}"),
                healthChecks = listOf(ManagedPackageHealthCheck("bin/${recipe.command}", recipe.healthArgs)),
                artifactSha256 = null,
                abi = "arm64-v8a",
            )
            packageInstaller.adoptReviewedRecord(record)
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
        }
    }

    private fun validateReport(
        recipe: PyPiInstallRecipe,
        report: PipInstallReport,
        expectedPipVersion: String,
        expectedPythonVersion: String,
    ): List<PyPiClosureArtifact> {
        require(report.version == "1") { "Unsupported pip installation-report schema" }
        require(report.pipVersion == expectedPipVersion) { "pip report was produced by an unexpected resolver version" }
        require(report.environment["sys_platform"] == "linux") { "PyPI resolver did not execute in the expected Linux guest" }
        require(report.environment["platform_machine"]?.lowercase() in setOf("aarch64", "arm64")) { "PyPI resolver did not execute for ARM64" }
        require(report.environment["python_version"] == expectedPythonVersion) { "PyPI resolver Python environment changed during transaction" }
        require(report.install.isNotEmpty() && report.install.size <= 256) { "Invalid PyPI dependency closure size" }
        val artifacts = report.install.map { item ->
            require(!item.isDirect) { "Direct URL Python dependencies are not allowed in reviewed PyPI transactions" }
            require(!item.isYanked) { "Yanked Python releases are not allowed in reviewed PyPI transactions" }
            require(item.metadata.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}"))) { "Unsafe Python project name in pip report" }
            require(item.metadata.version.matches(Regex("[0-9A-Za-z][0-9A-Za-z._+!-]{0,79}"))) { "Unsafe Python version in pip report" }
            val url = item.downloadInfo.url.toHttpUrl()
            require(url.isHttps && url.host == "files.pythonhosted.org") { "Python dependency artifact escaped files.pythonhosted.org" }
            require(url.encodedPath.endsWith(".whl")) { "Reviewed PyPI transaction accepts wheel artifacts only" }
            val sha256 = item.downloadInfo.archiveInfo.hashes["sha256"]?.lowercase()
                ?: error("pip report omitted SHA-256 for ${item.metadata.name} ${item.metadata.version}")
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid Python artifact SHA-256" }
            PyPiClosureArtifact(item.metadata.name, item.metadata.version, url.toString(), sha256)
        }
        require(artifacts.map { normalizeProjectName(it.name) }.distinct().size == artifacts.size) { "Duplicate Python project in dependency closure" }
        val requested = report.install.filter { it.requested }
        require(requested.size == 1) { "PyPI transaction must have exactly one requested root project" }
        val root = requested.single()
        require(normalizeProjectName(root.metadata.name) == normalizeProjectName(recipe.packageName)) { "pip resolved a different root Python project" }
        require(root.metadata.version == recipe.packageVersion) { "pip resolved a different root Python version" }
        val rootHash = root.downloadInfo.archiveInfo.hashes["sha256"]?.lowercase()
            ?: error("pip report omitted root SHA-256")
        require(rootHash in recipe.releaseWheelSha256) { "Selected root wheel is not present in PyPI release metadata" }
        return artifacts
    }

    private suspend fun runLeasedGuestShell(namespace: String, command: String, maxOutputBytes: Int): BridgeShellResult {
        require(command.length <= 200_000 && '\u0000' !in command) { "Unsafe guest package command" }
        val guestCommand = q(environment.launcherPath()) + " /bin/sh -lc " + q(command)
        val lease = RemoteProcessLease.create(namespace)
        try {
            val result = bridge.shellStreaming(lease.wrap(guestCommand), maxOutputBytes = maxOutputBytes) { _, _ -> }
            withContext(NonCancellable) { runSuspendCatching { bridge.shellBounded(lease.cleanupCommand(), maxOutputBytes = 8_192) } }
            return result
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runSuspendCatching { bridge.ensureHealthyConnection() }
                if (bridge.state.value.connected != null) runSuspendCatching { bridge.shellBounded(lease.terminateCommand(), maxOutputBytes = 16_384) }
                runSuspendCatching { bridge.shellBounded(lease.cleanupCommand(), maxOutputBytes = 8_192) }
            }
            throw failure
        }
    }

    private fun pipEnvironment(): String =
        "export PIP_CONFIG_FILE=/dev/null PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_INPUT=1 PYTHONDONTWRITEBYTECODE=1;"

    private fun normalizeProjectName(name: String): String = name.lowercase().replace(Regex("[-_.]+"), "-")

    private fun q(value: String): String = DeviceBridgeManager.shellQuote(value)
    private fun guestQ(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val PYPI_PACKAGE_MAX_BYTES = 1_024L * 1_024L * 1_024L
        const val PYPI_TRANSIENT_HEADROOM_BYTES = 384L * 1024L * 1024L
    }
}
