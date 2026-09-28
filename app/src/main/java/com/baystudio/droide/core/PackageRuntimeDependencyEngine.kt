package com.baystudio.droide.core

import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


object PackageRuntimeDependencyPolicy {
    fun isResolvable(contract: DeclarativeRuntimeCompatibility): Boolean {
        contract.validate()
        val familiesReady = contract.requiredFamilyIds.all { WorkstationGuestPackageCatalog.forFamily(it).size == 1 }
        val commandsReady = contract.requiredCommands.all { WorkstationGuestPackageCatalog.providersForCommand(it).size == 1 }
        return familiesReady && commandsReady
    }
}






class PackageRuntimeDependencyEngine(
    private val registry: ManagedPackageRegistry,
    private val packageInstaller: ManagedPackageInstaller,
    private val guestInstaller: WorkstationGuestPackageInstaller,
    private val environment: WorkstationGuestEnvironmentManager,
) {
    suspend fun ensure(contract: DeclarativeRuntimeCompatibility): List<ManagedPackageRecord> = withContext(Dispatchers.IO) {
        guestInstaller.requireMixedBackend()
        PackageRuntimeCompatibilityPolicy.requireArtifactRuntimeCompatible(contract)
        val resolved = linkedMapOf<String, ManagedPackageRecord>()

        contract.requiredFamilyIds.forEach { familyId ->
            val recipes = WorkstationGuestPackageCatalog.forFamily(familyId)
            require(recipes.size == 1) { "Runtime dependency family '$familyId' is missing or ambiguous in the reviewed guest catalog" }
            ensureRecipe(recipes.single()).also { resolved[key(it)] = it }
        }

        contract.requiredCommands.forEach { command ->
            val providers = WorkstationGuestPackageCatalog.providersForCommand(command)
            require(providers.size == 1) { "Runtime command '$command' has no unique reviewed guest provider" }
            val record = ensureRecipe(providers.single())
            resolved[key(record)] = record
            val probe = environment.execute(listOf("sh", "-c", "command -v '$command' >/dev/null 2>&1"), maxOutputBytes = 8_192)
            check(probe.exitCode == 0) { "Managed runtime dependency did not provide '$command'" }
        }

        if (contract.requiredGuestPackages.isNotEmpty()) {
            val recipe = dependencyOnlyRecipe(contract.requiredGuestPackages)
            val record = ensureRecipe(recipe)
            resolved[key(record)] = record
        }

        resolved.values.toList()
    }

     
    suspend fun cleanupOrphanSynthetic(
        contract: DeclarativeRuntimeCompatibility,
        dependencyKeys: List<String>,
    ) = withContext(Dispatchers.IO) {
        guestInstaller.requireMixedBackend()
        if (contract.requiredGuestPackages.isEmpty()) return@withContext
        val recipe = dependencyOnlyRecipe(contract.requiredGuestPackages)
        val expected = "${recipe.familyId}@${recipe.version}"
        if (expected !in dependencyKeys) return@withContext
        val record = registry.find(recipe.familyId, recipe.version) ?: return@withContext
        val stillOwned = registry.list().any { it.familyId != record.familyId && expected in it.dependencies }
        if (!stillOwned) runCatching { guestInstaller.uninstall(recipe, record) }
    }

    private suspend fun ensureRecipe(recipe: WorkstationGuestPackageRecipe): ManagedPackageRecord {
        val previousDefault = registry.list().firstOrNull { it.familyId == recipe.familyId && it.active }
        val existing = registry.find(recipe.familyId, recipe.version)
        val healthy = existing?.let { runCatching { packageInstaller.verify(it) }.getOrDefault(false) } == true
        val record = if (healthy) requireNotNull(existing) else guestInstaller.repair(recipe, existing)
        if (previousDefault != null && previousDefault.version != record.version) {
            registry.find(previousDefault.familyId, previousDefault.version)?.let { previous ->
                if (!previous.active) runCatching { packageInstaller.activate(previous) }
            }
        }
        return record
    }

    private fun dependencyOnlyRecipe(packages: List<String>): WorkstationGuestPackageRecipe {
        val canonical = packages.sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((PackageRuntimeCompatibilityPolicy.CURRENT_GUEST_PROFILE + "\n" + canonical.joinToString("\n")).toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(20)
        return WorkstationGuestPackageRecipe(
            familyId = "runtime.deps.$digest",
            packages = canonical,
            commands = emptyMap(),
            healthCommand = null,
            healthArgs = emptyList(),
            provenanceUrl = "https://pkgs.alpinelinux.org/packages?branch=v3.24&arch=aarch64",
        ).also(WorkstationGuestPackageRecipe::validate)
    }

    private fun key(record: ManagedPackageRecord): String = "${record.familyId}@${record.version}"
}
