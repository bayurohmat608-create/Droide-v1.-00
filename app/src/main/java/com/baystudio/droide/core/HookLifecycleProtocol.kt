package com.baystudio.droide.core

 
enum class HookLifecycleEvent(val configKey: String, val blockable: Boolean) {
    SESSION_START("SessionStart", false),
    USER_PROMPT_SUBMIT("UserPromptSubmit", true),
    PRE_TOOL_USE("PreToolUse", true),
    POST_TOOL_USE("PostToolUse", false),
    POST_TOOL_USE_FAILURE("PostToolUseFailure", false),
    PRE_COMPACT("PreCompact", true),
    POST_COMPACT("PostCompact", false),
    STOP("Stop", true),
    ;

    companion object {
        fun fromConfigKey(value: String): HookLifecycleEvent? = entries.firstOrNull {
            it.configKey.equals(value.trim(), ignoreCase = true)
        }
    }
}

data class HookLifecycleDecision(
    val blocked: Boolean = false,
    val reason: String = "",
    val additionalContext: String = "",
)

 
object HookLifecycleProtocol {
    const val SCHEMA_VERSION = 1
    const val MAX_MATCHER_CHARS = 160
    const val MAX_REASON_CHARS = 1_000
    const val MAX_CONTEXT_CHARS = 4_000
    const val DEFAULT_TIMEOUT_MS = 10_000L
    const val MIN_TIMEOUT_MS = 500L
    const val MAX_TIMEOUT_MS = 30_000L

    fun timeoutMs(value: Long): Long = value.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

     
    fun matches(pattern: String, target: String): Boolean {
        val bounded = pattern.trim().take(MAX_MATCHER_CHARS)
        if (bounded.isBlank() || bounded == "*") return true
        val regex = buildString {
            append('^')
            bounded.forEach { ch ->
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
            append('$')
        }
        return Regex(regex).matches(target.take(512))
    }

    fun decision(
        event: HookLifecycleEvent,
        rawDecision: String?,
        reason: String?,
        additionalContext: String?,
    ): HookLifecycleDecision {
        val requestedBlock = rawDecision?.trim()?.equals("block", ignoreCase = true) == true
        return HookLifecycleDecision(
            blocked = requestedBlock && event.blockable,
            reason = reason.orEmpty().trim().take(MAX_REASON_CHARS),
            additionalContext = additionalContext.orEmpty().trim().take(MAX_CONTEXT_CHARS),
        )
    }
}
