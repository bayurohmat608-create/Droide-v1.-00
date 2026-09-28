package com.baystudio.droide.core

import kotlinx.coroutines.flow.StateFlow

val AgentService.mcpHealth: StateFlow<List<McpHealthSnapshot>>
    get() = McpHealthRegistry.observe(workDir)

fun AgentService.syncMcpHealth() {
    McpManager(workDir, mcpPluginSource()).list()
}

suspend fun AgentService.refreshMcpHealth(serverName: String): McpHealthSnapshot? {
    val manager = McpManager(workDir, mcpPluginSource(), mcpProcessHost)
    val server = manager.list().firstOrNull { it.name == serverName }
        ?: return mcpHealth.value.firstOrNull { it.serverName == serverName }
    val identity = manager.permissionResource(server.name)
    val scope = AgentPermissionScope.id(mode.value, AgentRequestProvenance.primary())
    when (perms.decide("mcp", identity, scope)) {
        PermEffect.ALLOW -> runCatching { manager.discoverTools(server.name) }
        PermEffect.DENY -> McpHealthRegistry.permissionDeferred(workDir, server, denied = true)
        else -> McpHealthRegistry.permissionDeferred(workDir, server, denied = false)
    }
    return mcpHealth.value.firstOrNull { it.serverName == serverName }
}
