package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException

 
class AgentHookLifecycleBridge(
    private val hooks: HookManager,
    private val sessionId: () -> String,
    private val mode: () -> AgentMode,
) {
    suspend fun sessionStart(trigger: String) = hooks.dispatch(
        HookLifecycleEvent.SESSION_START,
        HookLifecycleContext(sessionId = sessionId(), mode = mode().name.lowercase(), trigger = trigger),
    )

    suspend fun userPrompt(prompt: String): HookDispatchResult = hooks.dispatch(
        HookLifecycleEvent.USER_PROMPT_SUBMIT,
        HookLifecycleContext(sessionId = sessionId(), mode = mode().name.lowercase(), prompt = prompt),
    )

     
    suspend fun beforePrompt(creatingSession: Boolean, resumeTrigger: String?, prompt: String): HookDispatchResult {
        val start = when {
            creatingSession -> sessionStart("startup")
            !resumeTrigger.isNullOrBlank() -> sessionStart(resumeTrigger)
            else -> HookDispatchResult()
        }
        val submitted = userPrompt(prompt)
        val context = sequenceOf(start.additionalContext, submitted.additionalContext)
            .filter { it.isNotBlank() }.joinToString("\n").take(HookLifecycleProtocol.MAX_CONTEXT_CHARS)
        return submitted.copy(additionalContext = context)
    }

     
    fun requiresSerializedTool(tool: String): Boolean = hooks.hasToolLifecycleHandlers(tool)

    suspend fun tool(tool: String, input: String, execute: suspend () -> String): String {
        val before = hooks.dispatch(
            HookLifecycleEvent.PRE_TOOL_USE,
            HookLifecycleContext(sessionId = sessionId(), mode = mode().name.lowercase(), toolName = tool, toolInput = input),
        )
        if (before.blocked) return "DENIED (hook): ${before.reason.ifBlank { "PreToolUse blocked $tool" }}"
        return try {
            val result = execute()
            val after = hooks.dispatch(
                HookLifecycleEvent.POST_TOOL_USE,
                HookLifecycleContext(
                    sessionId = sessionId(), mode = mode().name.lowercase(), toolName = tool,
                    toolInput = input, toolOutput = result,
                ),
            )
            decorate(result, before, after)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            hooks.dispatch(
                HookLifecycleEvent.POST_TOOL_USE_FAILURE,
                HookLifecycleContext(
                    sessionId = sessionId(), mode = mode().name.lowercase(), toolName = tool,
                    toolInput = input, error = t.message ?: t::class.java.simpleName,
                ),
            )
            throw t
        }
    }

    suspend fun compact(trigger: String, action: suspend () -> Boolean): HookedCompaction {
        val before = hooks.dispatch(
            HookLifecycleEvent.PRE_COMPACT,
            HookLifecycleContext(sessionId = sessionId(), mode = mode().name.lowercase(), trigger = trigger),
        )
        if (before.blocked) return HookedCompaction(false, before.reason.ifBlank { "PreCompact hook blocked compaction" })
        val changed = action()
        if (changed) hooks.dispatch(
            HookLifecycleEvent.POST_COMPACT,
            HookLifecycleContext(sessionId = sessionId(), mode = mode().name.lowercase(), trigger = trigger),
        )
        return HookedCompaction(changed)
    }

    suspend fun stop(): HookDispatchResult = hooks.dispatch(
        HookLifecycleEvent.STOP,
        HookLifecycleContext(sessionId = sessionId(), mode = mode().name.lowercase()),
    )

    private fun decorate(result: String, vararg hooks: HookDispatchResult): String {
        // Raw hook stdout/logs are diagnostics and must not become an implicit prompt-injection channel.

        val context = hooks.asSequence().map { it.additionalContext }.filter { it.isNotBlank() }
            .joinToString("\n").take(HookLifecycleProtocol.MAX_CONTEXT_CHARS)
        return buildString {
            append(result)
            if (context.isNotBlank()) append("\n[hook context]\n").append(context)
        }.take(12_000)
    }
}

data class HookedCompaction(val changed: Boolean, val blockedReason: String = "")
