package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class PendingSafWrite(val path: String, val previous: String?, val intended: String)

// App-private, durable before-write journal for providers without atomic replace.
internal class SafWriteJournal(
    private val context: Context,
    uri: Uri,
    localRoot: File,
    private val find: (String) -> DocumentFile?,
    private val ensureParent: (String) -> Pair<DocumentFile, String>,
    private val currentManifest: () -> Map<String, String>,
    private val fingerprintExternal: (DocumentFile, String) -> String,
) {
    private val recoveryRoot = File(context.applicationContext.filesDir, ".droide/saf-write-recovery/" +
        MessageDigest.getInstance("SHA-256").digest((uri.toString() + "\u0000" + localRoot.absolutePath).toByteArray()).toHex())
    private val json = Json { ignoreUnknownKeys = true }

    fun writeFile(path: String, local: File, checkCancelled: () -> Unit = {}): String {
        require(local.isFile) { "Local source is not a file: $path" }
        val (parent, leaf) = ensureParent(path)
        var target = parent.findFile(leaf)
        if (target?.isDirectory == true) {
            if (!target.delete()) throw IllegalStateException("Cannot replace SAF directory: $path")
            target = null
        }
        // Both the old provider bytes and the intended new bytes are durable before "wt" can truncate the provider document.

        val entry = File(recoveryRoot, UUID.randomUUID().toString())
        check(entry.mkdirs()) { "Cannot create SAF recovery journal" }
        try {
            val oldHash = if (target?.isFile == true) copySafToPrivate(target, File(entry, "previous"), path) else null
            val newHash = copyPrivateFile(local, File(entry, "intended"))
            val record = PendingSafWrite(path, oldHash, newHash)
            val metadata = File(entry, "pending.json")
            val tmp = File(entry, "pending.tmp")
            FileOutputStream(tmp).use { output ->
                output.write(json.encodeToString(PendingSafWrite.serializer(), record).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            replace(tmp, metadata)
            
            var directory: File? = entry
            while (directory != null && directory != context.applicationContext.filesDir) {
                syncDirectory(directory)
                directory = directory.parentFile
            }
            try {
                if (target != null && fingerprintExternal(target, path) != oldHash) {
                    error("SAF original changed while preparing its backup: $path")
                }
                if (target == null) {
                    val mime = URLConnection.guessContentTypeFromName(leaf) ?: "application/octet-stream"
                    target = parent.createFile(mime, leaf) ?: throw IllegalStateException("Cannot create SAF file: $path")
                }
                writeSafFromPrivate(target, File(entry, "intended"), path, checkCancelled)
                check(fingerprintExternal(target, path) == newHash) { "SAF provider did not retain the complete write: $path" }
                return newHash
            } catch (failure: Throwable) {
                // An immediate rollback is best-effort; a failed rollback retains both payloads.
                val rolledBack = runCatching { restorePrevious(entry, record); true }.getOrDefault(false)
                if (rolledBack) PathSecurity.deleteTreeNoFollow(entry)
                if (!rolledBack) throw IllegalStateException(
                    "SAF write failed for $path; original and intended bytes remain in the recovery journal. Sync is blocked pending reconciliation.", failure
                )
                throw failure
            }
        } catch (failure: Throwable) {
            if (!File(entry, "pending.json").exists()) PathSecurity.deleteTreeNoFollow(entry)
            throw failure
        }
    }

    private fun fingerprintPrivateFile(source: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) digest.update(buffer, 0, n)
            }
        }
        return "f:${digest.digest().toHex()}"
    }

    private fun copyPrivateFile(source: File, destination: File): String {
        require(source.isFile && !PathSecurity.isSymbolicLink(source)) { "Invalid SAF write payload" }
        require(recoveryRoot.usableSpace > source.length() + MIN_JOURNAL_FREE_BYTES) { "Insufficient space for SAF recovery payload" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(destination).use { output ->
            source.inputStream().use { input -> copyHashed(input, output, digest) }
            output.fd.sync()
        }
        return "f:${digest.digest().toHex()}"
    }

    private fun copySafToPrivate(source: DocumentFile, destination: File, path: String): String {
        val size = source.length().coerceAtLeast(0)
        require(recoveryRoot.usableSpace > size + MIN_JOURNAL_FREE_BYTES) { "Insufficient space for SAF original backup: $path" }
        val input = context.contentResolver.openInputStream(source.uri)
            ?: throw IllegalStateException("Cannot back up SAF original before writing: $path")
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(destination).use { output ->
            input.use { copyHashed(it, output, digest) }
            output.fd.sync()
        }
        return "f:${digest.digest().toHex()}"
    }

    private fun copyHashed(input: java.io.InputStream, output: java.io.OutputStream, digest: MessageDigest) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            digest.update(buffer, 0, n)
            output.write(buffer, 0, n)
        }
    }

    private fun writeSafFromPrivate(target: DocumentFile, payload: File, path: String, checkCancelled: () -> Unit = {}) {
        val output = context.contentResolver.openOutputStream(target.uri, "wt")
            ?: throw IllegalStateException("Cannot open SAF document for writing: $path")
        payload.inputStream().buffered(COPY_BUFFER_BYTES).use { input ->
            output.use { out ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) out.write(buffer, 0, n)
                }
            }
        }
    }

    private fun restorePrevious(entry: File, record: PendingSafWrite) {
        val current = find(record.path)
        if (record.previous == null) {
            if (current != null) check(current.delete()) { "Cannot remove incomplete SAF creation: ${record.path}" }
            check(find(record.path) == null) { "SAF creation rollback was not verified" }
            return
        }
        val backup = File(entry, "previous")
        check(backup.isFile && fingerprintPrivateFile(backup) == record.previous) { "SAF recovery backup is incomplete" }
        val (parent, leaf) = ensureParent(record.path)
        val target = current ?: parent.createFile(URLConnection.guessContentTypeFromName(leaf) ?: "application/octet-stream", leaf)
            ?: error("Cannot recreate SAF original: ${record.path}")
        writeSafFromPrivate(target, backup, record.path)
        check(fingerprintExternal(target, record.path) == record.previous) { "SAF original restore could not be verified" }
    }

    fun recoverPendingWrites() {
        if (!recoveryRoot.exists()) return
        val known = currentManifest()
        for (entry in recoveryRoot.listFiles().orEmpty().filter { it.isDirectory }) {
            val metadata = File(entry, "pending.json")
            
            if (!metadata.isFile) { PathSecurity.deleteTreeNoFollow(entry); continue }
            val record = json.decodeFromString(PendingSafWrite.serializer(), metadata.readText())
            require(record.path.isNotBlank() && !record.path.startsWith('/') &&
                record.path.split('/').none { it == "." || it == ".." }) { "Invalid SAF recovery path" }
            val current = find(record.path)?.takeIf { it.isFile }
            val actual = current?.let { fingerprintExternal(it, record.path) }
            when {
                actual == record.previous -> PathSecurity.deleteTreeNoFollow(entry)
                actual == record.intended && known[record.path] == record.intended -> PathSecurity.deleteTreeNoFollow(entry)
                actual == record.intended -> {
                    restorePrevious(entry, record)
                    PathSecurity.deleteTreeNoFollow(entry)
                }
                else -> error("SAF write recovery pending for ${record.path}: external content differs from both saved versions. Original and intended bytes were retained; resolve the external change before syncing.")
            }
        }
    }

    fun clearCommittedWrites() {
        recoveryRoot.listFiles()?.filter { it.isDirectory }?.forEach { PathSecurity.deleteTreeNoFollow(it) }
    }

    private fun replace(tmp: File, target: File) {
        runCatching { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val MIN_JOURNAL_FREE_BYTES = 192L * 1024 * 1024
    }
}
