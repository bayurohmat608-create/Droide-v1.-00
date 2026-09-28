package com.baystudio.droide.core

 
enum class WorkspaceAgentMode { PLAN, EDIT }

internal class WorkspaceAgentAccessPolicy {
    @Volatile private var mode: WorkspaceAgentMode = WorkspaceAgentMode.PLAN

    fun setMode(value: WorkspaceAgentMode) {
        mode = value
    }

    fun currentMode(): WorkspaceAgentMode = mode

    fun allowsWorkspaceMutation(): Boolean = mode == WorkspaceAgentMode.EDIT

    fun requireMutation(operation: String) {
        check(allowsWorkspaceMutation()) {
            "External agent is read-only in PLAN/REVIEW/EXPLORE mode: $operation denied"
        }
    }

    fun rejectsPermissionKind(kind: String?): Boolean {
        if (allowsWorkspaceMutation()) return false
        return kind?.lowercase() in MUTATING_TOOL_KINDS
    }

    private companion object {
        val MUTATING_TOOL_KINDS = setOf("edit", "delete", "move", "execute", "write", "create")
    }
}
