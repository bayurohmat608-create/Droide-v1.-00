package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

// Durable busy-session prompt admission inbox.







class AgentPromptInbox(private val sessions: SessionManager) {
    data class Promoted(val input: AgentPendingInput, val runtimeConfig: AgentConfig?)

    sealed interface SubmissionDecision {
        data class StartRun(val lease: Long) : SubmissionDecision
        data class WaitForStartup(val lease: Long) : SubmissionDecision
        data class Admitted(val input: AgentPendingInput) : SubmissionDecision
    }

    private val mutex = Mutex()
    private val drain = AgentPromptDrainState()
    private val runtimeConfigs = mutableMapOf<String, AgentConfig>()
    private val _inputs = MutableStateFlow<List<AgentPendingInput>>(emptyList())
    private val _drainState = MutableStateFlow(drain.snapshot)
    val inputs: StateFlow<List<AgentPendingInput>> = _inputs.asStateFlow()
    internal val drainState: StateFlow<AgentPromptDrainSnapshot> = _drainState.asStateFlow()

    suspend fun routeSubmission(
        sessionId: String?,
        text: String,
        delivery: AgentPromptDelivery,
        config: AgentConfig,
        origin: AgentInputOrigin = AgentInputOrigin.USER,
        sourceId: String? = null,
        images: List<AgentImageRef> = emptyList(),
        ideContext: AgentIdeContextSnapshot? = null,
    ): SubmissionDecision = mutex.withLock {
        when (val route = drain.routeSubmission()) {
            is AgentPromptDrainRoute.Start -> {
                publishDrainState()
                SubmissionDecision.StartRun(route.lease)
            }
            is AgentPromptDrainRoute.WaitForStartup -> SubmissionDecision.WaitForStartup(route.lease)
            is AgentPromptDrainRoute.Admit -> {
                check(sessionId != null) { "Agent session became ready without a session id" }
                SubmissionDecision.Admitted(admitLocked(sessionId, text, delivery, config, origin, sourceId, images, ideContext))
            }
        }
    }

    suspend fun awaitStartupResolution(lease: Long) {
        drainState.filter { state ->
            state.lease != lease || state.phase != AgentPromptDrainPhase.STARTING
        }.first()
    }

    // Never starts a new run.
    suspend fun admitSteerIfReady(
        sessionId: String?,
        text: String,
        config: AgentConfig,
        origin: AgentInputOrigin = AgentInputOrigin.USER,
        sourceId: String? = null,
        images: List<AgentImageRef> = emptyList(),
        ideContext: AgentIdeContextSnapshot? = null,
    ): AgentPendingInput? = mutex.withLock {
        if (sessionId == null || drain.snapshot.phase != AgentPromptDrainPhase.READY) return@withLock null
        admitLocked(sessionId, text, AgentPromptDelivery.STEER, config, origin, sourceId, images, ideContext)
    }

    suspend fun reserveDirectRun(): Long = mutex.withLock {
        val lease = drain.reserveDirectRun()
        publishDrainState()
        lease
    }

    suspend fun markReady(lease: Long): Boolean = mutex.withLock {
        val changed = drain.markReady(lease)
        if (changed) publishDrainState()
        changed
    }

    suspend fun markIdle(lease: Long): Boolean = mutex.withLock {
        val changed = drain.markIdle(lease)
        if (changed) publishDrainState()
        changed
    }

    suspend fun takeSteers(runLease: Long, activeConfig: AgentConfig): List<Promoted> = mutex.withLock {
        if (!drain.isReady(runLease)) return@withLock emptyList()
        val selected = _inputs.value.filter {
            it.delivery == AgentPromptDelivery.STEER && canPromote(it, activeConfig)
        }
        if (selected.isEmpty()) return@withLock emptyList()
        removeSelected(selected)
    }

    




    suspend fun takeForIdle(runLease: Long, activeConfig: AgentConfig): List<Promoted> = mutex.withLock {
        if (!drain.isReady(runLease)) return@withLock emptyList()
        val steers = _inputs.value.filter {
            it.delivery == AgentPromptDelivery.STEER && canPromote(it, activeConfig)
        }
        val nextQueue = _inputs.value.firstOrNull { it.delivery == AgentPromptDelivery.QUEUE }
        val selected = if (steers.isNotEmpty()) steers else
            listOfNotNull(nextQueue?.takeIf { canPromote(it, activeConfig) })
        if (selected.isEmpty()) {
            if (drain.markIdle(runLease)) publishDrainState()
            return@withLock emptyList()
        }
        removeSelected(selected)
    }

    suspend fun remove(sessionId: String, id: String): Boolean = mutex.withLock {
        val before = _inputs.value
        if (before.none { it.id == id }) return@withLock false
        val next = before.filterNot { it.id == id }
        // A failed disk write must not make the in-memory inbox disagree with the durable session that will be restored after process death.

        sessions.savePendingInputs(sessionId, next)
        runtimeConfigs.remove(id)
        _inputs.value = next
        true
    }

    fun load(inputs: List<AgentPendingInput>) {
        runtimeConfigs.clear()
        _inputs.value = inputs.take(MAX_PENDING_INPUTS)
        drain.reset()
        publishDrainState()
    }

    fun clear() {
        runtimeConfigs.clear()
        _inputs.value = emptyList()
        drain.reset()
        publishDrainState()
    }

    private suspend fun admitLocked(
        sessionId: String,
        text: String,
        delivery: AgentPromptDelivery,
        config: AgentConfig,
        origin: AgentInputOrigin,
        sourceId: String?,
        images: List<AgentImageRef>,
        ideContext: AgentIdeContextSnapshot?,
    ): AgentPendingInput {
        require(text.isNotBlank()) { "Prompt is empty" }
        require(text.length <= AgentService.MAX_USER_MESSAGE_CHARS) {
            "Message exceeds ${AgentService.MAX_USER_MESSAGE_CHARS} characters"
        }
        require(_inputs.value.size < MAX_PENDING_INPUTS) { "Pending prompt inbox is full" }
        val modelId = ProviderModelIdentity.requireCanonical(config.model)
        ProviderRuntimeGuard.requireCertifiedSelection(config.providerId, config.baseUrl, modelId)
        val admitted = AgentPendingInput(
            id = "in-${UUID.randomUUID()}",
            text = text,
            delivery = delivery,
            providerId = config.providerId,
            modelId = modelId,
            reasoningEffort = config.reasoningEffort,
            origin = origin,
            sourceId = sourceId,
            images = images,
            ideContext = ideContext,
        )
        runtimeConfigs[admitted.id] = config
        _inputs.value = _inputs.value + admitted
        sessions.savePendingInputs(sessionId, _inputs.value)
        return admitted
    }

    private fun removeSelected(selected: List<AgentPendingInput>): List<Promoted> {
        val ids = selected.mapTo(hashSetOf()) { it.id }
        _inputs.value = _inputs.value.filterNot { it.id in ids }
        return selected.map { Promoted(it, runtimeConfigs.remove(it.id)) }
    }

    private fun canPromote(input: AgentPendingInput, activeConfig: AgentConfig): Boolean {
        if (runtimeConfigs[input.id] != null) return true
        return input.providerId == activeConfig.providerId &&
            input.modelId == activeConfig.model &&
            input.reasoningEffort == activeConfig.reasoningEffort
    }

    private fun publishDrainState() {
        _drainState.value = drain.snapshot
    }

    companion object {
        const val MAX_PENDING_INPUTS = 32
    }
}
