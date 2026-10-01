package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// Canonical permission identity and execution-time requirements for Native Agent tools.
data class AgentRuntimePermissionRequirement(val action: String, val resource: String)

object AgentToolPermissionPolicy {
    fun actionOf(tool: String): String = when {
        McpToolIdentity.isFirstClassName(tool) -> "mcp"
        tool in setOf("list_files", "read_file", "read") -> "read"
        tool == "glob" -> "glob"
        tool in setOf("search_files", "grep") -> "grep"
        tool in setOf("write_file", "write", "edit_file", "edit", "apply_patch") -> "edit"
        tool in setOf("run_command", "bash") -> "shell"
        tool == "background_job" -> "read"
        tool == "environment_probe" -> "read"
        tool == "ide" -> "ide_execute"
        tool == "undo_agent_edit" -> "edit"
        tool == "question" -> "question"
        tool in setOf("task", "task_control", "explore_codebase") -> "subagent"
        tool == "diagnose" -> "diagnose"
        tool in setOf("lsp", "lsp_edit") -> "lsp"
        tool == "todowrite" -> "todowrite"
        tool == "webfetch" -> "webfetch"
        tool == "websearch" -> "websearch"
        tool == "mcp" -> "mcp"
        tool == "browser" -> "webfetch"
        tool == "toolchain" -> "read"
        tool == "capability" -> "read"
        tool == "artifact" -> "edit"
        tool in setOf("git_status", "git_log", "git_diff") -> "read"
        tool == "git_branch" -> "shell"
        tool in setOf("git_pull", "git_push") -> "shell"
        tool in setOf("skill", "rule") -> "skill"
        tool in setOf("worktree", "git_commit") -> "shell"
        tool.startsWith("custom_") || tool.startsWith("plugin_") -> "shell"
        else -> tool
    }

    // Every returned requirement must be resolved immediately before the corresponding branch runs.


    fun runtimeRequirements(tool: String, args: JsonObject, sessionId: String?): List<AgentRuntimePermissionRequirement> = when (tool) {
        "background_job" -> listOf(AgentRuntimePermissionRequirement(
            "read",
            "background_job:${args["action"]?.jsonPrimitive?.contentOrNull ?: "list"}:${args["job_id"]?.jsonPrimitive?.contentOrNull ?: "*"}",
        ))
        "git_status" -> listOf(AgentRuntimePermissionRequirement("read", "git status"))
        "git_log" -> listOf(AgentRuntimePermissionRequirement("read", "git log"))
        "git_diff" -> listOf(AgentRuntimePermissionRequirement("read", "git diff"))
        "todowrite" -> listOf(AgentRuntimePermissionRequirement("todowrite", "session:${sessionId ?: "none"}"))
        else -> emptyList()
    }
}
