package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*

internal const val MCP_WORKSPACE_CONFIG = ".droide/mcp.json"

internal suspend fun retryMcpForUi(agent: AgentService, server: String): String {
    val snapshot = agent.refreshMcpHealth(server)
    return snapshot?.let { "${it.serverName}: ${it.message}" } ?: "MCP server not found: $server"
}

internal suspend fun ensureMcpWorkspaceConfig(files: FileRepository) {
    if (!files.exists(MCP_WORKSPACE_CONFIG)) files.writeText(MCP_WORKSPACE_CONFIG, "{\n  \"servers\": {}\n}\n")
}

@Composable
internal fun AndroidDevelopmentSheet(
    androidDevelopment: AndroidDevelopmentManager,
    deviceBridge: DeviceBridgeManager,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        DroidePanelHeader(
            title = "Android & Device",
            subtitle = "Wireless debugging · toolchain · device certification",
            onClose = onDismiss,
        )
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AndroidDevelopmentSettingsSection(androidDevelopment, deviceBridge)
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
