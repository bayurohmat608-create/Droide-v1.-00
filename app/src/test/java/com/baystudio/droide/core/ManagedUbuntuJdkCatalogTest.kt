package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ManagedUbuntuJdkCatalogTest {
    @Test fun shippedJdkIsAcceptedByTheRealResolverAndLocalWholeTreePolicy() {
        val asset = listOf(File("src/main/assets/droide-package-catalog-v1.json"),
            File("app/src/main/assets/droide-package-catalog-v1.json")).first { it.isFile }
        val document = Json.decodeFromString<DeclarativePackageCatalogDocument>(asset.readText())
        val source = requireNotNull(PackageSourceCatalog.fromDocument(document).installable("toolchain.jdk"))
        source.validate()
        assertTrue(LocalUbuntuReviewedWholeTreePolicy.supports(source))
        assertEquals("17.0.20.1+1", source.vendorPinnedVersion)
        assertEquals(DeclarativeVendorVersionStrategy.PINNED, source.vendorVersionStrategy)
        assertTrue(source.allowLatest)
        assertNull(source.vendorVersionUrl)
        assertTrue(requireNotNull(source.vendorArtifactUrlTemplate).contains("{tag}"))
        assertEquals("457b57af8f9c93ec39080bb8c764f559dc8c89a6da1a39d718a400b7890d3e41", source.vendorPinnedSha256)
        val manifest = document.packages.single { it.familyId == "toolchain.jdk" }.vendorOfficial!!
        assertEquals(DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE, manifest.treeLinkPolicy)
        assertEquals(DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE, manifest.treeModePolicy)
        assertEquals(14, manifest.treeAuxCommands.size)
        assertTrue(setOf("javac", "jar", "jlink", "jmod", "jstack").all(manifest.treeAuxCommands::containsKey))
    }
}
