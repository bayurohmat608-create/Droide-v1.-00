package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext














class AndroidToolchainCandidateManager(
    context: Context,
    private val manager: AndroidDevelopmentManager,
) {
    data class Candidate(
        val file: File,
        val packSha256: String,
        val sizeBytes: Long,
        val manifest: AndroidDevelopmentManager.ToolchainManifest,
        val sourceLockSha256: String,
        val provenancePurpose: String,
    ) {
        val displayId: String get() = manifest.candidateId ?: manifest.version
    }

    private val appContext = context.applicationContext
    private val licenseManager = AndroidSdkLicenseManager(appContext)
    private val cacheRoot = File(appContext.cacheDir, "android-toolchain-candidates").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun import(uri: Uri): Candidate = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val temp = File(cacheRoot, ".candidate-${System.nanoTime()}.part")
        require(temp.parentFile?.canonicalFile == cacheRoot.canonicalFile) { "Unsafe candidate cache path" }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            resolver.openInputStream(uri)?.buffered(128 * 1024).use { input ->
                requireNotNull(input) { "Could not open selected toolchain pack" }
                temp.outputStream().buffered(128 * 1024).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        require(total <= TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES) {
                            "Candidate toolchain exceeds ${TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES} bytes"
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(total > 0L) { "Selected toolchain pack is empty" }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val finalFile = File(cacheRoot, "$sha.zip")
            require(finalFile.parentFile?.canonicalFile == cacheRoot.canonicalFile) { "Unsafe candidate destination" }
            if (finalFile.exists()) {
                require(finalFile.isFile && finalFile.length() == total && sha256(finalFile) == sha) {
                    "Existing candidate cache entry does not match the selected bytes"
                }
                temp.delete()
            } else {
                check(temp.renameTo(finalFile)) { "Could not publish candidate snapshot" }
            }
            inspect(finalFile, sha, total)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    suspend fun install(candidate: Candidate): String = withContext(Dispatchers.IO) {
        require(candidate.file.parentFile?.canonicalFile == cacheRoot.canonicalFile) { "Candidate is outside the private cache" }
        require(candidate.file.isFile) { "Candidate toolchain snapshot no longer exists" }
        require(candidate.file.length() == candidate.sizeBytes) { "Candidate size changed after review" }
        require(sha256(candidate.file) == candidate.packSha256) { "Candidate bytes changed after review" }
        val expectedLicenseSha = requireNotNull(candidate.manifest.sdkLicenseSha256) { "Candidate SDK license hash is missing" }
        val currentLicense = licenseManager.fetchCurrent()
        require(currentLicense.sha256 == expectedLicenseSha) {
            "Current Android SDK license no longer matches this candidate; rebuild/review the source lock"
        }
        require(licenseManager.isAccepted(currentLicense)) { "Review and accept the exact Android SDK license before candidate installation" }
        manager.provision(candidate.file, candidate.packSha256)
    }

    suspend fun discard(candidate: Candidate) = withContext(Dispatchers.IO) {
        if (candidate.file.parentFile?.canonicalFile == cacheRoot.canonicalFile) candidate.file.delete()
    }

    private fun inspect(file: File, sha: String, size: Long): Candidate {
        ZipFile(file).use { zip ->
            val manifestEntry = zip.getEntry("toolchain.json") ?: error("Candidate has no toolchain.json")
            val manifestText = readBounded(zip, manifestEntry, MAX_MANIFEST_BYTES, "toolchain.json")
            val manifest = json.decodeFromString<AndroidDevelopmentManager.ToolchainManifest>(manifestText)
            require(manifest.schema >= 3) { "Developer certification requires a schema-3 toolchain pack" }
            val candidateId = requireNotNull(manifest.candidateId) { "Candidate id is missing" }
            require(candidateId.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Invalid candidate id" }
            val expectedLockSha = requireNotNull(manifest.sourceLockSha256) { "Source-lock hash is missing" }
            require(expectedLockSha.matches(Regex("[0-9a-f]{64}"))) { "Invalid source-lock hash" }
            require(!manifest.buildRecipeVersion.isNullOrBlank()) { "Build recipe version is missing" }
            require(manifest.sdkLicenseSha256?.matches(Regex("[0-9a-f]{64}")) == true) { "SDK license hash is missing/invalid" }

            val lockEntry = zip.getEntry(PROVENANCE_FILE) ?: error("Candidate has no $PROVENANCE_FILE")
            val lockBytes = readBoundedBytes(zip, lockEntry, MAX_PROVENANCE_BYTES, PROVENANCE_FILE)
            val actualLockSha = MessageDigest.getInstance("SHA-256").digest(lockBytes).joinToString("") { "%02x".format(it) }
            require(actualLockSha == expectedLockSha) { "Candidate source-lock hash does not match toolchain.json" }
            val root = json.parseToJsonElement(lockBytes.decodeToString()).jsonObject
            require(root["schema"]?.jsonPrimitive?.content == "1") { "Unsupported source-lock schema" }
            require(root["certified"]?.jsonPrimitive?.content == "false") {
                "A developer candidate source lock must not self-assert production certification"
            }
            val purpose = root["purpose"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: error("Source-lock purpose is missing")

            return Candidate(
                file = file,
                packSha256 = sha,
                sizeBytes = size,
                manifest = manifest,
                sourceLockSha256 = actualLockSha,
                provenancePurpose = purpose,
            )
        }
    }

    private fun readBounded(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        maxBytes: Int,
        label: String,
    ): String = readBoundedBytes(zip, entry, maxBytes, label).decodeToString()

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
        private const val PROVENANCE_FILE = "PROVENANCE.lock.json"
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val MAX_PROVENANCE_BYTES = 1024 * 1024
    }
}
