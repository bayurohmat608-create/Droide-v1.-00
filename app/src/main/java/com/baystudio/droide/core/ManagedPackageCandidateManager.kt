package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

// Importing never changes the production catalog.


class ManagedPackageCandidateManager(
    context: Context,
    private val installer: ManagedPackageInstaller,
) {
    data class Candidate(
        val file: File,
        val artifactSha256: String,
        val sizeBytes: Long,
        val manifest: ManagedPackageManifest,
    ) {
        val displayId: String get() = "${manifest.familyId}@${manifest.version}/${manifest.abi}"
    }

    private val appContext = context.applicationContext
    private val cacheRoot = File(appContext.cacheDir, "managed-package-candidates").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun import(uri: Uri): Candidate = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val temp = File(cacheRoot, ".candidate-${System.nanoTime()}.part")
        require(temp.parentFile?.canonicalFile == cacheRoot.canonicalFile) { "Unsafe managed-package candidate cache path" }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            resolver.openInputStream(uri)?.buffered(128 * 1024).use { input ->
                requireNotNull(input) { "Could not open selected managed-package candidate" }
                temp.outputStream().buffered(128 * 1024).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        require(total <= TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES) {
                            "Managed-package candidate exceeds ${TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES} bytes"
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(total > 0L) { "Selected managed-package candidate is empty" }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val finalFile = File(cacheRoot, "$sha.zip")
            require(finalFile.parentFile?.canonicalFile == cacheRoot.canonicalFile) { "Unsafe managed-package candidate destination" }
            if (finalFile.exists()) {
                require(finalFile.isFile && finalFile.length() == total && sha256(finalFile) == sha) {
                    "Existing managed-package candidate cache entry does not match the selected bytes"
                }
                temp.delete()
            } else {
                check(temp.renameTo(finalFile)) { "Could not publish managed-package candidate snapshot" }
            }
            inspect(finalFile, sha, total)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    suspend fun install(candidate: Candidate): ManagedPackageRecord = withContext(Dispatchers.IO) {
        require(candidate.file.parentFile?.canonicalFile == cacheRoot.canonicalFile) {
            "Managed-package candidate is outside the private cache"
        }
        require(candidate.file.isFile) { "Managed-package candidate snapshot no longer exists" }
        require(candidate.file.length() == candidate.sizeBytes) { "Managed-package candidate size changed after review" }
        require(sha256(candidate.file) == candidate.artifactSha256) { "Managed-package candidate bytes changed after review" }
        val inspected = inspect(candidate.file, candidate.artifactSha256, candidate.sizeBytes)
        require(inspected.manifest == candidate.manifest) { "Managed-package candidate manifest changed after review" }
        installer.installCandidate(candidate.file, candidate.artifactSha256, candidate.manifest)
    }

    suspend fun discard(candidate: Candidate) = withContext(Dispatchers.IO) {
        if (candidate.file.parentFile?.canonicalFile == cacheRoot.canonicalFile) candidate.file.delete()
    }

    private fun inspect(file: File, sha: String, size: Long): Candidate {
        require(size in 1..TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES) { "Invalid managed-package candidate size" }
        require(sha.matches(Regex("[0-9a-f]{64}"))) { "Invalid managed-package candidate SHA-256" }
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 4..MAX_ARCHIVE_ENTRIES) { "Invalid managed-package candidate entry count" }
            require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate managed-package candidate archive path" }
            val manifestEntry = entries.singleOrNull { it.name == "package.json" }
                ?: error("Managed-package candidate must contain exactly one package.json")
            val manifestBytes = readBoundedBytes(zip, manifestEntry, MAX_MANIFEST_BYTES, "package.json")
            val manifest = json.decodeFromString<ManagedPackageManifest>(manifestBytes.decodeToString())
            installer.validateCandidateManifest(manifest)
            require(manifest.schema == 2) { "Managed-package certification candidates must use schema 2" }
            val provenanceEntry = entries.singleOrNull { it.name == ManagedPackageProvenance.FILE_NAME }
                ?: error("Managed-package candidate must contain exactly one ${ManagedPackageProvenance.FILE_NAME}")
            val provenanceBytes = readBoundedBytes(
                zip,
                provenanceEntry,
                ManagedPackageProvenance.MAX_BYTES,
                ManagedPackageProvenance.FILE_NAME,
            )
            ManagedPackageProvenance.parseAndValidate(provenanceBytes, manifestBytes, manifest)
            require(entries.any { !it.isDirectory && it.name.startsWith("payload/") }) {
                "Managed-package schema-2 candidate payload is empty"
            }
            require(entries.all { entry ->
                entry.isDirectory ||
                    entry.name == "package.json" ||
                    entry.name == manifest.checksumFile ||
                    entry.name == ManagedPackageProvenance.FILE_NAME ||
                    entry.name.startsWith("payload/")
            }) { "Unexpected managed-package candidate archive path" }
            return Candidate(file, sha, size, manifest)
        }
    }

    private fun readBoundedBytes(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        maxBytes: Int,
        label: String,
    ): ByteArray {
        require(entry.size <= maxBytes.toLong() || entry.size == -1L) { "$label is too large" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        zip.getInputStream(entry).buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= maxBytes) { "$label is too large" }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_ARCHIVE_ENTRIES = 4096
        private const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}
