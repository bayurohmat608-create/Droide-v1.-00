package com.baystudio.droide.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginMarketplaceCatalogTest {
    private val json = Json { encodeDefaults = true }

    private fun manifest() = DroidePluginManifest(
        id = "com.example.formatter",
        version = "1.2.0",
        packageFamilyId = "plugin.example.formatter",
        entryCommand = "droide-plugin-example",
        permissions = setOf(PluginPermission.WORKSPACE_READ),
        commands = listOf(DroidePluginCommand("example.format", "Format current file")),
    )

    private fun entry(trust: PluginMarketplaceTrust = PluginMarketplaceTrust.REVIEWED_EXECUTABLE): Pair<PluginMarketplaceEntry, ByteArray> {
        val bytes = json.encodeToString(manifest()).toByteArray()
        return PluginMarketplaceEntry(
            id = "com.example.formatter",
            version = "1.2.0",
            publisher = "Example Publisher",
            packageFamilyId = "plugin.example.formatter",
            packageVersion = "1.2.0",
            packageArtifactSha256 = "a".repeat(64),
            manifestSha256 = PluginMarketplaceCatalog.sha256(bytes),
            entryCommand = "droide-plugin-example",
            permissions = setOf(PluginPermission.WORKSPACE_READ),
            commands = listOf(DroidePluginCommand("example.format", "Format current file")),
            trust = trust,
            homepageUrl = "https://example.com/plugin",
            provenanceUrl = "https://example.com/plugin/provenance.json",
        ) to bytes
    }

    @Test fun verifiesPinnedManifestAndInstalledArtifact() {
        val (entry, bytes) = entry()
        val decoded = PluginMarketplaceResolver.parseAndVerifyManifest(entry, bytes)
        assertEquals(manifest(), decoded)
        val record = ManagedPackageRecord(
            familyId = entry.packageFamilyId,
            version = entry.packageVersion,
            scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
            installRoot = "/data/local/tmp/droide/packages/plugin.example.formatter/1.2.0",
            installedAtEpochMs = 1,
            commands = mapOf("droide-plugin-example" to "/data/local/tmp/droide/packages/plugin.example.formatter/1.2.0/payload/bin/droide-plugin-example"),
            artifactSha256 = entry.packageArtifactSha256,
            abi = "arm64-v8a",
        )
        assertEquals(record, PluginMarketplaceResolver.resolveInstalled(entry, listOf(record)))
        PluginMarketplaceResolver.requireExecutableTrust(entry)
    }

    @Test fun metadataOnlyEntriesCannotExecute() {
        val (entry, _) = entry(PluginMarketplaceTrust.METADATA_ONLY)
        assertThrows(IllegalArgumentException::class.java) {
            PluginMarketplaceResolver.requireExecutableTrust(entry)
        }
    }

    @Test fun manifestOrArtifactMismatchFailsClosed() {
        val (entry, bytes) = entry()
        assertThrows(IllegalArgumentException::class.java) {
            PluginMarketplaceResolver.parseAndVerifyManifest(entry, bytes + 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginMarketplaceResolver.resolveInstalled(
                entry,
                listOf(
                    ManagedPackageRecord(
                        familyId = entry.packageFamilyId,
                        version = entry.packageVersion,
                        scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                        installRoot = "/data/local/tmp/droide/packages/plugin.example.formatter/1.2.0",
                        installedAtEpochMs = 1,
                        commands = mapOf("droide-plugin-example" to "/data/local/tmp/droide/packages/plugin.example.formatter/1.2.0/payload/bin/droide-plugin-example"),
                        artifactSha256 = "b".repeat(64),
                        abi = "arm64-v8a",
                    )
                ),
            )
        }
    }
}
