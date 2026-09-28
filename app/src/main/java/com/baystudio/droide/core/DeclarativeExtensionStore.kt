package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class InstalledDeclarativeExtensionRecord(
    val schema: Int = 1,
    val extensionId: String,
    val displayName: String,
    val publisher: String,
    val version: String,
    val sourceSha256: String,
    val installDirectory: String,
    val manifest: DroideExtensionManifest,
    val resourceSha256: Map<String, String>,
)

@Serializable
private data class DeclarativeExtensionIndex(
    val schema: Int = 1,
    val records: List<InstalledDeclarativeExtensionRecord> = emptyList(),
)

// App-private, integrity-checked store for data-only VSIX packages.
class DeclarativeExtensionStore(context: Context) {
    private val root = File(context.filesDir, "declarative-extensions")
    private val packages = File(root, "packages")
    private val indexFile = File(root, "index.json")
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; prettyPrint = true }

    @Synchronized
    fun listVerified(): List<InstalledDeclarativeExtensionRecord> = readIndex().records.mapNotNull { record ->
        runCatching { validateRecord(record); record.takeIf { verifyRecord(record) } }.getOrNull()
    }

    @Synchronized
    fun install(vsix: File): InstalledDeclarativeExtensionRecord {
        val parsed = DeclarativeVsixParser.parse(vsix)
        val safeId = safeSegment(parsed.manifest.id)
        val safeVersion = safeSegment(parsed.version)
        val staging = File(root, ".staging-$safeId-${System.nanoTime()}")
        val finalDir = File(packages, "$safeId/$safeVersion")
        val relativeInstallDir = "packages/$safeId/$safeVersion"
        check(staging.mkdirs()) { "Cannot create declarative extension staging directory" }
        try {
            val resourceHashes = extractResources(vsix, parsed, staging)
            val record = InstalledDeclarativeExtensionRecord(
                extensionId = parsed.manifest.id,
                displayName = parsed.displayName,
                publisher = parsed.publisher,
                version = parsed.version,
                sourceSha256 = parsed.sourceSha256,
                installDirectory = relativeInstallDir,
                manifest = parsed.manifest,
                resourceSha256 = resourceHashes,
            )
            validateRecord(record)
            check(verifyRecordAgainstRoot(record, staging)) { "Declarative extension staging integrity check failed" }
            val currentIndex = readIndex()
            validateContributionOwnership(record.manifest, currentIndex.records.filterNot { it.extensionId == record.extensionId }.map { it.manifest })

            finalDir.parentFile?.mkdirs()
            val replaced = currentIndex.records.firstOrNull { it.extensionId == record.extensionId }
            val backup = if (finalDir.exists()) File(root, ".backup-$safeId-${System.nanoTime()}") else null
            if (backup != null) moveDirectory(finalDir, backup)
            try {
                moveDirectory(staging, finalDir)
                val previous = currentIndex.records.filterNot { it.extensionId == record.extensionId }
                writeIndex(DeclarativeExtensionIndex(records = (previous + record).sortedBy { it.extensionId }))
                backup?.let { PathSecurity.deleteTreeNoFollow(it) }
                if (replaced != null && replaced.installDirectory != record.installDirectory) {
                    runCatching { PathSecurity.deleteTreeNoFollow(resolveInstallRoot(replaced)) }
                }
                return record
            } catch (t: Throwable) {
                val rollbackFailures = mutableListOf<Throwable>()
                runCatching {
                    if (finalDir.exists()) check(PathSecurity.deleteTreeNoFollow(finalDir)) { "Failed to remove failed declarative extension install" }
                }.exceptionOrNull()?.let(rollbackFailures::add)
                if (backup != null && backup.exists()) {
                    runCatching { moveDirectory(backup, finalDir) }.exceptionOrNull()?.let(rollbackFailures::add)
                }
                rollbackFailures.forEach(t::addSuppressed)
                throw t
            }
        } finally {
            if (staging.exists()) PathSecurity.deleteTreeNoFollow(staging)
        }
    }

    @Synchronized
    fun uninstall(extensionId: String) {
        val current = readIndex()
        val target = current.records.firstOrNull { it.extensionId == extensionId } ?: return
        val installRoot = resolveInstallRoot(target)
        val tombstone = File(root, ".uninstall-${safeSegment(extensionId)}-${System.nanoTime()}")
        var detached = false
        if (installRoot.exists()) {
            moveDirectory(installRoot, tombstone)
            detached = true
        }
        try {
            // A failed index commit restores the previously active payload instead of reporting an uninstall that never completed.

            writeIndex(DeclarativeExtensionIndex(records = current.records.filterNot { it.extensionId == extensionId }))
        } catch (t: Throwable) {
            if (detached && tombstone.exists()) {
                runCatching { moveDirectory(tombstone, installRoot) }
                    .exceptionOrNull()?.let(t::addSuppressed)
            }
            throw t
        }
        if (detached) runCatching { PathSecurity.deleteTreeNoFollow(tombstone) }
    }

    fun installRoot(record: InstalledDeclarativeExtensionRecord): File = resolveInstallRoot(record)

    private fun extractResources(
        vsix: File,
        parsed: ParsedDeclarativeVsix,
        staging: File,
    ): Map<String, String> {
        val hashes = linkedMapOf<String, String>()
        ZipFile(vsix).use { zip ->
            val selected = linkedSetOf("package.json")
            selected += parsed.resourcePaths
            selected.forEach { relative ->
                val sourceName = if (relative == "package.json") "extension/package.json" else "extension/$relative"
                val entry = zip.getEntry(sourceName) ?: error("VSIX resource disappeared: $relative")
                require(!entry.isDirectory && entry.size in 0..DeclarativeVsixParser.MAX_ENTRY_BYTES) { "Invalid VSIX resource" }
                val target = resolveWithin(staging, relative)
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output, 64 * 1024) }
                }
                require(target.length() <= DeclarativeVsixParser.MAX_ENTRY_BYTES) { "Extracted VSIX resource is too large" }
                hashes[relative] = sha256(target)
            }
        }
        return hashes
    }

    private fun verifyRecord(record: InstalledDeclarativeExtensionRecord): Boolean =
        verifyRecordAgainstRoot(record, resolveInstallRoot(record))

    private fun verifyRecordAgainstRoot(record: InstalledDeclarativeExtensionRecord, base: File): Boolean {
        if (!base.isDirectory) return false
        if (record.resourceSha256.size !in 1..DeclarativeVsixParser.MAX_RESOURCES + 1) return false
        return record.resourceSha256.all { (relative, expected) ->
            val file = runCatching { resolveWithin(base, relative) }.getOrNull() ?: return@all false
            file.isFile && file.length() <= DeclarativeVsixParser.MAX_ENTRY_BYTES && sha256(file) == expected
        }
    }

    private fun validateRecord(record: InstalledDeclarativeExtensionRecord) {
        require(record.schema == 1) { "Unsupported declarative extension record schema" }
        record.manifest.validate()
        require(record.manifest.runtime == ExtensionRuntimeKind.DECLARATIVE) { "Stored VSIX must be declarative" }
        require(record.extensionId == record.manifest.id && record.version == record.manifest.version) { "Stored VSIX metadata mismatch" }
        require(record.sourceSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid VSIX source hash" }
        require(record.resourceSha256.values.all { it.matches(Regex("[0-9a-f]{64}")) }) { "Invalid VSIX resource hash" }
        record.resourceSha256.keys.forEach(DeclarativeVsixParser::normalizeBundlePath)
        require(record.installDirectory.matches(Regex("packages/[a-z0-9._-]+/[A-Za-z0-9._+-]+"))) { "Invalid declarative install directory" }
    }

    private fun validateContributionOwnership(candidate: DroideExtensionManifest, existing: List<DroideExtensionManifest>) {
        val peers = existing + candidate
        fun unique(label: String, values: (DroideExtensionManifest) -> List<String>) {
            val owners = linkedMapOf<String, String>()
            peers.forEach { manifest ->
                values(manifest).forEach { value ->
                    val prior = owners.putIfAbsent(value, manifest.id)
                    require(prior == null || prior == manifest.id) { "Declarative $label collision for '$value': $prior and ${manifest.id}" }
                }
            }
        }
        unique("language") { it.contributes.languages.map(DroideLanguageContribution::id) }
        unique("grammar") { it.contributes.grammars.map(DroideGrammarContribution::language) }
        unique("theme") { it.contributes.themes.map(DroideThemeContribution::id) }
    }

    private fun readIndex(): DeclarativeExtensionIndex {
        if (!indexFile.exists()) return DeclarativeExtensionIndex()
        require(indexFile.isFile && !PathSecurity.isSymbolicLink(indexFile)) { "Declarative extension index is unsafe" }
        require(indexFile.length() <= 4L * 1024L * 1024L) { "Declarative extension index is too large" }
        return runCatching {
            json.decodeFromString<DeclarativeExtensionIndex>(indexFile.readText()).also { index ->
                require(index.schema == 1 && index.records.size <= 1_000) { "Invalid declarative extension index" }
            }
        }.getOrElse { failure -> throw IllegalStateException("Declarative extension index is corrupt", failure) }
    }

    private fun writeIndex(index: DeclarativeExtensionIndex) {
        root.mkdirs()
        val temp = File(root, ".index-${System.nanoTime()}.tmp")
        try {
            temp.writeText(json.encodeToString(index) + "\n")
            runCatching {
                Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun resolveInstallRoot(record: InstalledDeclarativeExtensionRecord): File = resolveWithin(root, record.installDirectory)

    private fun resolveWithin(base: File, relative: String): File {
        val normalized = DeclarativeVsixParser.normalizeBundlePath(relative)
        val canonicalBase = base.canonicalFile
        val target = File(canonicalBase, normalized).canonicalFile
        require(target.path == canonicalBase.path || target.path.startsWith(canonicalBase.path + File.separator)) { "Path escapes extension store" }
        return target
    }

    private fun safeSegment(raw: String): String = raw.filter { it.isLetterOrDigit() || it in "._-+" }.take(120).ifBlank { error("Invalid extension segment") }

    private fun moveDirectory(source: File, target: File) {
        target.parentFile?.mkdirs()
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            source.copyRecursively(target, overwrite = true)
            check(PathSecurity.deleteTreeNoFollow(source)) { "Failed to remove copied declarative extension source" }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
