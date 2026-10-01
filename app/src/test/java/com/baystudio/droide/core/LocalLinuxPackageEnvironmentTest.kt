package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLinuxPackageEnvironmentTest {
    @Test fun workspaceSelectionOverridesGlobalActiveVersion() {
        val root = "/data/user/0/droide/files"
        val records = listOf(
            record(root, "build.bazel", "8.7.0", active = true),
            record(root, "build.bazel", "9.1.0", active = false),
        )
        assertEquals(
            listOf("/opt/droide/packages/build.bazel/9.1.0/arm64-v8a/payload/bin"),
            LocalLinuxPackageEnvironment.guestBinPaths(records, mapOf("build.bazel" to "9.1.0"), root),
        )
    }

    @Test fun staleWorkspaceSelectionFallsBackToActiveVersion() {
        val root = "/data/user/0/droide/files"
        val records = listOf(record(root, "build.bazel", "8.7.0", active = true))
        assertEquals(
            listOf("/opt/droide/packages/build.bazel/8.7.0/arm64-v8a/payload/bin"),
            LocalLinuxPackageEnvironment.guestBinPaths(records, mapOf("build.bazel" to "missing"), root),
        )
    }

    @Test fun nonUbuntuLocalKindsNeverLeakIntoUbuntuPath() {
        val root = "/data/user/0/droide/files"
        val record = record(root, "cli.jq", "1.7.1", active = true).copy(metadata = emptyMap())
        assertEquals(emptyList<String>(), LocalLinuxPackageEnvironment.guestBinPaths(listOf(record), emptyMap(), root))
    }

    @Test fun wholeTreePackagesProjectOwnedEnvironmentIntoGuest() {
        val root = "/data/user/0/droide/files"
        val install = "$root/packages/toolchain.jdk/21/arm64-v8a"
        val record = ManagedPackageRecord(
            familyId = "toolchain.jdk", version = "21", scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
            installRoot = install, installedAtEpochMs = 3, active = true,
            commands = mapOf("java" to "$install/payload/bin/java"),
            environment = mapOf("JAVA_HOME" to "$install/payload/jdk", "ESCAPE" to "/sdcard/not-owned"),
            metadata = mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE),
        )
        assertEquals(
            listOf("/opt/droide/packages/toolchain.jdk/21/arm64-v8a/payload/jdk/bin",
                "/opt/droide/packages/toolchain.jdk/21/arm64-v8a/payload/bin"),
            LocalLinuxPackageEnvironment.guestBinPaths(listOf(record), emptyMap(), root),
        )
        assertEquals(
            mapOf("JAVA_HOME" to "/opt/droide/packages/toolchain.jdk/21/arm64-v8a/payload/jdk"),
            LocalLinuxPackageEnvironment.guestEnvironment(listOf(record), emptyMap(), root),
        )
    }

    @Test fun selectedJdkProjectsMatchingJavaHomeAndRealBinBeforeWrappers() {
        val root = "/data/user/0/droide/files"
        fun jdk(version: String, active: Boolean): ManagedPackageRecord {
            val base = record(root, "toolchain.jdk", version, active)
            return base.copy(environment = mapOf("JAVA_HOME" to "${base.installRoot}/payload/tree/jdk-$version"),
                metadata = mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE))
        }
        val records = listOf(jdk("17", true), jdk("21", false))
        assertEquals(mapOf("JAVA_HOME" to "/opt/droide/packages/toolchain.jdk/21/arm64-v8a/payload/tree/jdk-21"),
            LocalLinuxPackageEnvironment.guestEnvironment(records, mapOf("toolchain.jdk" to "21"), root))
        assertEquals(listOf("/opt/droide/packages/toolchain.jdk/21/arm64-v8a/payload/tree/jdk-21/bin",
            "/opt/droide/packages/toolchain.jdk/21/arm64-v8a/payload/bin"),
            LocalLinuxPackageEnvironment.guestBinPaths(records, mapOf("toolchain.jdk" to "21"), root))
    }

    @Test fun receiptCannotAcquireEnvironmentFromAnotherPackage() {
        val root = "/data/user/0/droide/files"
        val packageRecord = record(root, "build.bazel", "8", true).copy(
            environment = mapOf("JAVA_HOME" to "$root/packages/toolchain.jdk/17/arm64-v8a/payload/tree/jdk"))
        assertEquals(emptyMap<String, String>(), LocalLinuxPackageEnvironment.guestEnvironment(listOf(packageRecord), emptyMap(), root))
    }

    @Test fun traversalAndAlpineReceiptsCannotSelectUbuntuWorkspaceVersion() {
        val root = "/data/user/0/droide/files"
        val valid = record(root, "build.bazel", "8", true)
        org.junit.Assert.assertTrue(LocalLinuxPackageEnvironment.supportsWorkspaceSelection(valid, root))
        org.junit.Assert.assertFalse(LocalLinuxPackageEnvironment.supportsWorkspaceSelection(valid.copy(installRoot = "$root/packages/../elsewhere"), root))
        org.junit.Assert.assertFalse(LocalLinuxPackageEnvironment.supportsWorkspaceSelection(valid.copy(metadata = emptyMap()), root))
    }

    private fun record(root: String, family: String, version: String, active: Boolean) = ManagedPackageRecord(
        familyId = family,
        version = version,
        scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
        installRoot = "$root/packages/$family/$version/arm64-v8a",
        installedAtEpochMs = if (active) 2 else 1,
        active = active,
        commands = mapOf("bazel" to "$root/packages/$family/$version/arm64-v8a/payload/host-bin/bazel"),
        metadata = mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW),
    )

    @Test fun sdkPlatformReceiptParticipatesInVersionSelectionWithoutPollutingPath() {
        val root = "/data/user/0/droide/files"
        fun sdk(version: String, active: Boolean) = ManagedPackageRecord(
            familyId = "sdk.android",
            version = version,
            scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
            installRoot = "$root/packages/sdk.android/$version/arm64-v8a",
            installedAtEpochMs = if (active) 2 else 1,
            active = active,
            metadata = mapOf(
                LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT,
                LocalManagedPackageMetadata.ANDROID_COMPONENT_KIND_KEY to AndroidLocalComponentKind.SDK_PLATFORM.name,
                LocalManagedPackageMetadata.ANDROID_COMPONENT_GUEST_TARGET_KEY to "platforms/android-$version",
                LocalManagedPackageMetadata.ANDROID_COMPONENT_UPSTREAM_SHA1_KEY to "a".repeat(40),
            ),
        )
        val records = listOf(sdk("35", active = true), sdk("36", active = false))
        assertEquals(
            listOf("36"),
            LocalLinuxPackageEnvironment.selectedRecords(records, mapOf("sdk.android" to "36"), root).map { it.version },
        )
        assertEquals(emptyList<String>(), LocalLinuxPackageEnvironment.guestBinPaths(records, mapOf("sdk.android" to "36"), root))
    }

}
