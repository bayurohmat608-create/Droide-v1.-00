package com.baystudio.droide.core

// Stable permission identity used by project and per-agent policy.
object AgentPermissionScope {
    fun id(mode: AgentMode, provenance: AgentRequestProvenance): String =
        if (provenance.isSubagent) PermissionPolicyDocument.normalizeAgentId(provenance.agentType)
        else mode.name.lowercase()
}
