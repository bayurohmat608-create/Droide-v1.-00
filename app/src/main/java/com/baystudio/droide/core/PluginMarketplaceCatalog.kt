package com.baystudio.droide.core

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
enum class PluginMarketplaceTrust {
    REVIEWED_EXECUTABLE,
    METADATA_ONLY,
}

@Serializable
data class PluginMarketplaceEntry(
    val id: String,
    val version: String,
    val publisher: String,
    val packageFamilyId: String,
    val packageVersion: String,
    val packageArtifactSha256: String,
    val manifestSha256: String,
    val entryCommand: String,
    val permissions: Set<PluginPermission> = emptySet(),
    val commands: List<DroidePluginCommand> = emptyList(),
    val trust: PluginMarketplaceTrust = PluginMarketplaceTrust.METADATA_ONLY,
    val homepageUrl: String,
    val provenanceUrl: String,
) {
    fun validate() {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{1,119}"))) { "Invalid marketplace plugin id" }
        require(version.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid marketplace plugin version" }
        require(publisher.isNotBlank() && publisher.length <= 160) { "Invalid plugin publisher" }
        require(packageFamilyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid plugin package family" }
        require(packageVersion.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))) { "Invalid plugin package version" }
        require(packageArtifactSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid plugin package artifact SHA-256" }
        require(manifestSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid plugin manifest SHA-256" }
        require(entryCommand.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid plugin entry command" }
        require(commands.size <= 128 && commands.map { it.id }.distinct().size == commands.size) { "Invalid plugin command catalog" }
        commands.forEach { command ->
            require(command.id.matches(Regex("[a-z0-9][a-z0-9._-]{1,159}"))) { "Invalid plugin command id" }
            require(command.title.isNotBlank() && command.title.length <= 160) { "Invalid plugin command title" }
        }
        listOf(homepageUrl, provenanceUrl).forEach { raw ->
            val url = raw.toHttpUrlOrNull()
            require(url != null && url.isHttps && url.host.isNotBlank()) { "Plugin marketplace URLs must use HTTPS" }
            require(url.username.isEmpty() && url.password.isEmpty()) { "Plugin marketplace URLs must not contain credentials" }
        }
    }

    fun toManifest(): DroidePluginManifest = DroidePluginManifest(
        id = id,
        version = version,
        packageFamilyId = packageFamilyId,
        entryCommand = entryCommand,
        permissions = permissions,
        commands = commands,
    ).also(DroidePluginManifest::validate)
}

@Serializable
data class PluginMarketplaceCatalogDocument(
    val schema: Int = 1,
    val revision: String,
    val generatedAtEpochMs: Long,
    val entries: List<PluginMarketplaceEntry>,
) {
    fun validate() {
        require(schema == 1) { "Unsupported plugin marketplace schema" }
        require(revision.matches(Regex("[A-Za-z0-9._+-]{1,100}"))) { "Invalid plugin marketplace revision" }
        require(generatedAtEpochMs > 0) { "Invalid plugin marketplace timestamp" }
        require(entries.size <= 2_000) { "Plugin marketplace catalog is unexpectedly large" }
        entries.forEach(PluginMarketplaceEntry::validate)
        require(entries.map { it.id to it.version }.distinct().size == entries.size) { "Duplicate plugin marketplace entry" }
    }
}

// Hash-pinned catalog parser.


object PluginMarketplaceCatalog {
    private const val MAX_CATALOG_BYTES = 4 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = false }

    fun parseVerified(bytes: ByteArray, expectedSha256: String): PluginMarketplaceCatalogDocument {
        require(bytes.size in 1..MAX_CATALOG_BYTES) { "Plugin marketplace catalog size is invalid" }
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid expected catalog SHA-256" }
        val actual = sha256(bytes)
        require(actual == expectedSha256) { "Plugin marketplace catalog SHA-256 mismatch" }
        return json.decodeFromString<PluginMarketplaceCatalogDocument>(bytes.decodeToString())
            .also(PluginMarketplaceCatalogDocument::validate)
    }

    fun select(document: PluginMarketplaceCatalogDocument, pluginId: String, version: String? = null): PluginMarketplaceEntry? {
        document.validate()
        return document.entries.asSequence()
            .filter { it.id == pluginId && (version == null || it.version == version) }
            .sortedWith(compareByDescending<PluginMarketplaceEntry> { it.trust == PluginMarketplaceTrust.REVIEWED_EXECUTABLE }.thenByDescending { it.version })
            .firstOrNull()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

 
object PluginMarketplaceResolver {
    fun resolveInstalled(entry: PluginMarketplaceEntry, records: List<ManagedPackageRecord>): ManagedPackageRecord {
        entry.validate()
        val record = records.firstOrNull {
            it.familyId == entry.packageFamilyId && it.version == entry.packageVersion && it.active
        } ?: error("Marketplace plugin package is not installed and active")
        require(record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name) { "Marketplace plugin package must run in Device Workstation" }
        require(record.artifactSha256 == entry.packageArtifactSha256) { "Installed plugin artifact does not match marketplace catalog" }
        require(record.commands[entry.entryCommand] != null) { "Installed plugin package does not own the declared entry command" }
        return record
    }

    fun requireExecutableTrust(entry: PluginMarketplaceEntry) {
        require(entry.trust == PluginMarketplaceTrust.REVIEWED_EXECUTABLE) {
            "This marketplace entry is metadata-only; executable third-party plugins remain fail-closed until reviewed isolation/trust requirements are met"
        }
    }

    fun parseAndVerifyManifest(entry: PluginMarketplaceEntry, manifestBytes: ByteArray): DroidePluginManifest {
        entry.validate()
        require(manifestBytes.size in 1..256_000) { "Plugin manifest size is invalid" }
        require(PluginMarketplaceCatalog.sha256(manifestBytes) == entry.manifestSha256) { "Plugin manifest SHA-256 mismatch" }
        val manifest = Json { ignoreUnknownKeys = false }.decodeFromString<DroidePluginManifest>(manifestBytes.decodeToString())
        manifest.validate()
        require(manifest == entry.toManifest()) { "Plugin manifest does not match marketplace catalog" }
        return manifest
    }
}
