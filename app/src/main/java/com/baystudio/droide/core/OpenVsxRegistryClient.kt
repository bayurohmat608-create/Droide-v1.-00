package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

@Serializable
data class MarketplaceExtensionReference(
    val namespace: String,
    val extension: String,
    val version: String? = null,
) {
    val id: String get() = "${namespace.lowercase()}.${extension.lowercase()}"
}

@Serializable
data class MarketplaceExtensionSummary(
    val id: String,
    val namespace: String,
    val name: String,
    val displayName: String,
    val version: String,
    val description: String = "",
    val verifiedPublisher: Boolean = false,
    val downloadCount: Int = 0,
    val averageRating: Double? = null,
    val deprecated: Boolean = false,
    val iconUrl: String? = null,
    val metadataUrl: String,
    val iconCachePath: String? = null,
)

@Serializable
data class MarketplaceExtensionDetail(
    val id: String,
    val namespace: String,
    val name: String,
    val displayName: String,
    val version: String,
    val description: String = "",
    val targetPlatform: String? = null,
    val verifiedPublisher: Boolean = false,
    val trustedPublishing: Boolean = false,
    val reviewStatus: String? = null,
    val deprecated: Boolean = false,
    val downloadable: Boolean = true,
    val license: String? = null,
    val homepage: String? = null,
    val repository: String? = null,
    val engines: Map<String, String> = emptyMap(),
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val dependencies: List<MarketplaceExtensionReference> = emptyList(),
    val bundledExtensions: List<MarketplaceExtensionReference> = emptyList(),
    val files: Map<String, String> = emptyMap(),
    val timestamp: String = "",
)

@Serializable
data class MarketplaceSearchPage(
    val query: String,
    val offset: Int,
    val totalSize: Int,
    val extensions: List<MarketplaceExtensionSummary>,
    val staleCache: Boolean = false,
    val cachedAtEpochMs: Long = 0L,
)

internal data class MarketplaceHttpPayload(
    val bytes: ByteArray,
    val finalUrl: String,
    val etag: String?,
    val retryAfter: String?,
)

data class DownloadedMarketplaceArtifact(
    val file: File,
    val sha256: String,
    val bytes: Long,
    val finalUrl: String,
)

class MarketplaceRateLimitException(
    val retryAfter: String?,
) : IllegalStateException("Open VSX rate limit reached${retryAfter?.let { "; retry after $it" } ?: ""}")

@Serializable
private data class OpenVsxSearchResultDto(
    val offset: Int = 0,
    val totalSize: Int = 0,
    val extensions: List<OpenVsxSearchEntryDto> = emptyList(),
    val error: String? = null,
)

@Serializable
private data class OpenVsxSearchEntryDto(
    val url: String = "",
    val files: Map<String, String> = emptyMap(),
    val name: String,
    val namespace: String,
    val version: String,
    val timestamp: String = "",
    val verified: Boolean = false,
    val averageRating: Double? = null,
    val downloadCount: Int = 0,
    val displayName: String? = null,
    val description: String? = null,
    val deprecated: Boolean = false,
)

@Serializable
private data class OpenVsxExtensionRefDto(
    val namespace: String,
    val extension: String,
    val version: String? = null,
)

@Serializable
private data class OpenVsxExtensionDto(
    val files: Map<String, String> = emptyMap(),
    val name: String,
    val namespace: String,
    val targetPlatform: String? = null,
    val version: String,
    val publishedWithTrustedPublishing: Boolean = false,
    val reviewStatus: String? = null,
    val verified: Boolean = false,
    val timestamp: String = "",
    val displayName: String? = null,
    val description: String? = null,
    val engines: Map<String, String> = emptyMap(),
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val license: String? = null,
    val homepage: String? = null,
    val repository: String? = null,
    val dependencies: List<OpenVsxExtensionRefDto> = emptyList(),
    val bundledExtensions: List<OpenVsxExtensionRefDto> = emptyList(),
    val deprecated: Boolean = false,
    val downloadable: Boolean = true,
    val error: String? = null,
)








class OpenVsxRegistryClient(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://open-vsx.org".toHttpUrl()
    private val iconCache = File(appContext.cacheDir, "open-vsx-icons").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun search(query: String, offset: Int = 0, size: Int = 24): MarketplaceSearchPage {
        val normalized = query.trim().take(MAX_QUERY_CHARS)
        require(offset >= 0 && size in 1..MAX_SEARCH_PAGE) { "Invalid marketplace search page" }
        val url = baseUrl.newBuilder()
            .addPathSegments("api/-/search")
            .addQueryParameter("query", normalized)
            .addQueryParameter("size", size.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("sortOrder", "desc")
            .addQueryParameter("sortBy", "relevance")
            .addQueryParameter("includeAllVersions", "false")
            .build()
            .toString()
        val payload = getBytes(url, "application/json", MAX_SEARCH_BYTES, externalResource = false)
        val dto = json.decodeFromString<OpenVsxSearchResultDto>(payload.bytes.decodeToString())
        require(dto.error.isNullOrBlank()) { dto.error ?: "Open VSX search failed" }
        require(dto.offset >= 0 && dto.totalSize >= 0 && dto.extensions.size <= MAX_SEARCH_PAGE) { "Invalid Open VSX search response" }
        return MarketplaceSearchPage(
            query = normalized,
            offset = dto.offset,
            totalSize = dto.totalSize,
            extensions = dto.extensions.map(::summary),
            cachedAtEpochMs = System.currentTimeMillis(),
        )
    }

    suspend fun detail(extensionId: String, version: String? = null): MarketplaceExtensionDetail {
        val (namespace, name) = splitId(extensionId)
        val builder = baseUrl.newBuilder().addPathSegment("api").addPathSegment(namespace).addPathSegment(name)
        if (!version.isNullOrBlank()) builder.addPathSegment(version.take(80))
        val payload = getBytes(builder.build().toString(), "application/json", MAX_DETAIL_BYTES, externalResource = false)
        val dto = json.decodeFromString<OpenVsxExtensionDto>(payload.bytes.decodeToString())
        require(dto.error.isNullOrBlank()) { dto.error ?: "Open VSX extension lookup failed" }
        require(dto.namespace.equals(namespace, true) && dto.name.equals(name, true)) { "Open VSX metadata identity mismatch" }
        require(dto.version.matches(VERSION_RE)) { "Invalid Open VSX extension version" }
        return MarketplaceExtensionDetail(
            id = "${dto.namespace.lowercase()}.${dto.name.lowercase()}",
            namespace = dto.namespace,
            name = dto.name,
            displayName = dto.displayName?.take(160)?.ifBlank { dto.name } ?: dto.name,
            version = dto.version,
            description = dto.description?.take(MAX_DESCRIPTION_CHARS).orEmpty(),
            targetPlatform = dto.targetPlatform,
            verifiedPublisher = dto.verified,
            trustedPublishing = dto.publishedWithTrustedPublishing,
            reviewStatus = dto.reviewStatus,
            deprecated = dto.deprecated,
            downloadable = dto.downloadable,
            license = dto.license?.take(120),
            homepage = dto.homepage?.takeIf(::safeOptionalHttpsUrl),
            repository = dto.repository?.takeIf(::safeOptionalHttpsUrl),
            engines = dto.engines.entries.take(32).associate { it.key.take(80) to it.value.take(120) },
            categories = dto.categories.take(64).map { it.take(100) },
            tags = dto.tags.take(128).map { it.take(100) },
            dependencies = dto.dependencies.take(MAX_DEPENDENCIES).map(::reference),
            bundledExtensions = dto.bundledExtensions.take(MAX_DEPENDENCIES).map(::reference),
            files = sanitizeFiles(dto.files),
            timestamp = dto.timestamp.take(80),
        )
    }

    suspend fun manifest(detail: MarketplaceExtensionDetail): String {
        val url = detail.files["manifest"] ?: error("Open VSX extension has no manifest resource")
        return getBytes(url, "application/json,text/plain", MAX_MANIFEST_BYTES, externalResource = true).bytes.decodeToString()
    }

    suspend fun cacheIcon(summary: MarketplaceExtensionSummary): MarketplaceExtensionSummary {
        val raw = summary.iconUrl ?: return summary
        val file = File(iconCache, sha256(raw.encodeToByteArray()) + ".img")
        if (!file.isFile || file.length() !in 1..MAX_ICON_BYTES.toLong()) {
            val payload = getBytes(raw, "image/*", MAX_ICON_BYTES, externalResource = true)
            val temp = File(iconCache, file.name + ".part")
            try {
                temp.writeBytes(payload.bytes)
                require(temp.length() in 1..MAX_ICON_BYTES.toLong()) { "Marketplace icon is too large" }
                check(temp.renameTo(file) || runCatching { temp.copyTo(file, overwrite = true); temp.delete(); true }.getOrDefault(false)) {
                    "Cannot cache marketplace icon"
                }
            } finally {
                temp.delete()
            }
        }
        return summary.copy(iconCachePath = file.absolutePath)
    }

    suspend fun downloadVsix(detail: MarketplaceExtensionDetail): DownloadedMarketplaceArtifact = withContext(Dispatchers.IO) {
        require(detail.downloadable) { "Extension is not downloadable" }
        val raw = detail.files["download"] ?: error("Open VSX extension has no VSIX download resource")
        val expectedSha256 = detail.files["sha256"]?.let { hashUrl ->
            val text = getBytes(hashUrl, "text/plain,application/octet-stream", MAX_SHA256_BYTES, externalResource = true).bytes.decodeToString()
            SHA256_TEXT_RE.find(text)?.value?.lowercase() ?: error("Open VSX SHA-256 resource is malformed")
        }
        val destination = File(appContext.cacheDir, "open-vsx-${safeSegment(detail.id)}-${safeSegment(detail.version)}-${System.nanoTime()}.vsix")
        val digest = MessageDigest.getInstance("SHA-256")
        var target = NetworkSecurity.validatePublicHttpsTarget(raw)
        var total = 0L
        try {
            repeat(MAX_REDIRECTS + 1) { hop ->
                coroutineContext.ensureActive()
                val request = Request.Builder().url(target.url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/octet-stream")
                    .build()
                val pinned = client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build()
                pinned.newCall(request).awaitResponse().use { response ->
                    if (response.code == 429) throw MarketplaceRateLimitException(response.header("Retry-After"))
                    if (response.code in 300..399) {
                        require(hop < MAX_REDIRECTS) { "Open VSX redirect limit exceeded" }
                        val location = response.header("Location") ?: error("Marketplace redirect has no location")
                        val next = response.request.url.resolve(location)?.toString() ?: error("Invalid marketplace redirect")
                        target = NetworkSecurity.validatePublicHttpsTarget(next)
                        return@use
                    }
                    check(response.isSuccessful) { "Open VSX download failed with HTTP ${response.code}" }
                    val declared = response.body?.contentLength()?.takeIf { it >= 0 }
                    declared?.let { require(it <= DeclarativeVsixParser.MAX_VSIX_BYTES) { "Marketplace VSIX exceeds safe import limit" } }
                    response.body?.byteStream()?.buffered(128 * 1024)?.use { input ->
                        destination.outputStream().buffered(128 * 1024).use { out ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                total += read
                                require(total <= DeclarativeVsixParser.MAX_VSIX_BYTES) { "Marketplace VSIX exceeds safe import limit" }
                                digest.update(buffer, 0, read)
                                out.write(buffer, 0, read)
                            }
                        }
                    } ?: error("Open VSX download has no response body")
                    require(total > 0) { "Open VSX returned an empty VSIX" }
                    val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    expectedSha256?.let { expected ->
                        require(actualSha256 == expected) { "Open VSX VSIX SHA-256 does not match registry metadata" }
                    }
                    return@withContext DownloadedMarketplaceArtifact(
                        file = destination,
                        sha256 = actualSha256,
                        bytes = total,
                        finalUrl = target.url,
                    )
                }
            }
            error("Open VSX redirect limit exceeded")
        } catch (t: Throwable) {
            destination.delete()
            throw t
        }
    }

    private suspend fun getBytes(rawUrl: String, accept: String, maxBytes: Int, externalResource: Boolean): MarketplaceHttpPayload {
        require(maxBytes in 1..8 * 1024 * 1024) { "Invalid marketplace response limit" }
        var target = NetworkSecurity.validatePublicHttpsTarget(rawUrl)
        if (!externalResource) require(target.host.equals("open-vsx.org", true)) { "Marketplace metadata must use the Open VSX origin" }
        repeat(MAX_REDIRECTS + 1) { hop ->
            val request = Request.Builder().url(target.url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .build()
            val pinned = client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build()
            pinned.newCall(request).awaitResponse().use { response ->
                if (response.code == 429) throw MarketplaceRateLimitException(response.header("Retry-After"))
                if (response.code in 300..399) {
                    require(hop < MAX_REDIRECTS) { "Open VSX redirect limit exceeded" }
                    val location = response.header("Location") ?: error("Marketplace redirect has no location")
                    val next = response.request.url.resolve(location)?.toString() ?: error("Invalid marketplace redirect")
                    target = NetworkSecurity.validatePublicHttpsTarget(next)
                    if (!externalResource) require(target.host.equals("open-vsx.org", true)) { "Marketplace metadata redirect escaped Open VSX" }
                    return@use
                }
                check(response.isSuccessful) { "Open VSX request failed with HTTP ${response.code}" }
                val body = response.body ?: return MarketplaceHttpPayload(ByteArray(0), target.url, response.header("ETag"), response.header("Retry-After"))
                val declared = body.contentLength().takeIf { it >= 0 }
                declared?.let { require(it <= maxBytes) { "Marketplace response exceeds $maxBytes bytes" } }
                val bytes = body.byteStream().use { input -> input.readNBytes(maxBytes + 1) }
                require(bytes.size <= maxBytes) { "Marketplace response exceeds $maxBytes bytes" }
                return MarketplaceHttpPayload(bytes, target.url, response.header("ETag"), response.header("Retry-After"))
            }
        }
        error("Open VSX redirect limit exceeded")
    }

    private fun summary(dto: OpenVsxSearchEntryDto): MarketplaceExtensionSummary {
        val id = "${dto.namespace.lowercase()}.${dto.name.lowercase()}"
        require(id.matches(EXTENSION_ID_RE) && dto.version.matches(VERSION_RE)) { "Invalid Open VSX search result" }
        return MarketplaceExtensionSummary(
            id = id,
            namespace = dto.namespace.take(120),
            name = dto.name.take(120),
            displayName = dto.displayName?.take(160)?.ifBlank { dto.name } ?: dto.name.take(160),
            version = dto.version,
            description = dto.description?.take(MAX_DESCRIPTION_CHARS).orEmpty(),
            verifiedPublisher = dto.verified,
            downloadCount = dto.downloadCount.coerceAtLeast(0),
            averageRating = dto.averageRating?.takeIf { it in 0.0..5.0 },
            deprecated = dto.deprecated,
            iconUrl = dto.files["icon"]?.takeIf(::safeOptionalHttpsUrl),
            metadataUrl = dto.url.takeIf(::safeOptionalHttpsUrl) ?: baseUrl.newBuilder().addPathSegment("api").addPathSegment(dto.namespace).addPathSegment(dto.name).build().toString(),
        )
    }

    private fun reference(dto: OpenVsxExtensionRefDto): MarketplaceExtensionReference {
        require(dto.namespace.isNotBlank() && dto.extension.isNotBlank()) { "Invalid marketplace dependency" }
        return MarketplaceExtensionReference(dto.namespace.take(120), dto.extension.take(120), dto.version?.take(80))
    }

    private fun sanitizeFiles(files: Map<String, String>): Map<String, String> = files.entries
        .asSequence()
        .filter { it.key in SUPPORTED_FILE_KEYS && safeOptionalHttpsUrl(it.value) }
        .take(SUPPORTED_FILE_KEYS.size)
        .associate { it.key to it.value }

    


    private fun safeOptionalHttpsUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null &&
            !uri.host.equals("localhost", ignoreCase = true)
    }.getOrDefault(false)

    private fun splitId(id: String): Pair<String, String> {
        val normalized = id.trim().lowercase()
        require(normalized.matches(EXTENSION_ID_RE)) { "Invalid marketplace extension id" }
        val split = normalized.indexOf('.')
        require(split in 1 until normalized.lastIndex) { "Marketplace extension id must be namespace.name" }
        return normalized.substring(0, split) to normalized.substring(split + 1)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun safeSegment(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9._+-]"), "_").take(160)

    companion object {
        const val USER_AGENT = "Droide/1.00 OpenVSX"
        const val MAX_QUERY_CHARS = 120
        const val MAX_SEARCH_PAGE = 50
        const val MAX_DEPENDENCIES = 64
        const val MAX_DESCRIPTION_CHARS = 8_000
        const val MAX_SEARCH_BYTES = 2 * 1024 * 1024
        const val MAX_DETAIL_BYTES = 1 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 512 * 1024
        const val MAX_SHA256_BYTES = 4 * 1024
        const val MAX_ICON_BYTES = 1 * 1024 * 1024
        const val MAX_REDIRECTS = 5
        private val EXTENSION_ID_RE = Regex("[a-z0-9][a-z0-9._-]{1,119}")
        private val VERSION_RE = Regex("[A-Za-z0-9._+-]{1,80}")
        private val SHA256_TEXT_RE = Regex("(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")
        private val SUPPORTED_FILE_KEYS = setOf("download", "manifest", "icon", "readme", "license", "changelog", "sha256")
    }
}
