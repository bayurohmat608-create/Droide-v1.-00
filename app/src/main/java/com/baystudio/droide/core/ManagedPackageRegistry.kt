package com.baystudio.droide.core

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ManagedPackageRecord(
    val familyId: String,
    val version: String,
    val scope: String,
    val installRoot: String,
    val installedAtEpochMs: Long,
    val active: Boolean = true,
    val pathEntries: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val commands: Map<String, String> = emptyMap(),
    val dependencies: List<String> = emptyList(),
    val healthChecks: List<ManagedPackageHealthCheck> = emptyList(),
    val artifactSha256: String? = null,
    val checksumFile: String = "SHA256SUMS",
    val abi: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)


internal object ManagedPackageMutationGate {
    val mutex = Mutex()
}

// Persisted state remains the source of truth.


class ManagedPackageRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = false }

    @Synchronized
    fun list(): List<ManagedPackageRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ManagedPackageRecord>>(raw) }
            .getOrElse { failure -> throw IllegalStateException("Managed package registry is corrupt", failure) }
    }

    @Synchronized
    fun find(familyId: String, version: String): ManagedPackageRecord? =
        list().firstOrNull { it.familyId == familyId && it.version == version }

    @Synchronized
    fun put(record: ManagedPackageRecord) {
        validateRecord(record)
        val existing = list().filterNot { it.familyId == record.familyId && it.version == record.version }
        val normalized = if (record.active) existing.map { if (it.familyId == record.familyId) it.copy(active = false) else it } else existing
        replaceAll(normalized + record)
    }

    @Synchronized
    fun replaceAll(records: List<ManagedPackageRecord>) {
        require(records.size <= 512) { "Too many managed package records" }
        records.forEach(::validateRecord)
        require(records.map { it.familyId to it.version }.distinct().size == records.size) { "Duplicate managed package record" }
        check(prefs.edit().putString(KEY_RECORDS, json.encodeToString(records)).commit()) { "Failed to durably persist managed package registry" }
    }

    private fun validateRecord(record: ManagedPackageRecord) {
        require(record.familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid package family" }
        require(record.version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))) { "Invalid package version" }
        require(record.scope in ExecutionScope.entries.map { it.name }) { "Invalid package scope" }
        require(record.installRoot.length in 1..400) { "Invalid package install root" }
        require(record.pathEntries.size <= 64) { "Too many package PATH entries" }
        require(record.environment.size <= 32) { "Too many package environment variables" }
        require(record.environment.keys.all { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) }) { "Invalid package environment key" }
        require(record.commands.size <= 128) { "Too many package commands" }
        require(record.commands.keys.all { it.matches(Regex("[A-Za-z0-9._+-]{1,80}")) }) { "Invalid package command name" }
        require(record.dependencies.size <= 32) { "Too many package dependencies" }
        require(record.dependencies.all { it.matches(Regex("[A-Za-z0-9._-]{1,120}@[A-Za-z0-9._+ -]{1,80}")) }) { "Invalid package dependency" }
        require(record.healthChecks.size <= 32) { "Too many package health checks" }
        require(record.artifactSha256 == null || record.artifactSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid package artifact SHA-256" }
        require(record.checksumFile.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Invalid package checksum filename" }
        require(record.abi == null || record.abi in AndroidToolchainCatalogEntry.SUPPORTED_ABIS) { "Invalid package ABI" }
        require(record.metadata.size <= 16) { "Too many package metadata entries" }
        require(record.metadata.keys.all { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }) { "Invalid package metadata key" }
        require(record.metadata.values.all { it.length <= 4096 && '\u0000' !in it && '\n' !in it && '\r' !in it }) { "Invalid package metadata value" }
        require(record.healthChecks.all { check ->
            val path = check.executable.replace('\\', '/')
            path.length in 1..300 && !path.startsWith('/') &&
                path.split('/').none { it.isBlank() || it == "." || it == ".." } &&
                check.args.size <= 16 && check.args.all { it.length <= 200 && '\u0000' !in it && '\n' !in it && '\r' !in it }
        }) { "Invalid package health check" }
    }

    @Synchronized
    fun remove(familyId: String, version: String) {
        val next = list().filterNot { it.familyId == familyId && it.version == version }
        check(prefs.edit().putString(KEY_RECORDS, json.encodeToString(next)).commit()) { "Failed to durably persist managed package registry" }
    }

    companion object {
        private const val KEY_RECORDS = "records_json"
    }
}
