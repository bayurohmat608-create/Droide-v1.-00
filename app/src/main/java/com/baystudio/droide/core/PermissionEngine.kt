package com.baystudio.droide.core

enum class PermEffect { ALLOW, ASK, DENY }
data class PermRule(val action: String, val resource: String, val effect: PermEffect)

// Unified agent permission engine.


class PermissionEngine(
    base: List<PermRule> = defaults(),
    policy: PermissionPolicyDocument = PermissionPolicyDocument.EMPTY,
    private val hardRules: List<PermRule> = emptyList(),
    toolRestrictions: List<Set<String>> = emptyList(),
) {
    private val baseRules = base.toList()
    private val toolRestrictionLayers = toolRestrictions.map { it.toSet() }
    private var projectPolicy = policy.validated()
    private enum class SessionMatch { PATTERN, EXACT }
    private data class SessionGrant(val agent: String, val rule: PermRule, val match: SessionMatch)
    private val sessionAllows = mutableListOf<SessionGrant>()
    private val lock = Any()

    // Resolve one permission request for a concrete agent scope.


    fun decide(action: String, resource: String, agent: String = "build"): PermEffect = synchronized(lock) {
        val scope = PermissionPolicyDocument.normalizeAgentId(agent)
        if (hardRules.any { it.effect == PermEffect.DENY && wild(it.action, action) && wild(it.resource, resource) }) {
            return PermEffect.DENY
        }

        val baseMatches = baseRules.filter { wild(it.action, action) && wild(it.resource, resource) }
        var baseEffect = when {
            baseMatches.any { it.effect == PermEffect.DENY } -> PermEffect.DENY
            baseMatches.any { it.effect == PermEffect.ASK } -> PermEffect.ASK
            baseMatches.any { it.effect == PermEffect.ALLOW } -> PermEffect.ALLOW
            else -> PermEffect.ASK
        }
        if (action == "read" && SensitivePathPolicy.isSensitive(resource) && baseEffect != PermEffect.DENY) {
            baseEffect = PermEffect.ASK
        }

        val projectEffect = orderedEffect(projectPolicy.rules, action, resource)
        val agentEffect = orderedEffect(projectPolicy.rulesFor(scope), action, resource)
        if (baseEffect == PermEffect.DENY || projectEffect == PermEffect.DENY || agentEffect == PermEffect.DENY) {
            return PermEffect.DENY
        }

        val configured = agentEffect ?: projectEffect ?: baseEffect
        if (configured == PermEffect.ASK && sessionAllows.any { grant ->
                (grant.agent == "*" || grant.agent == scope) && when (grant.match) {
                    SessionMatch.EXACT -> grant.rule.action == action && grant.rule.resource == resource
                    SessionMatch.PATTERN -> wild(grant.rule.action, action) && wild(grant.rule.resource, resource)
                }
            }) {
            return PermEffect.ALLOW
        }
        configured
    }

    // Explicit pattern grant for trusted/internal callers.


    fun allowForSession(action: String, resourcePattern: String, agent: String = "*") = synchronized(lock) {
        addSessionGrant(action, resourcePattern, agent, SessionMatch.PATTERN)
    }

     
    fun allowExactForSession(action: String, resource: String, agent: String) = synchronized(lock) {
        require(agent != "*") { "Interactive exact grants must be bound to one agent scope" }
        addSessionGrant(action, resource, agent, SessionMatch.EXACT)
    }

    private fun addSessionGrant(action: String, resource: String, agent: String, match: SessionMatch) {
        require(action.isNotBlank() && action.length <= 128) { "Invalid permission action" }
        require(resource.isNotBlank() && resource.length <= 4_096) { "Invalid permission resource" }
        val scope = if (agent == "*") "*" else PermissionPolicyDocument.normalizeAgentId(agent)
        val probeScope = if (scope == "*") "build" else scope
        if (decideWithoutSession(action, resource, probeScope) == PermEffect.DENY) return
        val grant = SessionGrant(scope, PermRule(action, resource, PermEffect.ALLOW), match)
        if (grant !in sessionAllows) sessionAllows += grant
    }

    fun clearSessionAllows() = synchronized(lock) { sessionAllows.clear() }

    fun replacePolicy(policy: PermissionPolicyDocument) = synchronized(lock) {
        projectPolicy = policy.validated()
        sessionAllows.clear()
    }

    fun policySnapshot(): PermissionPolicyDocument = synchronized(lock) { projectPolicy }

    // Child agents inherit durable policy and base guardrails, but never the parent's session grants.
    fun fork(extraHardRules: List<PermRule> = emptyList()): PermissionEngine = fork(extraHardRules, null)

    // Profile tool restrictions are restrictive-only layers; nested children must satisfy every layer.
    fun fork(extraHardRules: List<PermRule>, toolAllowlist: Set<String>?): PermissionEngine = synchronized(lock) {
        val nextLayers = if (toolAllowlist == null) toolRestrictionLayers else toolRestrictionLayers + listOf(toolAllowlist.toSet())
        PermissionEngine(baseRules, projectPolicy, hardRules + extraHardRules, nextLayers)
    }

     
    fun toolAllowed(tool: String): Boolean = synchronized(lock) {
        val canonical = canonicalTool(tool)
        toolRestrictionLayers.all { layer -> layer.any { pattern -> wild(canonicalTool(pattern), canonical) } }
    }

     
    fun snapshot(): List<PermRule> = synchronized(lock) {
        baseRules + projectPolicy.rules + sessionAllows.map { it.rule }
    }

     
    fun actionVisible(action: String, agent: String): Boolean = synchronized(lock) {
        decideWithoutSession(action, "*", PermissionPolicyDocument.normalizeAgentId(agent)) != PermEffect.DENY
    }

    private fun decideWithoutSession(action: String, resource: String, scope: String): PermEffect {
        if (hardRules.any { it.effect == PermEffect.DENY && wild(it.action, action) && wild(it.resource, resource) }) {
            return PermEffect.DENY
        }
        val baseMatches = baseRules.filter { wild(it.action, action) && wild(it.resource, resource) }
        var baseEffect = when {
            baseMatches.any { it.effect == PermEffect.DENY } -> PermEffect.DENY
            baseMatches.any { it.effect == PermEffect.ASK } -> PermEffect.ASK
            baseMatches.any { it.effect == PermEffect.ALLOW } -> PermEffect.ALLOW
            else -> PermEffect.ASK
        }
        if (action == "read" && SensitivePathPolicy.isSensitive(resource) && baseEffect != PermEffect.DENY) {
            baseEffect = PermEffect.ASK
        }
        val projectEffect = orderedEffect(projectPolicy.rules, action, resource)
        val agentEffect = orderedEffect(projectPolicy.rulesFor(scope), action, resource)
        if (baseEffect == PermEffect.DENY || projectEffect == PermEffect.DENY || agentEffect == PermEffect.DENY) return PermEffect.DENY
        return agentEffect ?: projectEffect ?: baseEffect
    }

    private fun orderedEffect(rules: List<PermRule>, action: String, resource: String): PermEffect? =
        rules.lastOrNull { wild(it.action, action) && wild(it.resource, resource) }?.effect

    private fun canonicalTool(tool: String): String = when {
        tool.startsWith("mcp__") -> "mcp"
        tool == "bash" -> "run_command"
        tool == "edit" -> "edit_file"
        tool == "write" -> "write_file"
        tool == "read" -> "read_file"
        tool == "grep" -> "search_files"
        else -> tool
    }

    companion object {
        fun defaults() = mutableListOf(
            

            PermRule("read", "*", PermEffect.ALLOW),
            PermRule("read", "*.env", PermEffect.ASK),
            PermRule("read", ".env*", PermEffect.ASK),
            PermRule("read", "*/.env", PermEffect.ASK),
            PermRule("read", "*/.env.*", PermEffect.ASK),
            PermRule("read", "*.pem", PermEffect.ASK),
            PermRule("read", "*.key", PermEffect.ASK),
            PermRule("read", "*.p12", PermEffect.ASK),
            PermRule("read", "*.pfx", PermEffect.ASK),
            PermRule("read", "*.npmrc", PermEffect.ASK),
            PermRule("read", "*.pypirc", PermEffect.ASK),
            PermRule("read", "*.git-credentials", PermEffect.ASK),
            PermRule("read", "*local.properties", PermEffect.ASK),
            PermRule("read", "*gradle.properties", PermEffect.ASK),
            PermRule("read", "*secrets.properties", PermEffect.ASK),
            PermRule("read", "*keystore.properties", PermEffect.ASK),
            PermRule("read", "*credentials.json", PermEffect.ASK),
            PermRule("read", "*service-account.json", PermEffect.ASK),
            PermRule("read", "*service_account.json", PermEffect.ASK),
            PermRule("read", "*google-services.json", PermEffect.ASK),
            PermRule("read", "*.jks", PermEffect.ASK),
            PermRule("read", "*.keystore", PermEffect.ASK),
            PermRule("read", "*id_rsa", PermEffect.ASK),
            PermRule("read", "*id_dsa", PermEffect.ASK),
            PermRule("read", "*id_ecdsa", PermEffect.ASK),
            PermRule("read", "*id_ed25519", PermEffect.ASK),
            PermRule("read", ".droide/*", PermEffect.ASK),
            PermRule("read", ".git/*", PermEffect.ASK),

            PermRule("glob", "*", PermEffect.ALLOW),
            PermRule("grep", "*", PermEffect.ALLOW),


            PermRule("shell", "pwd", PermEffect.ALLOW),
            PermRule("shell", "ls", PermEffect.ALLOW),
            PermRule("bash", "pwd", PermEffect.ALLOW),
            PermRule("bash", "ls", PermEffect.ALLOW),

            PermRule("question", "*", PermEffect.ALLOW),
            PermRule("skill", "*", PermEffect.ALLOW),
            PermRule("todowrite", "*", PermEffect.ALLOW),

            
            PermRule("doom_loop", "*", PermEffect.ASK),
            PermRule("diagnose", "*", PermEffect.ASK),
            PermRule("lsp", "*", PermEffect.ASK),
            PermRule("lsp_edit", "*", PermEffect.ASK),
            PermRule("webfetch", "*", PermEffect.ASK),
            PermRule("browser_interact", "*", PermEffect.ASK),
            PermRule("browser_local", "*", PermEffect.ASK),
            PermRule("toolchain_install", "*", PermEffect.ASK),
            PermRule("websearch", "*", PermEffect.ASK),
            PermRule("mcp", "*", PermEffect.ASK),
            PermRule("mcp_repair", "*", PermEffect.ASK),
            PermRule("ide_execute", "*", PermEffect.ASK),
            PermRule("subagent", "*", PermEffect.ASK),
            PermRule("hook", "*", PermEffect.ASK),
            PermRule("external_directory", "*", PermEffect.ASK),
            // Dynamic shell syntax/wrappers/custom scripts cannot be proven workspace-contained statically.

            PermRule("shell_escape", "*", PermEffect.ASK),
        )

        fun wild(pattern: String, value: String): Boolean {
            if (pattern == "*") return true
            if (pattern.endsWith(" *") && value == pattern.removeSuffix(" *")) return true
            val out = StringBuilder("^")
            pattern.forEach { ch ->
                when (ch) {
                    '*' -> out.append(".*")
                    '?' -> out.append('.')
                    else -> out.append(Regex.escape(ch.toString()))
                }
            }
            out.append('$')
            return Regex(out.toString()).matches(value)
        }
    }
}
