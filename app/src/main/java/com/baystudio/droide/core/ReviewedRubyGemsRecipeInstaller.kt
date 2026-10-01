package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

 
data class RubyGemsInstallRecipe(
    val familyId: String,
    val version: String,
    val gemName: String,
    val command: String,
    val rootSha256: String,
    val requiredRuby: String? = null,
    val provenanceUrl: String,
    val healthArgs: List<String> = listOf("--version"),
) {
    fun validate() {
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid RubyGems recipe family" }
        require(version.matches(Regex("[0-9][0-9A-Za-z._+-]{0,79}"))) { "Invalid RubyGems version" }
        require(gemName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}"))) { "Invalid RubyGems package name" }
        require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid RubyGems command" }
        require(rootSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid RubyGems root SHA-256" }
        require(requiredRuby == null || requiredRuby.length in 1..160 && '\u0000' !in requiredRuby && '\n' !in requiredRuby && '\r' !in requiredRuby) {
            "Invalid RubyGems Ruby requirement"
        }
        require(provenanceUrl.startsWith("https://")) { "RubyGems provenance must use HTTPS" }
        require(healthArgs.size <= 8 && healthArgs.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }) {
            "Invalid RubyGems health arguments"
        }
    }
}

@Serializable
private data class ReviewedRubyGemsRecipeLock(
    val schema: Int = 1,
    val familyId: String,
    val version: String,
    val installer: String = "rubygems-bundler-lock-checksum",
    val gemName: String,
    val command: String,
    val rootSha256: String,
    val requiredRuby: String? = null,
    val rubyVersion: String,
    val bundlerVersion: String,
    val gemfileLockSha256: String,
    val cachedGemSha256: Map<String, String>,
    val dependencies: List<String>,
    val provenanceUrl: String,
    val installedAtEpochMs: Long,
)

// Every cached .gem is then checked against the lock and the final installation is performed with --local, so activation cannot silently fetch new bytes.






class ReviewedRubyGemsRecipeInstaller(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val packageInstaller: ManagedPackageInstaller,
    private val environment: WorkstationGuestEnvironmentManager = WorkstationGuestEnvironmentManager(context.applicationContext),
) {
    private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }

    suspend fun install(
        recipe: RubyGemsInstallRecipe,
        rubyDependency: ManagedPackageRecord,
        bundlerDependency: ManagedPackageRecord,
        nativeBuildDependency: ManagedPackageRecord,
    ): ManagedPackageRecord = withContext(Dispatchers.IO) {
        PackageBackendContract.requireSame("ReviewedRubyGemsRecipeInstaller", PackageBackendId.DEVICE_ADB, packageInstaller.backendId, environment.backendId)
        recipe.validate()
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) { "Reviewed RubyGems recipes currently require ARM64" }
        require(rubyDependency.familyId == "runtime.ruby") { "RubyGems provider requires the reviewed Ruby runtime" }
        require(bundlerDependency.familyId == "package.bundler") { "RubyGems provider requires the reviewed Bundler package" }
        require(nativeBuildDependency.familyId == "toolchain.ruby-native-build") { "RubyGems provider requires the reviewed native-extension build toolchain" }
        listOf(rubyDependency, bundlerDependency, nativeBuildDependency).forEach { dependency ->
            require(packageInstaller.verify(dependency)) { "Managed dependency ${dependency.familyId}@${dependency.version} failed verification" }
        }
        environment.ensureInstalled()

        val rubyVersionProbe = environment.execute(listOf("/usr/bin/ruby", "--version"), maxOutputBytes = 16_384)
        check(rubyVersionProbe.exitCode == 0) { "Managed Ruby runtime is not executable" }
        val rubyVersion = rubyVersionProbe.output.trim().lineSequence().firstOrNull().orEmpty().take(160)
        require(rubyVersion.startsWith("ruby ")) { "Could not verify managed Ruby version" }
        val bundlerVersionProbe = environment.execute(listOf("/usr/bin/bundle", "--version"), maxOutputBytes = 16_384)
        check(bundlerVersionProbe.exitCode == 0) { "Managed Bundler is not executable" }
        val bundlerVersion = bundlerVersionProbe.output.trim().lineSequence().firstOrNull().orEmpty().take(160)
        require(bundlerVersion.contains("Bundler")) { "Could not verify managed Bundler version" }

        val root = DeviceBridgeManager.remoteRoot()
        val safeFamily = recipe.familyId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = recipe.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val tx = UUID.randomUUID().toString().replace("-", "").take(16)
        val stage = "$root/packages/.staging/$safeFamily-$safeVersion-rubygems-$tx"
        val final = "$root/packages/${recipe.familyId}/$safeVersion/arm64-v8a"
        val previous = "$root/packages/.previous/$safeFamily-$safeVersion-rubygems"
        val manifest = "$stage/manifest"
        val gemfile = "$manifest/Gemfile"
        val lockfile = "$manifest/Gemfile.lock"
        val cache = "$stage/vendor/cache"
        val bundlePath = "$stage/payload/gems"
        val hostBin = "$stage/payload/host-bin"
        val wrapper = "$hostBin/${recipe.command}"
        listOf(stage, final, previous, manifest, gemfile, lockfile, cache, bundlePath, hostBin, wrapper)
            .forEach(DeviceBridgeManager::requireSafeRemotePath)

        DeviceWorkstationStorageGuard.requireHeadroom(
            bridge,
            additionalBytes = RUBYGEMS_PACKAGE_MAX_BYTES + RUBYGEMS_TRANSIENT_HEADROOM_BYTES,
            purpose = "install reviewed RubyGems recipe ${recipe.familyId} ${recipe.version}",
        )
        val prep = bridge.shell(
            "rm -rf ${q(stage)} ${q(previous)} && mkdir -p ${q(manifest)} ${q(cache)} ${q(bundlePath)} ${q(hostBin)} ${q("$root/packages/.previous")}",
        )
        check(prep.exitCode == 0) { "Could not prepare RubyGems transaction: ${prep.combined}" }

        var activated = false
        try {
            val gemfileText = "source \"https://rubygems.org\"\n\ngem \"${recipe.gemName}\", \"= ${recipe.version}\"\n"
            ByteArrayInputStream(gemfileText.toByteArray(Charsets.UTF_8)).use { bridge.pushStream(it, gemfile, mode = 420) }

            val baseEnv = bundlerEnvironment(gemfile, bundlePath, cache, "$stage/.bundle")
            val lock = runLeasedGuestShell(
                "rubygems-lock",
                "$baseEnv /usr/bin/bundle lock --add-checksums",
                1_500_000,
            )
            check(lock.exitCode == 0) { "Bundler resolution failed: ${lock.combined.takeLast(12_000)}" }
            val lockRead = environment.execute(listOf("/bin/cat", lockfile), maxOutputBytes = 1_500_000)
            check(lockRead.exitCode == 0) { "Bundler did not create Gemfile.lock" }
            val lockText = lockRead.output
            require("\nGIT\n" !in "\n$lockText" && "\nPATH\n" !in "\n$lockText") { "RubyGems transaction cannot admit git/path dependency sources" }
            val checksums = parseLockChecksums(lockText)
            require(checksums.isNotEmpty()) { "Bundler lockfile omitted CHECKSUMS evidence" }
            val rootKey = "${recipe.gemName} (${recipe.version})"
            require(checksums[rootKey] == recipe.rootSha256) { "RubyGems root checksum does not match official registry metadata" }

            val cacheResult = runLeasedGuestShell(
                "rubygems-cache",
                "$baseEnv /usr/bin/bundle cache --no-install",
                1_500_000,
            )
            check(cacheResult.exitCode == 0) { "Bundler cache failed: ${cacheResult.combined.takeLast(12_000)}" }
            val cacheHashes = environment.execute(
                listOf("/bin/sh", "-lc", "set -eu; for f in ${guestQ(cache)}/*.gem; do [ -f \"\$f\" ] || continue; /usr/bin/sha256sum \"\$f\"; done"),
                maxOutputBytes = 512_000,
            )
            check(cacheHashes.exitCode == 0) { "Could not hash Bundler cache" }
            val cached = parseCachedGemHashes(cacheHashes.output)
            require(cached.isNotEmpty()) { "Bundler produced no exact gem cache" }
            val admittedHashes = checksums.values.toSet()
            require(cached.values.all { it in admittedHashes }) { "Cached RubyGems artifact is not bound by Gemfile.lock CHECKSUMS" }
            require(recipe.rootSha256 in cached.values) { "Official root gem was not cached with its reviewed SHA-256" }

            val install = runLeasedGuestShell(
                "rubygems-install",
                "$baseEnv /usr/bin/bundle install --local --jobs 1 --retry 0",
                1_500_000,
            )
            check(install.exitCode == 0) { "Offline Bundler installation failed: ${install.combined.takeLast(12_000)}" }
            val health = runLeasedGuestShell(
                "rubygems-health",
                "$baseEnv /usr/bin/bundle exec ${guestQ(recipe.command)} ${recipe.healthArgs.joinToString(" ") { guestQ(it) }}",
                256_000,
            )
            check(health.exitCode == 0) { "RubyGems command health check failed: ${health.combined.takeLast(8_000)}" }

            val lockSha = remoteSha256(lockfile)
            val cacheManifest = cached.entries.sortedBy { it.key }.joinToString("\n", postfix = "\n") { (name, sha) -> "$sha  $name" }
            ByteArrayInputStream(cacheManifest.toByteArray(Charsets.UTF_8)).use {
                bridge.pushStream(it, "$manifest/GEM_CACHE_SHA256SUMS", mode = 420)
            }
            val finalGemfile = "$final/manifest/Gemfile"
            val finalBundlePath = "$final/payload/gems"
            val wrapperScript = buildString {
                append("#!/system/bin/sh\nset -eu\nexec ")
                append(q(environment.launcherPath())).append(" /usr/bin/env ")
                append(q("BUNDLE_GEMFILE=$finalGemfile")).append(' ')
                append(q("BUNDLE_PATH=$finalBundlePath")).append(' ')
                append(q("BUNDLE_DISABLE_SHARED_GEMS=false")).append(' ')
                append("/usr/bin/bundle exec ").append(q(recipe.command)).append(" \"${'$'}@\"\n")
            }
            ByteArrayInputStream(wrapperScript.toByteArray(Charsets.UTF_8)).use { bridge.pushStream(it, wrapper, mode = 493) }

            val dependencyKeys = listOf(rubyDependency, bundlerDependency, nativeBuildDependency)
                .map { "${it.familyId}@${it.version}" }.distinct().sorted()
            val recipeLock = ReviewedRubyGemsRecipeLock(
                familyId = recipe.familyId,
                version = recipe.version,
                gemName = recipe.gemName,
                command = recipe.command,
                rootSha256 = recipe.rootSha256,
                requiredRuby = recipe.requiredRuby,
                rubyVersion = rubyVersion,
                bundlerVersion = bundlerVersion,
                gemfileLockSha256 = lockSha,
                cachedGemSha256 = cached.toSortedMap(),
                dependencies = dependencyKeys,
                provenanceUrl = recipe.provenanceUrl,
                installedAtEpochMs = System.currentTimeMillis(),
            )
            ByteArrayInputStream(json.encodeToString(recipeLock).toByteArray(Charsets.UTF_8)).use {
                bridge.pushStream(it, "$stage/RECIPE_LOCK.json", mode = 420)
            }
            
            val trim = bridge.shell("rm -rf ${q("$stage/vendor")} ${q("$stage/.bundle")}")
            check(trim.exitCode == 0) { "Could not clean RubyGems transaction cache" }

            val bounds = bridge.shellBounded(
                "set -eu; C=${'$'}(find ${q(stage)} -type f | wc -l); B=${'$'}(du -sk ${q(stage)} | awk '{print ${'$'}1}'); " +
                    "test \"${'$'}C\" -le 12000; test \"${'$'}B\" -le 1048576; printf '%s %s\\n' \"${'$'}C\" \"${'$'}B\"",
                maxOutputBytes = 16_384,
            )
            check(bounds.exitCode == 0) { "RubyGems package exceeded final safety bounds" }
            val integrity = bridge.shellBounded(
                "set -eu; cd ${q(stage)}; find . -type f ! -name SHA256SUMS -print | sort | while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done > SHA256SUMS; toybox sha256sum -c SHA256SUMS",
                maxOutputBytes = 1_500_000,
            )
            check(integrity.exitCode == 0) { "RubyGems package integrity manifest failed: ${integrity.combined.takeLast(8_000)}" }

            val move = bridge.shell(
                "set -eu; rm -rf ${q(previous)}; if [ -d ${q(final)} ]; then mv ${q(final)} ${q(previous)}; fi; " +
                    "mkdir -p ${q(final.substringBeforeLast('/'))}; mv ${q(stage)} ${q(final)}",
            )
            check(move.exitCode == 0) { "Could not activate RubyGems package: ${move.combined}" }
            activated = true

            val finalHostBin = "$final/payload/host-bin"
            val record = ManagedPackageRecord(
                familyId = recipe.familyId,
                version = recipe.version,
                scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                installRoot = final,
                installedAtEpochMs = System.currentTimeMillis(),
                active = true,
                pathEntries = listOf(finalHostBin),
                commands = mapOf(recipe.command to "$finalHostBin/${recipe.command}"),
                dependencies = dependencyKeys,
                healthChecks = listOf(ManagedPackageHealthCheck("host-bin/${recipe.command}", recipe.healthArgs)),
                artifactSha256 = recipe.rootSha256,
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
    }

    private fun parseLockChecksums(lock: String): Map<String, String> {
        val start = lock.lineSequence().indexOfFirst { it == "CHECKSUMS" }
        require(start >= 0) { "Gemfile.lock has no CHECKSUMS section" }
        val lines = lock.lineSequence().drop(start + 1).takeWhile { it.startsWith("  ") }.toList()
        val parsed = linkedMapOf<String, String>()
        val pattern = Regex("^  ([A-Za-z0-9_.-]+ \\([^)]+\\)) sha256=([0-9a-f]{64})$")
        for (line in lines) {
            val match = pattern.matchEntire(line) ?: continue
            require(parsed.put(match.groupValues[1], match.groupValues[2]) == null) { "Duplicate Gemfile.lock checksum entry" }
        }
        return parsed
    }

    private fun parseCachedGemHashes(text: String): Map<String, String> {
        val parsed = linkedMapOf<String, String>()
        for (line in text.lineSequence().filter { it.isNotBlank() }) {
            val match = Regex("^([0-9a-f]{64})\\s+(.+\\.gem)$").matchEntire(line.trim())
                ?: error("Invalid cached gem checksum output")
            val name = match.groupValues[2].substringAfterLast('/')
            require(name.matches(Regex("[A-Za-z0-9._+-]{1,220}\\.gem"))) { "Unsafe cached gem name" }
            require(parsed.put(name, match.groupValues[1]) == null) { "Duplicate cached gem file" }
        }
        return parsed
    }

    private fun bundlerEnvironment(gemfile: String, bundlePath: String, cache: String, appConfig: String): String =
        listOf(
            "BUNDLE_GEMFILE=$gemfile",
            "BUNDLE_PATH=$bundlePath",
            "BUNDLE_CACHE_PATH=$cache",
            "BUNDLE_APP_CONFIG=$appConfig",
            "BUNDLE_DISABLE_SHARED_GEMS=false",
            "BUNDLE_LOCKFILE_CHECKSUMS=true",
            "BUNDLE_FROZEN=false",
            "BUNDLE_DEPLOYMENT=false",
        ).joinToString(prefix = "/usr/bin/env ", separator = " ") { guestQ(it) }

    private suspend fun runLeasedGuestShell(namespace: String, command: String, maxOutputBytes: Int): BridgeShellResult {
        require(command.length <= 200_000 && '\u0000' !in command) { "Unsafe RubyGems guest command" }
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

    private suspend fun remoteSha256(path: String): String {
        val result = bridge.shellBounded("toybox sha256sum ${q(path)}", maxOutputBytes = 8_192)
        val sha = result.stdout.trim().substringBefore(' ').lowercase()
        check(result.exitCode == 0 && sha.matches(Regex("[0-9a-f]{64}"))) { "Could not hash RubyGems transaction file" }
        return sha
    }

    private fun q(value: String) = DeviceBridgeManager.shellQuote(value)
    private fun guestQ(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val RUBYGEMS_PACKAGE_MAX_BYTES = 1_024L * 1_024L * 1_024L
        const val RUBYGEMS_TRANSIENT_HEADROOM_BYTES = 512L * 1024L * 1024L
    }
}
