package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

 
@Serializable
data class AgentImageRef(
    val path: String,
    val sha256: String,
    val mediaType: String,
    val bytes: Long,
)

 
internal object VisionSupport {
    private const val MAX_IMAGES = 4
    private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024
    private const val REF_TYPE = "droide_image_ref"

    fun providerAcceptsImageUrl(config: AgentConfig): Boolean {
        ModelCapabilityRegistry.allowsImageInput(config.providerId, config.model)?.let { return it }
        return when (config.providerId.lowercase()) {
            "openai", "gemini", "openrouter" -> true
            "anthropic" -> true
            else -> false
        }
    }

    suspend fun stage(workDir: File, imagePaths: List<String>, config: AgentConfig): List<AgentImageRef> = withContext(Dispatchers.IO) {
        if (imagePaths.isEmpty()) return@withContext emptyList()
        require(providerAcceptsImageUrl(config)) {
            "Provider '${config.providerId}' does not support the required image-input format"
        }
        require(imagePaths.size <= MAX_IMAGES) { "At most $MAX_IMAGES images can be attached per prompt" }
        PathSecurity.resolveWithin(workDir, ".droide/vision").apply {
            check(isDirectory || mkdirs()) { "Cannot create vision snapshot directory" }
        }
        var total = 0L
        imagePaths.map { relative ->
            val source = PathSecurity.resolveWithin(workDir, relative)
            require(source.isFile) { "Image does not exist: $relative" }
            require(source.length() in 1..MAX_IMAGE_BYTES) { "Image exceeds ${MAX_IMAGE_BYTES / (1024 * 1024)} MiB limit: $relative" }
            total += source.length()
            require(total <= MAX_TOTAL_BYTES) { "Attached images exceed ${MAX_TOTAL_BYTES / (1024 * 1024)} MiB total limit" }
            val bytes = source.readBytes()
            val media = sniff(bytes) ?: throw IllegalArgumentException("Unsupported image format: $relative (PNG/JPEG/GIF/WebP only)")
            val sha = sha256(bytes)
            val ext = when (media) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/gif" -> "gif"; else -> "webp" }
            val snapshotRel = ".droide/vision/$sha.$ext"
            val snapshot = PathSecurity.resolveWithin(workDir, snapshotRel)
            if (!snapshot.exists()) snapshot.writeBytes(bytes)
            check(snapshot.length() == bytes.size.toLong() && sha256(snapshot.readBytes()) == sha) { "Vision snapshot integrity failure" }
            AgentImageRef(snapshotRel, sha, media, bytes.size.toLong())
        }
    }

    fun userMessage(text: String, images: List<AgentImageRef>, maxTextChars: Int): JsonObject = buildJsonObject {
        put("role", "user")
        if (images.isEmpty()) put("content", text.take(maxTextChars)) else put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", text.take(maxTextChars)) })
            images.forEach { ref -> add(buildJsonObject {
                put("type", REF_TYPE)
                put("image_ref", buildJsonObject {
                    put("path", ref.path); put("sha256", ref.sha256); put("media_type", ref.mediaType); put("bytes", ref.bytes)
                })
            }) }
        })
    }

    suspend fun materializeHistory(history: List<JsonObject>, workDir: File): List<JsonObject> = withContext(Dispatchers.IO) {
        history.map { materializeMessage(it, workDir) }
    }

    private fun materializeMessage(message: JsonObject, workDir: File): JsonObject {
        val content = message["content"] as? JsonArray ?: return message
        if (content.none { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == REF_TYPE }) return message
        return JsonObject(message + ("content" to JsonArray(content.map { part ->
            val obj = part as? JsonObject ?: return@map part
            if (obj["type"]?.jsonPrimitive?.contentOrNull != REF_TYPE) return@map part
            val ref = obj["image_ref"] as? JsonObject ?: error("Malformed image reference")
            val path = ref["path"]?.jsonPrimitive?.contentOrNull ?: error("Missing image reference path")
            val expectedSha = ref["sha256"]?.jsonPrimitive?.contentOrNull ?: error("Missing image reference digest")
            val expectedMedia = ref["media_type"]?.jsonPrimitive?.contentOrNull ?: error("Missing image reference media type")
            val file = PathSecurity.resolveWithin(workDir, path)
            require(file.isFile && file.length() in 1..MAX_IMAGE_BYTES) { "Vision snapshot unavailable: $path" }
            val bytes = file.readBytes()
            val actualMedia = sniff(bytes) ?: error("Vision snapshot format became invalid: $path")
            check(actualMedia == expectedMedia && sha256(bytes) == expectedSha) { "Vision snapshot integrity mismatch: $path" }
            buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:$actualMedia;base64,${Base64.getEncoder().encodeToString(bytes)}") })
            }
        })))
    }

    private fun sniff(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
