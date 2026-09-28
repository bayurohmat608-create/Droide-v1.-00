package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json







internal data class BundleProjectionRecipe(
    val familyId: String,
    val version: String,
    val bundleId: String,
    val primaryFamilyId: String,
    val primaryCommand: String,
    val healthArgs: List<String>,
    val provenanceUrl: String,
) {
    fun validate() {
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid bundle projection family" }
        require(version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))) { "Invalid bundle projection version" }
        require(bundleId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid bundle id" }
        require(primaryFamilyId.matches(Regex("[A-Za-z0-9._-]{1,120}")) && primaryFamilyId != familyId) { "Invalid bundle primary family" }
        require(primaryCommand.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid bundle primary command" }
        require(healthArgs.size in 1..8 && healthArgs.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }) {
            "Invalid bundle projection health arguments"
        }
        require(provenanceUrl.startsWith("https://") && provenanceUrl.length <= 500) { "Invalid bundle projection provenance" }
    }
}

internal class ReviewedBundleProjectionInstaller(
    private val bridge: DeviceBridgeManager,
    private val packageInstaller: ManagedPackageInstaller,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun install(recipe: BundleProjectionRecipe, primary: ManagedPackageRecord): ManagedPackageRecord = withContext(Dispatchers.IO) {
        recipe.validate()
        require(primary.familyId == recipe.primaryFamilyId && primary.version == recipe.version) { "Bundle primary/version mismatch" }
        require(packageInstaller.verify(primary)) { "Bundle primary failed managed-package verification" }
        val primaryTarget = primary.commands[recipe.primaryCommand]
            ?: error("Bundle primary does not export ${recipe.primaryCommand}")
        DeviceBridgeManager.requireSafeRemotePath(primaryTarget)
        require(primaryTarget.startsWith(primary.installRoot + "/")) { "Bundle primary command escapes its owned install root" }

        val root = DeviceBridgeManager.remoteRoot()
        val safeFamily = recipe.familyId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = recipe.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val stage = "$root/packages/.staging/$safeFamily-$safeVersion-bundle-projection"
        val final = "$root/packages/${recipe.familyId}/$safeVersion/arm64-v8a"
        val previous = "$root/packages/.previous/$safeFamily-$safeVersion-bundle-projection"
        val health = "$stage/payload/health"
        listOf(stage, final, previous, health).forEach(DeviceBridgeManager::requireSafeRemotePath)

        val prep = bridge.shell("set -eu; rm -rf ${q(stage)} ${q(previous)}; mkdir -p ${q("$stage/payload")} ${q("$root/packages/.previous")}")
        check(prep.exitCode == 0) { "Could not prepare bundle projection: ${prep.combined.takeLast(4_000)}" }

        var activated = false
        try {
            val healthScript = buildString {
                append("#!/system/bin/sh\nset -eu\nexec ").append(q(primaryTarget))
                recipe.healthArgs.forEach { append(' ').append(q(it)) }
                append("\n")
            }
            ByteArrayInputStream(healthScript.toByteArray(Charsets.UTF_8)).use { bridge.pushStream(it, health, mode = EXECUTABLE_MODE) }
            val primaryKey = "${primary.familyId}@${primary.version}"
            val lock = BundleProjectionLock(
                familyId = recipe.familyId,
                version = recipe.version,
                bundleId = recipe.bundleId,
                primaryPackage = primaryKey,
                primaryCommand = recipe.primaryCommand,
                healthArgs = recipe.healthArgs,
                provenanceUrl = recipe.provenanceUrl,
                installedAtEpochMs = System.currentTimeMillis(),
            )
            ByteArrayInputStream(json.encodeToString(lock).toByteArray(Charsets.UTF_8)).use {
                bridge.pushStream(it, "$stage/RECIPE_LOCK.json", mode = FILE_MODE)
            }
            val integrity = bridge.shellBounded(
                "set -eu; cd ${q(stage)}; find . -type f ! -name SHA256SUMS -print | sort | while IFS= read -r f; do toybox sha256sum \"${'$'}f\"; done > SHA256SUMS; toybox sha256sum -c SHA256SUMS >/dev/null; ${q(health)} >/dev/null",
                maxOutputBytes = 32_768,
            )
            check(integrity.exitCode == 0) { "Bundle projection health/integrity failed: ${integrity.combined.takeLast(4_000)}" }

            val move = bridge.shell(
                "set -eu; rm -rf ${q(previous)}; if [ -d ${q(final)} ]; then mv ${q(final)} ${q(previous)}; fi; " +
                    "mkdir -p ${q(final.substringBeforeLast('/'))}; mv ${q(stage)} ${q(final)}",
            )
            check(move.exitCode == 0) { "Could not activate bundle projection: ${move.combined.takeLast(4_000)}" }
            activated = true

            val record = ManagedPackageRecord(
                familyId = recipe.familyId,
                version = recipe.version,
                scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                installRoot = final,
                installedAtEpochMs = System.currentTimeMillis(),
                active = true,
                dependencies = listOf(primaryKey),
                healthChecks = listOf(ManagedPackageHealthCheck("health")),
            )
            packageInstaller.adoptReviewedRecord(record)
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

    private fun q(value: String): String = DeviceBridgeManager.shellQuote(value)

    private companion object {
        const val EXECUTABLE_MODE = 493
        const val FILE_MODE = 420
    }
}

@Serializable
private data class BundleProjectionLock(
    val schema: Int = 1,
    val installer: String = "bundle-projection-v1",
    val familyId: String,
    val version: String,
    val bundleId: String,
    val primaryPackage: String,
    val primaryCommand: String,
    val healthArgs: List<String>,
    val provenanceUrl: String,
    val installedAtEpochMs: Long,
)
