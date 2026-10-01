package com.baystudio.droide.core

import java.security.MessageDigest

// The model never receives arbitrary PATH executables as callable tools.


class AgentCapabilityActions(
    private val registry: UniversalCapabilityRegistry,
    private val execution: BuildRunDebugCoordinator,
) {
    data class PreparedRun(
        val qualifiedId: String,
        val title: String,
        val kind: DroideToolKind,
        val relativePath: String?,
        val executable: ExecutableCapability,
        val plan: RunPlan,
        val runtimeIdentity: String,
        val preview: String,
    )

    fun list(kind: String? = null): String {
        val requestedKind = kind?.trim()?.takeIf(String::isNotBlank)?.let(::parseKind)
        val tools = registry.tools()
            .asSequence()
            .filter { requestedKind == null || it.kind == requestedKind }
            .sortedWith(compareBy<ContributedToolCapability>({ it.kind.name }, { it.qualifiedId }))
            .take(128)
            .toList()
        if (tools.isEmpty()) return "No enabled extension-contributed capabilities${requestedKind?.let { " for ${it.name.lowercase()}" }.orEmpty()}."
        return tools.joinToString("\n") { tool ->
            val executable = registry.resolveExecutable(tool.command)
            buildString {
                append(tool.qualifiedId).append(" [").append(tool.kind.name.lowercase()).append("] ")
                append(if (executable != null) "ready" else "unresolved")
                append(" · ").append(tool.title.take(160))
                if (executable != null) {
                    append(" · ").append(executable.source.name.lowercase())
                    executable.version?.let { append("@").append(it) }
                }
            }
        }
    }

    fun status(toolId: String): String {
        val tool = registry.tool(cleanToolId(toolId)) ?: return "ERROR: capability tool is unknown or ambiguous: ${toolId.take(220)}"
        val executable = registry.resolveExecutable(tool.command)
        return buildString {
            append("id=").append(tool.qualifiedId).append('\n')
            append("title=").append(tool.title).append('\n')
            append("kind=").append(tool.kind.name.lowercase()).append('\n')
            append("command=").append(tool.command).append('\n')
            append("languages=").append(tool.languages.sorted().joinToString(",")).append('\n')
            append("resolved=").append(executable != null)
            if (executable != null) {
                append('\n').append("source=").append(executable.source.name.lowercase())
                append('\n').append("path=").append(executable.resolvedPath)
                executable.ownerId?.let { append('\n').append("owner=").append(it) }
                executable.version?.let { append('\n').append("version=").append(it) }
            }
        }
    }

    fun prepareRun(toolId: String, relativePath: String?): PreparedRun {
        val cleanId = cleanToolId(toolId)
        val cleanPath = relativePath?.trim()?.takeIf(String::isNotBlank)?.also {
            require(it.length <= 1_000 && '\u0000' !in it && '\n' !in it && '\r' !in it) { "Invalid capability path" }
        }
        val tool = registry.tool(cleanId) ?: error("Capability tool is unknown or ambiguous: $cleanId")
        val executable = registry.resolveExecutable(tool.command)
            ?: error("Capability executable is not currently discovered: ${tool.command}")
        val plan = registry.toolPlan(tool.qualifiedId, cleanPath)
            ?: error("Capability is not applicable to ${cleanPath ?: "this workspace"}: ${tool.qualifiedId}")
        require(plan.steps.size == 1 && plan.steps.single().firstOrNull() == executable.resolvedPath) {
            "Capability execution plan is not bound to the discovered executable"
        }
        val identity = runtimeIdentity(tool, executable, plan, cleanPath)
        val preview = buildString {
            append(tool.title).append(" [").append(tool.kind.name.lowercase()).append("]\n")
            append("Capability: ").append(tool.qualifiedId).append('\n')
            append("Executable: ").append(executable.resolvedPath).append('\n')
            append("Source: ").append(executable.source.name.lowercase())
            executable.version?.let { append("@").append(it) }
            cleanPath?.let { append("\nFile: ").append(it) }
            append("\nargv: ").append(plan.display.take(1_200))
            append("\nBinding: sha256:").append(identity)
        }
        return PreparedRun(tool.qualifiedId, tool.title, tool.kind, cleanPath, executable, plan, identity, preview)
    }

    fun permissionResource(prepared: PreparedRun): String =
        "capability:${prepared.qualifiedId}@sha:${prepared.runtimeIdentity.take(24)}"

    suspend fun run(prepared: PreparedRun): String {
        val current = prepareRun(prepared.qualifiedId, prepared.relativePath)
        require(current.runtimeIdentity == prepared.runtimeIdentity) {
            "Capability binding changed after approval. Review the current executable/version before running it."
        }
        return execution.executePreparedContributedTool(
            toolId = prepared.qualifiedId,
            expectedExecutable = prepared.executable.resolvedPath,
            relativePath = prepared.relativePath,
        )
    }

    private fun cleanToolId(value: String): String = value.trim().also {
        require(it.length in 1..300 && it.matches(Regex("[A-Za-z0-9._:+-]+"))) { "Invalid capability tool id" }
    }

    private fun parseKind(value: String): DroideToolKind = runCatching { DroideToolKind.valueOf(value.trim().uppercase()) }
        .getOrElse { error("Unknown capability kind: ${value.take(80)}") }

    private fun runtimeIdentity(
        tool: ContributedToolCapability,
        executable: ExecutableCapability,
        plan: RunPlan,
        relativePath: String?,
    ): String {
        val canonical = buildString {
            append("tool=").append(tool.qualifiedId).append('\n')
            append("kind=").append(tool.kind.name).append('\n')
            append("command=").append(tool.command).append('\n')
            append("source=").append(executable.source.name).append('\n')
            append("owner=").append(executable.ownerId.orEmpty()).append('\n')
            append("version=").append(executable.version.orEmpty()).append('\n')
            append("path=").append(executable.resolvedPath).append('\n')
            append("relativePath=").append(relativePath.orEmpty()).append('\n')
            plan.steps.forEachIndexed { index, argv ->
                append("step[").append(index).append("]=")
                argv.forEach { arg -> append(arg.length).append(':').append(arg).append('|') }
                append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
