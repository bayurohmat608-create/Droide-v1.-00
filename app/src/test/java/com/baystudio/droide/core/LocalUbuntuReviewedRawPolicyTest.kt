package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUbuntuReviewedRawPolicyTest {
    @Test fun admitsReviewedRawGlibcUbuntuSource() {
        assertTrue(LocalUbuntuReviewedRawPolicy.supports(source()))
    }

    @Test fun rejectsMuslContractOnUbuntuRoute() {
        assertFalse(LocalUbuntuReviewedRawPolicy.supports(source(runtime = runtime().copy(libc = DeclarativeLibcCompatibility.STATIC_OR_MUSL))))
    }

    @Test fun rawPolicyDoesNotClaimWholeTreeArtifacts() {
        assertFalse(LocalUbuntuReviewedRawPolicy.supports(source(layout = DeclarativeGitHubArtifactLayout.WHOLE_TREE)))
    }

    @Test fun rejectsManagedFamilyDependencyUntilLocalFamilyTransactionExists() {
        assertFalse(LocalUbuntuReviewedRawPolicy.supports(source(runtime = runtime().copy(requiredFamilyIds = listOf("runtime.python")))))
    }

    private fun source(
        layout: DeclarativeGitHubArtifactLayout = DeclarativeGitHubArtifactLayout.SINGLE_COMMAND,
        runtime: DeclarativeRuntimeCompatibility = runtime(),
    ) = PackageSourceDefinition(
        familyId = "build.bazel",
        source = PackageSourceKind.GITHUB_RELEASE,
        packageName = "bazelbuild/bazel",
        command = "bazel",
        provenanceUrl = "https://github.com/bazelbuild/bazel/releases",
        installerReady = true,
        githubTagPrefix = "",
        githubAssetTokens = listOf("bazel", "linux", "arm64"),
        githubAssetNameTemplate = "bazel-{version}-linux-arm64",
        githubArtifactFormat = DeclarativeGitHubArtifactFormat.RAW,
        githubArtifactLayout = layout,
        githubExecutableBasename = "bazel",
        githubAssetMaxBytes = 134_217_728,
        githubRuntimeCompatibility = runtime,
    )

    private fun runtime() = DeclarativeRuntimeCompatibility(
        guestProfile = DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64,
        libc = DeclarativeLibcCompatibility.GLIBC,
        integration = DeclarativeIntegrationKind.CLI,
        requiredGuestPackages = listOf("ca-certificates", "git", "unzip", "zip"),
        evidenceUrl = "https://example.com/evidence",
    )
}
