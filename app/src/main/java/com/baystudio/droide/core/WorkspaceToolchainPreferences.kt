package com.baystudio.droide.core

import android.content.Context
import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

 
class WorkspaceToolchainPreferences(
    context: Context,
    projectRoot: File,
) {
    private val prefs = context.applicationContext.getSharedPreferences("droide_workspace_toolchains", Context.MODE_PRIVATE)
    private val workspaceKey = "workspace_" + AndroidDevelopmentManager.stableProjectId(projectRoot)
    private val json = Json { ignoreUnknownKeys = false }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun selections(): Map<String, String> {
        val raw = prefs.getString(workspaceKey, null) ?: return emptyMap()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
            .filter { (family, version) ->
                family.matches(Regex("[A-Za-z0-9._-]{1,120}")) &&
                    version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))
            }
            .toList()
            .take(64)
            .toMap()
    }

    fun selectedVersion(familyId: String): String? = selections()[familyId]

    fun set(familyId: String, version: String) {
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid package family" }
        require(version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))) { "Invalid package version" }
        val next = selections().toMutableMap().apply { put(familyId, version) }
        require(next.size <= 64) { "Too many workspace toolchain overrides" }
        prefs.edit().putString(workspaceKey, json.encodeToString(serializer, next)).apply()
    }

    fun clear(familyId: String) {
        val next = selections().toMutableMap().apply { remove(familyId) }
        if (next.isEmpty()) prefs.edit().remove(workspaceKey).apply()
        else prefs.edit().putString(workspaceKey, json.encodeToString(serializer, next)).apply()
    }

    fun prune(valid: Set<Pair<String, String>>) {
        val next = selections().filter { (family, version) -> (family to version) in valid }
        if (next.isEmpty()) prefs.edit().remove(workspaceKey).apply()
        else prefs.edit().putString(workspaceKey, json.encodeToString(serializer, next)).apply()
    }
}
