package com.baystudio.droide.core





internal class ReviewedBundleProjectionCoordinator(
    private val sourceCatalog: PackageSourceCatalog,
    private val sourceResolver: PackageSourceResolver,
    private val registry: ManagedPackageRegistry,
    private val packageInstaller: ManagedPackageInstaller,
    private val reviewedArtifactBackend: ReviewedArtifactBackendRouter,
    private val bundleProjectionInstaller: ReviewedBundleProjectionInstaller,
) {
    fun prepare(item: ExtensionVersionState): ExtensionInstallPlan.BundleProjection? {
        val manifest = sourceCatalog.manifest(item.family.id) ?: return null
        val bundle = manifest.bundle ?: return null
        if (bundle.primaryFamilyId == item.family.id) return null
        val primary = sourceCatalog.installable(bundle.primaryFamilyId) ?: return null
        require(primary.bundleId == bundle.id && primary.bundlePrimaryFamilyId == bundle.primaryFamilyId) {
            "Bundle primary authority is inconsistent for ${item.family.id}"
        }
        val follower = sourceCatalog.find(item.family.id) ?: return null
        require(follower.bundleId == bundle.id && follower.bundlePrimaryFamilyId == bundle.primaryFamilyId) {
            "Bundle follower authority is inconsistent for ${item.family.id}"
        }
        return ExtensionInstallPlan.BundleProjection(
            familyId = item.family.id,
            familyName = item.family.name,
            requestedVersion = item.version.version,
            bundleId = bundle.id,
            primaryFamilyId = bundle.primaryFamilyId,
            primarySource = primary,
            command = follower.command,
            healthArgs = follower.healthArgs,
            provenanceUrl = follower.provenanceUrl,
            components = listOf(
                "Shared bundle: ${bundle.id}",
                "Primary owner: ${bundle.primaryFamilyId}",
                "No duplicate SDK download",
                "Exact dependency edge + independent projection uninstall",
            ),
        )
    }

    suspend fun install(
        plan: ExtensionInstallPlan.BundleProjection,
        ensureInterpreter: suspend (String, String) -> GitHubArtifactInterpreterDependency,
    ): ManagedPackageRecord {
        reviewedArtifactBackend.requireBackend()
        val primary = ensurePrimary(plan.primarySource, plan.requestedVersion, ensureInterpreter)
        return bundleProjectionInstaller.install(
            BundleProjectionRecipe(
                familyId = plan.familyId,
                version = primary.version,
                bundleId = plan.bundleId,
                primaryFamilyId = plan.primaryFamilyId,
                primaryCommand = plan.command,
                healthArgs = plan.healthArgs,
                provenanceUrl = plan.provenanceUrl,
            ),
            primary,
        )
    }

    private suspend fun ensurePrimary(
        source: PackageSourceDefinition,
        requestedVersion: String,
        ensureInterpreter: suspend (String, String) -> GitHubArtifactInterpreterDependency,
    ): ManagedPackageRecord {
        require(source.installerReady) { "Bundle primary is not owned by a certified installer" }
        val recipe = when (source.source) {
            PackageSourceKind.VENDOR_OFFICIAL -> sourceResolver.resolveReviewedVendorOfficial(source.familyId, requestedVersion)
            PackageSourceKind.GITHUB_RELEASE -> sourceResolver.resolveReviewedGitHubRelease(source.familyId, requestedVersion)
            else -> error("Bundle projection primary provider ${source.source.label} is not supported by the reviewed shared-bundle engine")
        }
        val resolvedVersion = when (recipe) {
            is VendorArtifactInstallRecipe -> recipe.version
            is GitHubReleaseInstallRecipe -> recipe.version
            else -> error("Unsupported shared-bundle primary recipe")
        }
        return registry.find(source.familyId, resolvedVersion)?.takeIf { packageInstaller.verify(it) }
            ?: installPrimary(recipe, ensureInterpreter)
    }

    private suspend fun installPrimary(
        recipe: Any,
        ensureInterpreter: suspend (String, String) -> GitHubArtifactInterpreterDependency,
    ): ManagedPackageRecord {
        val familyId: String
        val interpreterFamilyId: String?
        val interpreterCommand: String?
        when (recipe) {
            is VendorArtifactInstallRecipe -> {
                familyId = recipe.familyId; interpreterFamilyId = recipe.interpreterFamilyId; interpreterCommand = recipe.interpreterCommand
            }
            is GitHubReleaseInstallRecipe -> {
                familyId = recipe.familyId; interpreterFamilyId = recipe.interpreterFamilyId; interpreterCommand = recipe.interpreterCommand
            }
            else -> error("Unsupported shared-bundle primary recipe")
        }
        val previous = interpreterFamilyId?.let { id -> registry.list().firstOrNull { it.familyId == id && it.active } }
        val interpreter = if (interpreterFamilyId != null) ensureInterpreter(interpreterFamilyId, requireNotNull(interpreterCommand)) else null
        return try {
            when (recipe) {
                is VendorArtifactInstallRecipe -> reviewedArtifactBackend.install(recipe, interpreter)
                is GitHubReleaseInstallRecipe -> reviewedArtifactBackend.install(recipe, interpreter)
                else -> error("Unsupported shared-bundle primary recipe")
            }
        } finally {
            if (previous != null && interpreter != null && previous.version != interpreter.record.version) {
                registry.find(previous.familyId, previous.version)?.let { record ->
                    if (!record.active) runSuspendCatching { packageInstaller.activate(record) }
                }
            }
        }
    }
}
