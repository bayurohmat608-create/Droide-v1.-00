package com.baystudio.droide.core

// The model must not collapse these states: - a callable tool was advertised; - an environment dependency exists; - an operation was started.








internal enum class AgentToolEvidenceState {
    SUCCEEDED,
    STARTED,
    FAILED,
    DENIED,
    UNAVAILABLE,
}

internal data class AgentToolEvidence(
    val state: AgentToolEvidenceState,
    val operationStarted: Boolean,
    val success: Boolean?,
    val detail: String,
) {
    val isFailure: Boolean
        get() = state in setOf(AgentToolEvidenceState.FAILED, AgentToolEvidenceState.DENIED, AgentToolEvidenceState.UNAVAILABLE)

    fun annotate(tool: String, raw: String): String = buildString {
        append("[DROIDE_TOOL_EVIDENCE tool=")
        append(tool.take(120).replace(Regex("[^A-Za-z0-9_.:-]"), "_"))
        append(" state=").append(state.name)
        append(" operation_started=").append(operationStarted)
        append(" success=").append(success?.toString() ?: "unverified")
        append("]\n")
        append(raw)
    }

    val uiDetail: String
        get() = when (state) {
            AgentToolEvidenceState.SUCCEEDED -> "Succeeded"
            AgentToolEvidenceState.STARTED -> "Started · completion unverified"
            AgentToolEvidenceState.FAILED -> "Failed"
            AgentToolEvidenceState.DENIED -> "Denied"
            AgentToolEvidenceState.UNAVAILABLE -> "Unavailable"
        }
}

internal object AgentToolResultSemantics {
    private val evidenceMarkerRegex = Regex(
        "^\\[DROIDE_TOOL_EVIDENCE\\s+tool=([^\\s\\]]+)\\s+state=(SUCCEEDED|STARTED|FAILED|DENIED|UNAVAILABLE)\\s+operation_started=(true|false)\\s+success=(true|false|unverified)\\][ \t]*(?:\\r?\\n)?",
        RegexOption.IGNORE_CASE,
    )
    private val exitRegex = Regex("(?:^|\\n)exit=(-?\\d+)(?:\\s|$)")
    private val backgroundStateRegex = Regex("(?:^|\\s)state=(running|exited|failed|timed_out|killed)(?:\\s|$)", RegexOption.IGNORE_CASE)
    private val httpFailureRegex = Regex("^HTTP\\s+[45]\\d\\d(?:\\s|:)", RegexOption.IGNORE_CASE)

    // Parse evidence already attached to a durable tool result without re-inferring success.
    fun parseAnnotation(raw: String): AgentToolEvidence? {
        val match = evidenceMarkerRegex.find(raw.trimStart()) ?: return null
        val state = runCatching { AgentToolEvidenceState.valueOf(match.groupValues[2].uppercase()) }.getOrNull() ?: return null
        val operationStarted = match.groupValues[3].equals("true", ignoreCase = true)
        val success = when (match.groupValues[4].lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        val detail = when (state) {
            AgentToolEvidenceState.SUCCEEDED -> "Succeeded"
            AgentToolEvidenceState.STARTED -> "Started · completion unverified"
            AgentToolEvidenceState.FAILED -> "Failed"
            AgentToolEvidenceState.DENIED -> "Denied"
            AgentToolEvidenceState.UNAVAILABLE -> "Unavailable"
        }
        return AgentToolEvidence(state, operationStarted, success, detail)
    }

     
    fun stripAnnotation(raw: String): String = evidenceMarkerRegex.replaceFirst(raw.trimStart(), "")

    fun classify(tool: String, raw: String, operation: String? = null): AgentToolEvidence {
        val text = raw.trimStart()
        if (text.startsWith("DENIED", ignoreCase = true)) {
            return AgentToolEvidence(AgentToolEvidenceState.DENIED, operationStarted = false, success = false, detail = "Policy or user denied execution")
        }
        if (text.contains("BACKGROUND_JOB_STARTED")) {
            return AgentToolEvidence(AgentToolEvidenceState.STARTED, operationStarted = true, success = null, detail = "Background process started; terminal result not known yet")
        }
        backgroundStateRegex.find(text)?.groupValues?.getOrNull(1)?.lowercase()?.let { state ->
            return when (state) {
                "running" -> AgentToolEvidence(AgentToolEvidenceState.STARTED, true, null, "Background process is still running")
                "exited" -> {
                    val code = exitRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (code == 0) AgentToolEvidence(AgentToolEvidenceState.SUCCEEDED, true, true, "Background process exited with code 0")
                    else AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Background process exited without verified success")
                }
                "killed" -> if (operation == "kill") AgentToolEvidence(AgentToolEvidenceState.SUCCEEDED, true, true, "Background process was killed as requested")
                    else AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Background process was killed")
                else -> AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Background process ended in $state")
            }
        }
        exitRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { code ->
            return if (code == 0) {
                AgentToolEvidence(AgentToolEvidenceState.SUCCEEDED, true, true, "Process exited with code 0")
            } else {
                AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Process exited with code $code")
            }
        }
        if (text.contains("[TIMEOUT", ignoreCase = true) || text == "(timeout)" || text.contains("state=timed_out", ignoreCase = true)) {
            return AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Operation timed out")
        }
        val unavailable = text.startsWith("Unknown tool:", ignoreCase = true) ||
            text.contains("tool not found", ignoreCase = true) ||
            text.contains("tool definition is stale or unavailable", ignoreCase = true) ||
            text.contains("manager unavailable", ignoreCase = true) ||
            text.contains("subagents unavailable", ignoreCase = true) ||
            text.contains("executable not found", ignoreCase = true) ||
            text.contains("command not found", ignoreCase = true) ||
            text.contains("not installed", ignoreCase = true) ||
            (tool in setOf("diagnose", "lsp") && text.contains("unavailable", ignoreCase = true))
        if (unavailable) {
            return AgentToolEvidence(AgentToolEvidenceState.UNAVAILABLE, false, false, "Required tool/runtime dependency is unavailable")
        }
        if (text.startsWith("ERROR", ignoreCase = true) || text.startsWith("git error:", ignoreCase = true) || httpFailureRegex.containsMatchIn(text)) {
            return AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Tool returned an explicit failure")
        }
        return AgentToolEvidence(AgentToolEvidenceState.SUCCEEDED, true, true, "Tool returned normally")
    }
}
