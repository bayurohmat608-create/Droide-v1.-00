package com.baystudio.droide.core

import android.os.Build
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// These are deliberately separate from ManagedPackageCatalog: npm resolves transitive packages at install time.





data class WorkstationInstallRecipe(
    val familyId: String,
    val version: String,
    val packageName: String,
    val packageVersion: String,
    val command: String,
    val minimumNodeMajor: Int,
    val provenanceUrl: String,
    val registryIntegrity: String? = null,
    val allowLifecycleScripts: Boolean = false,
    val lifecycleScriptReview: String? = null,
) {
    fun validate() {
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}")))
        require(version.matches(Regex("[A-Za-z0-9._+-]{1,80}")))
        require(packageName.matches(Regex("(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+")))
        require(packageVersion.matches(Regex("[A-Za-z0-9._+-]{1,80}")))
        require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}")))
        require(minimumNodeMajor in 18..40)
        require(provenanceUrl.startsWith("https://"))
        registryIntegrity?.let {
            require(it.matches(Regex("sha512-[A-Za-z0-9+/=]{32,180}"))) { "Invalid npm registry integrity" }
        }
        if (allowLifecycleScripts) {
            require(!lifecycleScriptReview.isNullOrBlank()) { "Lifecycle-script execution requires reviewed evidence" }
            require(lifecycleScriptReview.length <= 600 && '\u0000' !in lifecycleScriptReview && '\r' !in lifecycleScriptReview) {
                "Invalid lifecycle-script review"
            }
        } else {
            require(lifecycleScriptReview == null) { "Blocked lifecycle scripts must not carry an execution review" }
        }
    }
}

object WorkstationInstallRecipeCatalog {
    private fun recipe(
        familyId: String,
        version: String,
        packageName: String,
        command: String,
        minimumNodeMajor: Int,
        provenanceUrl: String,
        allowLifecycleScripts: Boolean = false,
        lifecycleScriptReview: String? = null,
    ) = WorkstationInstallRecipe(
        familyId = familyId,
        version = version,
        packageName = packageName,
        packageVersion = version,
        command = command,
        minimumNodeMajor = minimumNodeMajor,
        provenanceUrl = provenanceUrl,
        registryIntegrity = null,
        allowLifecycleScripts = allowLifecycleScripts,
        lifecycleScriptReview = lifecycleScriptReview,
    )

    val entries: List<WorkstationInstallRecipe> = listOf(
        recipe(
            "plugin.opencode", "2.0.3", "@opencode/cli", "opencode", 20, "https://opencode.ai/docs/cli/",
            allowLifecycleScripts = true,
            lifecycleScriptReview = "Reviewed @opencode/cli 2.0.3 publish output requires its top-level postinstall.mjs to select/link the platform binary.",
        ),
        recipe("plugin.codex", "1.12.0", "@agentclientprotocol/codex-acp", "codex-acp", 20, "https://github.com/agentclientprotocol/codex-acp"),
        recipe("plugin.claude-code", "0.79.0", "@agentclientprotocol/claude-agent-acp", "claude-agent-acp", 22, "https://github.com/agentclientprotocol/claude-agent-acp"),
        recipe("plugin.gemini-cli", "0.60.0", "@google/gemini-cli", "gemini", 20, "https://github.com/google-gemini/gemini-cli"),
        recipe("plugin.github-copilot-cli", "1.0.86", "@github/copilot", "copilot", 22, "https://docs.github.com/en/copilot/concepts/agents/about-copilot-cli"),
    ).onEach(WorkstationInstallRecipe::validate)

    fun find(familyId: String, version: String): WorkstationInstallRecipe? =
        entries.firstOrNull { it.familyId == familyId && it.version == version }
}

@Serializable
private data class ReviewedRecipeLock(
    val schema: Int = 2,
    val familyId: String,
    val version: String,
    val installer: String = "npm",
    val packageName: String,
    val packageVersion: String,
    val command: String,
    val minimumNodeMajor: Int,
    val provenanceUrl: String,
    val registryIntegrity: String? = null,
    val allowLifecycleScripts: Boolean,
    val lifecycleScriptReview: String?,
    val nodeMajor: Int,
    val npmVersion: String,
    val nodeDependency: String,
    val installedAtEpochMs: Long,
)

class ReviewedWorkstationRecipeInstaller(
    private val androidDevelopment: AndroidDevelopmentManager,
    private val bridge: DeviceBridgeManager,
    private val packageInstaller: ManagedPackageInstaller,
) {
    private val json = Json { prettyPrint = true }

    suspend fun probeNodeMajor(nodeDependency: ManagedPackageRecord): Int? = withContext(Dispatchers.IO) {
        if (bridge.state.value.connected == null) return@withContext null
        if (nodeDependency.familyId != "runtime.node") return@withContext null
        val nodeCommand = nodeDependency.commands["node"] ?: return@withContext null
        if (runCatching { DeviceBridgeManager.requireSafeRemotePath(nodeCommand) }.isFailure) return@withContext null
        val result = bridge.shellBounded(
            DeviceBridgeManager.shellQuote(nodeCommand) + " -p 'process.versions.node.split(\".\")[0]'",
            maxOutputBytes = 8_192,
        )
        if (result.exitCode != 0) return@withContext null
        result.stdout.trim().lineSequence().lastOrNull()?.toIntOrNull()
    }

    suspend fun install(recipe: WorkstationInstallRecipe, nodeDependency: ManagedPackageRecord): ManagedPackageRecord = withContext(Dispatchers.IO) {
        recipe.validate()
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) { "Reviewed agent recipes currently require ARM64" }
        require(nodeDependency.familyId == "runtime.node") { "Reviewed npm recipe requires a managed Node.js dependency" }
        require(packageInstaller.verify(nodeDependency)) { "Managed Node.js dependency failed integrity or health verification" }
        val nodeCommand = nodeDependency.commands["node"] ?: error("Managed Node.js dependency does not export node")
        val npmCommand = nodeDependency.commands["npm"] ?: error("Managed Node.js dependency does not export npm")
        DeviceBridgeManager.requireSafeRemotePath(nodeCommand)
        DeviceBridgeManager.requireSafeRemotePath(npmCommand)
        val nodeMajor = probeNodeMajor(nodeDependency)
            ?: error("Managed Node.js dependency is not executable")
        require(nodeMajor >= recipe.minimumNodeMajor) {
            "${recipe.familyId} requires Node.js ${recipe.minimumNodeMajor}+; managed dependency has Node.js $nodeMajor"
        }
        val npmProbe = bridge.shellBounded(DeviceBridgeManager.shellQuote(npmCommand) + " --version", 8_192)
        check(npmProbe.exitCode == 0) { "Managed npm dependency is not executable for ${recipe.familyId}" }
        val npmVersion = npmProbe.stdout.trim().lineSequence().lastOrNull().orEmpty()
        require(npmVersion.matches(Regex("[0-9]+(?:\\.[0-9A-Za-z-]+){1,4}"))) { "Could not verify npm version" }
        recipe.registryIntegrity?.let { expectedIntegrity ->
            val exact = "${recipe.packageName}@${recipe.packageVersion}"
            val integrityProbe = bridge.shellBounded(
                "export npm_config_registry=https://registry.npmjs.org/ npm_config_userconfig=/dev/null; " +
                    DeviceBridgeManager.shellQuote(npmCommand) + " view " + DeviceBridgeManager.shellQuote(exact) +
                    " dist.integrity --json --registry=https://registry.npmjs.org/",
                maxOutputBytes = 16_384,
            )
            check(integrityProbe.exitCode == 0) { "Could not verify npm registry integrity for $exact" }
            val observed = integrityProbe.stdout.trim().lineSequence().lastOrNull().orEmpty().trim().removeSurrounding("\"")
            require(observed == expectedIntegrity) { "npm registry integrity changed for $exact" }
        }

        val root = DeviceBridgeManager.remoteRoot()
        val safeFamily = recipe.familyId.replace('.', '_')
        val safeVersion = recipe.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val transactionId = UUID.randomUUID().toString().replace("-", "").take(16)
        val stage = "$root/packages/.staging/$safeFamily-$safeVersion-recipe-$transactionId"
        val final = "$root/packages/${recipe.familyId}/${recipe.version}/arm64-v8a"
        val previous = "$root/packages/.previous/$safeFamily-$safeVersion-recipe-$transactionId"
        listOf(stage, final, previous).forEach(DeviceBridgeManager::requireSafeRemotePath)
        val npmRoot = "$stage/payload/npm"
        val binRoot = "$stage/payload/bin"
        val npmCache = "$stage/.npm-cache"
        val npmTmp = "$stage/.npm-tmp"
        val wrapper = "$binRoot/${recipe.command}"
        listOf(npmRoot, binRoot, npmCache, npmTmp, wrapper).forEach(DeviceBridgeManager::requireSafeRemotePath)
        DeviceWorkstationStorageGuard.requireHeadroom(
            bridge,
            additionalBytes = REVIEWED_RECIPE_MAX_BYTES + NPM_TRANSIENT_HEADROOM_BYTES,
            purpose = "install reviewed npm recipe ${recipe.familyId} ${recipe.version}",
        )

        val prep = bridge.shell(
            "rm -rf ${DeviceBridgeManager.shellQuote(stage)} ${DeviceBridgeManager.shellQuote(previous)} && " +
                "mkdir -p ${DeviceBridgeManager.shellQuote(npmRoot)} ${DeviceBridgeManager.shellQuote(binRoot)} " +
                "${DeviceBridgeManager.shellQuote(npmCache)} ${DeviceBridgeManager.shellQuote(npmTmp)}"
        )
        check(prep.exitCode == 0) { prep.combined }
        var projectionActivated = false
        var installLease: RemoteProcessLease? = null
        try {
            val exact = "${recipe.packageName}@${recipe.packageVersion}"
            val lifecycle = if (recipe.allowLifecycleScripts) " --foreground-scripts" else " --ignore-scripts"
            val lease = RemoteProcessLease.create("npm-install")
            installLease = lease
            val install = bridge.shellStreaming(
                lease.wrap(
                    "export npm_config_cache=${DeviceBridgeManager.shellQuote(npmCache)}; " +
                        "export npm_config_registry=https://registry.npmjs.org/ npm_config_userconfig=/dev/null; " +
                        "export TMPDIR=${DeviceBridgeManager.shellQuote(npmTmp)}; " +
                        "${DeviceBridgeManager.shellQuote(npmCommand)} install --global --prefix ${DeviceBridgeManager.shellQuote(npmRoot)} --registry=https://registry.npmjs.org/ --no-audit --no-fund --omit=dev$lifecycle ${DeviceBridgeManager.shellQuote(exact)}"
                ),
                maxOutputBytes = 1_500_000,
            ) { _, _ -> }
            withContext(NonCancellable) {
                runSuspendCatching { bridge.shellBounded(lease.cleanupCommand(), maxOutputBytes = 8_192) }
            }
            installLease = null
            check(install.exitCode == 0) { "npm install failed: ${install.combined.takeLast(12_000)}" }
            val launcher = "$npmRoot/bin/${recipe.command}"
            DeviceBridgeManager.requireSafeRemotePath(launcher)
            val present = bridge.shell("test -e ${DeviceBridgeManager.shellQuote(launcher)}")
            check(present.exitCode == 0) { "Installed package did not expose '${recipe.command}'" }

            val finalNpmRoot = "$final/payload/npm"
            val wrapperScript = buildString {
                append("#!/system/bin/sh\nset -eu\n")
                append("NODE=").append(DeviceBridgeManager.shellQuote(nodeCommand)).append("\n")
                append("NPM_ROOT=").append(DeviceBridgeManager.shellQuote(finalNpmRoot)).append("\n")
                append("LAUNCHER=\"${'$'}NPM_ROOT/bin/${recipe.command}\"\n")
                append("TARGET=\"${'$'}(toybox readlink -f \"${'$'}LAUNCHER\" 2>/dev/null || printf '%s' \"${'$'}LAUNCHER\")\"\n")
                append("HEAD=\"${'$'}(toybox head -c 160 \"${'$'}TARGET\" 2>/dev/null || true)\"\n")
                append("case \"${'$'}HEAD\" in\n")
                append("  *node*) exec \"${'$'}NODE\" \"${'$'}TARGET\" \"${'$'}@\" ;;\n")
                append("  *sh*) exec /system/bin/sh \"${'$'}TARGET\" \"${'$'}@\" ;;\n")
                append("  *) exec \"${'$'}LAUNCHER\" \"${'$'}@\" ;;\n")
                append("esac\n")
            }
            ByteArrayInputStream(wrapperScript.toByteArray()).use { bridge.pushStream(it, wrapper, mode = 493) }

            val lock = ReviewedRecipeLock(
                familyId = recipe.familyId,
                version = recipe.version,
                packageName = recipe.packageName,
                packageVersion = recipe.packageVersion,
                command = recipe.command,
                minimumNodeMajor = recipe.minimumNodeMajor,
                provenanceUrl = recipe.provenanceUrl,
                registryIntegrity = recipe.registryIntegrity,
                allowLifecycleScripts = recipe.allowLifecycleScripts,
                lifecycleScriptReview = recipe.lifecycleScriptReview,
                nodeMajor = nodeMajor,
                npmVersion = npmVersion,
                nodeDependency = "${nodeDependency.familyId}@${nodeDependency.version}",
                installedAtEpochMs = System.currentTimeMillis(),
            )
            ByteArrayInputStream(json.encodeToString(lock).toByteArray()).use { bridge.pushStream(it, "$stage/RECIPE_LOCK.json", mode = 420) }
            val transientCleanup = bridge.shell(
                "rm -rf ${DeviceBridgeManager.shellQuote(npmCache)} ${DeviceBridgeManager.shellQuote(npmTmp)}"
            )
            check(transientCleanup.exitCode == 0) { "Could not reclaim npm transaction cache: ${transientCleanup.combined.takeLast(4_000)}" }

            val bounds = bridge.shellBounded(
                "set -eu; C=${'$'}(find ${DeviceBridgeManager.shellQuote(stage)} -type f | wc -l); " +
                    "B=${'$'}(du -sk ${DeviceBridgeManager.shellQuote(stage)} | awk '{print ${'$'}1}'); " +
                    "test \"${'$'}C\" -le 30000; test \"${'$'}B\" -le 1048576; printf '%s %s\\n' \"${'$'}C\" \"${'$'}B\"",
                maxOutputBytes = 16_384,
            )
            check(bounds.exitCode == 0) { "Reviewed recipe exceeded package safety bounds" }

            val checksums = bridge.shellBounded(
                "set -eu; cd ${DeviceBridgeManager.shellQuote(stage)}; " +
                    "find . -type f ! -name SHA256SUMS -print | sort | while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done > SHA256SUMS; " +
                    "toybox sha256sum -c SHA256SUMS",
                maxOutputBytes = 256_000,
            )
            check(checksums.exitCode == 0) { "Reviewed recipe integrity manifest failed: ${checksums.combined}" }

            val move = bridge.shell(
                "set -eu; rm -rf ${DeviceBridgeManager.shellQuote(previous)}; " +
                    "if [ -d ${DeviceBridgeManager.shellQuote(final)} ]; then mkdir -p ${DeviceBridgeManager.shellQuote("$root/packages/.previous")}; mv ${DeviceBridgeManager.shellQuote(final)} ${DeviceBridgeManager.shellQuote(previous)}; fi; " +
                    "mkdir -p ${DeviceBridgeManager.shellQuote(final.substringBeforeLast('/'))}; mv ${DeviceBridgeManager.shellQuote(stage)} ${DeviceBridgeManager.shellQuote(final)}"
            )
            check(move.exitCode == 0) { "Could not activate reviewed recipe: ${move.combined}" }
            projectionActivated = true

            val record = ManagedPackageRecord(
                familyId = recipe.familyId,
                version = recipe.version,
                scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                installRoot = final,
                installedAtEpochMs = System.currentTimeMillis(),
                active = true,
                pathEntries = listOf("$final/payload/bin", "$final/payload/npm/bin"),
                commands = mapOf(recipe.command to "$final/payload/bin/${recipe.command}"),
                dependencies = listOf("${nodeDependency.familyId}@${nodeDependency.version}"),
                healthChecks = listOf(ManagedPackageHealthCheck("bin/${recipe.command}", listOf("--version"))),
                artifactSha256 = null,
                abi = "arm64-v8a",
            )
            packageInstaller.adoptReviewedRecord(record)
            // Once registry/projection adoption succeeds, cleanup is a short commit tail and must not be interrupted into a state where a healthy install is reported.

            withContext(NonCancellable) {
                bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(previous)}")
            }
            projectionActivated = false
            record
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                installLease?.let { terminateInstallLease(it) }
                if (projectionActivated) {
                    bridge.shell(
                        "rm -rf ${DeviceBridgeManager.shellQuote(final)}; " +
                            "if [ -d ${DeviceBridgeManager.shellQuote(previous)} ]; then mv ${DeviceBridgeManager.shellQuote(previous)} ${DeviceBridgeManager.shellQuote(final)}; fi"
                    )
                }
                bridge.shell("rm -rf ${DeviceBridgeManager.shellQuote(stage)} ${DeviceBridgeManager.shellQuote(previous)}")
            }
            throw failure
        }
    }

    private companion object {
        const val REVIEWED_RECIPE_MAX_BYTES = 1_024L * 1_024L * 1_024L
        const val NPM_TRANSIENT_HEADROOM_BYTES = 256L * 1024L * 1024L
    }

    private suspend fun terminateInstallLease(lease: RemoteProcessLease) {
        runSuspendCatching { bridge.ensureHealthyConnection() }
        if (bridge.state.value.connected != null) {
            runSuspendCatching { bridge.shellBounded(lease.terminateCommand(), maxOutputBytes = 16_384) }
        }
    }
}
