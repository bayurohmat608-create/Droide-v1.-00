package com.baystudio.droide.core

import kotlinx.serialization.Serializable

// API keys are deliberately never persisted.


@Serializable
enum class AgentPromptDelivery { STEER, QUEUE }

@Serializable
enum class AgentInputOrigin { USER, SUBAGENT }

@Serializable
data class AgentPendingInput(
    val id: String,
    val text: String,
    val delivery: AgentPromptDelivery,
    val providerId: String,
    val modelId: String,
    val reasoningEffort: ReasoningEffort? = null,
    val origin: AgentInputOrigin = AgentInputOrigin.USER,
    val sourceId: String? = null,
    val images: List<AgentImageRef> = emptyList(),
     
    val ideContext: AgentIdeContextSnapshot? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
