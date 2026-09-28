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

// The installer never falls back to VCS or private-module routing.







data class GoInstallRecipe(
    val familyId: String,
    val version: String,
    val modulePath: String,
    val packagePath: String,
    val command: String,
    val minimumGoMajor: Int,
    val minimumGoMinor: Int,
    val provenanceUrl: String,
    val healthArgs: List<String> = listOf("--version"),
) {
    fun validate() {
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid Go recipe family" }
        require(version.matches(Regex("v[0-9][0-9A-Za-z.+_-]{0,119}"))) { "Invalid Go module version" }
        require(modulePath.matches(Regex("[a-z0-9][a-z0-9._~/-]{2,219}"))) { "Invalid Go module path" }
        require(packagePath == modulePath || packagePath.startsWith("$modulePath/")) { "Go command package escaped reviewed module" }
        require(packagePath.matches(Regex("[a-z0-9][a-z0-9._~/-]{2,300}"))) { "Invalid Go command package path" }
        require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid Go command" }
        require(minimumGoMajor == 1 && minimumGoMinor in 16..99) { "Invalid Go minimum" }
        require(provenanceUrl.startsWith("https://")) { "Go provenance must use HTTPS" }
        require(healthArgs.size <= 8 && healthArgs.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }) {
            "Invalid Go health arguments"
        }
    }
}

@Serializable
private data class GoDownloadReport(
    @SerialName("Path") val path: String,
    @SerialName("Version") val version: String,
    @SerialName("Sum") val sum: String? = null,
    @SerialName("GoModSum") val goModSum: String? = null,
    @SerialName("Error") val error: String? = null,
)

@Serializable
private data class GoBuildModule(
    val kind: String,
    val path: String,
    val version: String,
    val sum: String,
)

@Serializable
private data class ReviewedGoRecipeLock(
    val schema: Int = 1,
    val familyId: String,
    val version: String,
    val installer: String = "go-install-sumdb",
    val modulePath: String,
    val packagePath: String,
    val command: String,
    val minimumGo: String,
    val provenanceUrl: String,
    val goVersion: String,
    val goDependency: String,
    val rootModuleSum: String,
    val rootGoModSum: String,
    val buildModules: List<GoBuildModule>,
    val installedAtEpochMs: Long,
)

class ReviewedGoRecipeInstaller(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val packageInstaller: ManagedPackageInstaller,
    private val environment: WorkstationGuestEnvironmentManager = WorkstationGuestEnvironmentManager(context.applicationContext),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun probeGoVersion(goDependency: ManagedPackageRecord): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        PackageBackendContract.requireSame("ReviewedGoRecipeInstaller", PackageBackendId.DEVICE_ADB, packageInstaller.backendId, environment.backendId)
        if (bridge.state.value.connected == null || goDependency.familyId != "toolchain.go") return@withContext null
        if (!packageInstaller.verify(goDependency)) return@withContext null
        environment.ensureInstalled()
        val probe = environment.execute(listOf("/usr/bin/env", "GOENV=off", "/usr/bin/go", "env", "GOVERSION"), maxOutputBytes = 8_192)
        if (probe.exitCode != 0) return@withContext null
        val match = Regex("go([0-9]+)\\.([0-9]+)").find(probe.output.trim().lineSequence().lastOrNull().orEmpty()) ?: return@withContext null
        val major = match.groupValues[1].toIntOrNull() ?: return@withContext null
        val minor = match.groupValues[2].toIntOrNull() ?: return@withContext null
        major to minor
    }

    suspend fun install(recipe: GoInstallRecipe, goDependency: ManagedPackageRecord): ManagedPackageRecord = withContext(Dispatchers.IO) {
        PackageBackendContract.requireSame("ReviewedGoRecipeInstaller", PackageBackendId.DEVICE_ADB, packageInstaller.backendId, environment.backendId)
        recipe.validate()
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) { "Reviewed Go recipes currently require ARM64" }
        require(goDependency.familyId == "toolchain.go") { "Reviewed Go recipe requires a managed Go dependency" }
        require(packageInstaller.verify(goDependency)) { "Managed Go dependency failed integrity or health verification" }
        val goVersionPair = probeGoVersion(goDependency) ?: error("Managed Go dependency is not executable")
        require(goVersionPair.first > recipe.minimumGoMajor ||
            goVersionPair.first == recipe.minimumGoMajor && goVersionPair.second >= recipe.minimumGoMinor) {
            "${recipe.familyId} requires Go ${recipe.minimumGoMajor}.${recipe.minimumGoMinor}+; managed dependency has ${goVersionPair.first}.${goVersionPair.second}"
        }
        val goVersionProbe = environment.execute(listOf("/usr/bin/env", "GOENV=off", "/usr/bin/go", "env", "GOVERSION"), maxOutputBytes = 8_192)
        check(goVersionProbe.exitCode == 0) { "Managed Go dependency does not expose go env" }
        val goVersion = goVersionProbe.output.trim().lineSequence().lastOrNull().orEmpty()
        require(goVersion.matches(Regex("go[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[A-Za-z0-9.-]+)?"))) { "Could not verify Go version" }

        environment.ensureInstalled()
        val root = DeviceBridgeManager.remoteRoot()
        val safeFamily = recipe.familyId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = recipe.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val transactionId = UUID.randomUUID().toString().replace("-", "").take(16)
        val stage = "$root/packages/.staging/$safeFamily-$safeVersion-go-$transactionId"
        val final = "$root/packages/${recipe.familyId}/$safeVersion/arm64-v8a"
        val previous = "$root/packages/.previous/$safeFamily-$safeVersion-go"
        val transient = "$stage/.go-build"
        val goPath = "$transient/gopath"
        val moduleCache = "$transient/modcache"
        val buildCache = "$transient/buildcache"
        val binRoot = "$stage/payload/bin"
        val hostBinRoot = "$stage/payload/host-bin"
        val binary = "$binRoot/${recipe.command}"
        val wrapper = "$hostBinRoot/${recipe.command}"
        listOf(stage, final, previous, transient, goPath, moduleCache, buildCache, binRoot, hostBinRoot, binary, wrapper).forEach(DeviceBridgeManager::requireSafeRemotePath)

        DeviceWorkstationStorageGuard.requireHeadroom(
            bridge,
            additionalBytes = GO_PACKAGE_MAX_BYTES + GO_TRANSIENT_HEADROOM_BYTES,
            purpose = "install reviewed Go recipe ${recipe.familyId} ${recipe.version}",
        )
        val prep = bridge.shell(
            "rm -rf ${q(stage)} ${q(previous)} && mkdir -p ${q(goPath)} ${q(moduleCache)} ${q(buildCache)} ${q(binRoot)} ${q(hostBinRoot)} ${q("$root/packages/.previous")}",
        )
        check(prep.exitCode == 0) { "Could not prepare Go transaction: ${prep.combined}" }

        var projectionActivated = false
        try {
            val exactModule = "${recipe.modulePath}@${recipe.version}"
            val moduleReportResult = runLeasedGo(
                namespace = "go-download",
                argv = goEnvironment(goPath, moduleCache, buildCache, binRoot) + listOf("/usr/bin/go", "mod", "download", "-json", exactModule),
                maxOutputBytes = 256_000,
            )
            check(moduleReportResult.exitCode == 0) { "Go root-module download failed: ${moduleReportResult.combined.takeLast(12_000)}" }
            val moduleReport = json.decodeFromString<GoDownloadReport>(moduleReportResult.stdout)
            require(moduleReport.error.isNullOrBlank()) { "Go module resolver reported an error: ${moduleReport.error}" }
            require(moduleReport.path == recipe.modulePath && moduleReport.version == recipe.version) { "Go module resolver changed root identity" }
            val rootSum = moduleReport.sum ?: error("Go checksum database did not authenticate the root module zip")
            val rootGoModSum = moduleReport.goModSum ?: error("Go checksum database did not authenticate the root go.mod")
            require(rootSum.matches(H1_REGEX) && rootGoModSum.matches(H1_REGEX)) { "Invalid Go checksum evidence" }

            val exactPackage = "${recipe.packagePath}@${recipe.version}"
            val install = runLeasedGo(
                namespace = "go-install",
                argv = goEnvironment(goPath, moduleCache, buildCache, binRoot) +
                    listOf("/usr/bin/go", "install", "-trimpath", "-buildvcs=false", exactPackage),
                maxOutputBytes = 1_500_000,
            )
            check(install.exitCode == 0) { "Go command build failed: ${install.combined.takeLast(12_000)}" }
            val present = environment.execute(listOf("/bin/sh", "-lc", "test -x ${guestQ(binary)}"), maxOutputBytes = 16_384)
            check(present.exitCode == 0) { "Go install did not expose '${recipe.command}'" }

            val health = environment.execute(listOf(binary) + recipe.healthArgs, maxOutputBytes = 128_000)
            check(health.exitCode == 0) { "Go command health check failed: ${health.output.takeLast(8_000)}" }

            val buildInfoResult = environment.execute(listOf("/usr/bin/go", "version", "-m", binary), maxOutputBytes = 512_000)
            check(buildInfoResult.exitCode == 0) { "Could not inspect Go binary module provenance" }
            val buildModules = parseBuildModules(buildInfoResult.output)
            val rootBuildModule = buildModules.singleOrNull { it.kind == "mod" }
                ?: error("Go binary build info did not expose exactly one root module")
            require(rootBuildModule.path == recipe.modulePath && rootBuildModule.version == recipe.version) { "Go binary was built from a different root module" }
            require(rootBuildModule.sum == rootSum) { "Go binary root module checksum differs from authenticated download" }

            val finalBinary = "$final/payload/bin/${recipe.command}"
            val wrapperScript = buildString {
                append("#!/system/bin/sh\nset -eu\nexec ")
                append(q(environment.launcherPath())).append(' ').append(q(finalBinary)).append(" \"${'$'}@\"\n")
            }
            ByteArrayInputStream(wrapperScript.toByteArray(Charsets.UTF_8)).use { bridge.pushStream(it, wrapper, mode = 493) }

            val lock = ReviewedGoRecipeLock(
                familyId = recipe.familyId,
                version = recipe.version,
                modulePath = recipe.modulePath,
                packagePath = recipe.packagePath,
                command = recipe.command,
                minimumGo = "${recipe.minimumGoMajor}.${recipe.minimumGoMinor}",
                provenanceUrl = recipe.provenanceUrl,
                goVersion = goVersion,
                goDependency = "${goDependency.familyId}@${goDependency.version}",
                rootModuleSum = rootSum,
                rootGoModSum = rootGoModSum,
                buildModules = buildModules,
                installedAtEpochMs = System.currentTimeMillis(),
            )
            ByteArrayInputStream(json.encodeToString(lock).toByteArray(Charsets.UTF_8)).use {
                bridge.pushStream(it, "$stage/RECIPE_LOCK.json", mode = 420)
            }

            val cleanup = bridge.shell("rm -rf ${q(transient)}")
            check(cleanup.exitCode == 0) { "Could not reclaim Go transaction cache" }
            val bounds = bridge.shellBounded(
                "set -eu; C=${'$'}(find ${q(stage)} -type f | wc -l); B=${'$'}(du -sk ${q(stage)} | awk '{print ${'$'}1}'); " +
                    "test \"${'$'}C\" -le 1024; test \"${'$'}B\" -le 1048576; printf '%s %s\\n' \"${'$'}C\" \"${'$'}B\"",
                maxOutputBytes = 16_384,
            )
            check(bounds.exitCode == 0) { "Reviewed Go recipe exceeded package safety bounds" }
            val checksums = bridge.shellBounded(
                "set -eu; cd ${q(stage)}; find . -type f ! -name SHA256SUMS -print | sort | while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done > SHA256SUMS; toybox sha256sum -c SHA256SUMS",
                maxOutputBytes = 128_000,
            )
            check(checksums.exitCode == 0) { "Reviewed Go recipe integrity manifest failed: ${checksums.combined}" }

            val move = bridge.shell(
                "set -eu; rm -rf ${q(previous)}; if [ -d ${q(final)} ]; then mv ${q(final)} ${q(previous)}; fi; " +
                    "mkdir -p ${q(final.substringBeforeLast('/'))}; mv ${q(stage)} ${q(final)}",
            )
            check(move.exitCode == 0) { "Could not activate reviewed Go recipe: ${move.combined}" }
            projectionActivated = true

            val finalHostBinRoot = "$final/payload/host-bin"
            val finalWrapper = "$finalHostBinRoot/${recipe.command}"

            val record = ManagedPackageRecord(
                familyId = recipe.familyId,
                version = recipe.version,
                scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                installRoot = final,
                installedAtEpochMs = System.currentTimeMillis(),
                active = true,
                pathEntries = listOf(finalHostBinRoot),
                commands = mapOf(recipe.command to finalWrapper),
                dependencies = listOf("${goDependency.familyId}@${goDependency.version}"),
                healthChecks = listOf(ManagedPackageHealthCheck("host-bin/${recipe.command}", recipe.healthArgs)),
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

    private fun parseBuildModules(text: String): List<GoBuildModule> {
        require(text.length <= 512_000) { "Go build info exceeded safety bound" }
        val modules = text.lineSequence().map(String::trim).filter { it.startsWith("mod ") || it.startsWith("dep ") }.map { line ->
            val fields = line.split(Regex("\\s+"))
            require(fields.size >= 4) { "Go build info omitted module checksum" }
            val kind = fields[0]
            val path = fields[1]
            val version = fields[2]
            val sum = fields[3]
            require(kind == "mod" || kind == "dep") { "Unexpected Go build module kind" }
            require(path.matches(Regex("[A-Za-z0-9][A-Za-z0-9._~!/-]{1,300}"))) { "Unsafe module path in Go build info" }
            require(version.matches(Regex("v[0-9][0-9A-Za-z.+_-]{0,119}"))) { "Unsafe module version in Go build info" }
            require(sum.matches(H1_REGEX)) { "Go build dependency lacks authenticated h1 checksum" }
            GoBuildModule(kind, path, version, sum)
        }.toList()
        require(modules.isNotEmpty() && modules.size <= 512) { "Invalid Go build module closure" }
        require(modules.count { it.kind == "mod" } == 1) { "Go build info must expose one root module" }
        require(modules.map { it.kind to it.path }.distinct().size == modules.size) { "Duplicate Go module in build info" }
        return modules
    }

    private suspend fun runLeasedGo(namespace: String, argv: List<String>, maxOutputBytes: Int): BridgeShellResult {
        require(argv.isNotEmpty() && argv.size <= 48) { "Invalid Go guest command" }
        val guestCommand = buildString {
            append(q(environment.launcherPath()))
            argv.forEach { arg ->
                require(arg.length <= 1_000 && '\u0000' !in arg && '\n' !in arg && '\r' !in arg) { "Unsafe Go guest argument" }
                append(' ').append(q(arg))
            }
        }
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

    private fun goEnvironment(goPath: String, moduleCache: String, buildCache: String, binRoot: String): List<String> = listOf(
        "/usr/bin/env",
        "GOENV=off",
        "GOWORK=off",
        "GOPROXY=https://proxy.golang.org",
        "GOSUMDB=sum.golang.org",
        "GOPRIVATE=",
        "GONOPROXY=",
        "GONOSUMDB=",
        "GOINSECURE=",
        "GOVCS=*:off",
        "GOTOOLCHAIN=local",
        "GOPATH=$goPath",
        "GOMODCACHE=$moduleCache",
        "GOCACHE=$buildCache",
        "GOBIN=$binRoot",
    )

    private fun q(value: String) = DeviceBridgeManager.shellQuote(value)
    private fun guestQ(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        val H1_REGEX = Regex("h1:[A-Za-z0-9+/=]{43,48}")
        const val GO_PACKAGE_MAX_BYTES = 1_024L * 1_024L * 1_024L
        const val GO_TRANSIENT_HEADROOM_BYTES = 512L * 1024L * 1024L
    }
}
