package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

 
enum class AgentPluginFormat { DROIDE, CLAUDE_CODE, ANTIGRAVITY }

@Serializable
data class InstalledAgentPluginRecord(
    val schema: Int = 1,
    val id: String,
    val displayName: String,
    val version: String,
    val description: String = "",
    val format: String,
    val enabled: Boolean = true,
    val contentSha256: String,
    val installedAtMs: Long,
    val installDirectory: String,
    // This is authoritative; workspace projections are disposable.
    val files: Map<String, String>,
)

// One immutable, integrity-checked plugin projected into a workspace for runtime consumption.
data class AgentPluginContributionRoot(
    val id: String,
    val displayName: String,
    val version: String,
    val description: String,
    val format: AgentPluginFormat,
    val root: File,
    val contentSha256: String,
)

 
fun interface AgentPluginSource {
    fun contributions(workDir: File): List<AgentPluginContributionRoot>

    companion object {
        val EMPTY = AgentPluginSource { emptyList() }
    }
}

data class AgentPluginRule(
    val name: String,
    val description: String,
    val body: String,
    val activation: AgentPluginRuleActivation,
    val globs: List<String> = emptyList(),
    val sourceLabel: String,
)

enum class AgentPluginRuleActivation { ALWAYS, MANUAL, MODEL, GLOB }

data class AgentPluginToolDef(
    val pluginId: String,
    val name: String,
    val functionName: String,
    val description: String,
    val argv: List<String>,
    val inputSchema: JsonObject,
    val timeoutMs: Long,
    val fingerprint: String,
)
