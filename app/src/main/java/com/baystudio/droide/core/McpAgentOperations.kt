package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

 
internal object McpAgentOperations {
    suspend fun execute(
        args: JsonObject,
        c: AgentTools.Ctx,
        gate: suspend (action: String, resource: String) -> String?,
        gatePreview: suspend (action: String, resource: String, kind: String, summary: String, detail: String) -> String?,
    ): String {
        val server = args["server"]?.jsonPrimitive?.contentOrNull ?: return "ERROR: missing 'server'"
        val operation = args["operation"]?.jsonPrimitive?.contentOrNull
        val toolName = args["tool"]?.jsonPrimitive?.contentOrNull
        val manager = McpManager(c.workDir, c.pluginSource, c.mcpProcessHost)
        val repair = McpRepairEngine(c.workDir, c.pluginSource, c.mcpProcessHost)

        suspend fun gateCurrentMcp(tool: String? = null): String? {
            val resource = manager.permissionResource(server, tool)
            gate("mcp", resource)?.let { return it }
            require(manager.permissionResource(server, tool) == resource) {
                "MCP server configuration changed after approval. Review the current configuration before running it."
            }
            return null
        }

        return when (operation) {
            "status" -> repair.status(server)
            "diagnose" -> repair.diagnose(server)
            "repair_config" -> {
                val patch = args["patch"] as? JsonObject ?: return "ERROR: MCP repair_config requires object 'patch'"
                val preview = repair.previewConfigRepair(server, patch)
                gatePreview("mcp_repair", preview.permissionResource, "mcp_repair", preview.summary, preview.detail)?.let { return it }
                repair.applyConfigRepair(preview)
            }
            "rollback" -> {
                val repairId = args["repair_id"]?.jsonPrimitive?.contentOrNull ?: return "ERROR: MCP rollback requires 'repair_id'"
                val preview = repair.previewRollback(server, repairId)
                gatePreview("mcp_repair", preview.permissionResource, "mcp_repair", preview.summary, preview.detail)?.let { return it }
                repair.applyRollback(preview)
            }
            "retry", "verify" -> {
                gateCurrentMcp()?.let { return it }
                repair.verify(server, clearCatalog = false)
            }
            "refresh_catalog" -> {
                gateCurrentMcp()?.let { return it }
                repair.verify(server, clearCatalog = true)
            }
            null, "" -> {
                gateCurrentMcp(toolName)?.let { return it }
                val legacy = args["input"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (toolName.isNullOrBlank()) manager.tool(server, legacy)
                else manager.callTool(server, toolName, (args["arguments"] as? JsonObject) ?: buildJsonObject {})
            }
            "list_tools" -> {
                gateCurrentMcp()?.let { return it }
                manager.listTools(server)
            }
            "call_tool" -> {
                val selected = toolName ?: return "ERROR: MCP call_tool requires 'tool'"
                gateCurrentMcp(selected)?.let { return it }
                manager.callTool(server, selected, (args["arguments"] as? JsonObject) ?: buildJsonObject {})
            }
            else -> "ERROR: Unsupported MCP operation: $operation"
        }.take(8_000)
    }
}
