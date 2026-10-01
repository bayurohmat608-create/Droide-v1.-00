package com.baystudio.droide.core

import kotlinx.serialization.Serializable


@Serializable
enum class AgentMode { BUILD, PLAN, REVIEW, EXPLORE }

object AgentModePolicy {
    

    private val mutating = setOf(
        "write_file", "write", "edit_file", "edit", "apply_patch", "run_command", "bash",
        "git_branch", "git_commit", "git_pull", "git_push", "worktree", "artifact", "mcp", "ide", "undo_agent_edit"
    )

    // Canonicalize compatibility aliases before both model-surface projection and runtime checks.
    fun canonicalTool(tool: String): String = when {
        tool.startsWith("mcp__") -> "mcp"
        tool == "bash" -> "run_command"
        tool == "edit" -> "edit_file"
        tool == "write" -> "write_file"
        tool == "read" -> "read_file"
        tool == "grep" -> "search_files"
        else -> tool
    }

    // Runtime check remains authoritative too, so a stale/provider-injected call still fails closed instead of executing.


    fun isToolVisible(mode: AgentMode, tool: String): Boolean = check(mode, tool) == null

    fun check(mode: AgentMode, tool: String): String? {
        val canonical = canonicalTool(tool)
        return when (mode) {
            AgentMode.BUILD -> null
            AgentMode.PLAN -> if (canonical in mutating || (canonical.startsWith("custom_") || canonical.startsWith("plugin_")))
                "DENIED (PLAN mode): only read and plan. Switch to BUILD to execute."
            else null
            AgentMode.REVIEW -> if (canonical in mutating || (canonical.startsWith("custom_") || canonical.startsWith("plugin_")))
                "DENIED (REVIEW mode): review is read-only. Switch to BUILD to modify files or run mutating commands."
            else null
            AgentMode.EXPLORE -> if (canonical in mutating + setOf("question", "task", "task_control", "explore_codebase", "diagnose", "todowrite") || (canonical.startsWith("custom_") || canonical.startsWith("plugin_")))
                "DENIED (EXPLORE mode): subagent read-only + anti-recursion (todowrite forbidden)."
            else null
        }
    }
}
