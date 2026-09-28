package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MarketplaceArtifactRecord(
    val extensionId: String,
    val version: String,
    val sha256: String,
    val relativePath: String,
    val sourceUrl: String,
    val bytes: Long,
    val installedAtEpochMs: Long,
)

@Serializable
private data class CachedMarketplaceSearch(
    val key: String,
    val savedAtEpochMs: Long,
    val page: MarketplaceSearchPage,
)

@Serializable
private data class CachedMarketplaceDetail(
    val extensionId: String,
    val savedAtEpochMs: Long,
    val detail: MarketplaceExtensionDetail,
)

@Serializable
private data class MarketplaceStoreIndex(
    val schema: Int = 1,
    val searches: List<CachedMarketplaceSearch> = emptyList(),
    val details: List<CachedMarketplaceDetail> = emptyList(),
    val artifacts: List<MarketplaceArtifactRecord> = emptyList(),
    val activeArtifactSha256: Map<String, String> = emptyMap(),
)

// App-private metadata cache and exact VSIX rollback history for Open VSX installs.
class ExtensionMarketplaceStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "extension-marketplace")
    private val artifactsRoot = File(root, "artifacts")
    private val indexFile = File(root, "index.json")
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; prettyPrint = true }

    @Synchronized
    fun cachedSearch(query: String, offset: Int, size: Int, maxAgeMs: Long, allowStale: Boolean): MarketplaceSearchPage? {
        val index = readIndex()
        val key = searchKey(query, offset, size)
        val cached = index.searches.firstOrNull { it.key == key } ?: return null
        val age = (System.currentTimeMillis() - cached.savedAtEpochMs).coerceAtLeast(0L)
        if (!allowStale && age > maxAgeMs) return null
        return cached.page.copy(staleCache = age > maxAgeMs, cachedAtEpochMs = cached.savedAtEpochMs)
    }

    @Synchronized
    fun putSearch(page: MarketplaceSearchPage, size: Int) {
        val now = System.currentTimeMillis()
        val index = readIndex()
        val key = searchKey(page.query, page.offset, size)
        val next = (listOf(CachedMarketplaceSearch(key, now, page.copy(staleCache = false, cachedAtEpochMs = now))) +
            index.searches.filterNot { it.key == key })
            .sortedByDescending { it.savedAtEpochMs }
            .take(MAX_SEARCH_CACHE_ENTRIES)
        writeIndex(index.copy(searches = next))
    }

    @Synchronized
    fun cachedDetail(extensionId: String, maxAgeMs: Long, allowStale: Boolean): MarketplaceExtensionDetail? {
        val id = normalizedId(extensionId)
        val cached = readIndex().details.firstOrNull { it.extensionId == id } ?: return null
        val age = (System.currentTimeMillis() - cached.savedAtEpochMs).coerceAtLeast(0L)
        if (!allowStale && age > maxAgeMs) return null
        return cached.detail
    }

    @Synchronized
    fun putDetail(detail: MarketplaceExtensionDetail) {
        val now = System.currentTimeMillis()
        val index = readIndex()
        val id = normalizedId(detail.id)
        val next = (listOf(CachedMarketplaceDetail(id, now, detail)) + index.details.filterNot { it.extensionId == id })
            .sortedByDescending { it.savedAtEpochMs }
            .take(MAX_DETAIL_CACHE_ENTRIES)
        writeIndex(index.copy(details = next))
    }

    @Synchronized
    fun persistArtifact(detail: MarketplaceExtensionDetail, downloaded: DownloadedMarketplaceArtifact): MarketplaceArtifactRecord {
        val id = normalizedId(detail.id)
        require(downloaded.file.isFile && downloaded.bytes == downloaded.file.length()) { "Marketplace artifact is incomplete" }
        require(downloaded.sha256.matches(SHA_RE) && sha256(downloaded.file) == downloaded.sha256) { "Marketplace artifact SHA-256 mismatch" }
        require(downloaded.bytes in 1..DeclarativeVsixParser.MAX_VSIX_BYTES) { "Marketplace artifact is outside safe size limits" }
        val relative = "artifacts/${safeSegment(id)}/${safeSegment(detail.version)}/${downloaded.sha256}.vsix"
        val target = resolveWithin(root, relative)
        target.parentFile?.mkdirs()
        if (!target.isFile || target.length() != downloaded.bytes || sha256(target) != downloaded.sha256) {
            val temp = File(target.parentFile, target.name + ".part-${System.nanoTime()}")
            try {
                downloaded.file.copyTo(temp, overwrite = true)
                require(temp.length() == downloaded.bytes && sha256(temp) == downloaded.sha256) { "Marketplace artifact cache verification failed" }
                atomicMove(temp, target)
            } finally {
                temp.delete()
            }
        }
        val record = MarketplaceArtifactRecord(
            extensionId = id,
            version = detail.version,
            sha256 = downloaded.sha256,
            relativePath = relative,
            sourceUrl = downloaded.finalUrl,
            bytes = downloaded.bytes,
            installedAtEpochMs = System.currentTimeMillis(),
        )
        val index = readIndex()
        val merged = (index.artifacts.filterNot { it.extensionId == id && it.sha256 == record.sha256 } + record)
        writeIndex(prune(index.copy(artifacts = merged, activeArtifactSha256 = index.activeArtifactSha256 + (id to record.sha256))))
        return record
    }

    @Synchronized
    fun markActive(extensionId: String, sha256: String) {
        val id = normalizedId(extensionId)
        require(sha256.matches(SHA_RE)) { "Invalid marketplace artifact SHA-256" }
        val index = readIndex()
        val record = index.artifacts.firstOrNull { it.extensionId == id && it.sha256 == sha256 }
            ?: error("Marketplace rollback artifact is not indexed")
        require(verifyArtifact(record)) { "Marketplace rollback artifact integrity check failed" }
        writeIndex(prune(index.copy(activeArtifactSha256 = index.activeArtifactSha256 + (id to sha256))))
    }

    @Synchronized
    fun activeArtifact(extensionId: String): MarketplaceArtifactRecord? {
        val id = normalizedId(extensionId)
        val index = readIndex()
        val sha = index.activeArtifactSha256[id] ?: return null
        return index.artifacts.firstOrNull { it.extensionId == id && it.sha256 == sha }?.takeIf(::verifyArtifact)
    }

    @Synchronized
    fun previousArtifact(extensionId: String): MarketplaceArtifactRecord? {
        val id = normalizedId(extensionId)
        val index = readIndex()
        val activeSha = index.activeArtifactSha256[id]
        return index.artifacts.asSequence()
            .filter { it.extensionId == id && it.sha256 != activeSha }
            .sortedByDescending { it.installedAtEpochMs }
            .firstOrNull(::verifyArtifact)
    }

    @Synchronized
    fun artifactFile(record: MarketplaceArtifactRecord): File {
        require(record.extensionId == normalizedId(record.extensionId) && record.sha256.matches(SHA_RE)) { "Invalid marketplace artifact record" }
        return resolveWithin(root, record.relativePath).also { require(verifyArtifact(record)) { "Marketplace artifact integrity check failed" } }
    }

    @Synchronized
    fun clearActive(extensionId: String) {
        val id = normalizedId(extensionId)
        val index = readIndex()
        writeIndex(index.copy(activeArtifactSha256 = index.activeArtifactSha256 - id))
    }

    private fun prune(index: MarketplaceStoreIndex): MarketplaceStoreIndex {
        val keep = linkedSetOf<String>()
        index.artifacts.groupBy { it.extensionId }.forEach { (id, records) ->
            val active = index.activeArtifactSha256[id]
            records.sortedByDescending { it.installedAtEpochMs }.forEach { record ->
                if (record.sha256 == active || keep.count { it.startsWith("$id|") } < MAX_ARTIFACTS_PER_EXTENSION) {
                    keep += "$id|${record.sha256}"
                }
            }
        }
        val kept = index.artifacts.filter { "${it.extensionId}|${it.sha256}" in keep }
        val removed = index.artifacts.filterNot { "${it.extensionId}|${it.sha256}" in keep }
        removed.forEach { runCatching { resolveWithin(root, it.relativePath).delete() } }
        return index.copy(
            artifacts = kept.sortedWith(compareBy<MarketplaceArtifactRecord> { it.extensionId }.thenByDescending { it.installedAtEpochMs }),
            activeArtifactSha256 = index.activeArtifactSha256.filter { (id, sha) -> kept.any { it.extensionId == id && it.sha256 == sha } },
        )
    }

    private fun verifyArtifact(record: MarketplaceArtifactRecord): Boolean = runCatching {
        val file = resolveWithin(root, record.relativePath)
        file.isFile && file.length() == record.bytes && record.bytes in 1..DeclarativeVsixParser.MAX_VSIX_BYTES && sha256(file) == record.sha256
    }.getOrDefault(false)

    private fun readIndex(): MarketplaceStoreIndex {
        if (!indexFile.exists()) return MarketplaceStoreIndex()
        require(!Files.isSymbolicLink(indexFile.toPath())) { "Marketplace index must not be a symbolic link" }
        require(indexFile.isFile) { "Marketplace index is not a regular file" }
        require(indexFile.length() in 1..MAX_INDEX_BYTES) { "Marketplace index exceeds safe size limit or is empty" }
        return runCatching {
            json.decodeFromString<MarketplaceStoreIndex>(indexFile.readText()).also(::validateIndex)
        }.getOrElse { failure ->
            throw IllegalStateException("Marketplace index is corrupt; repair or clear marketplace state before mutating extensions", failure)
        }
    }

    private fun writeIndex(index: MarketplaceStoreIndex) {
        validateIndex(index)
        root.mkdirs()
        val temp = File(root, ".index-${System.nanoTime()}.tmp")
        try {
            temp.writeText(json.encodeToString(index) + "\n")
            require(temp.length() <= MAX_INDEX_BYTES) { "Marketplace index exceeds safe size limit" }
            atomicMove(temp, indexFile)
        } finally {
            temp.delete()
        }
    }

    private fun validateIndex(index: MarketplaceStoreIndex) {
        require(index.schema == 1) { "Unsupported marketplace store schema" }
        require(index.searches.size <= MAX_SEARCH_CACHE_ENTRIES && index.details.size <= MAX_DETAIL_CACHE_ENTRIES) { "Marketplace metadata cache is too large" }
        require(index.artifacts.size <= MAX_ARTIFACT_INDEX_ENTRIES) { "Marketplace artifact history is too large" }
        index.artifacts.forEach { record ->
            require(record.extensionId == normalizedId(record.extensionId)) { "Invalid marketplace artifact id" }
            require(record.version.matches(VERSION_RE) && record.sha256.matches(SHA_RE)) { "Invalid marketplace artifact metadata" }
            require(record.bytes in 1..DeclarativeVsixParser.MAX_VSIX_BYTES && record.installedAtEpochMs > 0) { "Invalid marketplace artifact bounds" }
            require(record.relativePath.startsWith("artifacts/") && record.relativePath.endsWith(".vsix")) { "Invalid marketplace artifact path" }
        }
        require(index.activeArtifactSha256.all { (id, sha) -> id == normalizedId(id) && sha.matches(SHA_RE) }) { "Invalid marketplace active history" }
    }

    private fun searchKey(query: String, offset: Int, size: Int): String = "${query.trim().lowercase()}|$offset|$size"

    private fun normalizedId(raw: String): String = raw.trim().lowercase().also {
        require(it.matches(ID_RE)) { "Invalid marketplace extension id" }
    }

    private fun safeSegment(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9._+-]"), "_").take(180)

    private fun resolveWithin(base: File, relative: String): File {
        val normalized = relative.replace('\\', '/')
        require(normalized.length in 1..360 && !normalized.startsWith('/') && normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Invalid marketplace storage path"
        }
        val canonicalBase = base.canonicalFile
        val target = File(canonicalBase, normalized).canonicalFile
        require(target.path.startsWith(canonicalBase.path + File.separator)) { "Marketplace storage path escapes root" }
        return target
    }

    private fun atomicMove(source: File, target: File) {
        target.parentFile?.mkdirs()
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
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
        const val SEARCH_FRESH_MS = 2L * 60 * 1000
        const val DETAIL_FRESH_MS = 10L * 60 * 1000
        private const val MAX_SEARCH_CACHE_ENTRIES = 24
        private const val MAX_DETAIL_CACHE_ENTRIES = 256
        private const val MAX_ARTIFACTS_PER_EXTENSION = 3
        private const val MAX_ARTIFACT_INDEX_ENTRIES = 512
        private const val MAX_INDEX_BYTES = 8L * 1024 * 1024
        private val ID_RE = Regex("[a-z0-9][a-z0-9._-]{1,119}")
        private val VERSION_RE = Regex("[A-Za-z0-9._+-]{1,80}")
        private val SHA_RE = Regex("[0-9a-f]{64}")
    }
}
