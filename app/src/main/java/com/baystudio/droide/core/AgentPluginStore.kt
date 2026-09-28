package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private data class ParsedAgentPluginArchive(
    val id: String,
    val displayName: String,
    val version: String,
    val description: String,
    val format: AgentPluginFormat,
    val rootPrefix: String,
    val entries: List<String>,
)

@Serializable
private data class AgentPluginIndex(
    val schema: Int = 1,
    val records: List<InstalledAgentPluginRecord> = emptyList(),
)

// App-private authoritative store for Agent customization bundles.
class AgentPluginStore(context: Context) {
    private val root = File(context.filesDir, "agent-plugins")
    private val packages = File(root, "packages")
    private val indexFile = File(root, "index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Synchronized
    fun listVerified(): List<InstalledAgentPluginRecord> = readIndex().records.mapNotNull { record ->
        runCatching { validateRecord(record); record.takeIf { verifyRecord(record) } }.getOrNull()
    }

    @Synchronized
    fun install(archive: File): InstalledAgentPluginRecord {
        require(archive.isFile && archive.length() in 1..MAX_ARCHIVE_BYTES) { "Agent plugin archive is missing or exceeds safe limit" }
        val parsed = parseArchive(archive)
        val safeId = safeSegment(parsed.id)
        val staging = File(root, ".staging-$safeId-${System.nanoTime()}")
        check(staging.mkdirs()) { "Cannot create Agent plugin staging directory" }
        try {
            val hashes = extractArchive(archive, parsed, staging)
            AgentPluginCompatibilityValidator.validate(staging, parsed.format)
            val aggregate = aggregateHash(hashes)
            val version = parsed.version.ifBlank { "0.0.0-local-${aggregate.take(12)}" }
            val safeVersion = safeSegment(version)
            val relativeInstall = "packages/$safeId/$safeVersion-${aggregate.take(12)}"
            val finalDir = File(root, relativeInstall)
            val record = InstalledAgentPluginRecord(
                id = parsed.id,
                displayName = parsed.displayName,
                version = version,
                description = parsed.description,
                format = parsed.format.name,
                contentSha256 = aggregate,
                installedAtMs = System.currentTimeMillis(),
                installDirectory = relativeInstall,
                files = hashes,
            )
            validateRecord(record)
            check(verifyRecordAgainstRoot(record, staging)) { "Agent plugin staging integrity check failed" }

            val current = readIndex()
            require(current.records.count { it.id != record.id } < MAX_INSTALLED_PLUGINS) { "Too many Agent plugins installed" }
            finalDir.parentFile?.mkdirs()
            val previous = current.records.firstOrNull { it.id == record.id }
            val backup = previous?.let { resolveInstallRoot(it).takeIf(File::exists) }?.let {
                File(root, ".backup-$safeId-${System.nanoTime()}").also { backup -> moveDirectory(it, backup) }
            }
            try {
                if (finalDir.exists()) check(PathSecurity.deleteTreeNoFollow(finalDir)) { "Failed to clear prior Agent plugin destination" }
                moveDirectory(staging, finalDir)
                val records = (current.records.filterNot { it.id == record.id } + record).sortedBy { it.id }
                writeIndex(AgentPluginIndex(records = records))
                backup?.let { PathSecurity.deleteTreeNoFollow(it) }
                previous?.takeIf { it.installDirectory != record.installDirectory }?.let { old ->
                    runCatching { PathSecurity.deleteTreeNoFollow(resolveInstallRoot(old)) }
                }
                return record
            } catch (t: Throwable) {
                val rollbackFailures = mutableListOf<Throwable>()
                runCatching {
                    if (finalDir.exists()) check(PathSecurity.deleteTreeNoFollow(finalDir)) { "Failed to remove failed Agent plugin install" }
                }.exceptionOrNull()?.let(rollbackFailures::add)
                if (backup != null && backup.exists()) {
                    runCatching { moveDirectory(backup, resolveInstallRoot(requireNotNull(previous))) }
                        .exceptionOrNull()?.let(rollbackFailures::add)
                }
                rollbackFailures.forEach(t::addSuppressed)
                throw t
            }
        } finally {
            if (staging.exists()) PathSecurity.deleteTreeNoFollow(staging)
        }
    }

    @Synchronized
    fun uninstall(id: String) {
        val index = readIndex()
        val target = index.records.firstOrNull { it.id == id } ?: return
        val installRoot = resolveInstallRoot(target)
        val tombstone = File(root, ".uninstall-${safeSegment(id)}-${System.nanoTime()}")
        var detached = false
        if (installRoot.exists()) {
            moveDirectory(installRoot, tombstone)
            detached = true
        }
        try {
            // If the atomic index write fails, restore the previous payload before surfacing error.

            writeIndex(AgentPluginIndex(records = index.records.filterNot { it.id == id }))
        } catch (t: Throwable) {
            if (detached && tombstone.exists()) {
                runCatching { moveDirectory(tombstone, installRoot) }
                    .exceptionOrNull()?.let(t::addSuppressed)
            }
            throw t
        }
        if (detached) runCatching { PathSecurity.deleteTreeNoFollow(tombstone) }
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        val index = readIndex()
        val target = index.records.firstOrNull { it.id == id } ?: return
        val updated = index.records.map { if (it.id == id) target.copy(enabled = enabled) else it }
        writeIndex(AgentPluginIndex(records = updated))
    }

    fun installRoot(record: InstalledAgentPluginRecord): File = resolveInstallRoot(record)

    private fun parseArchive(archive: File): ParsedAgentPluginArchive = ZipFile(archive).use { zip ->
        val files = zip.entries().asSequence().filterNot(ZipEntry::isDirectory).toList()
        require(files.size in 1..MAX_ENTRIES) { "Agent plugin archive has too many files" }
        var declared = 0L
        files.forEach { entry ->
            val rel = normalizeArchivePath(entry.name)
            require(rel.isNotBlank()) { "Invalid Agent plugin archive path" }
            if (entry.size >= 0) {
                require(entry.size <= MAX_ENTRY_BYTES) { "Agent plugin entry exceeds safe limit: $rel" }
                declared += entry.size
                require(declared <= MAX_UNCOMPRESSED_BYTES) { "Agent plugin archive exceeds uncompressed limit" }
            }
        }
        val names = files.map { normalizeArchivePath(it.name) }.toSet()
        val manifest = manifestCandidate(names) ?: error("Agent plugin manifest not found")
        val manifestEntry = zip.getEntry(manifest.first) ?: error("Agent plugin manifest disappeared")
        val manifestText = readBounded(zip, manifestEntry, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
        require('\u0000' !in manifestText) { "Agent plugin manifest contains NUL" }
        val obj = json.parseToJsonElement(manifestText) as? JsonObject ?: error("Agent plugin manifest must be an object")
        val idRaw = obj.text("id") ?: obj.text("name") ?: manifest.second.substringBefore('/').ifBlank { "plugin" }
        val id = normalizeId(idRaw)
        val displayName = (obj.text("displayName") ?: obj.text("display_name") ?: obj.text("name") ?: id).trim().take(120)
        val version = (obj.text("version") ?: "").trim().take(80)
        require(version.isEmpty() || VERSION.matches(version)) { "Invalid Agent plugin version" }
        val description = (obj.text("description") ?: "").trim().take(1_000)
        val prefix = manifest.second
        val normalizedFiles = names.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotBlank() }
            .sorted()
            .toList()
        require(normalizedFiles.isNotEmpty()) { "Agent plugin archive is empty" }
        ParsedAgentPluginArchive(id, displayName, version, description, manifest.third, prefix, normalizedFiles)
    }

     
    private fun manifestCandidate(names: Set<String>): Triple<String, String, AgentPluginFormat>? {
        val candidates = mutableListOf<Triple<String, String, AgentPluginFormat>>()
        names.forEach { path ->
            when {
                path == "droide-agent-plugin.json" -> candidates += Triple(path, "", AgentPluginFormat.DROIDE)
                path.endsWith("/droide-agent-plugin.json") && path.count { it == '/' } == 1 -> candidates += Triple(path, path.substringBeforeLast('/') + "/", AgentPluginFormat.DROIDE)
                path == ".droide-plugin/plugin.json" -> candidates += Triple(path, "", AgentPluginFormat.DROIDE)
                path.endsWith("/.droide-plugin/plugin.json") && path.count { it == '/' } == 2 -> candidates += Triple(path, path.substringBefore(".droide-plugin/plugin.json"), AgentPluginFormat.DROIDE)
                path.endsWith("/.claude-plugin/plugin.json") || path == ".claude-plugin/plugin.json" -> {
                    val suffix = ".claude-plugin/plugin.json"
                    val prefix = path.removeSuffix(suffix)
                    if (prefix.count { it == '/' } <= 1) candidates += Triple(path, prefix, AgentPluginFormat.CLAUDE_CODE)
                }
                path == "plugin.json" -> candidates += Triple(path, "", AgentPluginFormat.ANTIGRAVITY)
                path.count { it == '/' } == 1 && path.endsWith("/plugin.json") -> {
                    candidates += Triple(path, path.substringBeforeLast('/') + "/", AgentPluginFormat.ANTIGRAVITY)
                }
            }
        }
        val priority = mapOf(AgentPluginFormat.DROIDE to 0, AgentPluginFormat.CLAUDE_CODE to 1, AgentPluginFormat.ANTIGRAVITY to 2)
        return candidates.sortedWith(compareBy<Triple<String, String, AgentPluginFormat>> { priority[it.third] ?: 9 }.thenBy { it.second.length }.thenBy { it.first }).firstOrNull()
    }

    private fun extractArchive(archive: File, parsed: ParsedAgentPluginArchive, staging: File): Map<String, String> {
        val hashes = linkedMapOf<String, String>()
        var total = 0L
        ZipFile(archive).use { zip ->
            parsed.entries.forEach { relative ->
                val sourceName = parsed.rootPrefix + relative
                val entry = zip.getEntry(sourceName) ?: error("Agent plugin resource disappeared: $relative")
                require(!entry.isDirectory) { "Agent plugin resource is a directory" }
                val target = resolveWithin(staging, relative)
                target.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var entryTotal = 0L
                zip.getInputStream(entry).buffered().use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            entryTotal += read
                            total += read
                            require(entryTotal <= MAX_ENTRY_BYTES && total <= MAX_UNCOMPRESSED_BYTES) { "Agent plugin archive expands beyond safe limit" }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                hashes[relative] = digest.digest().hex()
            }
        }
        require(hashes.size in 1..MAX_ENTRIES) { "Invalid Agent plugin resource count" }
        return hashes
    }

    private fun verifyRecord(record: InstalledAgentPluginRecord): Boolean = verifyRecordAgainstRoot(record, resolveInstallRoot(record))

    private fun verifyRecordAgainstRoot(record: InstalledAgentPluginRecord, base: File): Boolean {
        if (!base.isDirectory || record.files.size !in 1..MAX_ENTRIES) return false
        if (aggregateHash(record.files) != record.contentSha256) return false
        return record.files.all { (relative, expected) ->
            val file = runCatching { resolveWithin(base, relative) }.getOrNull() ?: return@all false
            file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() <= MAX_ENTRY_BYTES && sha256(file) == expected
        }
    }

    private fun validateRecord(record: InstalledAgentPluginRecord) {
        require(record.schema == 1) { "Unsupported Agent plugin record schema" }
        normalizeId(record.id).also { require(it == record.id) { "Invalid Agent plugin id" } }
        require(record.displayName.isNotBlank() && record.displayName.length <= 120) { "Invalid Agent plugin display name" }
        require(record.version.isNotBlank() && record.version.length <= 80) { "Invalid Agent plugin version" }
        require(runCatching { AgentPluginFormat.valueOf(record.format) }.isSuccess) { "Invalid Agent plugin format" }
        require(record.contentSha256.matches(SHA256)) { "Invalid Agent plugin content hash" }
        require(record.files.values.all { it.matches(SHA256) }) { "Invalid Agent plugin file hash" }
        record.files.keys.forEach(::normalizeArchivePath)
        require(record.installDirectory.matches(Regex("packages/[a-z0-9._-]+/[A-Za-z0-9._+-]+"))) { "Invalid Agent plugin install directory" }
    }

    private fun readIndex(): AgentPluginIndex {
        if (!indexFile.exists()) return AgentPluginIndex()
        require(indexFile.isFile && !PathSecurity.isSymbolicLink(indexFile)) { "Agent plugin index is unsafe" }
        require(indexFile.length() <= 4L * 1024L * 1024L) { "Agent plugin index is too large" }
        return runCatching {
            json.decodeFromString<AgentPluginIndex>(indexFile.readText()).also {
                require(it.schema == 1 && it.records.size <= MAX_INSTALLED_PLUGINS) { "Invalid Agent plugin index" }
            }
        }.getOrElse { failure -> throw IllegalStateException("Agent plugin index is corrupt", failure) }
    }

    private fun writeIndex(index: AgentPluginIndex) {
        root.mkdirs()
        val temp = File(root, ".index-${System.nanoTime()}.tmp")
        try {
            temp.writeText(json.encodeToString(index) + "\n")
            runCatching { Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                .getOrElse { Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun resolveInstallRoot(record: InstalledAgentPluginRecord): File = resolveWithin(root, record.installDirectory)

    private fun resolveWithin(base: File, relative: String): File {
        val normalized = normalizeArchivePath(relative)
        val canonicalBase = base.canonicalFile
        val target = File(canonicalBase, normalized).canonicalFile
        require(target.path == canonicalBase.path || target.path.startsWith(canonicalBase.path + File.separator)) { "Agent plugin path escapes store" }
        return target
    }

    private fun normalizeArchivePath(raw: String): String {
        require(raw.isNotBlank() && '\u0000' !in raw && '\\' !in raw && !raw.startsWith('/')) { "Invalid Agent plugin path" }
        val segments = raw.split('/')
        require(segments.all { it.isNotBlank() && it != "." && it != ".." }) { "Invalid Agent plugin path" }
        val normalized = segments.joinToString("/")
        require(normalized.length <= 512) { "Agent plugin path too long" }
        return normalized
    }

    private fun normalizeId(raw: String): String {
        val id = raw.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(96)
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,95}"))) { "Invalid Agent plugin id" }
        return id
    }

    private fun safeSegment(raw: String): String = raw.filter { it.isLetterOrDigit() || it in "._-+" }.take(120).ifBlank { error("Invalid Agent plugin segment") }

    private fun readBounded(zip: ZipFile, entry: ZipEntry, max: Long): ByteArray {
        require(entry.size < 0 || entry.size <= max) { "Agent plugin manifest too large" }
        return zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= max) { "Agent plugin manifest too large" }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    private fun aggregateHash(files: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (path, hash) ->
            digest.update(path.toByteArray(Charsets.UTF_8)); digest.update(0)
            digest.update(hash.toByteArray(Charsets.US_ASCII)); digest.update('\n'.code.toByte())
        }
        return digest.digest().hex()
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
        return digest.digest().hex()
    }

    private fun moveDirectory(source: File, target: File) {
        target.parentFile?.mkdirs()
        runCatching { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }.getOrElse {
            source.copyRecursively(target, overwrite = true)
            check(PathSecurity.deleteTreeNoFollow(source)) { "Failed to remove copied Agent plugin source" }
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
        const val MAX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L
        const val MAX_ENTRY_BYTES = 4L * 1024L * 1024L
        const val MAX_ENTRIES = 2_048
        const val MAX_INSTALLED_PLUGINS = 32
        private const val MAX_MANIFEST_BYTES = 256L * 1024L
        private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,79}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
