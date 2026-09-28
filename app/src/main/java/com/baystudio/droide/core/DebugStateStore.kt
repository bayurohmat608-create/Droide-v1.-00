package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

// It never participates in project Git or SAF sync.
data class DebugPersistentState(
    val breakpoints: Map<String, List<Int>>,
    val watches: List<String>,
)

class DebugStateStore(context: Context, projectId: String) {
    private val safeProjectId = PathSecurity.safeLeafName(projectId)
    private val cipher = KeystoreBlobCipher("droide_debug_state_v1")
    private val aad = safeProjectId.toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = true }
    private val root = File(context.applicationContext.filesDir, ".droide/debug-state").apply { mkdirs() }
    private val file = File(root, "$safeProjectId.bin")

    suspend fun load(): DebugPersistentState? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return@withContext null
        try {
            val plain = cipher.decrypt(file.readBytes(), aad)
            require(plain.size <= MAX_PLAINTEXT_BYTES) { "Debug state too large" }
            val obj = json.parseToJsonElement(plain.toString(Charsets.UTF_8)) as? JsonObject ?: return@withContext null
            if (obj["version"]?.jsonPrimitive?.intOrNull != VERSION) return@withContext null

            val breakpoints = linkedMapOf<String, List<Int>>()
            val bpObj = obj["breakpoints"] as? JsonObject ?: buildJsonObject {}
            var total = 0
            for ((path, value) in bpObj.entries.take(MAX_BREAKPOINT_FILES)) {
                if (!validPath(path)) continue
                val lines = (value as? JsonArray).orEmpty()
                    .mapNotNull { it.jsonPrimitive.intOrNull }
                    .filter { it in 1..MAX_LINE }
                    .distinct().sorted().take(MAX_BREAKPOINTS_PER_FILE)
                if (lines.isNotEmpty()) {
                    total += lines.size
                    if (total > MAX_BREAKPOINTS_TOTAL) break
                    breakpoints[path] = lines
                }
            }
            val watches = (obj["watches"] as? JsonArray).orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .map(String::trim)
                .filter { it.isNotBlank() && it.length <= MAX_WATCH_LENGTH }
                .distinct().take(MAX_WATCHES)
            DebugPersistentState(breakpoints, watches)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            file.delete()
            null
        }
    }

    suspend fun save(state: DebugPersistentState) = withContext(Dispatchers.IO) {
        var total = 0
        val bp = buildJsonObject {
            for ((path, rawLines) in state.breakpoints.entries.take(MAX_BREAKPOINT_FILES)) {
                if (!validPath(path)) continue
                val lines = rawLines.filter { it in 1..MAX_LINE }.distinct().sorted().take(MAX_BREAKPOINTS_PER_FILE)
                if (lines.isEmpty()) continue
                total += lines.size
                require(total <= MAX_BREAKPOINTS_TOTAL) { "Too many breakpoints" }
                put(path, buildJsonArray { lines.forEach { add(JsonPrimitive(it)) } })
            }
        }
        val watches = state.watches.asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() }
            .distinct().take(MAX_WATCHES).toList()
        require(watches.all { it.length <= MAX_WATCH_LENGTH }) { "Watch expression too large" }
        val obj = buildJsonObject {
            put("version", VERSION)
            put("breakpoints", bp)
            put("watches", buildJsonArray { watches.forEach { add(JsonPrimitive(it)) } })
        }
        val plain = obj.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_PLAINTEXT_BYTES) { "Debug state too large" }
        val encrypted = cipher.encrypt(plain, aad)
        require(encrypted.size <= MAX_FILE_BYTES) { "Encrypted debug state too large" }
        atomicWrite(encrypted)
    }

    private fun validPath(path: String): Boolean {
        if (path.isBlank() || path.length > 1_024 || File(path).isAbsolute || '\u0000' in path) return false
        return path.split('/').none { it == "." || it == ".." }
    }

    private fun atomicWrite(bytes: ByteArray) {
        root.mkdirs()
        val tmp = File(root, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
            runCatching {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private companion object {
        const val VERSION = 1
        const val MAX_BREAKPOINT_FILES = 256
        const val MAX_BREAKPOINTS_PER_FILE = 256
        const val MAX_BREAKPOINTS_TOTAL = 2_000
        const val MAX_LINE = 10_000_000
        const val MAX_WATCHES = 200
        const val MAX_WATCH_LENGTH = 20_000
        const val MAX_PLAINTEXT_BYTES = 1_000_000
        const val MAX_FILE_BYTES = 1_100_000L
    }
}
