package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl


internal class LocalUbuntuNpmRecipeInstaller(
    context: Context,
    private val projectRoot: File,
    private val authority: LocalManagedPackageAuthority,
    private val ubuntu: FoundryUbuntuGuestEnvironmentManager,
) {
    private val root = context.applicationContext.filesDir.canonicalPath

    suspend fun install(request: WorkstationInstallRecipe): ManagedPackageRecord = withContext(Dispatchers.IO) {
        request.validate()
        require(LocalUbuntuNpmPolicy.supports(request)) { "This npm recipe has no reviewed local Ubuntu adapter" }
        require(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") { "Local AI plugins require ARM64 Android" }
        PackageBackendContract.requireSame("Local Ubuntu npm recipe", PackageBackendId.LOCAL_APP, authority.backendId, ubuntu.backendId)
        ubuntu.ensureInstalled()
        val launch = LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
        suspend fun run(argv: List<String>, timeout: Long = 30_000, limit: Int = 128_000): ExecResult =
            LocalProcessSupervisor.capture(launch.command(argv), projectRoot, launch.environment,
                maxOutputBytes = limit, timeoutMs = timeout, keepTail = true)
        val node = run(listOf("node", "-p", "JSON.stringify({major:parseInt(process.versions.node),platform:process.platform,arch:process.arch})"))
        check(node.exitCode == 0 && !node.timedOut) { "Install Node.js ${request.minimumNodeMajor}+ and npm in the Linux terminal, then retry Install" }
        val runtime = Json.parseToJsonElement(node.output.trim()).jsonObject
        require(runtime["major"]?.jsonPrimitive?.intOrNull?.let { it >= request.minimumNodeMajor } == true &&
            runtime["platform"]?.jsonPrimitive?.content == "linux" && runtime["arch"]?.jsonPrimitive?.content == "arm64") {
            "This plugin needs Node.js ${request.minimumNodeMajor}+ for Linux ARM64 in local Ubuntu"
        }
        val npm = run(listOf("npm", "--version"))
        require(npm.exitCode == 0 && !npm.timedOut && npm.output.trim().substringBefore('.').toIntOrNull()?.let { it >= 10 } == true) {
            "Install npm 10+ in the Linux terminal, then retry Install"
        }
        val nodeLocation = run(listOf("/bin/sh", "-c", "command -v node"))
        val nodeCommand = nodeLocation.output.trim()
        require(nodeLocation.exitCode == 0 && !nodeLocation.timedOut && nodeCommand.startsWith('/') &&
            nodeCommand.length <= 1_000 && nodeCommand.none { it.code < 32 || it.code == 127 || it == ':' || it == '\\' } &&
            nodeCommand.drop(1).split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "The selected Node.js executable cannot be projected into the plugin runtime"
        }
        val nodeBin = nodeCommand.substringBeforeLast('/').ifEmpty { "/" }
        require(File(root).usableSpace >= 350_000_000L) { "Free at least 350 MB before installing an AI plugin" }
        val url = "https://registry.npmjs.org".toHttpUrl().newBuilder().addPathSegment(request.packageName).addPathSegment(request.packageVersion).build()
        val response = SafeHttp.get(url.toString(), accept = "application/json", maxBytes = 1_000_000)
        check(response.code == 200 && response.finalUrl.toHttpUrl().host == "registry.npmjs.org") { "Official npm metadata is unavailable (HTTP ${response.code})" }
        val metadata = Json.parseToJsonElement(response.body).jsonObject
        require(metadata["name"]?.jsonPrimitive?.content == request.packageName && metadata["version"]?.jsonPrimitive?.content == request.packageVersion) { "npm package identity mismatch" }
        val dist = metadata["dist"]?.jsonObject ?: error("npm artifact metadata is missing")
        val integrity = dist["integrity"]?.jsonPrimitive?.content ?: error("npm integrity is missing")
        LocalUbuntuNpmPolicy.requireIntegrity(integrity)
        LocalUbuntuNpmPolicy.requireRegistryTarball(dist["tarball"]?.jsonPrimitive?.content ?: error("npm artifact URL is missing"))
        request.registryIntegrity?.let { require(it == integrity) { "Reviewed npm integrity changed" } }
        val recipe = request.copy(registryIntegrity = integrity)
        val paths = LocalPackageInstallJournal.paths(root, recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
        LocalPackageInstallJournal.transactionMutex.withLock {
            val existing = authority.ownedReceipt(recipe.familyId, recipe.version)
            LocalPackageInstallJournal.recover(paths.stage, paths.final, paths.previous,
                existing?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
            val npmRoot = File(paths.stage, "payload/npm")
            check(npmRoot.mkdirs()) { "Cannot stage npm package" }
            var activation: LocalPackageInstallJournal.Activation? = null
            try {
                val activationId = LocalPackageInstallJournal.prepare(paths.stage)
                File(npmRoot, "package.json").writeText(buildJsonObject {
                    put("name", "droide-managed-agent"); put("version", "1.0.0"); put("private", true)
                    put("dependencies", buildJsonObject { put(recipe.packageName, recipe.packageVersion) })
                }.toString())
                val guestNpm = "/opt/droide/packages/" + npmRoot.absolutePath.removePrefix("$root/packages/")
                val options = listOf("--prefix", guestNpm, "--registry", "https://registry.npmjs.org", "--cache", "$guestNpm/.cache",
                    "--no-audit", "--no-fund", "--fetch-retries=1", "--fetch-timeout=60000")
                suspend fun npmCommand(args: List<String>) {
                    val result = run(listOf("npm") + args + options, timeout = 600_000)
                    check(result.exitCode == 0 && !result.timedOut) { "npm installation failed: ${result.output.takeLast(4_000)}" }
                }
                val lock = File(npmRoot, "package-lock.json")
                val previousLock = existing?.let { File(it.installRoot, "payload/npm/package-lock.json") }
                val preservedLock = previousLock?.takeIf { it.isFile && it.length() <= 8_000_000L &&
                    LocalUbuntuNpmPolicy.sha256(it) == existing?.artifactSha256 }
                if (preservedLock != null) lock.writeText(preservedLock.readText())
                else npmCommand(listOf("install", "--package-lock-only", "--ignore-scripts", "--omit=dev", "--include=optional"))
                require(lock.isFile && lock.length() <= 8_000_000L) { "npm did not produce a bounded dependency lock" }
                LocalUbuntuNpmPolicy.validateLock(lock.readText(), recipe)
                npmCommand(listOf("ci", "--ignore-scripts", "--omit=dev", "--include=optional"))
                if (recipe.allowLifecycleScripts) {
                    // The reviewed root binary selector may run; transitive lifecycle scripts stay blocked.
                    npmCommand(listOf("rebuild", recipe.packageName, "--ignore-scripts=false", "--foreground-scripts", "--offline"))
                }
                check(PathSecurity.deleteTreeNoFollow(File(npmRoot, ".cache"))) { "Cannot remove npm transfer cache" }
                LocalUbuntuNpmPolicy.validateLock(lock.readText(), recipe)
                val commands = LocalUbuntuNpmPolicy.commands(recipe)
                val bin = File(paths.stage, "payload/bin").apply { check(mkdirs()) }
                val hostBin = File(paths.stage, "payload/host-bin").apply { check(mkdirs()) }
                val finalGuest = "/opt/droide/packages/" + paths.final.absolutePath.removePrefix("$root/packages/")
                for ((name, args) in commands) {
                    val entry = File(npmRoot, "node_modules/.bin/${args.first()}")
                    require(entry.isFile && entry.canonicalFile.toPath().startsWith(npmRoot.canonicalFile.toPath())) { "The original $name entry point is missing" }
                    val tail = args.drop(1).joinToString(" ", transform = ::q)
                    val health = run(listOf("/bin/sh", "-c", "exec ${q("$guestNpm/node_modules/.bin/${args.first()}")} $tail --version"))
                    require(health.exitCode == 0 && !health.timedOut && health.output.isNotBlank()) { "$name failed its Linux ARM64 health check: ${health.output.takeLast(2_000)}" }
                    File(bin, name).apply {
                        writeText("#!/bin/sh\nset -eu\nexport NO_UPDATE_NOTIFIER=1\nexport PATH=${q(nodeBin)}:\"${'$'}PATH\"\nexec ${q("$finalGuest/payload/npm/node_modules/.bin/${args.first()}")} $tail \"${'$'}@\"\n")
                        check(setExecutable(true, true))
                    }
                    File(hostBin, name).apply {
                        writeText("#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${q(ubuntu.launcherPath())} /bin/sh ${q("$finalGuest/payload/bin/$name")} \"${'$'}@\"\n")
                        check(setExecutable(true, true))
                    }
                }
                File(paths.stage, "RECIPE_LOCK.json").writeText(buildJsonObject {
                    put("schema", 1); put("familyId", recipe.familyId); put("version", recipe.version)
                    put("packageName", recipe.packageName); put("packageVersion", recipe.packageVersion); put("registryIntegrity", integrity)
                    put("nodeMajor", runtime["major"]!!); put("npmVersion", npm.output.trim()); put("provenanceUrl", recipe.provenanceUrl)
                    put("nodeCommand", nodeCommand)
                    put("allowLifecycleScripts", recipe.allowLifecycleScripts); recipe.lifecycleScriptReview?.let { put("lifecycleScriptReview", it) }
                }.toString())
                val seal = File(paths.stage, LocalUbuntuNpmPolicy.SEAL)
                seal.writeText(LocalUbuntuNpmPolicy.tree(paths.stage).toString())
                val sealHash = LocalUbuntuNpmPolicy.sha256(seal)
                activation = LocalPackageInstallJournal.activate(paths.stage, paths.final, paths.previous)
                val record = ManagedPackageRecord(
                    familyId = recipe.familyId, version = recipe.version, scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                    installRoot = paths.final.absolutePath, installedAtEpochMs = System.currentTimeMillis(),
                    pathEntries = listOf("${paths.final.absolutePath}/payload/host-bin"),
                    commands = commands.keys.associateWith { "${paths.final.absolutePath}/payload/host-bin/$it" },
                    healthChecks = commands.keys.map { ManagedPackageHealthCheck("host-bin/$it", listOf("--version")) },
                    artifactSha256 = LocalUbuntuNpmPolicy.sha256(File(paths.final, "payload/npm/package-lock.json")), abi = "arm64-v8a",
                    metadata = mapOf(PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                        LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activationId, LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_NPM,
                        LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                        LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name, LocalManagedPackageMetadata.SEAL_SHA256_KEY to sealHash),
                )
                authority.adopt(record)
                activation = null
                withContext(NonCancellable) { LocalPackageInstallJournal.commit(paths.previous) }
                record
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    activation?.let { installed -> runCatching { LocalPackageInstallJournal.rollback(paths.final, paths.previous, installed) }.exceptionOrNull()?.let(failure::addSuppressed) }
                    if (!PathSecurity.deleteTreeNoFollow(paths.stage)) failure.addSuppressed(IllegalStateException("Cannot clean failed npm staging tree"))
                }
                throw failure
            }
        }
    }

    private fun q(value: String) = LocalExecutionSubstrate.shellQuote(value)
}
