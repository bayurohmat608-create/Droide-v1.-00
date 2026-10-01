package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUbuntuReviewedWholeTreePolicyTest {
    @Test fun admitsReviewedNativeUbuntuWholeTree() {
        assertTrue(LocalUbuntuReviewedWholeTreePolicy.supports(githubSource()))
    }

    @Test fun rejectsInterpretedWholeTreeEvenOnUbuntu() {
        assertFalse(LocalUbuntuReviewedWholeTreePolicy.supports(githubSource(interpreter = "python3")))
    }

    @Test fun rejectsAlpineOrMuslWholeTree() {
        val incompatible = runtime().copy(
            guestProfile = DeclarativeGuestProfile.ALPINE_3_24_MUSL_ARM64,
            libc = DeclarativeLibcCompatibility.STATIC_OR_MUSL,
        )
        assertFalse(LocalUbuntuReviewedWholeTreePolicy.supports(githubSource(runtime = incompatible)))
    }

    @Test fun rejectsManagedFamilyDependencyUntilCrossFamilyLocalTransactionExists() {
        assertFalse(LocalUbuntuReviewedWholeTreePolicy.supports(
            githubSource(runtime = runtime().copy(requiredFamilyIds = listOf("runtime.python"))),
        ))
    }

    @Test fun reverseTcpRequiresCertifiedLoopbackCapability() {
        val dap = runtime().copy(
            integration = DeclarativeIntegrationKind.DAP_REVERSE_TCP,
            requiredGuestCapabilities = listOf(DeclarativeGuestCapability.LOOPBACK_TCP),
        )
        assertTrue(LocalUbuntuReviewedWholeTreePolicy.supports(githubSource(runtime = dap)))
        assertFalse(LocalUbuntuReviewedWholeTreePolicy.supports(
            githubSource(runtime = dap.copy(requiredGuestCapabilities = listOf(DeclarativeGuestCapability.PTRACE))),
        ))
    }

    private fun githubSource(
        runtime: DeclarativeRuntimeCompatibility = runtime(),
        interpreter: String? = null,
    ) = PackageSourceDefinition(
        familyId = "debug.netcoredbg",
        source = PackageSourceKind.GITHUB_RELEASE,
        packageName = "Samsung/netcoredbg",
        command = "netcoredbg",
        provenanceUrl = "https://github.com/Samsung/netcoredbg/releases",
        installerReady = true,
        githubTagPrefix = "",
        githubAssetTokens = listOf("netcoredbg", "linux", "arm64"),
        githubArtifactFormat = DeclarativeGitHubArtifactFormat.TAR_GZ,
        githubArtifactLayout = DeclarativeGitHubArtifactLayout.WHOLE_TREE,
        githubTreeEntryPoint = "netcoredbg",
        githubTreeExecutablePaths = listOf("netcoredbg"),
        githubTreeMaxFiles = 512,
        githubTreeMaxUnpackedBytes = 268_435_456,
        githubInterpreterFamilyId = interpreter?.let { "runtime.python" },
        githubInterpreterCommand = interpreter,
        githubAssetMaxBytes = 134_217_728,
        githubRuntimeCompatibility = runtime,
    )

    private fun runtime() = DeclarativeRuntimeCompatibility(
        guestProfile = DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64,
        libc = DeclarativeLibcCompatibility.GLIBC,
        integration = DeclarativeIntegrationKind.CLI,
        requiredGuestPackages = listOf("ca-certificates", "libgcc-s1", "libstdc++6"),
        evidenceUrl = "https://example.com/evidence",
    )
}
