package com.baystudio.droide.core

import org.junit.Assert.assertThrows
import org.junit.Test

class ManagedPackageCatalogDistributionTest {
    private fun termuxEntry(
        sourcePath: String = "data/data/com.termux/files/usr/bin/rust-analyzer",
        executableName: String = "rust-analyzer",
        abi: String = "arm64-v8a",
    ) = ManagedPackageCatalogEntry(
        id = "rust-analyzer-termux-20260914-arm64",
        familyId = "lsp.rust-analyzer",
        version = "20260914",
        abi = abi,
        downloadUrl = "https://packages.example.invalid/rust-analyzer.deb",
        fileName = "rust-analyzer.deb",
        sizeBytes = 1024,
        sha256 = "a".repeat(64),
        provenance = "Pinned Android-native source distribution",
        provenanceUrl = "https://packages.example.invalid/provenance",
        distribution = ManagedPackageDistribution.TERMUX_DEB_SINGLE_EXECUTABLE,
        sourcePath = sourcePath,
        executableName = executableName,
    )

    @Test fun schema2AcceptsNarrowTermuxExecutableLane() {
        ManagedPackageCatalog.validate(
            ManagedPackageCatalogDocument(schema = 2, revision = "catalog-test", entries = listOf(termuxEntry()))
        )
    }

    @Test fun schema1RejectsSourceDistributionLane() {
        assertThrows(IllegalArgumentException::class.java) {
            ManagedPackageCatalog.validate(
                ManagedPackageCatalogDocument(schema = 1, revision = "legacy", entries = listOf(termuxEntry()))
            )
        }
    }

    @Test fun termuxLaneRejectsPathsOutsideCanonicalBin() {
        assertThrows(IllegalArgumentException::class.java) {
            termuxEntry(sourcePath = "data/data/com.termux/files/usr/lib/rust-analyzer").validate()
        }
    }

    @Test fun termuxLaneRejectsNonArm64CatalogRows() {
        assertThrows(IllegalArgumentException::class.java) {
            termuxEntry(abi = "x86_64").validate()
        }
    }

    @Test fun termuxLaneRequiresExactExecutableBasename() {
        assertThrows(IllegalArgumentException::class.java) {
            termuxEntry(executableName = "other").validate()
        }
    }
}
