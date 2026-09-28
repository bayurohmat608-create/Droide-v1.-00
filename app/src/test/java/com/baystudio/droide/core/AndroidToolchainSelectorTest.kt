package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidToolchainSelectorTest {
    @Test fun healthyManagedToolchainWinsWithoutConsultingUserFallback() = runBlocking {
        var userReads = 0
        val managed = manifest("managed")
        val selector = AndroidToolchainSelector(
            managedProvider = { managed },
            userProvider = { userReads++; manifest("user") },
            verifier = { _, _ -> null },
        )

        val result = selector.resolve(36)

        assertEquals(AndroidToolchainSelector.Source.MANAGED, result.resolved?.source)
        assertEquals("managed", result.resolved?.manifest?.version)
        assertEquals(0, userReads)
        assertNull(result.failure)
    }

    @Test fun brokenManagedToolchainFallsBackToExplicitUserToolchain() = runBlocking {
        val selector = AndroidToolchainSelector(
            managedProvider = { manifest("managed") },
            userProvider = { manifest("user") },
            verifier = { candidate, _ -> if (candidate.version == "managed") "broken managed JDK" else null },
        )

        val result = selector.resolve(36)

        assertEquals(AndroidToolchainSelector.Source.USER, result.resolved?.source)
        assertEquals("user", result.resolved?.manifest?.version)
        assertNull(result.failure)
    }

    @Test fun invalidUserMarkerFailsClosedAndKeepsDiagnostic() = runBlocking {
        val selector = AndroidToolchainSelector(
            managedProvider = { null },
            userProvider = { error("marker escapes workstation user root") },
            verifier = { _, _ -> null },
        )

        val result = selector.resolve(36)

        assertNull(result.resolved)
        assertTrue(result.failure.orEmpty().contains("marker escapes workstation user root"))
    }

    @Test fun unhealthyCandidatesReturnCombinedActionableFailure() = runBlocking {
        val selector = AndroidToolchainSelector(
            managedProvider = { manifest("managed") },
            userProvider = { manifest("user") },
            verifier = { candidate, _ -> "${candidate.version} health probe failed" },
        )

        val result = selector.resolve(36)

        assertNull(result.resolved)
        assertTrue(result.failure.orEmpty().contains("Managed toolchain: managed health probe failed"))
        assertTrue(result.failure.orEmpty().contains("User toolchain user: user health probe failed"))
    }

    private fun manifest(version: String) = AndroidDevelopmentManager.ToolchainManifest(
        version = version,
        abi = "arm64-v8a",
        compileSdk = 36,
        javaVersion = 17,
        gradleVersion = "8.13",
        aapt2Path = "/data/local/tmp/droide/user/toolchains/android/sdk/build-tools/36.0.0/aapt2",
        javaHome = "/data/local/tmp/droide/user/toolchains/android/jdk",
        sdkRoot = "/data/local/tmp/droide/user/toolchains/android/sdk",
    )
}
