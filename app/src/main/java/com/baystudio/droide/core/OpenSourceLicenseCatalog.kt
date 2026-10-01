package com.baystudio.droide.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class OpenSourceLicenseEntry(
    val id: String,
    val name: String,
    val version: String,
    val licenseName: String,
    val spdx: String,
    val copyright: String,
    val licenseTextAsset: String,
    val source: String,
    val notice: String,
    val dependencyKeys: List<String> = emptyList(),
)

@Serializable
data class OpenSourceLicenseManifest(
    val schemaVersion: Int,
    val closureStatus: String,
    val closureNote: String,
    val entries: List<OpenSourceLicenseEntry>,
)

// Keep this fail-closed: malformed or missing legal data must never be silently hidden.


object OpenSourceLicenseCatalog {
    private val json = Json { ignoreUnknownKeys = false }

    fun load(context: Context): OpenSourceLicenseManifest {
        val raw = context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
        val manifest = json.decodeFromString<OpenSourceLicenseManifest>(raw)
        require(manifest.schemaVersion == 1) { "Unsupported open-source license manifest schema" }
        require(manifest.entries.isNotEmpty()) { "Open-source license manifest is empty" }
        require(manifest.entries.map { it.id }.distinct().size == manifest.entries.size) { "Duplicate open-source license id" }
        manifest.entries.forEach { entry ->
            require(entry.id.isNotBlank() && entry.name.isNotBlank() && entry.licenseName.isNotBlank()) { "Incomplete open-source license entry" }
            require(entry.licenseTextAsset.startsWith("legal/licenses/")) { "License text must be packaged under legal/licenses" }
        }
        return manifest
    }

    fun readAsset(context: Context, assetPath: String): String =
        context.assets.open(assetPath).bufferedReader().use { it.readText() }

    const val MANIFEST_ASSET = "legal/open_source_licenses.json"
    const val THIRD_PARTY_NOTICES_ASSET = "legal/THIRD_PARTY_NOTICES.md"
    const val BRAND_ASSET_TERMS_ASSET = "legal/BRAND_ASSET_TERMS.md"
    const val DISTRIBUTION_COMPLIANCE_ASSET = "legal/DISTRIBUTION_COMPLIANCE.md"
    const val DROIDE_LICENSE_ASSET = "legal/DROIDE_LICENSE.md"
}
