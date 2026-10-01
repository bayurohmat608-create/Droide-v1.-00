package com.baystudio.droide.core





sealed interface ReviewedBundlePrimaryRecipe {
    val version: String
    val sha256: String
    data class GitHub(val recipe: GitHubReleaseInstallRecipe) : ReviewedBundlePrimaryRecipe {
        override val version: String get() = recipe.version
        override val sha256: String get() = recipe.assetSha256
    }
    data class Vendor(val recipe: VendorArtifactInstallRecipe) : ReviewedBundlePrimaryRecipe {
        override val version: String get() = recipe.version
        override val sha256: String get() = recipe.assetSha256
    }
}

internal class ReviewedBundleProjectionCoordinator(
    private val sourceCatalog: PackageSourceCatalog,
    private val sourceResolver: PackageSourceResolver,
    private val registry: ManagedPackageRegistry,
    private val packageInstaller: ManagedPackageInstaller,
    private val reviewedArtifactBackend: ReviewedArtifactBackendRouter,
    private val bundleProjectionInstaller: ReviewedBundleProjectionInstaller,
) {
    suspend fun prepare(item: ExtensionVersionState): ExtensionInstallPlan.BundleProjection? {
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
        val primaryRecipe = when (primary.source) {
            PackageSourceKind.VENDOR_OFFICIAL -> ReviewedBundlePrimaryRecipe.Vendor(
                sourceResolver.resolveReviewedVendorOfficial(primary.familyId, item.version.version),
            )
            PackageSourceKind.GITHUB_RELEASE -> ReviewedBundlePrimaryRecipe.GitHub(
                sourceResolver.resolveReviewedGitHubRelease(primary.familyId, item.version.version),
            )
            else -> error("Unsupported shared-bundle primary provider: ${primary.source.label}")
        }
        return ExtensionInstallPlan.BundleProjection(
            familyId = item.family.id,
            familyName = item.family.name,
            requestedVersion = item.version.version,
            bundleId = bundle.id,
            primaryFamilyId = bundle.primaryFamilyId,
            primarySource = primary,
            primaryRecipe = primaryRecipe,
            command = follower.command,
            healthArgs = follower.healthArgs,
            provenanceUrl = follower.provenanceUrl,
            components = listOf(
                "Shared bundle: ${bundle.id}",
                "Primary owner: ${bundle.primaryFamilyId}",
                "Resolved version: ${primaryRecipe.version}",
                "Primary SHA-256: ${primaryRecipe.sha256}",
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
        val primary = ensurePrimary(plan.primarySource, plan.primaryRecipe, ensureInterpreter)
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
        pinned: ReviewedBundlePrimaryRecipe,
        ensureInterpreter: suspend (String, String) -> GitHubArtifactInterpreterDependency,
    ): ManagedPackageRecord {
        require(source.installerReady) { "Bundle primary is not owned by a certified installer" }
        val recipe = when (pinned) {
            is ReviewedBundlePrimaryRecipe.Vendor -> pinned.recipe.also {
                require(source.source == PackageSourceKind.VENDOR_OFFICIAL && it.familyId == source.familyId)
            }
            is ReviewedBundlePrimaryRecipe.GitHub -> pinned.recipe.also {
                require(source.source == PackageSourceKind.GITHUB_RELEASE && it.familyId == source.familyId)
            }
        }
        return registry.find(source.familyId, pinned.version)?.takeIf {
            it.artifactSha256 == pinned.sha256 && packageInstaller.verify(it)
        }
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
