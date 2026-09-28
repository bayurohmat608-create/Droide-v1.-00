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
}
