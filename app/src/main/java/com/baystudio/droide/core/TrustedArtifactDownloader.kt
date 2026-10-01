package com.baystudio.droide.core

import android.content.Context
import android.os.storage.StorageManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.coroutineContext

// Specs are app-owned data, never agent/user supplied URLs.
data class TrustedArtifactDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val fromCache: Boolean = false,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let { (bytesDownloaded.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}

interface TrustedArtifactDescriptor {
    val id: String
    val url: String
    val fileName: String
    val maxBytes: Long
    val expectedBytes: Long?
    val digestAlgorithm: String
    val digestHex: String

    
    fun validateStructure() {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid artifact id" }
        require(fileName.matches(Regex("[A-Za-z0-9._+-]{1,180}"))) { "Invalid artifact filename" }
        require(maxBytes in 1L..TrustedArtifactSpec.MAX_TRUSTED_ARTIFACT_BYTES) { "Invalid artifact size limit" }
        expectedBytes?.let { require(it in 1L..maxBytes) { "Invalid expected artifact size" } }
        val digestLength = when (digestAlgorithm) {
            "SHA-256" -> 64
            "SHA-512" -> 128
            else -> error("Unsupported trusted-artifact digest algorithm")
        }
        require(digestHex.length == digestLength && digestHex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            "Invalid trusted-artifact digest"
        }
        val uri = runCatching { URI(url.trim()) }.getOrElse { error("Invalid trusted-artifact URL") }
        require(!uri.isOpaque && uri.scheme.equals("https", ignoreCase = true)) { "Trusted artifact URL must use HTTPS" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Invalid trusted-artifact URL" }
    }

    
    fun validateDescriptor() {
        validateStructure()
        NetworkSecurity.validatePublicHttpsTarget(url)
    }
}

data class TrustedArtifactSpec(
    override val id: String,
    override val url: String,
    val sha256: String,
    override val fileName: String,
    override val maxBytes: Long,
    override val expectedBytes: Long? = null,
) : TrustedArtifactDescriptor {
    override val digestAlgorithm: String get() = "SHA-256"
    override val digestHex: String get() = sha256

    fun validate() {
        validateDescriptor()
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid artifact SHA-256" }
    }

    companion object {
        const val MAX_TRUSTED_ARTIFACT_BYTES = 1_500_000_000L
    }
}


data class TrustedSha512ArtifactSpec(
    override val id: String,
    override val url: String,
    val sha512: String,
    override val fileName: String,
    override val maxBytes: Long,
    override val expectedBytes: Long? = null,
) : TrustedArtifactDescriptor {
    override val digestAlgorithm: String get() = "SHA-512"
    override val digestHex: String get() = sha512

    fun validate() {
        validateDescriptor()
        require(sha512.matches(Regex("[0-9a-fA-F]{128}"))) { "Invalid artifact SHA-512" }
    }
}

// Keep untrusted input and output bounded.

class TrustedArtifactDownloader(context: Context) {
    private val appContext = context.applicationContext
    private val cacheRoot = File(appContext.cacheDir, "trusted-artifacts").apply { mkdirs() }
    private val storageManager = appContext.getSystemService(StorageManager::class.java)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun download(
        spec: TrustedArtifactSpec,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
    ): File = downloadDescriptor(spec, onProgress)

    suspend fun download(
        spec: TrustedSha512ArtifactSpec,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
    ): File = downloadDescriptor(spec, onProgress)

    private suspend fun downloadDescriptor(
        spec: TrustedArtifactDescriptor,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        downloadMutex.withLock { downloadUnlocked(spec, onProgress) }
    }

    private suspend fun downloadUnlocked(
        spec: TrustedArtifactDescriptor,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit,
    ): File {
        validate(spec)
        cleanupStalePartials()

        val cacheName = cacheName(spec)
        pruneFinalCache(protectedNames = setOf(cacheName))
        val finalFile = File(cacheRoot, cacheName)
        val partial = File(cacheRoot, cacheName + PART_SUFFIX)
        val metadataFile = File(cacheRoot, cacheName + PART_METADATA_SUFFIX)
        require(finalFile.parentFile?.canonicalFile == cacheRoot.canonicalFile) { "Unsafe artifact destination" }

        if (isTrustedFinal(finalFile, spec)) {
            // The file is disposable cache, never install state.
            finalFile.setLastModified(System.currentTimeMillis())
            onProgress(TrustedArtifactDownloadProgress(finalFile.length(), finalFile.length(), fromCache = true))
            return finalFile
        }
        if (finalFile.exists()) finalFile.delete()

        var resume = loadResumeMetadata(spec, partial, metadataFile)
        requireLocalHeadroom(spec, partial.length())
        if (resume != null && isPotentiallyComplete(partial, spec, resume)) {
            if (publishIfTrusted(partial, metadataFile, finalFile, spec, onProgress)) return finalFile
            clearPartial(partial, metadataFile)
            resume = null
        }

        var fullRestartUsed = false
        while (true) {
            coroutineContext.ensureActive()
            val resumeOffset = resume?.let { partial.length().takeIf { length -> length > 0L } } ?: 0L
            val resumedAttempt = resumeOffset > 0L
            try {
                val result = fetchOneRepresentation(spec, partial, metadataFile, resume, onProgress)
                if (result.restartFromZero) {
                    check(!fullRestartUsed) { "Artifact server rejected resume more than once" }
                    clearPartial(partial, metadataFile)
                    resume = null
                    fullRestartUsed = true
                    continue
                }

                if (publishIfTrusted(partial, metadataFile, finalFile, spec, onProgress)) return finalFile
                clearPartial(partial, metadataFile)
                if (resumedAttempt && !fullRestartUsed) {

                    resume = null
                    fullRestartUsed = true
                    continue
                }
                error("Artifact ${spec.digestAlgorithm} mismatch")
            } catch (t: Throwable) {
                if (t is CancellationException || t is IOException) {
                    retainOnlyValidPartial(spec, partial, metadataFile)
                } else {
                    clearPartial(partial, metadataFile)
                }
                throw t
            }
        }
    }

    private suspend fun fetchOneRepresentation(
        spec: TrustedArtifactDescriptor,
        partial: File,
        metadataFile: File,
        prior: PartialMetadata?,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit,
    ): FetchResult {
        val resumeOffset = prior?.let { partial.length().takeIf { length -> length > 0L } } ?: 0L
        var target = NetworkSecurity.validatePublicHttpsTarget(spec.url)

        repeat(MAX_REDIRECTS + 1) { hop ->
            coroutineContext.ensureActive()
            val builder = Request.Builder()
                .url(target.url)
                .header("User-Agent", "Droide/1.00")
                .header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "identity")
            if (resumeOffset > 0L) {
                builder.header("Range", "bytes=$resumeOffset-")
                prior?.strongEtag?.let { builder.header("If-Range", it) }
            }

            val hopClient = client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build()
            hopClient.newCall(builder.build()).awaitResponse().use { response ->
                if (response.code in 300..399) {
                    require(hop < MAX_REDIRECTS) { "Too many artifact redirects" }
                    val location = response.header("Location") ?: error("Artifact redirect has no Location header")
                    val base = target.url.toHttpUrlOrNull() ?: error("Invalid redirect base")
                    val next = base.resolve(location)?.toString() ?: error("Invalid artifact redirect")
                    target = NetworkSecurity.validatePublicHttpsTarget(next)
                    return@use
                }

                if (response.code == 416 && resumeOffset > 0L) {
                    val completeLength = parseUnsatisfiedContentRange(response.header("Content-Range"))
                    if (completeLength != null && completeLength == resumeOffset &&
                        (spec.expectedBytes == null || completeLength == spec.expectedBytes)
                    ) {
                        return FetchResult(restartFromZero = false)
                    }
                    return FetchResult(restartFromZero = true)
                }

                if (response.code == 408 || response.code == 429 || response.code in 500..599) {
                    throw IOException("Artifact download temporarily failed: HTTP ${response.code}")
                }
                check(response.code == 200 || response.code == 206) { "Artifact download failed: HTTP ${response.code}" }

                if (resumeOffset > 0L && response.code == 200) {
                    // Never append a full response.
                    return streamResponse(
                        response = response,
                        spec = spec,
                        partial = partial,
                        metadataFile = metadataFile,
                        append = false,
                        initialBytes = 0L,
                        expectedTotal = expectedTotalForFull(response, spec),
                        prior = null,
                        onProgress = onProgress,
                    )
                }

                if (response.code == 206) {
                    check(resumeOffset > 0L) { "Unexpected partial response without a Range request" }
                    val range = parseSatisfiedContentRange(response.header("Content-Range"))
                        ?: error("Artifact 206 response has invalid Content-Range")
                    check(range.start == resumeOffset) { "Artifact resume offset mismatch" }
                    check(range.total in 1L..spec.maxBytes) { "Artifact Content-Range exceeds size limit" }
                    spec.expectedBytes?.let { expected ->
                        check(range.total == expected) { "Artifact Content-Range does not match pinned size" }
                    }
                    val declared = response.body?.contentLength() ?: -1L
                    val expectedBodyBytes = Math.addExact(Math.subtractExact(range.end, range.start), 1L)
                    if (declared >= 0L) {
                        check(declared == expectedBodyBytes) { "Artifact partial Content-Length mismatch" }
                    }
                    prior?.strongEtag?.let { pinnedTag ->
                        response.header("ETag")?.let { responseTag ->
                            check(responseTag == pinnedTag) { "Artifact resume validator changed" }
                        }
                    }
                    return streamResponse(
                        response = response,
                        spec = spec,
                        partial = partial,
                        metadataFile = metadataFile,
                        append = true,
                        initialBytes = resumeOffset,
                        expectedTotal = range.total,
                        prior = prior,
                        onProgress = onProgress,
                    )
                }

                return streamResponse(
                    response = response,
                    spec = spec,
                    partial = partial,
                    metadataFile = metadataFile,
                    append = false,
                    initialBytes = 0L,
                    expectedTotal = expectedTotalForFull(response, spec),
                    prior = null,
                    onProgress = onProgress,
                )
            }
        }
        error("Artifact redirect limit exceeded")
    }

    private suspend fun streamResponse(
        response: Response,
        spec: TrustedArtifactDescriptor,
        partial: File,
        metadataFile: File,
        append: Boolean,
        initialBytes: Long,
        expectedTotal: Long?,
        prior: PartialMetadata?,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit,
    ): FetchResult {
        val body = response.body ?: error("Artifact response body is empty")
        val declared = body.contentLength()
        if (!append) {
            require(declared <= spec.maxBytes || declared < 0L) { "Artifact exceeds declared size limit" }
            if (declared >= 0L && spec.expectedBytes != null) {
                require(declared == spec.expectedBytes) { "Artifact Content-Length does not match pinned size" }
            }
        }

        val totalHint = expectedTotal ?: spec.expectedBytes
        totalHint?.let { require(it in 1L..spec.maxBytes) { "Invalid artifact total size" } }
        val strongEtag = strongEtag(response.header("ETag")) ?: prior?.strongEtag
        writeResumeMetadata(
            metadataFile,
            PartialMetadata(
                digestAlgorithm = spec.digestAlgorithm,
                digestHex = spec.digestHex.lowercase(),
                url = spec.url,
                expectedBytes = spec.expectedBytes,
                maxBytes = spec.maxBytes,
                strongEtag = strongEtag,
                totalBytes = totalHint,
            ),
        )

        var total = initialBytes
        var lastReported = initialBytes
        onProgress(TrustedArtifactDownloadProgress(total, totalHint))
        FileOutputStream(partial, append).buffered(128 * 1024).use { out ->
            body.byteStream().buffered(128 * 1024).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total = Math.addExact(total, read.toLong())
                    require(total <= spec.maxBytes) { "Artifact exceeds ${spec.maxBytes} bytes" }
                    totalHint?.let { require(total <= it) { "Artifact exceeds expected representation size" } }
                    out.write(buffer, 0, read)
                    if (total - lastReported >= PROGRESS_REPORT_BYTES || (totalHint != null && total == totalHint)) {
                        lastReported = total
                        onProgress(TrustedArtifactDownloadProgress(total, totalHint))
                    }
                }
            }
        }
        totalHint?.let { check(total == it) { "Artifact transfer ended early: expected $it bytes, got $total" } }
        return FetchResult(restartFromZero = false)
    }

    suspend fun discard(spec: TrustedArtifactSpec) = discardDescriptor(spec)
    suspend fun discard(spec: TrustedSha512ArtifactSpec) = discardDescriptor(spec)

    private suspend fun discardDescriptor(spec: TrustedArtifactDescriptor) = withContext(Dispatchers.IO) {
        validate(spec)
        downloadMutex.withLock {
            val cacheName = cacheName(spec)
            val finalFile = File(cacheRoot, cacheName)
            val partial = File(cacheRoot, cacheName + PART_SUFFIX)
            val metadataFile = File(cacheRoot, cacheName + PART_METADATA_SUFFIX)
            require(finalFile.parentFile?.canonicalFile == cacheRoot.canonicalFile) { "Unsafe artifact destination" }
            finalFile.delete()
            clearPartial(partial, metadataFile)
        }
    }

    suspend fun cached(spec: TrustedArtifactSpec): File? = cachedDescriptor(spec)
    suspend fun cached(spec: TrustedSha512ArtifactSpec): File? = cachedDescriptor(spec)

    private suspend fun cachedDescriptor(spec: TrustedArtifactDescriptor): File? = withContext(Dispatchers.IO) {
        validate(spec)
        downloadMutex.withLock {
            val file = File(cacheRoot, cacheName(spec))
            file.takeIf { isTrustedFinal(it, spec) }
        }
    }

    private fun cacheName(spec: TrustedArtifactDescriptor): String =
        "${if (spec.digestAlgorithm == "SHA-512") "s512" else "s256"}-${spec.digestHex.lowercase().take(CACHE_SHA_PREFIX_CHARS)}-${spec.fileName}"

    private fun requireLocalHeadroom(spec: TrustedArtifactDescriptor, existingPartialBytes: Long) {
        val targetBytes = spec.expectedBytes ?: spec.maxBytes
        val remainingBytes = Math.max(0L, Math.subtractExact(targetBytes, existingPartialBytes.coerceAtMost(targetBytes)))
        val requiredBytes = Math.addExact(remainingBytes, LOCAL_DOWNLOAD_RESERVE_BYTES)
        val uuid = runCatching { storageManager.getUuidForPath(cacheRoot) }.getOrNull()
        val allocatableBytes = uuid?.let { id -> runCatching { storageManager.getAllocatableBytes(id) }.getOrNull() }
            ?: cacheRoot.usableSpace
        check(allocatableBytes >= requiredBytes) {
            "Not enough local storage for trusted artifact: need ${requiredBytes / (1024L * 1024L)} MiB including reserve, " +
                "allocatable ${allocatableBytes / (1024L * 1024L)} MiB"
        }

        if (existingPartialBytes == 0L && uuid != null && cacheRoot.usableSpace < requiredBytes) {
            runCatching { storageManager.allocateBytes(uuid, requiredBytes) }
                .getOrElse { error -> throw IOException("Could not prepare local storage for trusted artifact", error) }
            val physicalAfterAllocation = cacheRoot.usableSpace.coerceAtLeast(0L)
            val allocatableAfterAllocation = runCatching { storageManager.getAllocatableBytes(uuid) }.getOrElse { physicalAfterAllocation }
            check(physicalAfterAllocation >= requiredBytes && allocatableAfterAllocation >= requiredBytes) {
                "Local storage changed while preparing trusted artifact download"
            }
        }
    }

    private fun expectedTotalForFull(response: Response, spec: TrustedArtifactDescriptor): Long? {
        val declared = response.body?.contentLength() ?: -1L
        return when {
            spec.expectedBytes != null -> spec.expectedBytes
            declared >= 0L -> declared
            else -> null
        }
    }

    private fun isTrustedFinal(file: File, spec: TrustedArtifactDescriptor): Boolean {
        if (!file.isFile) return false
        if (file.length() > spec.maxBytes) return false
        if (spec.expectedBytes != null && file.length() != spec.expectedBytes) return false
        return digest(file, spec.digestAlgorithm).equals(spec.digestHex, ignoreCase = true)
    }

    private fun isPotentiallyComplete(partial: File, spec: TrustedArtifactDescriptor, metadata: PartialMetadata): Boolean {
        val length = partial.length()
        return when {
            spec.expectedBytes != null -> length == spec.expectedBytes
            metadata.totalBytes != null -> length == metadata.totalBytes
            else -> false
        }
    }

    private fun publishIfTrusted(
        partial: File,
        metadataFile: File,
        finalFile: File,
        spec: TrustedArtifactDescriptor,
        onProgress: (TrustedArtifactDownloadProgress) -> Unit,
    ): Boolean {
        if (!partial.isFile || partial.length() <= 0L || partial.length() > spec.maxBytes) return false
        if (spec.expectedBytes != null && partial.length() != spec.expectedBytes) return false
        if (!digest(partial, spec.digestAlgorithm).equals(spec.digestHex, ignoreCase = true)) return false
        if (finalFile.exists()) finalFile.delete()
        check(partial.renameTo(finalFile)) { "Could not atomically publish downloaded artifact" }
        finalFile.setLastModified(System.currentTimeMillis())
        metadataFile.delete()
        pruneFinalCache(protectedNames = setOf(finalFile.name))
        onProgress(TrustedArtifactDownloadProgress(finalFile.length(), finalFile.length()))
        return true
    }

    private fun loadResumeMetadata(spec: TrustedArtifactDescriptor, partial: File, metadataFile: File): PartialMetadata? {
        if (!partial.isFile || partial.length() <= 0L) {
            clearPartial(partial, metadataFile)
            return null
        }
        if (partial.length() > spec.maxBytes || !metadataFile.isFile || metadataFile.length() !in 1L..MAX_METADATA_BYTES) {
            clearPartial(partial, metadataFile)
            return null
        }
        return runCatching {
            val props = Properties().apply { metadataFile.inputStream().buffered().use { input -> load(input) } }
            check(props.getProperty("version") == METADATA_VERSION)
            val metadata = PartialMetadata(
                digestAlgorithm = props.getProperty("digestAlgorithm") ?: error("Missing partial digest algorithm"),
                digestHex = props.getProperty("digestHex") ?: error("Missing partial digest identity"),
                url = props.getProperty("url") ?: error("Missing partial URL identity"),
                expectedBytes = props.getProperty("expectedBytes")?.takeIf(String::isNotBlank)?.toLong(),
                maxBytes = props.getProperty("maxBytes")?.toLong() ?: error("Missing partial maxBytes"),
                strongEtag = props.getProperty("strongEtag")?.takeIf(String::isNotBlank)?.let(::strongEtag),
                totalBytes = props.getProperty("totalBytes")?.takeIf(String::isNotBlank)?.toLong(),
            )
            check(metadata.digestAlgorithm == spec.digestAlgorithm)
            check(metadata.digestHex.equals(spec.digestHex, ignoreCase = true))
            check(metadata.url == spec.url)
            check(metadata.expectedBytes == spec.expectedBytes)
            check(metadata.maxBytes == spec.maxBytes)
            metadata.totalBytes?.let { check(it in partial.length()..spec.maxBytes) }
            metadata
        }.getOrElse {
            clearPartial(partial, metadataFile)
            null
        }
    }

    private fun retainOnlyValidPartial(spec: TrustedArtifactDescriptor, partial: File, metadataFile: File) {
        if (!partial.isFile || partial.length() <= 0L || partial.length() > spec.maxBytes || !metadataFile.isFile) {
            clearPartial(partial, metadataFile)
            return
        }
        // Re-read identity so a torn/corrupt sidecar never makes a future transfer append blindly.
        loadResumeMetadata(spec, partial, metadataFile)
    }

    private fun writeResumeMetadata(file: File, metadata: PartialMetadata) {
        val temp = File(file.parentFile, file.name + ".tmp")
        val props = Properties().apply {
            setProperty("version", METADATA_VERSION)
            setProperty("digestAlgorithm", metadata.digestAlgorithm)
            setProperty("digestHex", metadata.digestHex)
            setProperty("url", metadata.url)
            setProperty("expectedBytes", metadata.expectedBytes?.toString().orEmpty())
            setProperty("maxBytes", metadata.maxBytes.toString())
            setProperty("strongEtag", metadata.strongEtag.orEmpty())
            setProperty("totalBytes", metadata.totalBytes?.toString().orEmpty())
        }
        FileOutputStream(temp).use { output ->
            props.store(output, null)
            output.fd.sync()
        }
        if (file.exists()) file.delete()
        check(temp.renameTo(file)) { "Could not persist artifact resume metadata" }
    }

    private fun cleanupStalePartials() {
        val cutoff = System.currentTimeMillis() - PARTIAL_MAX_AGE_MS
        cacheRoot.listFiles()?.forEach { file ->
            if (!file.name.endsWith(PART_SUFFIX) || file.lastModified() >= cutoff) return@forEach
            val baseName = file.name.removeSuffix(PART_SUFFIX)
            file.delete()
            File(cacheRoot, baseName + PART_METADATA_SUFFIX).delete()
        }
        cacheRoot.listFiles()?.forEach { file ->
            if (!file.name.endsWith(PART_METADATA_SUFFIX)) return@forEach
            val baseName = file.name.removeSuffix(PART_METADATA_SUFFIX)
            if (!File(cacheRoot, baseName + PART_SUFFIX).isFile) file.delete()
        }
        cacheRoot.listFiles()?.forEach { file ->
            if (file.name.endsWith(PART_METADATA_SUFFIX + ".tmp") && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun pruneFinalCache(protectedNames: Set<String>) {
        val root = cacheRoot.canonicalFile
        val candidates = cacheRoot.listFiles().orEmpty()
            .asSequence()
            .filter { file ->
                file.isFile &&
                    file.parentFile?.canonicalFile == root &&
                    !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                    !file.name.endsWith(PART_SUFFIX) &&
                    !file.name.endsWith(PART_METADATA_SUFFIX) &&
                    !file.name.endsWith(PART_METADATA_SUFFIX + ".tmp")
            }
            .toList()
        var total = 0L
        candidates.forEach { total = Math.addExact(total, it.length().coerceAtLeast(0L)) }
        val cacheBudgetBytes = finalCacheBudgetBytes()
        if (total <= cacheBudgetBytes) return
        candidates.asSequence()
            .filterNot { it.name in protectedNames }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .forEach { file ->
                if (total <= cacheBudgetBytes) return@forEach
                val size = file.length().coerceAtLeast(0L)
                if (file.delete()) total = (total - size).coerceAtLeast(0L)
            }
    }

    // This changes only re-download frequency, never extension availability.

    private fun finalCacheBudgetBytes(): Long {
        val uuid = runCatching { storageManager.getUuidForPath(cacheRoot) }.getOrNull()
        val quota = uuid?.let { id -> runCatching { storageManager.getCacheQuotaBytes(id) }.getOrNull() }
            ?.takeIf { it > 0L }
            ?: MAX_FINAL_CACHE_BYTES
        val freeSensitive = cacheRoot.usableSpace.coerceAtLeast(0L) / CACHE_FREE_SPACE_DIVISOR
        return minOf(MAX_FINAL_CACHE_BYTES, quota, freeSensitive).coerceAtLeast(0L)
    }

    private fun clearPartial(partial: File, metadataFile: File) {
        partial.delete()
        metadataFile.delete()
        File(metadataFile.parentFile, metadataFile.name + ".tmp").delete()
    }

    private fun strongEtag(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.length in 2..MAX_ETAG_CHARS } ?: return null
        if (value.startsWith("W/")) return null
        return value.takeIf { it.startsWith('"') && it.endsWith('"') }
    }

    private fun parseSatisfiedContentRange(raw: String?): ByteRange? {
        val match = raw?.trim()?.let { CONTENT_RANGE_REGEX.matchEntire(it) } ?: return null
        return runCatching {
            val start = match.groupValues[1].toLong()
            val end = match.groupValues[2].toLong()
            val total = match.groupValues[3].toLong()
            require(start >= 0L && end >= start && total > end)
            ByteRange(start, end, total)
        }.getOrNull()
    }

    private fun parseUnsatisfiedContentRange(raw: String?): Long? {
        val match = raw?.trim()?.let { UNSATISFIED_RANGE_REGEX.matchEntire(it) } ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it >= 0L }
    }

    private fun validate(spec: TrustedArtifactDescriptor) {
        spec.validateDescriptor()
        val expectedLength = when (spec.digestAlgorithm) {
            "SHA-256" -> 64
            "SHA-512" -> 128
            else -> error("Unsupported trusted-artifact digest algorithm")
        }
        require(spec.digestHex.length == expectedLength && spec.digestHex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            "Invalid trusted-artifact digest"
        }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class PartialMetadata(
        val digestAlgorithm: String,
        val digestHex: String,
        val url: String,
        val expectedBytes: Long?,
        val maxBytes: Long,
        val strongEtag: String?,
        val totalBytes: Long?,
    )

    private data class ByteRange(val start: Long, val end: Long, val total: Long)
    private data class FetchResult(val restartFromZero: Boolean)

    companion object {
        private val downloadMutex = Mutex()
        private val CONTENT_RANGE_REGEX = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
        private val UNSATISFIED_RANGE_REGEX = Regex("^bytes\\s+\\*/(\\d+)$", RegexOption.IGNORE_CASE)
        private const val MAX_REDIRECTS = 5
        private const val CACHE_SHA_PREFIX_CHARS = 16
        private const val PROGRESS_REPORT_BYTES = 512L * 1024L
        private const val PART_SUFFIX = ".part"
        private const val PART_METADATA_SUFFIX = ".part.properties"
        private const val METADATA_VERSION = "2"
        private const val MAX_METADATA_BYTES = 16L * 1024L
        private const val MAX_ETAG_CHARS = 512
        private const val PARTIAL_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        private const val LOCAL_DOWNLOAD_RESERVE_BYTES = 512L * 1024L * 1024L
        private const val MAX_FINAL_CACHE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val CACHE_FREE_SPACE_DIVISOR = 8L
    }
}
