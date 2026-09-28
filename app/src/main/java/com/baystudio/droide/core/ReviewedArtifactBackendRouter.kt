package com.baystudio.droide.core

import android.content.Context








class ReviewedArtifactBackendRouter(
    context: Context,
    private val bridge: DeviceBridgeManager,
    private val packageInstaller: ManagedPackageInstaller,
    private val runtimeDependencyEngine: PackageRuntimeDependencyEngine,
    private val registry: ManagedPackageRegistry,
    private val sourceCatalog: PackageSourceCatalog,
    alpineGuest: WorkstationGuestEnvironmentManager,
) {
    private val appContext = context.applicationContext
    private val alpineEnvironment = AlpineReviewedGuestEnvironment(appContext, alpineGuest)
    private val ubuntuEnvironment = FoundryUbuntuGuestEnvironmentManager(appContext)

    fun requireBackend() = PackageBackendContract.requireSame(
        "Reviewed artifact transaction", PackageBackendId.DEVICE_ADB, packageInstaller.backendId,
        alpineEnvironment.backendId, ubuntuEnvironment.backendId,
    )

    suspend fun install(
        recipe: GitHubReleaseInstallRecipe,
        interpreter: GitHubArtifactInterpreterDependency?,
    ): ManagedPackageRecord {
        requireBackend()
        return ReviewedGitHubReleaseInstaller(
        appContext,
        bridge,
        packageInstaller,
        environment = environment(recipe.runtimeCompatibility.guestProfile),
    ).install(recipe, interpreter, dependencies(recipe.runtimeCompatibility))
    }

    suspend fun install(
        recipe: VendorArtifactInstallRecipe,
        interpreter: GitHubArtifactInterpreterDependency?,
    ): ManagedPackageRecord {
        requireBackend()
        return ReviewedVendorArtifactInstaller(
        appContext,
        bridge,
        packageInstaller,
        environment = environment(recipe.runtimeCompatibility.guestProfile),
    ).install(recipe, interpreter, dependencies(recipe.runtimeCompatibility))
    }

    private fun environment(profile: DeclarativeGuestProfile): ReviewedGuestExecutionEnvironment = when (profile) {
        DeclarativeGuestProfile.ALPINE_3_24_MUSL_ARM64 -> alpineEnvironment
        DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64 -> ubuntuEnvironment
    }

    private suspend fun dependencies(contract: DeclarativeRuntimeCompatibility): List<ManagedPackageRecord> = when (contract.guestProfile) {
        DeclarativeGuestProfile.ALPINE_3_24_MUSL_ARM64 -> runtimeDependencyEngine.ensure(contract)
        DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64 -> {
            require(contract.requiredFamilyIds.isEmpty()) {
                "Ubuntu Foundry routes currently admit reviewed apt package layers, not managed-family dependencies"
            }
            reconcileLegacyUbuntuOwnershipIfNeeded()
            val layer = ubuntuEnvironment.ensurePackages(contract.requiredGuestPackages)
            if (layer == null) {
                emptyList()
            } else {
                packageInstaller.adoptReviewedRecord(layer)
                listOf(layer)
            }
        }
    }

    




    private suspend fun reconcileLegacyUbuntuOwnershipIfNeeded() {
        val contracts = sourceCatalog.manifests.mapNotNull { manifest ->
            val contract = when (manifest.provider) {
                PackageSourceKind.GITHUB_RELEASE -> manifest.githubRelease?.runtimeCompatibility
                PackageSourceKind.VENDOR_OFFICIAL -> manifest.vendorOfficial?.runtimeCompatibility
                else -> null
            } ?: return@mapNotNull null
            if (contract.guestProfile != DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64) return@mapNotNull null
            manifest.familyId to contract
        }.toMap()

        if (ubuntuEnvironment.needsLegacyOwnershipAdoption()) {
            val legacyConsumers = registry.list().filter { record -> record.familyId in contracts }
            for (snapshot in legacyConsumers) {
                val current = registry.find(snapshot.familyId, snapshot.version) ?: continue
                val contract = requireNotNull(contracts[current.familyId])
                val expectedKey = ubuntuEnvironment.layerRecordKey(contract.requiredGuestPackages) ?: continue
                if (expectedKey in current.dependencies) continue
                check(packageInstaller.verify(current)) {
                    "Legacy glibc package ${current.familyId}@${current.version} is unhealthy; preserving broad r1 Ubuntu runtime for repair"
                }
                val layer = requireNotNull(ubuntuEnvironment.ensurePackages(contract.requiredGuestPackages))
                packageInstaller.adoptReviewedRecord(layer)
                val rebound = packageInstaller.rebindDependencies(current, current.dependencies + expectedKey)
                check(packageInstaller.verify(rebound)) {
                    "Legacy glibc package ${current.familyId}@${current.version} failed post-adoption health verification"
                }
            }

            val unresolved = registry.list().filter { record ->
                val contract = contracts[record.familyId] ?: return@filter false
                val expectedKey = ubuntuEnvironment.layerRecordKey(contract.requiredGuestPackages) ?: return@filter false
                expectedKey !in record.dependencies
            }
            check(unresolved.isEmpty()) {
                "Legacy Ubuntu ownership remains unresolved for: " + unresolved.joinToString { "${it.familyId}@${it.version}" }
            }
            ubuntuEnvironment.markLegacyOwnershipComplete()
        }

        if (!ubuntuEnvironment.needsLegacyPhysicalCompaction()) return
        val snapshot = registry.list()
        val layerSets = snapshot.filter { record ->
            record.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] == FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND
        }.mapNotNull { record ->
            record.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]
                ?.split(',')
                ?.filter(String::isNotBlank)
        }
        val consumers = snapshot.filter { it.familyId in contracts }
        check(consumers.all { packageInstaller.verify(it) }) {
            "Legacy glibc consumer is unhealthy before physical compaction; preserving broad Ubuntu runtime"
        }

        val transaction = ubuntuEnvironment.beginLegacyPhysicalCompaction(layerSets)
        val healthyAfterCompaction = runCatching { consumers.all { packageInstaller.verify(it) } }.getOrDefault(false)
        if (!healthyAfterCompaction) {
            ubuntuEnvironment.rollbackLegacyPhysicalCompaction(transaction)
            check(consumers.all { packageInstaller.verify(it) }) {
                "Legacy Ubuntu rollback completed but a glibc consumer remains unhealthy"
            }
            error("Legacy Ubuntu compaction failed consumer health verification and was rolled back")
        }
        ubuntuEnvironment.finalizeLegacyPhysicalCompaction(transaction)
    }

    




    suspend fun cleanupOrphanDependencies(
        contract: DeclarativeRuntimeCompatibility,
        dependencyKeys: List<String>,
    ) {
        requireBackend()
        when (contract.guestProfile) {
            DeclarativeGuestProfile.ALPINE_3_24_MUSL_ARM64 ->
                runtimeDependencyEngine.cleanupOrphanSynthetic(contract, dependencyKeys)
            DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64 -> cleanupOrphanUbuntuLayer(contract, dependencyKeys)
        }
    }

    private suspend fun cleanupOrphanUbuntuLayer(
        contract: DeclarativeRuntimeCompatibility,
        dependencyKeys: List<String>,
    ) {
        val expected = ubuntuEnvironment.layerRecordKey(contract.requiredGuestPackages) ?: return
        if (expected !in dependencyKeys) return
        val familyId = expected.substringBeforeLast('@')
        val version = expected.substringAfterLast('@')
        val record = registry.find(familyId, version) ?: return
        if (registry.list().any { expected in it.dependencies }) return

        val otherLayers = registry.list().filter { candidate ->
            candidate.familyId != familyId &&
                candidate.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] == FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND
        }
        val protectedSets = otherLayers.mapNotNull { candidate ->
            candidate.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]
                ?.split(',')
                ?.filter(String::isNotBlank)
        }
        val reclaimed = runCatching {
            ubuntuEnvironment.releaseLayer(
                packages = contract.requiredGuestPackages,
                protectedPackageSets = protectedSets,
                

                removeWholeEnvironment = false,
            )
            packageInstaller.uninstall(record)
        }.isSuccess
        if (!reclaimed) return
    }
}
