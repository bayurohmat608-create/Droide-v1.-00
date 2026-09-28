package com.baystudio.droide.core

import android.content.Context
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class ManagedPackageCatalogDocument(
    val schema: Int = 1,
    val revision: String,
    val entries: List<ManagedPackageCatalogEntry>,
)

@Serializable
enum class ManagedPackageDistribution {
    DROIDE_ZIP,
    TERMUX_DEB_SINGLE_EXECUTABLE,
}

@Serializable
data class ManagedPackageCatalogEntry(
    val id: String,
    val familyId: String,
    val version: String,
    val abi: String,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val dependencies: List<String> = emptyList(),
    val provenance: String,
    val provenanceUrl: String,
    val distribution: ManagedPackageDistribution = ManagedPackageDistribution.DROIDE_ZIP,
    val sourcePath: String? = null,
    val executableName: String? = null,
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid managed package id" }
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid managed package family" }
        require(version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))) { "Invalid managed package version" }
        require(abi in AndroidToolchainCatalogEntry.SUPPORTED_ABIS) { "Unsupported managed package ABI" }
        require(fileName.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "Invalid managed package filename" }
        require(sizeBytes in 1..TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES) { "Invalid managed package size" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid managed package SHA-256" }
        require(dependencies.size <= 32 && dependencies.all { it.matches(Regex("[A-Za-z0-9._-]{1,120}@[A-Za-z0-9._+ -]{1,80}")) }) { "Invalid managed package dependency" }
        require(provenance.isNotBlank() && provenance.length <= 300) { "Invalid managed package provenance" }
        when (distribution) {
            ManagedPackageDistribution.DROIDE_ZIP -> {
                require(sourcePath == null && executableName == null) { "Droide ZIP packages must not declare upstream extraction fields" }
            }
            ManagedPackageDistribution.TERMUX_DEB_SINGLE_EXECUTABLE -> {
                val path = requireNotNull(sourcePath) { "Termux package sourcePath is required" }
                require(path.length in 1..300 && !path.startsWith('/') && path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                    "Invalid Termux package sourcePath"
                }
                require(path.startsWith("data/data/com.termux/files/usr/bin/")) {
                    "Termux executable extraction is restricted to the canonical usr/bin prefix"
                }
                val name = requireNotNull(executableName) { "Termux package executableName is required" }
                require(name.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid managed executable name" }
                require(path.substringAfterLast('/') == name) { "Termux sourcePath/executableName mismatch" }
                require(abi == "arm64-v8a") { "Termux native bootstrap currently supports arm64-v8a only" }
            }
        }
        listOf(downloadUrl, provenanceUrl).forEach { value ->
            val parsed = value.toHttpUrlOrNull()
            require(parsed != null && parsed.isHttps && parsed.host.isNotBlank()) { "Managed package URLs must use HTTPS" }
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Managed package URLs must not embed credentials" }
        }
    }

    fun toArtifactSpec() = TrustedArtifactSpec(
        id = id,
        url = downloadUrl,
        sha256 = sha256,
        fileName = fileName,
        maxBytes = sizeBytes,
        expectedBytes = sizeBytes,
    )
}

object ManagedPackageCatalog {
    private const val ASSET_NAME = "managed-package-catalog.json"
    const val PINNED_ASSET_SHA256 = "8c58a6d6379bfdcc30465fa70ada899f3959bd4e0aa9627ae7c63160a4016a27"
    private val json = Json { ignoreUnknownKeys = false }

    fun load(context: Context): ManagedPackageCatalogDocument {
        val bytes = context.applicationContext.assets.open(ASSET_NAME).use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == PINNED_ASSET_SHA256) { "Pinned managed package catalog integrity check failed" }
        return json.decodeFromString<ManagedPackageCatalogDocument>(bytes.decodeToString()).also(::validate)
    }

    fun validate(document: ManagedPackageCatalogDocument) {
        require(document.schema in 1..2) { "Unsupported managed package catalog schema" }
        if (document.schema == 1) {
            require(document.entries.all { it.distribution == ManagedPackageDistribution.DROIDE_ZIP }) {
                "Managed package catalog schema 1 supports Droide ZIP entries only"
            }
        }
        require(document.revision.matches(Regex("[A-Za-z0-9._+-]{1,100}"))) { "Invalid managed package catalog revision" }
        require(document.entries.size <= 256) { "Managed package catalog is unexpectedly large" }
        document.entries.forEach(ManagedPackageCatalogEntry::validate)
        require(document.entries.map { it.id }.distinct().size == document.entries.size) { "Duplicate managed package id" }
        require(document.entries.map { Triple(it.familyId, it.version, it.abi) }.distinct().size == document.entries.size) { "Duplicate managed package family/version/ABI" }
    }

    fun select(document: ManagedPackageCatalogDocument, familyId: String, version: String, supportedAbis: List<String>): ManagedPackageCatalogEntry? =
        document.entries.asSequence()
            .filter { it.familyId == familyId && it.version == version && it.abi in supportedAbis }
            .sortedBy { supportedAbis.indexOf(it.abi) }
            .firstOrNull()
}

@Serializable
data class ManagedPackageHealthCheck(
    val executable: String,
    val args: List<String> = listOf("--version"),
)

@Serializable
data class ManagedPackageManifest(
    val schema: Int = 2,
    val familyId: String,
    val version: String,
    val abi: String,
    val dependencies: List<String> = emptyList(),
    val pathEntries: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val executablePaths: List<String> = emptyList(),
    val healthChecks: List<ManagedPackageHealthCheck> = emptyList(),
    val checksumFile: String = "SHA256SUMS",
)
