package com.baystudio.droide.core

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Never lives inside a project and never participates in Git/SAF sync.
data class EditorSelectionSnapshot(val start: Int, val end: Int)

class EditorRecoveryBuffer internal constructor(
    val path: String,
    internal val blobName: String,
    internal val contentSha256: String?,
    internal val owner: Any,
    private val loader: suspend () -> String,
) {
    suspend fun readText(): String = loader()
}

data class EditorRecoverySnapshot(
    val openFiles: List<String>,
    val activeFile: String,
    val dirtyBuffers: Map<String, String>,
    val selections: Map<String, EditorSelectionSnapshot> = emptyMap(),
    val reviewFiles: Set<String> = emptySet(),
    val deferredBuffers: Map<String, EditorRecoveryBuffer> = emptyMap(),
) {
    val dirtyPaths: Set<String> get() = dirtyBuffers.keys + deferredBuffers.keys
}

class EditorStateStore(context: Context, projectId: String) {
    private val appFilesDir = context.applicationContext.filesDir
    private val safeProjectId = PathSecurity.safeLeafName(projectId)
    private val cipher = KeystoreBlobCipher("droide_editor_recovery_v1")
    private val aad = safeProjectId.toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = true }
    private val root = File(appFilesDir, ".droide/editor-recovery").apply { mkdirs() }
    private val file = File(root, "$safeProjectId.bin")
    private val blobs = File(root, "$safeProjectId.blobs")
    private val mutex = Mutex()
    private val legacyPlaintextFile = File(root, "$safeProjectId.json")

    init {
        // Never carry that format forward.
        if (legacyPlaintextFile.isFile) legacyPlaintextFile.delete()
    }

    suspend fun load(): EditorRecoverySnapshot? = mutex.withLock { withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        require(file.isFile && file.length() in 1..MAX_FILE_BYTES) { "Editor recovery manifest is invalid; original data was preserved" }
        try {
            val encrypted = file.readBytes()
            val plain = cipher.decrypt(encrypted, aad)
            require(plain.size <= MAX_PLAINTEXT_BYTES) { "Recovery payload is too large" }
            val rootObj = json.parseToJsonElement(plain.toString(Charsets.UTF_8)) as? JsonObject
                ?: error("Recovery payload is not an object")
            val version = (rootObj["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            require(version in 1..4) { "Unsupported editor recovery format: $version" }
            val open = (rootObj["open"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .filter(::validPath).distinct().take(MAX_OPEN_FILES)
            val active = (rootObj["active"] as? JsonPrimitive)?.contentOrNull?.takeIf(::validPath).orEmpty()
            val dirty = linkedMapOf<String, String>()
            val deferred = linkedMapOf<String, EditorRecoveryBuffer>()
            val arr = rootObj["dirty"] as? JsonArray ?: JsonArray(emptyList())
            var total = 0L
            require(arr.size <= MAX_DIRTY_FILES) { "Too many recovery documents" }
            for (item in arr) {
                val o = item as? JsonObject ?: error("Invalid recovery buffer entry")
                val path = (o["path"] as? JsonPrimitive)?.contentOrNull ?: error("Missing recovery buffer path")
                require(validPath(path) && path !in dirty && path !in deferred) { "Invalid or duplicated recovery buffer path" }
                if (version == 4) {
                    val id = (o["blob"] as? JsonPrimitive)?.contentOrNull
                    require(id != null && BLOB_NAME.matches(id)) { "Invalid recovery blob for $path" }
                    val blob = File(blobs, id)
                    require(blob.isFile && blob.length() in 1..MAX_ENCRYPTED_BUFFER_BYTES) { "Missing recovery buffer for $path" }
                    val hash = (o["sha256"] as? JsonPrimitive)?.contentOrNull
                    require(hash == null || hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid recovery checksum for $path" }
                    deferred[path] = EditorRecoveryBuffer(path, id, hash, this@EditorStateStore) {
                        mutex.withLock { withContext(Dispatchers.IO) { readBlob(path, id, hash) } }
                    }
                } else {
                    val content = (o["content"] as? JsonPrimitive)?.contentOrNull ?: error("Missing legacy recovery buffer")
                    val bytes = content.toByteArray(Charsets.UTF_8).size
                    require(bytes <= MAX_BUFFER_BYTES && total + bytes <= MAX_TOTAL_BUFFER_BYTES) { "Legacy recovery exceeds safety limit" }
                    dirty[path] = content
                    total += bytes
                }
            }
            val selections = linkedMapOf<String, EditorSelectionSnapshot>()
            if (version >= 2) {
                val selectionArray = rootObj["selections"] as? JsonArray ?: JsonArray(emptyList())
                for (item in selectionArray.take(MAX_SNAPSHOT_ENTRIES)) {
                    val o = item as? JsonObject ?: continue
                    val path = (o["path"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val start = (o["start"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: continue
                    val end = (o["end"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: start
                    if (validPath(path) && start in 0..MAX_SELECTION_OFFSET && end in 0..MAX_SELECTION_OFFSET)
                        selections[path] = EditorSelectionSnapshot(start, end)
                }
            }
            val reviewFiles = if (version >= 3) {
                (rootObj["review"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .filter(::validPath).distinct().take(MAX_SNAPSHOT_ENTRIES).toSet()
            } else emptySet()
            EditorRecoverySnapshot(open, active, dirty, selections, reviewFiles, deferred)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            
            throw IllegalStateException("Editor recovery could not be read; encrypted data was preserved", error)
        }
    } }

    suspend fun save(snapshot: EditorRecoverySnapshot) = mutex.withLock { withContext(Dispatchers.IO) {
        val open = snapshot.openFiles.filter(::validPath).distinct().take(MAX_OPEN_FILES)
        val active = snapshot.activeFile.takeIf(::validPath).orEmpty()
        require(snapshot.dirtyPaths.size <= MAX_DIRTY_FILES) { "Too many unsaved documents to recover" }
        check(blobs.isDirectory || blobs.mkdirs()) { "Cannot create recovery buffer storage" }
        syncDirectory(root)
        val created = mutableListOf<File>()
        var committed = false
        try {
        val previous = if (file.isFile) {
            require(file.length() in 1..MAX_FILE_BYTES) { "Existing editor recovery manifest is invalid" }
            val oldRoot = json.parseToJsonElement(cipher.decrypt(file.readBytes(), aad).toString(Charsets.UTF_8)) as JsonObject
            if ((oldRoot["version"] as? JsonPrimitive)?.contentOrNull == "4")
                (oldRoot["dirty"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val path = (obj["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    path to obj
                }.toMap()
            else emptyMap()
        } else emptyMap()
        val retained = hashSetOf<String>()
        val dirty = buildJsonArray {
            for ((path, content) in snapshot.dirtyBuffers) {
                require(validPath(path)) { "Invalid recovery path" }
                val bytes = content.toByteArray(Charsets.UTF_8).size
                require(bytes <= MAX_BUFFER_BYTES) { "Recovery buffer too large: $path" }
                val hash = sha256(content)
                val prior = previous[path]
                val priorId = (prior?.get("blob") as? JsonPrimitive)?.contentOrNull
                val priorHash = (prior?.get("sha256") as? JsonPrimitive)?.contentOrNull
                val id = if (priorHash == hash && priorId != null && BLOB_NAME.matches(priorId) && File(blobs, priorId).isFile)
                    priorId else UUID.randomUUID().toString() + ".bin"
                if (id != priorId || priorHash != hash) {
                    val blob = File(blobs, id)
                    atomicWrite(blob, cipher.encrypt(content.toByteArray(Charsets.UTF_8), blobAad(path)))
                    created += blob
                }
                retained += id
                add(buildJsonObject {
                    put("path", path)
                    put("blob", id)
                    put("sha256", hash)
                })
            }
            for ((path, reference) in snapshot.deferredBuffers) {
                require(path !in snapshot.dirtyBuffers && validPath(path) && reference.path == path && reference.owner === this@EditorStateStore) { "Invalid deferred recovery ownership" }
                val id = reference.blobName
                require(BLOB_NAME.matches(id) && File(blobs, id).isFile && File(blobs, id).length() in 1..MAX_ENCRYPTED_BUFFER_BYTES) { "Missing deferred recovery buffer: $path" }
                val hash = reference.contentSha256 ?: sha256(readBlob(path, id, null))
                retained += id
                add(buildJsonObject { put("path", path); put("blob", id); put("sha256", hash) })
            }
        }
        val selections = buildJsonArray {
            val relevant = (open + snapshot.dirtyPaths).toSet()
            snapshot.selections.entries.filter { validPath(it.key) && it.key in relevant }
                .take(MAX_SNAPSHOT_ENTRIES).forEach { (path, selection) ->
                add(buildJsonObject {
                    put("path", path)
                    put("start", selection.start.coerceIn(0, MAX_SELECTION_OFFSET))
                    put("end", selection.end.coerceIn(0, MAX_SELECTION_OFFSET))
                })
            }
        }
        val rootObj = buildJsonObject {
            put("version", 4)
            put("active", active)
            put("open", buildJsonArray { open.forEach { add(JsonPrimitive(it)) } })
            put("dirty", dirty)
            put("selections", selections)
            put("review", buildJsonArray {
                val relevant = (open + snapshot.dirtyPaths).toSet()
                snapshot.reviewFiles.filter { validPath(it) && it in relevant }
                    .take(MAX_SNAPSHOT_ENTRIES).forEach { add(JsonPrimitive(it)) }
            })
        }
        val text = rootObj.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Recovery state too large" }
        val plain = text.toByteArray(Charsets.UTF_8)
        val encrypted = cipher.encrypt(plain, aad)
        require(encrypted.size <= MAX_FILE_BYTES) { "Encrypted recovery state too large" }
        

        committed = true
        atomicWrite(file, encrypted)
        
        blobs.listFiles()?.filter { it.name !in retained }?.forEach { it.delete() }
        } catch (error: Throwable) {
            
            if (!committed) created.forEach { it.delete() }
            throw error
        }
    } }

    suspend fun clear() = mutex.withLock { withContext(Dispatchers.IO) {
        if (file.exists()) check(file.delete()) { "Cannot delete editor recovery state" }
        if (legacyPlaintextFile.exists()) check(legacyPlaintextFile.delete()) { "Cannot delete legacy editor recovery state" }
        blobs.listFiles()?.forEach { it.delete() }
        blobs.delete()
    } }

    private fun readBlob(path: String, id: String, expected: String?): String {
        val blob = File(blobs, id)
        require(BLOB_NAME.matches(id) && blob.isFile && blob.length() in 1..MAX_ENCRYPTED_BUFFER_BYTES) { "Invalid recovery blob: $path" }
        val plain = cipher.decrypt(blob.readBytes(), blobAad(path))
        require(plain.size <= MAX_BUFFER_BYTES) { "Recovery buffer too large: $path" }
        val content = plain.toString(Charsets.UTF_8)
        if (expected != null) require(sha256(content) == expected) { "Recovery buffer checksum failed: $path" }
        return content
    }

    private fun blobAad(path: String): ByteArray = (safeProjectId + "\u0000" + path).toByteArray(Charsets.UTF_8)

    private fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun validPath(path: String): Boolean {
        if (path.isBlank() || path.length > 1_024 || File(path).isAbsolute || '\u0000' in path) return false
        return path.split('/').none { it == "." || it == ".." }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        root.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            syncDirectory(target.parentFile)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    companion object {
        private const val MAX_OPEN_FILES = 32
        private const val MAX_SELECTION_OFFSET = 100_000_000
        private const val MAX_DIRTY_FILES = 256
        private const val MAX_SNAPSHOT_ENTRIES = MAX_OPEN_FILES + MAX_DIRTY_FILES
        // Recovery must not become the hidden bottleneck after the editor enters large-file mode.
        private const val MAX_BUFFER_BYTES = 20 * 1024 * 1024
        private const val MAX_TOTAL_BUFFER_BYTES = 40 * 1024 * 1024
        private const val MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024
        private const val MAX_FILE_BYTES = 65L * 1024L * 1024L
        private const val MAX_ENCRYPTED_BUFFER_BYTES = 21L * 1024L * 1024L
        private val BLOB_NAME = Regex("[0-9a-f-]{36}\\.bin")
    }
}
