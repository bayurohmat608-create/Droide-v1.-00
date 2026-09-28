package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleDependencyRuntimeTest {
    @Test fun missingExplicitCacheMarkerIsNormal() = runBlocking {
        val resolver = GradleReadOnlyDependencyCacheResolver { command, _ ->
            assertTrue(command.contains("gradle-ro-cache.json"))
            BridgeShellResult(0, "", "")
        }
        assertNull(resolver.resolve("8.13"))
    }

    @Test fun validExplicitCacheIsStrictlyBoundToRuntimeVersionAndUserRoot() = runBlocking {
        var calls = 0
        val root = GradleReadOnlyDependencyCacheResolver.userCacheRoot() + "/seed-8.13"
        val resolver = GradleReadOnlyDependencyCacheResolver { command, cap ->
            calls++
            if (calls == 1) {
                assertEquals(16_384, cap)
                BridgeShellResult(0, """{"schema":1,"gradleVersion":"8.13","metadataFormat":"metadata-2.107","root":"$root"}""", "")
            } else {
                assertTrue(command.contains("modules-2"))
                BridgeShellResult(0, "", "")
            }
        }
        val cache = resolver.resolve("8.13")
        assertEquals(root, cache?.root)
        assertEquals("metadata-2.107", cache?.metadataFormat)
        assertEquals(2, calls)
    }

    @Test fun explicitCacheCannotEscapeUserRootOrTargetAnotherGradleVersion() = runBlocking {
        suspend fun marker(json: String): Throwable? {
            val resolver = GradleReadOnlyDependencyCacheResolver { _, _ -> BridgeShellResult(0, json, "") }
            return runCatching { resolver.resolve("8.13") }.exceptionOrNull()
        }
        assertTrue(marker("""{"schema":1,"gradleVersion":"8.14","metadataFormat":"metadata-2.107","root":"/data/local/tmp/droide/user/gradle-cache/x"}""")?.message.orEmpty().contains("8.14"))
        assertTrue(marker("""{"schema":1,"gradleVersion":"8.13","metadataFormat":"metadata-2.107","root":"/data/local/tmp/droide/workspaces/evil"}""")?.message.orEmpty().contains("must stay inside"))
    }

    @Test fun projectConfigurationFingerprintChangesWithBuildLogicButIgnoresBuildOutput() {
        val root = Files.createTempDirectory("droide-gradle-config").toFile()
        try {
            File(root, "settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
            File(root, "app").mkdirs()
            File(root, "app/build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }\n")
            val first = GradleProjectConfigurationFingerprint.compute(root)
            File(root, "app/build").mkdirs()
            File(root, "app/build/generated.txt").writeText("ignored")
            assertEquals(first, GradleProjectConfigurationFingerprint.compute(root))
            File(root, "app/build.gradle.kts").appendText("repositories { mavenCentral() }\n")
            assertTrue(first != GradleProjectConfigurationFingerprint.compute(root))
        } finally { root.deleteRecursively() }
    }


    @Test fun projectConfigurationFingerprintTracksCustomCatalogAndIncludedBuildLogic() {
        val root = Files.createTempDirectory("droide-gradle-config").toFile()
        try {
            File(root, "settings.gradle.kts").writeText("rootProject.name = \"demo\"\nincludeBuild(\"convention-plugins\")\n")
            File(root, "gradle").mkdirs()
            File(root, "gradle/dependencies.toml").writeText("[versions]\nx=\"1\"\n")
            File(root, "convention-plugins/src/main/kotlin").mkdirs()
            File(root, "convention-plugins/src/main/kotlin/Plugin.kt").writeText("class PluginA\n")
            val first = GradleProjectConfigurationFingerprint.compute(root)
            File(root, "gradle/dependencies.toml").writeText("[versions]\nx=\"2\"\n")
            val second = GradleProjectConfigurationFingerprint.compute(root)
            assertTrue(first != second)
            File(root, "convention-plugins/src/main/kotlin/Plugin.kt").writeText("class PluginB\n")
            assertTrue(second != GradleProjectConfigurationFingerprint.compute(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun dependencyProbeUsesReadOnlyCacheAndCachesOnlySuccess() = runBlocking {
        var calls = 0
        var observed = ""
        val probe = GradleDependencyRuntimeProbe { command, cap ->
            calls++
            observed = command
            assertEquals(500_000, cap)
            if (calls == 1) BridgeShellResult(1, "", "java.net.UnknownHostException: repo.example")
            else BridgeShellResult(0, "BUILD SUCCESSFUL", "")
        }
        val cache = GradleReadOnlyDependencyCache(
            root = GradleReadOnlyDependencyCacheResolver.userCacheRoot() + "/seed-8.13",
            gradleVersion = "8.13",
            metadataFormat = "metadata-2.107",
        )
        val first = runCatching { probe.ensureReady(workspace(), manifest(), wrapper(), "d".repeat(64), cache) }.exceptionOrNull()
        assertTrue(first?.message.orEmpty().contains("repositories are unreachable"))
        probe.ensureReady(workspace(), manifest(), wrapper(), "d".repeat(64), cache)
        probe.ensureReady(workspace(), manifest(), wrapper(), "d".repeat(64), cache)
        assertEquals(2, calls)
        assertTrue(observed.contains("GRADLE_RO_DEP_CACHE='${cache.root}'"))
        assertTrue(observed.contains("--max-workers=1"))
        assertTrue(observed.contains("help"))
        assertTrue(!observed.contains("--offline"))
    }

    @Test fun dependencyProbeCacheSeparatesSdkAndAapt2Identity() = runBlocking {
        var calls = 0
        val probe = GradleDependencyRuntimeProbe { _, _ -> calls++; BridgeShellResult(0, "BUILD SUCCESSFUL", "") }
        val base = manifest()
        probe.ensureReady(workspace(), base, wrapper(), "e".repeat(64), null)
        probe.ensureReady(workspace(), base, wrapper(), "e".repeat(64), null)
        val otherSdk = base.copy(
            sdkRoot = "/data/local/tmp/droide/user/toolchains/android/sdk-alt",
            aapt2Path = "/data/local/tmp/droide/user/toolchains/android/sdk-alt/build-tools/36.0.0/aapt2",
        )
        probe.ensureReady(workspace(), otherSdk, wrapper(), "e".repeat(64), null)
        assertEquals(2, calls)
    }

    @Test fun buildCommandUsesSameReadOnlyCacheWithoutForcingOfflineMode() {
        val cache = GradleReadOnlyDependencyCache(
            root = GradleReadOnlyDependencyCacheResolver.userCacheRoot() + "/seed-8.13",
            gradleVersion = "8.13",
            metadataFormat = "metadata-2.107",
        )
        val command = GradleBuildCommand.create(manifest(), workspace(), "assembleDebug", 2, true, true, 120_000, false, cache)
        assertTrue(command.contains("GRADLE_RO_DEP_CACHE='${cache.root}'"))
        assertTrue(command.contains("--build-cache"))
        assertTrue(command.contains("assembleDebug"))
        assertTrue(!command.contains("--offline"))
    }

    private fun workspace() = DeviceBridgeManager.remoteRoot() + "/workspaces/demo"

    private fun wrapper() = GradleWrapperDescriptor(
        distributionUrl = "https://services.gradle.org/distributions/gradle-8.13-bin.zip",
        declaredVersion = "8.13",
        distributionSha256 = null,
        fingerprintSha256 = "a".repeat(64),
    )

    private fun manifest() = AndroidDevelopmentManager.ToolchainManifest(
        version = "user-1",
        abi = "arm64-v8a",
        compileSdk = 36,
        javaVersion = 17,
        gradleVersion = "8.13",
        aapt2Path = "/data/local/tmp/droide/user/toolchains/android/sdk/build-tools/36.0.0/aapt2",
        javaHome = "/data/local/tmp/droide/user/toolchains/android/jdk",
        sdkRoot = "/data/local/tmp/droide/user/toolchains/android/sdk",
    )
}
