package com.baystudio.droide.core

object LocalUbuntuReviewedRawPolicy {
    fun supports(source: PackageSourceDefinition): Boolean =
        source.installerReady &&
            source.source == PackageSourceKind.GITHUB_RELEASE &&
            source.githubArtifactFormat == DeclarativeGitHubArtifactFormat.RAW &&
            source.githubArtifactLayout == DeclarativeGitHubArtifactLayout.SINGLE_COMMAND &&
            source.githubInterpreterFamilyId == null &&
            source.githubInterpreterCommand == null &&
            source.githubInterpreterArgs.isEmpty() &&
            source.githubRuntimeCompatibility?.let(::supports) == true

    fun supports(recipe: GitHubReleaseInstallRecipe): Boolean = runCatching {
        recipe.validate()
        recipe.artifactFormat == DeclarativeGitHubArtifactFormat.RAW &&
            recipe.artifactLayout == DeclarativeGitHubArtifactLayout.SINGLE_COMMAND &&
            recipe.interpreterFamilyId == null &&
            recipe.interpreterCommand == null &&
            recipe.interpreterArgs.isEmpty() &&
            supports(recipe.runtimeCompatibility)
    }.getOrDefault(false)

    private fun supports(contract: DeclarativeRuntimeCompatibility): Boolean =
        contract.guestProfile == DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64 &&
            contract.libc == DeclarativeLibcCompatibility.GLIBC &&
            contract.integration == DeclarativeIntegrationKind.CLI &&
            contract.requiredFamilyIds.isEmpty() &&
            contract.requiredGuestCapabilities.all {
                PackageRuntimeCompatibilityPolicy.capabilitySupport(contract.guestProfile, it) == GuestCapabilitySupport.CERTIFIED
            }
}

internal object LocalManagedPackageMetadata {
    const val ACTIVATION_ID_KEY = "droide.local.activation-id"
    const val KIND_KEY = "droide.local.kind"
    const val KIND_UBUNTU_REVIEWED_RAW = "ubuntu-reviewed-raw-v1"
    const val KIND_UBUNTU_REVIEWED_TREE = "ubuntu-reviewed-tree-v1"
    const val KIND_UBUNTU_NPM = "ubuntu-npm-agent-v1"
    const val KIND_ANDROID_SDK_COMPONENT = "android-sdk-component-v1"
    const val ANDROID_COMPONENT_KIND_KEY = "droide.android.component.kind"
    const val ANDROID_COMPONENT_GUEST_TARGET_KEY = "droide.android.component.guest-target"
    const val ANDROID_COMPONENT_UPSTREAM_SHA1_KEY = "droide.android.component.upstream-sha1"
    const val ANDROID_COMPONENT_NATIVE_TOOLS_KEY = "droide.android.component.native-tools"
    const val PROFILE_KEY = "droide.runtime.profile"
    const val LIBC_KEY = "droide.runtime.libc"
    const val COMMAND_KEY = "droide.local.command"
    const val ELF_INTERPRETER_KEY = "droide.elf.interpreter"
    const val TREE_SHA256_KEY = "droide.tree.sha256"
    const val TREE_LINKS_SHA256_KEY = "droide.tree.links.sha256"
    const val TREE_COMMANDS_SHA256_KEY = "droide.tree.commands.sha256"
    const val SEAL_SHA256_KEY = "droide.local.seal.sha256"
}
