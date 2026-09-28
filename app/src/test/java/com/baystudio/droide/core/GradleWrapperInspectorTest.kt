package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleWrapperInspectorTest {
    @Test fun parsesPinnedStandardWrapperAndFingerprintIsStable() {
        val root = fixture(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip\n" +
                "distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78\n"
        )
        try {
            val first = GradleWrapperInspector.inspect(root)
            val second = GradleWrapperInspector.inspect(root)
            assertEquals("8.13", first.declaredVersion)
            assertEquals("20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78", first.distributionSha256)
            assertEquals(first.fingerprintSha256, second.fingerprintSha256)
            assertTrue(first.fingerprintSha256.matches(Regex("[0-9a-f]{64}")))
        } finally { root.deleteRecursively() }
    }

    @Test fun customDistributionRemainsCompatibleWithoutInventingVersion() {
        val root = fixture("distributionUrl=https\\://repo.example.invalid/custom-gradle.zip\n")
        try {
            val descriptor = GradleWrapperInspector.inspect(root)
            assertNull(descriptor.declaredVersion)
            assertEquals("https://repo.example.invalid/custom-gradle.zip", descriptor.distributionUrl)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsMalformedDistributionChecksum() {
        val root = fixture("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip\ndistributionSha256Sum=abc\n")
        try {
            val error = runCatching { GradleWrapperInspector.inspect(root) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("distributionSha256Sum"))
        } finally { root.deleteRecursively() }
    }

    @Test fun wrapperMutationChangesFingerprint() {
        val root = fixture("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip\n")
        try {
            val before = GradleWrapperInspector.inspect(root).fingerprintSha256
            File(root, "gradlew").appendText("\n# changed\n")
            val after = GradleWrapperInspector.inspect(root).fingerprintSha256
            assertTrue(before != after)
        } finally { root.deleteRecursively() }
    }

    private fun fixture(properties: String): File {
        val root = Files.createTempDirectory("droide-wrapper-test").toFile()
        File(root, "gradle/wrapper").mkdirs()
        File(root, "gradlew").writeText("#!/system/bin/sh\necho wrapper\n")
        File(root, "gradle/wrapper/gradle-wrapper.properties").writeText(properties)
        File(root, "gradle/wrapper/gradle-wrapper.jar").writeBytes(byteArrayOf(1, 2, 3, 4))
        return root
    }
}

class GradleWrapperRuntimeProbeTest {
    @Test fun runtimeProbeUsesExactToolchainAndCachesSuccessfulHandshake() = kotlinx.coroutines.runBlocking {
        var calls = 0
        var observed = ""
        val probe = GradleWrapperRuntimeProbe { command, cap ->
            calls++
            observed = command
            assertEquals(160_000, cap)
            BridgeShellResult(0, "Gradle 8.13\n", "")
        }
        val manifest = manifest()
        val descriptor = GradleWrapperDescriptor(
            distributionUrl = "https://services.gradle.org/distributions/gradle-8.13-bin.zip",
            declaredVersion = "8.13",
            distributionSha256 = null,
            fingerprintSha256 = "a".repeat(64),
        )

        assertEquals("8.13", probe.ensureReady("/data/local/tmp/droide/workspaces/demo", manifest, descriptor))
        assertEquals("8.13", probe.ensureReady("/data/local/tmp/droide/workspaces/demo", manifest, descriptor))
        assertEquals(1, calls)
        assertTrue(observed.contains("JAVA_HOME='/data/local/tmp/droide/user/toolchains/android/jdk'"))
        assertTrue(observed.contains("ANDROID_SDK_ROOT='/data/local/tmp/droide/user/toolchains/android/sdk'"))
        assertTrue(observed.contains("./.droide-gradlew --console=plain --version"))
    }

    @Test fun runtimeProbeCacheIdentityIncludesExecutionAndSdkPaths() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val probe = GradleWrapperRuntimeProbe { _, _ ->
            calls++
            BridgeShellResult(0, "Gradle 8.13\n", "")
        }
        val descriptor = GradleWrapperDescriptor(
            "https://services.gradle.org/distributions/gradle-8.13-bin.zip",
            "8.13",
            null,
            "d".repeat(64),
        )
        val compatibility = manifest().copy(
            runtime = AndroidToolchainRuntimeSpec(
                mode = AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU,
                launcherPath = "/data/local/tmp/droide/user/toolchains/android/proot",
                rootfsPath = "/data/local/tmp/droide/user/toolchains/android/rootfs",
                hostBinPath = "/data/local/tmp/droide/user/toolchains/android/host-bin",
                guestJavaHome = "/opt/jdk",
                qemuPath = "/data/local/tmp/droide/user/toolchains/android/qemu-a",
            )
        )
        probe.ensureReady("/data/local/tmp/droide/workspaces/demo", compatibility, descriptor)
        probe.ensureReady(
            "/data/local/tmp/droide/workspaces/demo",
            compatibility.copy(runtime = compatibility.runtime.copy(qemuPath = "/data/local/tmp/droide/user/toolchains/android/qemu-b")),
            descriptor,
        )
        probe.ensureReady(
            "/data/local/tmp/droide/workspaces/demo",
            compatibility.copy(
                sdkRoot = "/data/local/tmp/droide/user/toolchains/android/sdk-alt",
                aapt2Path = "/data/local/tmp/droide/user/toolchains/android/sdk-alt/build-tools/35.0.0/aapt2",
            ),
            descriptor,
        )
        assertEquals(3, calls)
    }

    @Test fun runtimeProbeRejectsDeclaredRuntimeVersionMismatch() = kotlinx.coroutines.runBlocking {
        val probe = GradleWrapperRuntimeProbe { _, _ -> BridgeShellResult(0, "Gradle 8.14\n", "") }
        val error = runCatching {
            probe.ensureReady(
                "/data/local/tmp/droide/workspaces/demo",
                manifest(),
                GradleWrapperDescriptor("https://services.gradle.org/distributions/gradle-8.13-bin.zip", "8.13", null, "b".repeat(64)),
            )
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("version mismatch"))
    }

    @Test fun failedRuntimeProbeIsNotCached() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val probe = GradleWrapperRuntimeProbe { _, _ ->
            calls++
            if (calls == 1) BridgeShellResult(1, "", "network unavailable") else BridgeShellResult(0, "Gradle 8.13\n", "")
        }
        val descriptor = GradleWrapperDescriptor("https://services.gradle.org/distributions/gradle-8.13-bin.zip", "8.13", null, "c".repeat(64))
        assertTrue(runCatching { probe.ensureReady("/data/local/tmp/droide/workspaces/demo", manifest(), descriptor) }.isFailure)
        assertEquals("8.13", probe.ensureReady("/data/local/tmp/droide/workspaces/demo", manifest(), descriptor))
        assertEquals(2, calls)
    }

    private fun manifest() = AndroidDevelopmentManager.ToolchainManifest(
        version = "user-1",
        abi = "arm64-v8a",
        compileSdk = 36,
        javaVersion = 17,
        gradleVersion = "8.13",
        aapt2Path = "/data/local/tmp/droide/user/toolchains/android/sdk/build-tools/35.0.0/aapt2",
        javaHome = "/data/local/tmp/droide/user/toolchains/android/jdk",
        sdkRoot = "/data/local/tmp/droide/user/toolchains/android/sdk",
    )
}
