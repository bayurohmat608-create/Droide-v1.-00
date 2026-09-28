package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

internal data class McpRepairSnapshot(
    val serverName: String,
    val beforeText: String,
    val afterSha256: String,
)

// Bounded app-temp rollback snapshots.
internal class McpRepairSnapshotStore(private val workDir: File) {
    private val root: File by lazy {
        val base = File(System.getProperty("java.io.tmpdir") ?: error("App temp directory is unavailable"), "droide-mcp-repair")
        File(base, sha256(runCatching { workDir.canonicalPath }.getOrElse { workDir.absolutePath }).take(24)).apply { mkdirs() }
    }

    fun save(serverName: String, beforeText: String, afterSha256: String): String {
        trim()
        val id = "mcp-${System.currentTimeMillis().toString(36)}-${sha256(serverName + beforeText + afterSha256).take(12)}"
        val props = Properties().apply {
            setProperty("version", "1")
            setProperty("server", encode(serverName))
            setProperty("afterSha256", afterSha256)
            setProperty("before", encode(beforeText))
        }
        val file = File(root, "$id.properties")
        file.outputStream().use { props.store(it, null) }
        file.setReadable(false, false); file.setWritable(false, false)
        file.setReadable(true, true); file.setWritable(true, true)
        trim()
        return id
    }

    fun load(id: String): McpRepairSnapshot? {
        val file = File(root, "$id.properties")
        if (!file.isFile || file.length() !in 1..MAX_SNAPSHOT_BYTES.toLong()) return null
        if (System.currentTimeMillis() - file.lastModified() > SNAPSHOT_TTL_MS) { file.delete(); return null }
        return runCatching {
            val props = Properties().apply { file.inputStream().use { input -> load(input) } }
            require(props.getProperty("version") == "1")
            val server = decode(props.getProperty("server"))
            val before = decode(props.getProperty("before"))
            val after = props.getProperty("afterSha256")
            require(after.matches(Regex("[a-f0-9]{64}")))
            McpRepairSnapshot(server, before, after)
        }.getOrNull()
    }

    fun delete(id: String) { File(root, "$id.properties").delete() }

    private fun trim() {
        val now = System.currentTimeMillis()
        val files = root.listFiles { f -> f.isFile && f.name.endsWith(".properties") }.orEmpty().sortedByDescending(File::lastModified)
        files.forEachIndexed { index, file -> if (index >= MAX_SNAPSHOTS || now - file.lastModified() > SNAPSHOT_TTL_MS) file.delete() }
    }

    private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String?): String = String(Base64.getDecoder().decode(value ?: error("Invalid snapshot")), Charsets.UTF_8)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_SNAPSHOTS = 8
        const val SNAPSHOT_TTL_MS = 24L * 60L * 60L * 1_000L
        const val MAX_SNAPSHOT_BYTES = 256_000
    }
}
