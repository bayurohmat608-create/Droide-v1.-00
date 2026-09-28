package com.baystudio.droide.core

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class ManagedPackageSourcePin(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

@Serializable
data class ManagedPackageProvenanceLock(
    val schema: Int,
    val purpose: String,
    val familyId: String,
    val version: String,
    val abi: String,
    val manifestSha256: String,
    val provenanceUrl: String,
    val summary: String,
    val sources: List<ManagedPackageSourcePin>,
)








object ManagedPackageProvenance {
    const val FILE_NAME = "PROVENANCE.lock.json"
    const val PURPOSE = "droide-managed-package"
    const val MAX_BYTES = 512 * 1024
    private val json = Json { ignoreUnknownKeys = false }

    fun parseAndValidate(
        bytes: ByteArray,
        manifestBytes: ByteArray,
        manifest: ManagedPackageManifest,
    ): ManagedPackageProvenanceLock {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Managed package provenance lock is missing or too large" }
        val lock = json.decodeFromString<ManagedPackageProvenanceLock>(bytes.decodeToString())
        require(lock.schema == 1) { "Unsupported managed package provenance schema" }
        require(lock.purpose == PURPOSE) { "Invalid managed package provenance purpose" }
        require(lock.familyId == manifest.familyId && lock.version == manifest.version && lock.abi == manifest.abi) {
            "Managed package provenance identity does not match package.json"
        }
        require(lock.manifestSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid managed package manifest digest" }
        require(lock.manifestSha256 == sha256(manifestBytes)) { "Managed package provenance manifest digest mismatch" }
        require(lock.summary.isNotBlank() && lock.summary.length <= 300) { "Invalid managed package provenance summary" }
        validateHttps(lock.provenanceUrl, "provenance URL")
        require(lock.sources.size in 1..32) { "Managed package provenance must pin 1..32 source artifacts" }
        require(lock.sources.map { Triple(it.url, it.sha256, it.sizeBytes) }.distinct().size == lock.sources.size) {
            "Duplicate managed package provenance source pin"
        }
        lock.sources.forEach { source ->
            validateHttps(source.url, "source URL")
            require(source.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid managed package source SHA-256" }
            require(source.sizeBytes in 1..TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES) { "Invalid managed package source size" }
        }
        return lock
    }

    private fun validateHttps(value: String, label: String) {
        val parsed = value.toHttpUrlOrNull()
        require(parsed != null && parsed.isHttps && parsed.host.isNotBlank()) { "Managed package $label must use HTTPS" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Managed package $label must not embed credentials" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
