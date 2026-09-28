package com.baystudio.droide.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID









class LocalGgufModelStore(private val requestedRoot: File) {
    companion object {
        const val MAX_MODEL_BYTES: Long = 96L * 1024 * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val MIN_FREE_MARGIN_BYTES = 64L * 1024 * 1024
        private const val STALE_STAGING_AGE_MS = 6L * 60 * 60 * 1000
        private val MODEL_FILE = Regex("^[0-9a-f]{64}\\.gguf$")
    }

    data class ImportedModel(
        val id: String,
        val displayName: String,
        val file: File,
        val sha256: String,
        val sizeBytes: Long,
        val header: GgufHeaderProbe.Summary,
        val importedAtEpochMs: Long,
    )

    data class StoredModel(
        val id: String,
        val displayName: String,
        val file: File,
        val sizeBytes: Long,
        val header: GgufHeaderProbe.Summary,
    )

    private val root: File
    private val staging: File

    init {
        requestedRoot.mkdirs()
        require(requestedRoot.isDirectory) { "Local GGUF store is unavailable" }
        require(!PathSecurity.isSymbolicLink(requestedRoot)) { "Local GGUF store must not be a symbolic link" }
        root = requestedRoot.canonicalFile
        staging = PathSecurity.resolveWithin(root, ".staging")
        staging.mkdirs()
        require(staging.isDirectory && !PathSecurity.isSymbolicLink(staging)) { "Local GGUF staging directory is unavailable" }
        cleanupStaleStaging()
    }

    fun import(
        input: InputStream,
        displayName: String,
        declaredSizeBytes: Long? = null,
        checkCancelled: () -> Unit = {},
        onProgress: (copiedBytes: Long, declaredSizeBytes: Long?) -> Unit = { _, _ -> },
    ): ImportedModel {
        val safeDisplayName = sanitizeDisplayName(displayName)
        val declared = declaredSizeBytes?.takeIf { it >= 0 }
        require(declared == null || declared in GgufHeaderProbe.HEADER_BYTES.toLong()..MAX_MODEL_BYTES) {
            "GGUF file size is outside the supported import range"
        }
        ensureSpaceFor(declared)

        val stage = Files.createTempFile(staging.toPath(), "gguf-${UUID.randomUUID()}-", ".part").toFile()
        require(PathSecurity.contains(root, stage) && !PathSecurity.isSymbolicLink(stage)) { "Unsafe GGUF staging path" }

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            FileOutputStream(stage, false).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    checkCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    copied = Math.addExact(copied, read.toLong())
                    require(copied <= MAX_MODEL_BYTES) { "GGUF file exceeds the supported import size" }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    onProgress(copied, declared)
                }
                output.flush()
                output.fd.sync()
            }

            require(copied >= GgufHeaderProbe.HEADER_BYTES) { "GGUF file is truncated" }
            if (declared != null) require(copied == declared) {
                "GGUF import size changed while reading ($copied != $declared)"
            }
            val stagedHeader = GgufHeaderProbe.inspect(stage)
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val finalFile = PathSecurity.resolveWithin(root, "$sha.gguf")

            if (finalFile.exists()) {
                require(finalFile.isFile && !PathSecurity.isSymbolicLink(finalFile)) { "Stored GGUF path is unsafe" }
                require(finalFile.length() == copied) { "Stored GGUF digest path has a conflicting size" }
                require(sha256(finalFile, checkCancelled) == sha) { "Stored GGUF content failed integrity verification" }
                GgufHeaderProbe.inspect(finalFile)
                stage.delete()
            } else {
                publish(stage, finalFile)
            }

            val finalHeader = GgufHeaderProbe.inspect(finalFile)
            require(finalHeader == stagedHeader) { "GGUF header changed during import publication" }
            persistDisplayName(sha, safeDisplayName)
            val now = Instant.now().toEpochMilli()
            val result = ImportedModel(
                id = sha,
                displayName = safeDisplayName,
                file = finalFile,
                sha256 = sha,
                sizeBytes = copied,
                header = finalHeader,
                importedAtEpochMs = now,
            )
            require(listStored().any { it.id == result.id && it.file == result.file }) {
                "GGUF import was not durably discoverable after publication"
            }
            return result
        } catch (t: Throwable) {
            runCatching { stage.delete() }
            throw t
        }
    }

    fun findStored(modelId: String): StoredModel? {
        require(modelId.matches(Regex("^[0-9a-f]{64}$"))) { "Invalid local GGUF model id" }
        val file = PathSecurity.resolveWithin(root, "$modelId.gguf")
        if (!file.isFile || PathSecurity.isSymbolicLink(file)) return null
        return runCatching {
            StoredModel(
                id = modelId,
                displayName = readDisplayName(modelId) ?: "${modelId.take(12)}.gguf",
                file = file.canonicalFile,
                sizeBytes = file.length(),
                header = GgufHeaderProbe.inspect(file),
            )
        }.getOrNull()
    }

    fun listStored(): List<StoredModel> =
        root.listFiles().orEmpty().asSequence()
            .filter { MODEL_FILE.matches(it.name) }
            .mapNotNull { findStored(it.name.removeSuffix(".gguf")) }
            .sortedBy { it.displayName.lowercase() }
            .toList()

    fun verifyIntegrity(modelId: String, checkCancelled: () -> Unit = {}): Boolean {
        require(modelId.matches(Regex("^[0-9a-f]{64}$"))) { "Invalid local GGUF model id" }
        val file = PathSecurity.resolveWithin(root, "$modelId.gguf")
        if (!file.isFile || PathSecurity.isSymbolicLink(file)) return false
        if (runCatching { GgufHeaderProbe.inspect(file) }.isFailure) return false
        return sha256(file, checkCancelled) == modelId
    }

    private fun publish(stage: File, destination: File) {
        require(PathSecurity.contains(root, destination)) { "GGUF destination escaped the model store" }
        try {
            Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!stage.renameTo(destination)) error("Could not publish imported GGUF model")
        } catch (_: FileAlreadyExistsException) {
            // A concurrent import of the same bytes won the race.
            stage.delete()
        }
        require(destination.isFile && !PathSecurity.isSymbolicLink(destination)) { "Imported GGUF was not published" }
    }

    private fun persistDisplayName(modelId: String, displayName: String) {
        val target = PathSecurity.resolveWithin(root, "$modelId.name")
        if (target.exists()) return
        val temp = Files.createTempFile(staging.toPath(), "name-$modelId-", ".tmp").toFile()
        try {
            FileOutputStream(temp, false).use { output ->
                output.write(displayName.toByteArray(Charsets.UTF_8))
                output.write('\n'.code)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                if (!temp.renameTo(target)) error("Could not publish local GGUF display name")
            } catch (_: FileAlreadyExistsException) {
                temp.delete()
            }
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun readDisplayName(modelId: String): String? {
        val file = PathSecurity.resolveWithin(root, "$modelId.name")
        if (!file.isFile || PathSecurity.isSymbolicLink(file) || file.length() > 1024L) return null
        return runCatching { sanitizeDisplayName(file.readText(Charsets.UTF_8).lineSequence().firstOrNull().orEmpty()) }.getOrNull()
    }

    private fun sanitizeDisplayName(raw: String): String {
        val leaf = raw.trim().substringAfterLast('/').substringAfterLast('\\').trim()
        require(leaf.isNotBlank()) { "GGUF display name is empty" }
        require(leaf.none { it == '\u0000' || it == '\r' || it == '\n' || it.code < 0x20 || it.code == 0x7f }) {
            "GGUF display name contains control characters"
        }
        return leaf.take(240)
    }

    private fun ensureSpaceFor(declaredSize: Long?) {
        if (declaredSize == null) return
        val margin = maxOf(MIN_FREE_MARGIN_BYTES, minOf(declaredSize / 20, 2L * 1024 * 1024 * 1024))
        val required = Math.addExact(declaredSize, margin)
        val usable = root.usableSpace
        if (usable > 0) require(usable >= required) {
            "Not enough free storage to import this GGUF model safely"
        }
    }

    private fun cleanupStaleStaging(nowMs: Long = System.currentTimeMillis()) {
        staging.listFiles().orEmpty().forEach { candidate ->
            if (!candidate.isFile || PathSecurity.isSymbolicLink(candidate)) return@forEach
            if (!candidate.name.endsWith(".part") && !candidate.name.endsWith(".tmp")) return@forEach
            if (nowMs - candidate.lastModified() >= STALE_STAGING_AGE_MS) runCatching { candidate.delete() }
        }
    }

    private fun sha256(file: File, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
