package com.baystudio.droide.core

import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogValidationTest {
    private fun packageEntry(id: String = "gopls-arm64") = ManagedPackageCatalogEntry(
        id = id,
        familyId = "lsp.gopls",
        version = "0.23.0",
        abi = "arm64-v8a",
        downloadUrl = "https://example.invalid/gopls.zip",
        fileName = "gopls.zip",
        sizeBytes = 1234,
        sha256 = "a".repeat(64),
        provenance = "Reproducible test fixture",
        provenanceUrl = "https://example.invalid/provenance",
    )

    @Test fun managedCatalogRejectsDuplicateIdsAndTriples() {
        ManagedPackageCatalog.validate(ManagedPackageCatalogDocument(revision = "test-1", entries = listOf(packageEntry())))
        assertThrows(IllegalArgumentException::class.java) {
            ManagedPackageCatalog.validate(ManagedPackageCatalogDocument(revision = "test-1", entries = listOf(packageEntry(), packageEntry())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManagedPackageCatalog.validate(
                ManagedPackageCatalogDocument(
                    revision = "test-1",
                    entries = listOf(packageEntry("one"), packageEntry("two")),
                )
            )
        }
    }

    @Test fun androidToolchainCatalogRejectsDuplicateAbiSdkMode() {
        fun entry(id: String) = AndroidToolchainCatalogEntry(
            id = id,
            version = "1.0.0",
            abi = "arm64-v8a",
            compileSdk = 36,
            javaVersion = 17,
            gradleVersion = "8.13",
            downloadUrl = "https://example.invalid/toolchain.zip",
            fileName = "$id.zip",
            sizeBytes = 1234,
            sha256 = "b".repeat(64),
            sdkLicenseSha256 = "c".repeat(64),
            provenance = "Test fixture",
            provenanceUrl = "https://example.invalid/provenance",
        )
        AndroidToolchainCatalog.validate(AndroidToolchainCatalogDocument(revision = "test-1", entries = listOf(entry("one"))))
        assertThrows(IllegalArgumentException::class.java) {
            AndroidToolchainCatalog.validate(AndroidToolchainCatalogDocument(revision = "test-1", entries = listOf(entry("one"), entry("two"))))
        }
    }

    @Test fun androidToolchainCatalogEnforcesArtifactHostForExecutionMode() {
        fun entry(
            mode: AndroidToolchainExecutionMode,
            host: AndroidToolchainArtifactHost,
        ) = AndroidToolchainCatalogEntry(
            id = "fixture-${mode.name.lowercase()}-${host.name.lowercase()}",
            version = "1.0.0",
            abi = "arm64-v8a",
            compileSdk = 36,
            javaVersion = 17,
            gradleVersion = "8.13",
            downloadUrl = "https://example.invalid/toolchain.zip",
            fileName = "toolchain.zip",
            sizeBytes = 1234,
            sha256 = "d".repeat(64),
            sdkLicenseSha256 = "e".repeat(64),
            provenance = "Test fixture",
            provenanceUrl = "https://example.invalid/provenance",
            executionMode = mode,
            artifactHost = host,
        )

        AndroidToolchainCatalog.validate(
            AndroidToolchainCatalogDocument(
                revision = "test-host-arm64",
                entries = listOf(entry(AndroidToolchainExecutionMode.LINUX_ARM64_PROOT, AndroidToolchainArtifactHost.LINUX_ARM64)),
            )
        )
        AndroidToolchainCatalog.validate(
            AndroidToolchainCatalogDocument(
                revision = "test-host-x86",
                entries = listOf(entry(AndroidToolchainExecutionMode.LINUX_X86_64_PROOT_QEMU, AndroidToolchainArtifactHost.LINUX_X86_64)),
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            AndroidToolchainCatalog.validate(
                AndroidToolchainCatalogDocument(
                    revision = "test-host-mismatch",
                    entries = listOf(entry(AndroidToolchainExecutionMode.LINUX_ARM64_PROOT, AndroidToolchainArtifactHost.LINUX_X86_64)),
                )
            )
        }
    }

    @Test fun localAndroidComponentCatalogPinsSdkPlatformLayoutAndDigest() {
        fun entry(
            id: String = "platform-36",
            target: String = "platforms/android-36",
            sha256: String = "f".repeat(64),
        ) = AndroidLocalComponentCatalogEntry(
            id = id,
            familyId = "sdk.android",
            version = "36",
            kind = AndroidLocalComponentKind.SDK_PLATFORM,
            downloadUrl = "https://dl.google.com/android/repository/platform-36_r02.zip",
            fileName = "platform-36_r02.zip",
            sizeBytes = 65_878_410,
            sha256 = sha256,
            upstreamSha1 = "2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576",
            archiveRoot = "android-36",
            guestTarget = target,
            maxFiles = 2048,
            maxUnpackedBytes = 268_435_456,
            provenance = "Official Google repository fixture",
            provenanceUrl = "https://dl.google.com/android/repository/repository2-3.xml",
        )

        AndroidLocalComponentCatalog.validate(
            AndroidLocalComponentCatalogDocument(revision = "test-local-sdk", entries = listOf(entry()))
        )
        assertThrows(IllegalArgumentException::class.java) {
            AndroidLocalComponentCatalog.validate(
                AndroidLocalComponentCatalogDocument(revision = "test-local-sdk-target", entries = listOf(entry(target = "build-tools/36.0.0")))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidLocalComponentCatalog.validate(
                AndroidLocalComponentCatalogDocument(
                    revision = "test-local-sdk-duplicate",
                    entries = listOf(entry("one"), entry("two")),
                )
            )
        }
    }

    @Test fun localAndroidComponentCatalogPinsBuildToolsArm64Overlay() {
        val tools = listOf(
            AndroidLocalNativeTool("aapt2", "https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/aapt2", 6_661_672, "1".repeat(64)),
            AndroidLocalNativeTool("aidl", "https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/aidl", 2_234_776, "2".repeat(64)),
            AndroidLocalNativeTool("zipalign", "https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/zipalign", 199_824, "3".repeat(64)),
            AndroidLocalNativeTool("split-select", "https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/split-select", 1_707_816, "4".repeat(64)),
        )
        fun entry(nativeTools: List<AndroidLocalNativeTool> = tools) = AndroidLocalComponentCatalogEntry(
            id = "build-tools-36-arm64",
            familyId = "build.android-tools",
            version = "36.0.0",
            kind = AndroidLocalComponentKind.BUILD_TOOLS_ARM64,
            downloadUrl = "https://dl.google.com/android/repository/build-tools_r36_linux.zip",
            fileName = "build-tools_r36_linux.zip",
            sizeBytes = 63_737_259,
            sha256 = "5".repeat(64),
            upstreamSha1 = "6".repeat(40),
            archiveRoot = "android-16",
            guestTarget = "build-tools/36.0.0",
            maxFiles = 4096,
            maxUnpackedBytes = 536_870_912,
            provenance = "Google metadata plus pinned linux-glibc-arm64 AOSP-derived native overlays",
            provenanceUrl = "https://dl.google.com/android/repository/repository2-3.xml",
            nativeTools = nativeTools,
        )
        AndroidLocalComponentCatalog.validate(
            AndroidLocalComponentCatalogDocument(revision = "test-build-tools-arm64", entries = listOf(entry()))
        )
        assertThrows(IllegalArgumentException::class.java) {
            AndroidLocalComponentCatalog.validate(
                AndroidLocalComponentCatalogDocument(revision = "test-build-tools-incomplete", entries = listOf(entry(tools.dropLast(1))))
            )
        }
    }

}
