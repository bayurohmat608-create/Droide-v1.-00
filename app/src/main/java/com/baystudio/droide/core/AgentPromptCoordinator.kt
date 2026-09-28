package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

 
class AgentPromptCoordinator(
    sessions: SessionManager,
    private val maxUserMessageChars: Int,
    private val maxAgentTurns: Int,
) {
    private val inbox = AgentPromptInbox(sessions)

    data class PromotionBatch(
        val nextConfig: AgentConfig,
        val userMessages: List<AgentMessage>,
        val apiEntries: List<JsonObject>,
        val streamParts: List<AgentStreamPart>,
        val deliveredSourceIds: List<String>,
        val fallbackTitle: String,
    )

    val pendingInputs = inbox.inputs

    fun load(inputs: List<AgentPendingInput>) = inbox.load(inputs)
    fun clear() = inbox.clear()

    fun submit(
        scope: CoroutineScope,
        userText: String,
        config: AgentConfig,
        delivery: AgentPromptDelivery,
        maxTurns: Int,
        currentSessionId: () -> String?,
        startRun: (Long) -> Unit,
        origin: AgentInputOrigin = AgentInputOrigin.USER,
        sourceId: String? = null,
        requiredSessionId: String? = null,
        prepareImages: suspend () -> List<AgentImageRef> = { emptyList() },
        ideContext: AgentIdeContextSnapshot? = null,
        reportError: (String?) -> Unit,
    ) {
        val invalid = validate(userText, config, maxTurns)
        if (invalid != null) {
            reportError(invalid)
            return
        }
        scope.launch {
            try {
                val images = prepareImages()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val activeSessionId = currentSessionId()
                    if (requiredSessionId != null && activeSessionId != requiredSessionId) return@launch
                    when (val decision = inbox.routeSubmission(activeSessionId, userText, delivery, config, origin, sourceId, images, ideContext)) {
                        is AgentPromptInbox.SubmissionDecision.StartRun -> {
                            reportError(null)
                            startRun(decision.lease)
                            return@launch
                        }
                        is AgentPromptInbox.SubmissionDecision.Admitted -> {
                            reportError(null)
                            return@launch
                        }
                        is AgentPromptInbox.SubmissionDecision.WaitForStartup -> {
                            inbox.awaitStartupResolution(decision.lease)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) reportError("Unable to admit prompt: ${t.message ?: "unknown error"}")
            }
        }
    }

    suspend fun admitSteerIfActive(sessionId: String?, userText: String, config: AgentConfig, ideContext: AgentIdeContextSnapshot? = null): Boolean {
        val invalid = validate(userText, config, maxAgentTurns)
        if (invalid != null) return false
        return inbox.admitSteerIfReady(sessionId, userText, config, ideContext = ideContext) != null
    }

    suspend fun reserveDirectRun(): Long = inbox.reserveDirectRun()
    suspend fun markRunReady(lease: Long): Boolean = inbox.markReady(lease)
    suspend fun markRunInactive(lease: Long): Boolean = inbox.markIdle(lease)
    suspend fun remove(sessionId: String, id: String): Boolean = inbox.remove(sessionId, id)

     
    suspend fun promoteAtBoundary(
        runLease: Long,
        allowQueue: Boolean,
        activeConfig: AgentConfig,
        step: Int,
        maxApiMessageChars: Int,
    ): PromotionBatch? {
        val promoted = if (allowQueue) inbox.takeForIdle(runLease, activeConfig)
        else inbox.takeSteers(runLease, activeConfig)
        if (promoted.isEmpty()) return null
        var nextConfig = activeConfig
        promoted.forEach { it.runtimeConfig?.let { cfg -> nextConfig = cfg } }
        return PromotionBatch(
            nextConfig = nextConfig,
            userMessages = promoted.filter { it.input.origin == AgentInputOrigin.USER }
                .map { AgentMessage("user", it.input.text) },
            apiEntries = promoted.map { item ->
                if (item.input.origin == AgentInputOrigin.USER) {
                    VisionSupport.userMessage(AgentIdeContextProjection.forModel(item.input.text, item.input.ideContext, maxApiMessageChars), item.input.images, maxApiMessageChars)
                } else buildJsonObject {
                    put("role", "user")
                    put("content", "[Background subagent completion; synthetic system event, not user-authored]\n${item.input.text}".take(maxApiMessageChars))
                }
            },
            streamParts = promoted.map { item ->
                val synthetic = item.input.origin == AgentInputOrigin.SUBAGENT
                AgentStreamPart(
                    id = "input:${item.input.id}",
                    step = step,
                    kind = if (synthetic) AgentStreamPartKind.STATUS else AgentStreamPartKind.INPUT,
                    state = AgentStreamPartState.COMPLETED,
                    title = if (synthetic) "Subagent result" else if (item.input.delivery == AgentPromptDelivery.STEER) "Steer" else "Queued prompt",
                    detail = if (synthetic) "Delivered from ${item.input.sourceId ?: "background task"}" else if (item.input.delivery == AgentPromptDelivery.STEER)
                        "Promoted at safe provider-turn boundary" else "Promoted from FIFO queue",
                    text = item.input.text,
                    endedAtMs = System.currentTimeMillis(),
                )
            },
            deliveredSourceIds = promoted.mapNotNull { item ->
                item.input.sourceId?.takeIf { item.input.origin == AgentInputOrigin.SUBAGENT }
            },
            fallbackTitle = promoted.first().input.text.take(40),
        )
    }

    private fun validate(userText: String, config: AgentConfig, maxTurns: Int): String? = when {
        userText.isBlank() -> "Prompt is empty"
        ProviderRegistry.findById(config.providerId) == null -> "Unknown AI provider"
        ProviderRegistry.findById(config.providerId)?.needsKey == true && config.apiKey.isBlank() &&
            ProviderRegistry.findById(config.providerId)?.let { !ProviderAuthMethods.canResolveWithoutStoredSecret(it) } == true ->
            "Connect an AI provider and enter its API key first"
        config.model.isBlank() -> "Select a validated model first"
        !ProviderModelIdentity.isCanonical(config.model) -> "Model ID is invalid or non-canonical; refresh the provider and reselect it"
        !ProviderModelCertificationRegistry.isCertified(config.providerId, config.model) ->
            "Refresh the provider and select a live-validated model first"
        userText.length > maxUserMessageChars -> "Message exceeds $maxUserMessageChars characters"
        maxTurns !in 1..maxAgentTurns -> "maxTurns must be between 1 and $maxAgentTurns"
        else -> null
    }
}
