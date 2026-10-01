package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

// App-private store is authoritative; per-workspace projections are disposable and reverified.


object AgentPluginRuntime : AgentPluginSource {
    private const val URI_COPY_BUFFER = 64 * 1024
    private const val PROJECTION_DIR = ".droide/.runtime-agent-plugins"
    private var store: AgentPluginStore? = null
    private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (store == null) {
            appContext = context.applicationContext
            store = AgentPluginStore(context.applicationContext)
        }
    }

    @Synchronized
    fun records(): List<InstalledAgentPluginRecord> = requireStore().listVerified()

    @Synchronized
    fun install(archive: File): InstalledAgentPluginRecord = requireStore().install(archive)

    @Synchronized
    fun installFromUri(uri: Uri): InstalledAgentPluginRecord {
        val context = appContext ?: error("Agent plugin runtime is not initialized")
        val temp = File(context.cacheDir, "agent-plugin-${System.nanoTime()}.zip")
        try {
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(URI_COPY_BUFFER)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        require(total <= AgentPluginStore.MAX_ARCHIVE_BYTES) { "Agent plugin archive exceeds safe import limit" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Cannot open Agent plugin archive")
            return install(temp)
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun uninstall(id: String) = requireStore().uninstall(id)

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) = requireStore().setEnabled(id, enabled)

    override fun contributions(workDir: File): List<AgentPluginContributionRoot> = synchronized(this) {
        val targetStore = requireStore()
        val workspace = workDir.canonicalFile
        require(workspace.isDirectory) { "Workspace does not exist" }
        val records = targetStore.listVerified().filter { it.enabled }.sortedBy { it.id }.take(AgentPluginContributions.MAX_PLUGIN_COUNT)
        val base = PathSecurity.resolveWithin(workspace, PROJECTION_DIR)
        base.mkdirs()
        val expectedNames = mutableSetOf<String>()
        val roots = records.mapNotNull { record ->
            runCatching {
                val name = safeSegment(record.id) + "-" + record.contentSha256.take(12)
                expectedNames += name
                val projected = PathSecurity.resolveWithin(base, name)
                if (!projectionVerified(record, projected)) {
                    if (projected.exists()) check(PathSecurity.deleteTreeNoFollow(projected)) { "Could not clear stale Agent plugin projection" }
                    projectRecord(targetStore, record, projected)
                    check(projectionVerified(record, projected)) { "Agent plugin projection integrity check failed: ${record.id}" }
                }
                AgentPluginContributionRoot(
                    id = record.id,
                    displayName = record.displayName,
                    version = record.version,
                    description = record.description,
                    format = AgentPluginFormat.valueOf(record.format),
                    root = projected,
                    contentSha256 = record.contentSha256,
                )
            }.getOrNull()
        }
        base.listFiles().orEmpty().filter { it.name !in expectedNames }.forEach { runCatching { PathSecurity.deleteTreeNoFollow(it) } }
        roots
    }

    @Synchronized
    fun cleanupWorkspace(workDir: File) {
        runCatching { PathSecurity.deleteTreeNoFollow(PathSecurity.resolveWithin(workDir.canonicalFile, PROJECTION_DIR)) }
    }

    private fun projectRecord(store: AgentPluginStore, record: InstalledAgentPluginRecord, destination: File) {
        val source = store.installRoot(record)
        destination.mkdirs()
        record.files.toSortedMap().forEach { (relative, expected) ->
            val src = PathSecurity.resolveWithin(source, relative)
            require(src.isFile && !PathSecurity.isSymbolicLink(src) && sha256(src) == expected) { "Agent plugin source integrity changed" }
            val out = PathSecurity.resolveWithin(destination, relative)
            out.parentFile?.mkdirs()
            src.inputStream().buffered().use { input -> out.outputStream().buffered().use { input.copyTo(it, 64 * 1024) } }
            require(sha256(out) == expected) { "Agent plugin projection hash mismatch" }
        }
        File(destination, ".droide-plugin-integrity").writeText(record.contentSha256 + "\n")
    }

    private fun projectionVerified(record: InstalledAgentPluginRecord, root: File): Boolean {
        if (!root.isDirectory) return false
        val marker = File(root, ".droide-plugin-integrity")
        if (!marker.isFile || marker.readText().trim() != record.contentSha256) return false
        return record.files.all { (relative, expected) ->
            val f = runCatching { PathSecurity.resolveWithin(root, relative) }.getOrNull() ?: return@all false
            f.isFile && !PathSecurity.isSymbolicLink(f) && f.length() <= AgentPluginStore.MAX_ENTRY_BYTES && sha256(f) == expected
        }
    }

    private fun requireStore(): AgentPluginStore = store ?: error("Agent plugin runtime is not initialized")
    private fun safeSegment(raw: String): String = raw.filter { it.isLetterOrDigit() || it in "._-" }.take(96).ifBlank { "plugin" }
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
