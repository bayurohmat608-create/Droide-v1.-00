package com.baystudio.droide.core

// Commit durable state only after verification succeeds.
object LocalUbuntuReviewedArtifactPolicy {
    fun supports(source: PackageSourceDefinition): Boolean =
        LocalUbuntuReviewedRawPolicy.supports(source) || LocalUbuntuReviewedWholeTreePolicy.supports(source)

    fun supports(recipe: GitHubReleaseInstallRecipe): Boolean =
        LocalUbuntuReviewedRawPolicy.supports(recipe) || LocalUbuntuReviewedWholeTreePolicy.supports(recipe)

    fun supports(recipe: VendorArtifactInstallRecipe): Boolean =
        LocalUbuntuReviewedWholeTreePolicy.supports(recipe)
}

object LocalUbuntuReviewedWholeTreePolicy {
    fun supports(source: PackageSourceDefinition): Boolean = runCatching {
        source.validate()
        when (source.source) {
            PackageSourceKind.GITHUB_RELEASE ->
                source.installerReady &&
                    source.githubArtifactLayout == DeclarativeGitHubArtifactLayout.WHOLE_TREE &&
                    source.githubArtifactFormat in WHOLE_TREE_FORMATS &&
                    source.githubInterpreterFamilyId == null &&
                    source.githubInterpreterCommand == null &&
                    source.githubInterpreterArgs.isEmpty() &&
                    source.githubRuntimeCompatibility?.let(::supportsRuntime) == true

            PackageSourceKind.VENDOR_OFFICIAL ->
                source.installerReady &&
                    source.vendorArtifactLayout == DeclarativeGitHubArtifactLayout.WHOLE_TREE &&
                    source.vendorArtifactFormat in VENDOR_WHOLE_TREE_FORMATS &&
                    source.vendorInterpreterFamilyId == null &&
                    source.vendorInterpreterCommand == null &&
                    source.vendorRuntimeCompatibility?.let(::supportsRuntime) == true

            else -> false
        }
    }.getOrDefault(false)

    fun supports(recipe: GitHubReleaseInstallRecipe): Boolean = runCatching {
        recipe.validate()
        recipe.artifactLayout == DeclarativeGitHubArtifactLayout.WHOLE_TREE &&
            recipe.artifactFormat in WHOLE_TREE_FORMATS &&
            recipe.interpreterFamilyId == null &&
            recipe.interpreterCommand == null &&
            recipe.interpreterArgs.isEmpty() &&
            supportsRuntime(recipe.runtimeCompatibility)
    }.getOrDefault(false)

    fun supports(recipe: VendorArtifactInstallRecipe): Boolean = runCatching {
        recipe.validate()
        recipe.artifactLayout == DeclarativeGitHubArtifactLayout.WHOLE_TREE &&
            recipe.artifactFormat in VENDOR_WHOLE_TREE_FORMATS &&
            recipe.interpreterFamilyId == null &&
            recipe.interpreterCommand == null &&
            supportsRuntime(recipe.runtimeCompatibility)
    }.getOrDefault(false)

    private fun supportsRuntime(contract: DeclarativeRuntimeCompatibility): Boolean = runCatching {
        contract.validate()
        contract.guestProfile == DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64 &&
            contract.libc == DeclarativeLibcCompatibility.GLIBC &&
            contract.integration in setOf(
                DeclarativeIntegrationKind.CLI,
                DeclarativeIntegrationKind.DAP_STDIO,
                DeclarativeIntegrationKind.DAP_REVERSE_TCP,
            ) &&
            contract.requiredFamilyIds.isEmpty() &&
            contract.requiredGuestCapabilities.all {
                PackageRuntimeCompatibilityPolicy.capabilitySupport(contract.guestProfile, it) == GuestCapabilitySupport.CERTIFIED
            }
    }.getOrDefault(false)

    private val WHOLE_TREE_FORMATS = setOf(
        DeclarativeGitHubArtifactFormat.TAR_GZ,
        DeclarativeGitHubArtifactFormat.TGZ,
        DeclarativeGitHubArtifactFormat.ZIP,
        DeclarativeGitHubArtifactFormat.VSIX,
    )

    private val VENDOR_WHOLE_TREE_FORMATS = setOf(
        DeclarativeGitHubArtifactFormat.TAR_GZ,
        DeclarativeGitHubArtifactFormat.TGZ,
        DeclarativeGitHubArtifactFormat.ZIP,
    )
}
