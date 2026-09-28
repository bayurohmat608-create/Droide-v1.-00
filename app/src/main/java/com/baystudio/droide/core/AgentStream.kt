package com.baystudio.droide.core

import kotlinx.serialization.Serializable

// The UI consumes ordered parts, never provider-specific SSE/JSON.







@Serializable
enum class AgentStreamPartKind { STEP, INPUT, TEXT, TOOL, COMPACTION, RETRY, STATUS }

@Serializable
enum class AgentStreamPartState { PENDING, RUNNING, COMPLETED, ERROR, CANCELLED }

@Serializable
data class AgentStreamPart(
    val id: String,
    val step: Int,
    val kind: AgentStreamPartKind,
    val state: AgentStreamPartState,
    val title: String = "",
    val detail: String = "",
    val text: String = "",
    val toolName: String = "",
    val toolCallId: String = "",
    val input: String = "",
    val output: String = "",
    val finishReason: String = "",
    val attempt: Int = 0,
    val nextRetryAtMs: Long? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val costUsd: Double = 0.0,
    val tokenUsageAuthority: UsageAuthority = UsageAuthority.UNKNOWN,
    val costAuthority: UsageAuthority = UsageAuthority.UNKNOWN,
    val startedAtMs: Long = System.currentTimeMillis(),
    val endedAtMs: Long? = null,
) {
    val durationMs: Long
        get() = ((endedAtMs ?: System.currentTimeMillis()) - startedAtMs).coerceAtLeast(0L)
}

@Serializable
enum class AgentStreamTransport { UNKNOWN, NATIVE, BUFFERED }

 
@Serializable
data class AgentStreamState(
    val runId: Long = 0L,
    val sessionId: String = "",
    val status: Status = Status.IDLE,
    val providerId: String = "",
    val modelId: String = "",
    val activeStep: Int = 0,
    val baseMessageCount: Int = 0,
    val startedAtMs: Long = 0L,
    val endedAtMs: Long? = null,
    val retryAttempt: Int = 0,
    val retryAtMs: Long? = null,
    val transport: AgentStreamTransport = AgentStreamTransport.UNKNOWN,
    val parts: List<AgentStreamPart> = emptyList(),
) {
    @Serializable
    enum class Status { IDLE, BUSY, RETRYING, ERROR, CANCELLED }

    val durationMs: Long
        get() = if (startedAtMs <= 0L) 0L else ((endedAtMs ?: System.currentTimeMillis()) - startedAtMs).coerceAtLeast(0L)
}
