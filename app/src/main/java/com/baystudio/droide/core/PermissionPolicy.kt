package com.baystudio.droide.core

import java.util.Collections

// Durable user-owned permission policy for one Droide project.







data class PermissionPolicyDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val rules: List<PermRule> = emptyList(),
    val agents: Map<String, List<PermRule>> = emptyMap(),
) {
    fun validated(): PermissionPolicyDocument {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported permission policy schema: $schemaVersion" }
        require(rules.size <= MAX_PROJECT_RULES) { "Too many project permission rules" }
        require(agents.size <= MAX_AGENTS) { "Too many agent permission scopes" }
        rules.forEach(::validateRule)
        agents.forEach { (agent, scopedRules) ->
            require(agent == normalizeAgentId(agent)) { "Invalid or non-normalized agent permission scope: $agent" }
            require(scopedRules.size <= MAX_AGENT_RULES) { "Too many permission rules for agent $agent" }
            scopedRules.forEach(::validateRule)
        }
        return PermissionPolicyDocument(
            schemaVersion = schemaVersion,
            rules = Collections.unmodifiableList(rules.toList()),
            agents = Collections.unmodifiableMap(agents.mapValues { (_, scopedRules) ->
                Collections.unmodifiableList(scopedRules.toList())
            }),
        )
    }

    fun rulesFor(agentId: String): List<PermRule> = agents[normalizeAgentId(agentId)].orEmpty()

    companion object {
        const val SCHEMA_VERSION = 1
        private const val MAX_PROJECT_RULES = 512
        private const val MAX_AGENT_RULES = 256
        private const val MAX_AGENTS = 64
        val EMPTY = PermissionPolicyDocument()

        fun normalizeAgentId(value: String): String {
            val normalized = value.trim().lowercase()
            require(normalized.matches(Regex("[a-z0-9._-]{1,64}"))) { "Invalid agent permission scope" }
            return normalized
        }

        private fun validateRule(rule: PermRule) {
            require(rule.action.isNotBlank() && rule.action.length <= 128) { "Invalid permission action" }
            require(rule.action == rule.action.trim()) { "Permission action must be trimmed" }
            require(rule.action.matches(Regex("[A-Za-z0-9_.*?-]{1,128}"))) { "Invalid permission action pattern" }
            require(rule.resource.isNotBlank() && rule.resource.length <= 4_096) { "Invalid permission resource" }
            require(rule.resource == rule.resource.trim()) { "Permission resource must be trimmed" }
            require('\u0000' !in rule.resource && '\n' !in rule.resource && '\r' !in rule.resource) {
                "Permission resource must be single-line text"
            }
        }
    }
}

