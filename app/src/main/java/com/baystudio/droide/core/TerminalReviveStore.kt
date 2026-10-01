package com.baystudio.droide.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

 
internal data class TerminalReviveDescriptor(
    val id: String,
    val title: String,
    val workDir: File,
)

internal data class TerminalReviveSnapshot(
    val tabs: List<TerminalReviveDescriptor>,
    val activeId: String?,
)

// After process death Droide relaunches a fresh shell; it never pretends that the killed process survived.






internal class TerminalReviveStore(
    appFilesDir: File,
) {
    private val rootDir = File(appFilesDir, ".droide/terminal-revive")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(projectKey: String, projectRoot: File): TerminalReviveSnapshot? {
        val target = fileFor(projectKey)
        if (!target.isFile || target.length() <= 0L || target.length() > MAX_FILE_BYTES) return null
        return runCatching {
            val root = json.parseToJsonElement(target.readText(Charsets.UTF_8)).jsonObject
            if (root["schema"]?.jsonPrimitive?.intOrNull != SCHEMA_VERSION) return@runCatching null
            if (root["projectKey"]?.jsonPrimitive?.contentOrNull != projectKey) return@runCatching null
            val tabs = root["tabs"]?.jsonArray.orEmpty().take(MAX_TABS).mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf(::validId) ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.let(::sanitizeTitle) ?: "Local"
                val relative = obj["workDir"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.length <= MAX_RELATIVE_PATH_CHARS }
                    ?: return@mapNotNull null
                val workDir = runCatching { PathSecurity.resolveWithin(projectRoot, relative) }.getOrNull()
                    ?: return@mapNotNull null
                TerminalReviveDescriptor(id = id, title = title, workDir = workDir)
            }.distinctBy { it.id }
            if (tabs.isEmpty()) return@runCatching null
            val activeId = root["activeId"]?.jsonPrimitive?.contentOrNull?.takeIf { id -> tabs.any { it.id == id } }
            TerminalReviveSnapshot(tabs = tabs, activeId = activeId ?: tabs.first().id)
        }.getOrNull()
    }

    fun save(
        projectKey: String,
        projectRoot: File,
        tabs: List<TerminalReviveDescriptor>,
        activeId: String?,
    ) {
        val bounded = tabs.take(MAX_TABS).mapNotNull { descriptor ->
            val id = descriptor.id.takeIf(::validId) ?: return@mapNotNull null
            val root = projectRoot.canonicalFile
            val work = descriptor.workDir.canonicalFile
            if (!PathSecurity.contains(root, work)) return@mapNotNull null
            val relative = root.toPath().relativize(work.toPath()).toString()
                .ifBlank { "." }
                .replace(File.separatorChar, '/')
                .takeIf { it.length <= MAX_RELATIVE_PATH_CHARS }
                ?: return@mapNotNull null
            TerminalReviveDescriptor(id, sanitizeTitle(descriptor.title), File(relative))
        }.distinctBy { it.id }

        if (bounded.isEmpty()) {
            delete(projectKey)
            return
        }

        val resolvedActive = activeId?.takeIf { id -> bounded.any { it.id == id } } ?: bounded.first().id
        val objectJson = buildJsonObject {
            put("schema", JsonPrimitive(SCHEMA_VERSION))
            put("projectKey", JsonPrimitive(projectKey))
            put("activeId", JsonPrimitive(resolvedActive))
            put("tabs", JsonArray(bounded.map { descriptor ->
                buildJsonObject {
                    put("id", JsonPrimitive(descriptor.id))
                    put("title", JsonPrimitive(descriptor.title))
                    put("workDir", JsonPrimitive(descriptor.workDir.path.replace(File.separatorChar, '/')))
                }
            }))
        }
        atomicText(fileFor(projectKey), objectJson.toString())
    }

    fun delete(projectKey: String) {
        runCatching { Files.deleteIfExists(fileFor(projectKey).toPath()) }
    }

    fun clearAll() {
        if (!rootDir.exists()) return
        rootDir.listFiles()?.forEach { file -> runCatching { Files.deleteIfExists(file.toPath()) } }
    }

    private fun fileFor(projectKey: String): File {
        val safe = PathSecurity.safeLeafName(projectKey)
        return File(rootDir, "$safe.json")
    }

    private fun atomicText(target: File, text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Terminal revive state is too large" }
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun sanitizeTitle(raw: String): String = raw
        .filter { ch -> ch >= ' ' && ch != '\u007f' }
        .trim()
        .ifBlank { "Local" }
        .take(MAX_TITLE_CHARS)

    private fun validId(id: String): Boolean =
        id.length in 1..MAX_ID_CHARS && id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_TABS = 16
        const val MAX_FILE_BYTES = 64 * 1024L
        const val MAX_ID_CHARS = 96
        const val MAX_TITLE_CHARS = 80
        const val MAX_RELATIVE_PATH_CHARS = 1_024
    }
}

 
internal object TerminalProcessExitPolicy {
    fun allowRevive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        val activity = context.getSystemService(ActivityManager::class.java) ?: return true
        val lastExit = runCatching {
            activity.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return true
        return lastExit.reason != ApplicationExitInfo.REASON_USER_REQUESTED
    }
}
