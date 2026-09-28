package com.baystudio.droide.core

 
data class AgentPluginToolSurface(
    val skills: SkillManager,
    val rules: AgentRuleManager,
    val mcpTools: List<McpAgentTool>,
    val pluginTools: List<AgentPluginToolDef>,
)

object AgentPluginToolSurfaceFactory {
    suspend fun create(
        workDir: java.io.File,
        source: AgentPluginSource,
        permissions: PermissionEngine,
        permissionScope: String,
        finalStep: Boolean,
        mcpProcessHost: StdioProcessHost?,
    ): AgentPluginToolSurface = AgentPluginToolSurface(
        skills = SkillManager(workDir, source),
        rules = AgentRuleManager(workDir, source),
        mcpTools = if (finalStep) emptyList() else McpToolRegistry(workDir, source, mcpProcessHost).discoverAgentTools(permissions, permissionScope),
        pluginTools = if (finalStep) emptyList() else AgentPluginToolManager(workDir, source).discover(),
    )
}
