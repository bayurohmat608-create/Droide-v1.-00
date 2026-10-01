package com.baystudio.droide.core
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
@Serializable
data class AgentMessage(
    val role: String,
    val content: String,
    val streamPart: AgentStreamPart? = null,
     
    val forkHistoryEnd: Int? = null,
)
@Serializable
data class AgentConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "",
    val providerId: String = "",
    val reasoningEffort: ReasoningEffort? = null,
)


class AgentService(
    private val files: FileRepository,
    private val terminal: ITerminalSession,
    private val git: GitManager,
    val approvals: ApprovalManager = ApprovalManager(),
    val lsp: LspManager = CliLspManager(terminal, files),
    val questions: QuestionManager = QuestionManager(),
    val perms: PermissionEngine = PermissionEngine(),
    val workDir: File = files.root,
    private val subagentDepth: Int = 0,
    private val maxSubagentDepth: Int = SubagentManager.DEFAULT_MAX_DEPTH,
    private val systemOverlay: String = "",
    private val executionIdentity: AgentExecutionIdentity = AgentExecutionIdentity.primary(),
    private val agentPluginSource: AgentPluginSource = AgentPluginSource.EMPTY, private val documentAuthority: WorkspaceDocumentAuthority? = null,
    private val ideActions: AgentIdeActions? = null,
    private val toolchainActions: AgentToolchainActions? = null,
    private val capabilityActions: AgentCapabilityActions? = null, val browserController: AgentBrowserController? = null,
    internal val mcpProcessHost: StdioProcessHost? = null,
) {
    private val llm = LlmClient()
    private val hooks = HookManager(
        workDir, terminal, perms, approvals,
        provenanceProvider = { requestProvenance() }, permissionScopeProvider = { permissionScope() },
        plugins = agentPluginSource,
    )
    private val lifecycleHooks by lazy { AgentHookLifecycleBridge(hooks, { currentSessionId.orEmpty() }, { _mode.value }) }
    val sessions = SessionManager(workDir, llm)
    private val promptCoordinator = AgentPromptCoordinator(sessions, MAX_USER_MESSAGE_CHARS, MAX_AGENT_TURNS)
    val pendingInputs: StateFlow<List<AgentPendingInput>> = promptCoordinator.pendingInputs
    val todos = TodoManager()
    val tokens = TokenTracker()
    private val json = Json { ignoreUnknownKeys = true }
    private val editHistory = EditHistory(files, documentAuthority, lsp)
    private val lspEditActions: AgentLspEditActions? = documentAuthority?.let { AgentLspEditActions(files, lsp, editHistory, it) }
    private val backgroundCommands = BackgroundCommandManager(terminal)
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val subagentBridge = AgentSubagentBridge(
        files = files, terminal = terminal, git = git, approvals = approvals, lsp = lsp, questions = questions,
        permissions = perms, workDir = workDir, depth = subagentDepth, maxDepth = maxSubagentDepth,
        pluginSource = agentPluginSource, documentAuthority = documentAuthority,
        maxAssistantChars = MAX_ASSISTANT_CHARS, maxApiMessageChars = MAX_API_MESSAGE_CHARS,
        onBackgroundCompletion = { completion ->
            val record = completion.record
            if (record.delivery == SubagentDeliveryState.PENDING && currentSessionId == record.parentSessionId) {
                submitAsync(
                    lifecycleScope, renderSubagentCompletionText(record, MAX_API_MESSAGE_CHARS, completion.activeSiblings), completion.config, AgentPromptDelivery.STEER,
                    inputOrigin = AgentInputOrigin.SUBAGENT, sourceId = record.id, requiredSessionId = record.parentSessionId,
                )
            }
        },
    )
    private val subagentManager: SubagentManager? get() = subagentBridge.manager
    val subagentDashboard: StateFlow<SubagentDashboardState> get() = subagentBridge.dashboard
    suspend fun controlSubagentFromUi(action: String, taskId: String, message: String? = null): String {
        val sessionId = currentSessionId ?: return "No active parent session."
        return try {
            subagentBridge.controlFromUi(sessionId, action, taskId, message).take(5_000)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            "ERROR: subagent action failed: ${error.message?.take(320) ?: error::class.java.simpleName}"
        }
    }
    suspend fun steerActive(userText: String, config: AgentConfig, requiredSessionId: String, ideContext: AgentIdeContextSnapshot? = null): Boolean = currentSessionId == requiredSessionId && promptCoordinator.admitSteerIfActive(currentSessionId, userText, config, ideContext)
    private val _messages = MutableStateFlow<List<AgentMessage>>(emptyList())
    val messages: StateFlow<List<AgentMessage>> = _messages.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _mode = MutableStateFlow(AgentMode.BUILD)
    val mode: StateFlow<AgentMode> = _mode.asStateFlow(); internal fun mcpPluginSource(): AgentPluginSource = agentPluginSource
    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()
    private val _streamState = MutableStateFlow(AgentStreamState())
    private val streamProjector = AgentStreamProjector(_streamState, MAX_ASSISTANT_CHARS, MAX_TOOL_ARGUMENT_CHARS)
    val streamState: StateFlow<AgentStreamState> = _streamState.asStateFlow()
    fun setMode(m: AgentMode) { _mode.value = m }
    private val conversationHistory = AgentConversationHistory()
    private val _apiHistory: MutableList<JsonObject> get() = conversationHistory.active
    var currentSessionId: String? = null
        private set
     
    private var pendingSessionStartTrigger: String? = null
    private var job: Job? = null
    private val runGeneration = AtomicLong(0L)
    private fun requestProvenance(sessionId: String? = currentSessionId): AgentRequestProvenance = executionIdentity.request(sessionId)
    private fun permissionScope(): String = AgentPermissionScope.id(_mode.value, requestProvenance())
    fun cancel() { job?.cancel() }
    fun shutdown() {
        currentSessionId?.let(approvals::clearSessionApprovals); job?.cancel(); McpManager.shutdownSessions(workDir, mcpProcessHost)
        subagentBridge.close()
        backgroundCommands.close()
        lifecycleScope.cancel("Agent service shut down")
    }
    fun clear() {
        currentSessionId?.let(approvals::clearSessionApprovals); job?.cancel()
        runGeneration.incrementAndGet()
        promptCoordinator.clear()
        _messages.value = emptyList()
        conversationHistory.resetNew()
        currentSessionId = null
        pendingSessionStartTrigger = null
        subagentBridge.bindParent(null)
        perms.clearSessionAllows()
        todos.clear()
        _persistenceError.value = null
        _streamState.value = AgentStreamState()
    }
    fun loadSession(s: DroideSession) {
        val previousSessionId = currentSessionId
        job?.cancel()
        runGeneration.incrementAndGet()
        promptCoordinator.load(s.pendingInputs)
        _mode.value = s.mode
        _messages.value = conversationHistory.load(s)
        currentSessionId = s.id
        subagentBridge.bindParent(s.id)
        if (previousSessionId != s.id) { previousSessionId?.let(approvals::clearSessionApprovals); perms.clearSessionAllows() }
        todos.set(s.todos, s.id)
        _persistenceError.value = null
        _streamState.value = AgentStreamState()
        pendingSessionStartTrigger = "resume"
    }
    suspend fun compactCurrent(config: AgentConfig): String = try {
        AgentManualCompaction.run(
            currentSessionId, sessions, _messages.value, completeApiHistoryForModel(), conversationHistory.completeExactOrNull(),
            todos.get(currentSessionId.orEmpty()), _mode.value, pendingInputs.value, config, tokens, lifecycleHooks, ::loadSession,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val message = "Compaction failed: ${error.message?.take(240) ?: error::class.java.simpleName}"
        _persistenceError.value = message
        message
    }
    suspend fun undoLast(): String = editHistory.undo()


    fun submitAsync(
        scope: CoroutineScope,
        userText: String,
        config: AgentConfig,
        delivery: AgentPromptDelivery = AgentPromptDelivery.STEER,
        maxTurns: Int = MAX_AGENT_TURNS,
        inputOrigin: AgentInputOrigin = AgentInputOrigin.USER,
        sourceId: String? = null,
        requiredSessionId: String? = null,
        imagePaths: List<String> = emptyList(),
        ideContext: AgentIdeContextSnapshot? = null,
    ) = promptCoordinator.submit(
        scope = scope, userText = userText, config = config, delivery = delivery, maxTurns = maxTurns,
        currentSessionId = { currentSessionId },
        startRun = { lease -> job = scope.launch { chat(userText, config, maxTurns, lease, inputOrigin, sourceId, requiredSessionId, imagePaths, ideContext) } },
        origin = inputOrigin,
        sourceId = sourceId,
        requiredSessionId = requiredSessionId,
        prepareImages = { VisionSupport.stage(workDir, imagePaths, config) },
        ideContext = ideContext,
        reportError = { _persistenceError.value = it },
    )
    fun chatAsync(scope: CoroutineScope, userText: String, config: AgentConfig, maxTurns: Int = MAX_AGENT_TURNS) =
        submitAsync(scope, userText, config, AgentPromptDelivery.STEER, maxTurns)
    fun chatAsyncWithImages(scope: CoroutineScope, userText: String, config: AgentConfig, imagePaths: List<String>, maxTurns: Int = MAX_AGENT_TURNS) = submitAsync(scope, userText, config, AgentPromptDelivery.STEER, maxTurns, imagePaths = imagePaths)
    suspend fun removePendingInput(id: String): Boolean {
        val sid = currentSessionId ?: return false
        return try {
            promptCoordinator.remove(sid, id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _persistenceError.value = "Could not remove pending prompt: ${error.message?.take(240) ?: error::class.java.simpleName}"
            false
        }
    }
    suspend fun chat(
        userText: String,
        config: AgentConfig,
        maxTurns: Int = MAX_AGENT_TURNS,
        admissionLease: Long? = null,
        inputOrigin: AgentInputOrigin = AgentInputOrigin.USER,
        sourceId: String? = null,
        requiredSessionId: String? = null,
        imagePaths: List<String> = emptyList(),
        ideContext: AgentIdeContextSnapshot? = null,
    ) {
        require(ProviderRegistry.findById(config.providerId)?.let { !it.needsKey || config.apiKey.isNotBlank() || ProviderAuthMethods.canResolveWithoutStoredSecret(it) } == true) { "Connect an AI provider and enter its API key first" }
        val modelId = ProviderModelIdentity.requireCanonical(config.model)
        ProviderRuntimeGuard.requireCertifiedSelection(config.providerId, config.baseUrl, modelId)
        require(userText.length <= MAX_USER_MESSAGE_CHARS) { "Message exceeds $MAX_USER_MESSAGE_CHARS characters" }
        require(maxTurns in 1..MAX_AGENT_TURNS) { "maxTurns must be between 1 and $MAX_AGENT_TURNS" }
        require(inputOrigin == AgentInputOrigin.USER || imagePaths.isEmpty()) { "Synthetic inputs cannot attach user images" }
        val stagedImages = VisionSupport.stage(workDir, imagePaths, config)
        

        val runLease = admissionLease ?: promptCoordinator.reserveDirectRun()
        // Recheck state around cancellation-sensitive boundaries.

        val generation = runGeneration.incrementAndGet()
        val creatingSession = currentSessionId == null
        try {
            if (requiredSessionId != null && currentSessionId != requiredSessionId) {
                throw CancellationException("Target parent session is no longer active")
            }
            if (currentSessionId == null) {
                val created = sessions.create(title = userText.take(40), parentId = null)
                if (runGeneration.get() != generation) throw CancellationException("Agent session changed")
                currentSessionId = created.id
                conversationHistory.resetNew()
                subagentBridge.bindParent(created.id)
            }
        } catch (t: Throwable) {
            withContext(NonCancellable) { promptCoordinator.markRunInactive(runLease) }
            throw t
        }
        val runSessionId = currentSessionId ?: run {
            withContext(NonCancellable) { promptCoordinator.markRunInactive(runLease) }
            throw CancellationException("Agent session changed")
        }
        if (!promptCoordinator.markRunReady(runLease)) {
            throw CancellationException("Agent admission lease expired")
        }
        _streamState.value = AgentStreamState(
            runId = generation,
            sessionId = runSessionId,
            status = AgentStreamState.Status.BUSY,
            providerId = config.providerId,
            modelId = modelId,
            startedAtMs = System.currentTimeMillis(),
        )
        _busy.value = true


        val unresolvedApiToolCalls = linkedMapOf<String, String>()
        try {
            ensureRunCurrent(generation, runSessionId)
            val resumeTrigger = pendingSessionStartTrigger.also { pendingSessionStartTrigger = null }
            val promptHook = lifecycleHooks.beforePrompt(creatingSession, resumeTrigger, userText)
            if (promptHook.blocked) {
                _messages.value = _messages.value + AgentMessage("assistant", "Prompt blocked by project hook: ${promptHook.reason}")
                return
            }
            
            val projectBrief = loadProjectBrief() + promptHook.additionalContext.takeIf { it.isNotBlank() }
                ?.let { "\n\n[Lifecycle hook context]\n$it" }.orEmpty()
            ensureRunCurrent(generation, runSessionId)
            

            val uiHistory = _messages.value.toMutableList()
            val initialProjection = subagentBridge.projectInitial(inputOrigin, sourceId, userText, generation, stagedImages, ideContext)
            uiHistory += initialProjection.messages
            conversationHistory.append(initialProjection.apiEntries)
            val recoveredProjection = subagentBridge.recover(runSessionId, sourceId, _apiHistory.toList())
            ensureRunCurrent(generation, runSessionId)
            uiHistory += recoveredProjection.messages
            conversationHistory.append(recoveredProjection.apiEntries)
            _messages.value = uiHistory.toList()
            _streamState.value = _streamState.value.copy(baseMessageCount = _messages.value.size)
            val initialDeliveryIds = initialProjection.deliveryTaskIds + recoveredProjection.deliveryTaskIds
            if (initialDeliveryIds.isNotEmpty()) {
                // Parent persistence must win before the child is marked delivered.


                val persisted = withContext(NonCancellable) {
                    persistCurrent(userText.take(40), runSessionId, generation)
                }
                if (persisted) subagentBridge.markDelivered(initialDeliveryIds)
            }
            var lastSig = ""
            var repeat = 0
            var activeConfig = config
            var modelStepsInSegment = 0
            var turn = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                ensureRunCurrent(generation, runSessionId)
                promotePendingAtBoundary(
                    runLease = runLease,
                    allowQueue = false,
                    uiHistory = uiHistory,
                    activeConfig = activeConfig,
                    runSessionId = runSessionId,
                    generation = generation,
                    step = turn + 1,
                )?.let { promotedConfig ->
                    activeConfig = promotedConfig
                    modelStepsInSegment = 0
                }
                turn += 1
                modelStepsInSegment += 1
                

                ProviderRuntimeGuard.requireCertifiedSelection(activeConfig.providerId, activeConfig.baseUrl, activeConfig.model)
                val finalStep = modelStepsInSegment == maxTurns || !ModelCapabilityRegistry.allowsToolUse(activeConfig.providerId, activeConfig.model)
                // Context compaction happens at this safe model-step boundary and never consumes an additional agent step.


                val modeInstruction = when (_mode.value) {
                    AgentMode.PLAN -> "PLAN mode is strictly read-only. Investigate first, then produce an actionable implementation plan. Do not request mutation tools."
                    AgentMode.REVIEW -> "REVIEW mode is strictly read-only. Inspect code and evidence, prioritize concrete defects/regressions/security issues, and do not request mutation tools."
                    AgentMode.EXPLORE -> "EXPLORE mode is read-only and optimized for codebase investigation."
                    AgentMode.BUILD -> "BUILD mode may use permitted tools to implement and verify changes; prefer apply_patch for multi-file changes."
                }
                val pluginSurface = AgentPluginToolSurfaceFactory.create(workDir, agentPluginSource, perms, permissionScope(), finalStep, mcpProcessHost)
                val toolDefinitions = if (finalStep) null else AgentTools.definitionsWithCustom(
                    pluginSurface.skills, CustomToolManager(workDir), allowSubagents = subagentManager != null, mode = _mode.value,
                    permissions = perms, permissionScope = permissionScope(), subagentProfiles = subagentManager?.profileSummaries().orEmpty(),
                    mcpTools = pluginSurface.mcpTools, rules = pluginSurface.rules, pluginTools = pluginSurface.pluginTools,
                    ideAvailable = ideActions != null, lspEditAvailable = lspEditActions != null,
                    toolchainAvailable = toolchainActions != null, capabilityAvailable = capabilityActions != null,
                    browserAvailable = browserController != null)
                val systemText = AgentRuntimePrompt.compose(_mode.value, modeInstruction, projectBrief, systemOverlay, finalStep, toolDefinitions)
                var preflightCompacted = false
                var overflowRecovered = false
                var reqJson: JsonObject
                var resp: String
                var streamMode = LlmStreamMode.NATIVE
                val stepId = "step:$turn"
                _streamState.value = _streamState.value.copy(status = AgentStreamState.Status.BUSY, activeStep = turn)
                upsertStreamPart(
                    AgentStreamPart(
                        id = stepId,
                        step = turn,
                        kind = AgentStreamPartKind.STEP,
                        state = AgentStreamPartState.RUNNING,
                        title = if (finalStep) "Final response" else "Step $turn",
                        detail = "${activeConfig.model} · ${activeConfig.providerId.ifBlank { "provider" }}",
                    )
                )
                while (true) {
                    currentCoroutineContext().ensureActive()
                    ensureRunCurrent(generation, runSessionId)
                    val activeHistory = VisionSupport.materializeHistory(completeApiHistoryForModel(), workDir)
                    val estimate = ContextBudget.estimate(systemText, activeHistory, toolDefinitions, activeConfig.providerId, activeConfig.model)
                    if (estimate.shouldCompact && !preflightCompacted) {
                        val compactId = "compaction:$turn:preflight"
                        upsertStreamPart(
                            AgentStreamPart(
                                id = compactId,
                                step = turn,
                                kind = AgentStreamPartKind.COMPACTION,
                                state = AgentStreamPartState.RUNNING,
                                title = "Compacting context",
                                detail = "Preserving complete recent conversation groups",
                            )
                        )
                        val changed = compactActiveContext(activeConfig, runSessionId, generation, overflowRecovery = false)
                        updateStreamPart(compactId) {
                            it.copy(
                                state = AgentStreamPartState.COMPLETED,
                                detail = if (changed) "Context compacted" else "No compaction required",
                                endedAtMs = System.currentTimeMillis(),
                            )
                        }
                        streamPart(compactId)?.let {
                            uiHistory += AgentMessage("assistant", "", streamPart = it)
                            _messages.value = uiHistory.toList()
                        }
                        preflightCompacted = true
                        if (changed) continue
                    }
                    ProviderRuntimeGuard.requireCertifiedSelection(activeConfig.providerId, activeConfig.baseUrl, activeConfig.model)
                    reqJson = AgentRequestSupport.build(activeConfig, systemText, activeHistory, toolDefinitions)
                    val streamedText = StringBuilder()
                    val textPartId = "text:$turn"
                    try {
                        val streamResult = llm.chatCompletionsStream(
                            activeConfig.providerId,
                            activeConfig.baseUrl,
                            activeConfig.apiKey,
                            activeConfig.model,
                            reqJson.toString(),
                        ) { event ->
                            if (!isRunCurrent(generation, runSessionId)) return@chatCompletionsStream
                            when (event) {
                                is LlmStreamEvent.TextDelta -> {
                                    if (_streamState.value.transport == AgentStreamTransport.UNKNOWN) {
                                        _streamState.value = _streamState.value.copy(transport = AgentStreamTransport.NATIVE)
                                    }
                                    val remaining = MAX_ASSISTANT_CHARS - streamedText.length
                                    if (remaining > 0) {
                                        val accepted = event.text.take(remaining)
                                        streamedText.append(accepted)
                                        appendTextPart(textPartId, turn, accepted, buffered = false)
                                    }
                                }
                                is LlmStreamEvent.BufferedResponse -> {
                                    _streamState.value = _streamState.value.copy(transport = AgentStreamTransport.BUFFERED)
                                    val remaining = MAX_ASSISTANT_CHARS - streamedText.length
                                    if (remaining > 0) {
                                        val accepted = event.text.take(remaining)
                                        streamedText.append(accepted)
                                        appendTextPart(textPartId, turn, accepted, buffered = true)
                                    }
                                }
                                is LlmStreamEvent.ToolCallDelta -> {
                                    if (_streamState.value.transport == AgentStreamTransport.UNKNOWN) {
                                        _streamState.value = _streamState.value.copy(transport = AgentStreamTransport.NATIVE)
                                    }
                                    applyToolCallDelta(turn, event)
                                }
                            }
                        }
                        resp = streamResult.responseBody
                        streamMode = streamResult.mode
                        _streamState.value = _streamState.value.copy(
                            transport = if (streamResult.mode == LlmStreamMode.NATIVE) AgentStreamTransport.NATIVE else AgentStreamTransport.BUFFERED,
                        )
                        break
                    } catch (t: Throwable) {
                        val overflow = t is LlmRequestTooLargeException || (t is LlmHttpException && t.isContextOverflow())
                        if (!overflow || overflowRecovered) throw t
                        val retryId = "retry:$turn:context"
                        val retryAt = System.currentTimeMillis()
                        _streamState.value = _streamState.value.copy(
                            status = AgentStreamState.Status.RETRYING,
                            activeStep = turn,
                            retryAttempt = 1,
                            retryAtMs = retryAt,
                        )
                        upsertStreamPart(
                            AgentStreamPart(
                                id = retryId,
                                step = turn,
                                kind = AgentStreamPartKind.RETRY,
                                state = AgentStreamPartState.RUNNING,
                                title = "Retrying model step",
                                detail = "Context overflow · compacting before retry",
                                attempt = 1,
                                nextRetryAtMs = retryAt,
                            )
                        )
                        val compactId = "compaction:$turn:overflow"
                        upsertStreamPart(
                            AgentStreamPart(
                                id = compactId,
                                step = turn,
                                kind = AgentStreamPartKind.COMPACTION,
                                state = AgentStreamPartState.RUNNING,
                                title = "Recovering context overflow",
                                detail = "Compacting active history without consuming another agent step",
                            )
                        )
                        removeStreamPart(textPartId)
                        val changed = compactActiveContext(activeConfig, runSessionId, generation, overflowRecovery = true)
                        updateStreamPart(compactId) {
                            it.copy(
                                state = if (changed) AgentStreamPartState.COMPLETED else AgentStreamPartState.ERROR,
                                detail = if (changed) "Context compacted" else "Unable to compact further",
                                endedAtMs = System.currentTimeMillis(),
                            )
                        }
                        streamPart(compactId)?.let {
                            uiHistory += AgentMessage("assistant", "", streamPart = it)
                            _messages.value = uiHistory.toList()
                        }
                        overflowRecovered = true
                        if (!changed) throw t
                        updateStreamPart(retryId) { it.copy(detail = "Retrying now") }
                        _streamState.value = _streamState.value.copy(
                            status = AgentStreamState.Status.BUSY,
                            activeStep = turn,
                            retryAtMs = null,
                        )
                        
                    }
                }


                val usageBefore = tokens.usage.value
                val stepAccounting = tokens.add(activeConfig.model, reqJson.toString(), resp)
                val usageAfter = tokens.usage.value
                val stepPromptTokens = (usageAfter.prompt - usageBefore.prompt).coerceAtLeast(0)
                val stepCompletionTokens = (usageAfter.completion - usageBefore.completion).coerceAtLeast(0)
                val stepTotalTokens = (usageAfter.total - usageBefore.total).coerceAtLeast(0)
                val stepReasoningTokens = (usageAfter.reasoning - usageBefore.reasoning).coerceAtLeast(0)
                val stepCacheReadTokens = (usageAfter.cacheRead - usageBefore.cacheRead).coerceAtLeast(0)
                val stepCacheWriteTokens = (usageAfter.cacheWrite - usageBefore.cacheWrite).coerceAtLeast(0)
                val stepCostUsd = (usageAfter.costUsd - usageBefore.costUsd).coerceAtLeast(0.0)
                val root = json.parseToJsonElement(resp) as? JsonObject
                    ?: throw IllegalStateException("Provider returned non-object JSON")
                val choices = root["choices"] as? JsonArray
                    ?: throw IllegalStateException("Provider response is missing choices")
                val choice = choices.firstOrNull() as? JsonObject
                    ?: throw IllegalStateException("Provider response has no choice")
                val msg = choice["message"] as? JsonObject
                    ?: throw IllegalStateException("Provider response is missing message")
                val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                updateStreamPart(stepId) {
                    it.copy(
                        finishReason = finishReason,
                        promptTokens = stepPromptTokens,
                        completionTokens = stepCompletionTokens,
                        totalTokens = stepTotalTokens,
                        reasoningTokens = stepReasoningTokens,
                        cacheReadTokens = stepCacheReadTokens,
                        cacheWriteTokens = stepCacheWriteTokens,
                        costUsd = stepCostUsd,
                        tokenUsageAuthority = stepAccounting.tokenAuthority,
                        costAuthority = stepAccounting.costAuthority,
                    )
                }
                val content = (msg["content"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(MAX_ASSISTANT_CHARS)
                val toolCalls = (msg["tool_calls"] as? JsonArray) ?: JsonArray(emptyList())
                require(toolCalls.size <= MAX_TOOL_CALLS_PER_TURN) {
                    "Provider requested ${toolCalls.size} tools in one turn; maximum is $MAX_TOOL_CALLS_PER_TURN"
                }
                updateStreamPart("text:$turn") {
                    it.copy(
                        state = AgentStreamPartState.COMPLETED,
                        text = content.ifBlank { it.text },
                        detail = if (streamMode == LlmStreamMode.NATIVE) "Complete" else "Complete · buffered provider",
                        finishReason = finishReason,
                        endedAtMs = System.currentTimeMillis(),
                    )
                }
                updateStreamPart("retry:$turn:context") {
                    it.copy(state = AgentStreamPartState.COMPLETED, detail = "Retry succeeded", endedAtMs = System.currentTimeMillis())
                }
                streamPart("retry:$turn:context")?.takeIf { it.state == AgentStreamPartState.COMPLETED }?.let {
                    if (uiHistory.none { message -> message.streamPart?.id == it.id }) {
                        uiHistory += AgentMessage("assistant", "", streamPart = it)
                        _messages.value = uiHistory.toList()
                    }
                }
                if (toolCalls.isEmpty()) {
                    uiHistory += AgentMessage("assistant", content.ifBlank { "(tidak ada respons)" })
                    _messages.value = uiHistory.toList()
                    conversationHistory.append(buildJsonObject { put("role", "assistant"); put("content", content) })
                    finishStep(turn, "Response complete").let { stepPart ->
                        uiHistory += AgentMessage("assistant", "", streamPart = stepPart)
                        _messages.value = uiHistory.toList()
                    }
                    val stopHook = lifecycleHooks.stop()
                    if (stopHook.blocked && !finalStep) {
                        conversationHistory.append(buildJsonObject { put("role", "system"); put("content", "Lifecycle Stop hook requested continuation: ${stopHook.reason}\n${stopHook.additionalContext}") })
                        continue
                    }
                    val nextConfig = promotePendingAtBoundary(
                        runLease = runLease,
                        allowQueue = true,
                        uiHistory = uiHistory,
                        activeConfig = activeConfig,
                        runSessionId = runSessionId,
                        generation = generation,
                        step = turn + 1,
                    )
                    if (nextConfig != null) {
                        activeConfig = nextConfig
                        modelStepsInSegment = 0
                        continue
                    }
                    break
                }
                if (finalStep) {
                    // Never execute an unadvertised tool call beyond the configured model-step budget.

                    val finalContent = content.ifBlank {
                        "The configured agent step limit was reached before a valid final response. No additional tools were executed."
                    }
                    uiHistory += AgentMessage("assistant", finalContent)
                    _messages.value = uiHistory.toList()
                    conversationHistory.append(buildJsonObject { put("role", "assistant"); put("content", finalContent) })
                    finishStep(turn, "Tool request rejected on final text-only step").let { stepPart ->
                        uiHistory += AgentMessage("assistant", "", streamPart = stepPart)
                        _messages.value = uiHistory.toList()
                    }
                    val nextConfig = promotePendingAtBoundary(
                        runLease = runLease,
                        allowQueue = true,
                        uiHistory = uiHistory,
                        activeConfig = activeConfig,
                        runSessionId = runSessionId,
                        generation = generation,
                        step = turn + 1,
                    )
                    if (nextConfig != null) {
                        activeConfig = nextConfig
                        modelStepsInSegment = 0
                        continue
                    }
                    break
                }
                

                if (content.isNotBlank()) {
                    uiHistory += AgentMessage("assistant", content)
                    _messages.value = uiHistory.toList()
                }
                val assistantWithTools = buildJsonObject {
                    put("role", "assistant"); put("content", content)
                    put("tool_calls", toolCalls); msg[AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD]?.let { put(AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD, it) }
                }
                conversationHistory.append(assistantWithTools)
                unresolvedApiToolCalls.clear()
                toolCalls.forEach { raw ->
                    val call = raw as? JsonObject ?: return@forEach
                    val callId = (call["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    val function = call["function"] as? JsonObject
                    val name = (function?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
                    unresolvedApiToolCalls[callId] = name
                }
                val prepared = AgentToolTurnPlanner.prepare(
                    toolCalls, json, turn, MAX_TOOL_ARGUMENT_CHARS, lastSig, repeat,
                )
                prepared.forEach { call ->
                    val id = call.id
                    val existingPart = _streamState.value.parts.firstOrNull { it.id == call.partId }
                    upsertStreamPart((existingPart ?: AgentStreamPart(
                        id = call.partId, step = turn, kind = AgentStreamPartKind.TOOL,
                        state = AgentStreamPartState.PENDING,
                    )).copy(
                        state = AgentStreamPartState.PENDING, title = call.name.replace('_', ' '),
                        toolName = call.name, toolCallId = id,
                        input = call.rawArgs.take(MAX_TOOL_ARGUMENT_CHARS),
                        detail = "Queued · ${call.index + 1}/${prepared.size}",
                    ))
                }
                val schedulingFacts = AgentToolTurnPlanner.schedulingFacts(
                    prepared,
                    permissionAllows = { access -> perms.decide(access.action, access.resource, permissionScope()) == PermEffect.ALLOW },
                    hasLifecycleHook = { tool -> lifecycleHooks.requiresSerializedTool(tool) },
                )
                suspend fun executePrepared(call: PreparedToolCall): ToolOutcome {
                    currentCoroutineContext().ensureActive()
                    
                    val fname = call.name
                    val rawArgs = call.rawArgs
                    val fargs = call.args
                    updateStreamPart(call.partId) {
                        it.copy(state = AgentStreamPartState.RUNNING, detail = "Running · ${call.index + 1}/${prepared.size}")
                    }
                    val ctx = AgentTools.Ctx(
                        files, terminal, git, approvals, questions, editHistory, perms,
                        _mode.value, llm, activeConfig, workDir, todos, runSessionId, lsp, backgroundCommands, subagentManager,
                        provenance = requestProvenance(runSessionId),
                        pluginSource = agentPluginSource, documentAuthority = documentAuthority,
                        activeToolNames = toolDefinitions?.let(AgentRuntimePrompt::toolNames)?.toSet().orEmpty(),
                        ideActions = ideActions, lspEditActions = lspEditActions,
                        toolchainActions = toolchainActions, capabilityActions = capabilityActions,
                        browserController = browserController,
                        mcpProcessHost = mcpProcessHost,
                    ) { progress ->
                        if (isRunCurrent(generation, runSessionId)) {
                            updateStreamPart(call.partId) { part ->
                                val mergedOutput = if (progress.outputDelta.isBlank()) part.output else
                                    (part.output + progress.outputDelta).takeLast(MAX_STREAM_TOOL_OUTPUT_CHARS)
                                part.copy(
                                    title = progress.title.ifBlank { part.title },
                                    detail = progress.detail.ifBlank { part.detail },
                                    output = mergedOutput,
                                )
                            }
                        }
                    }
                    return try {
                        // policy remains authoritative before hooks or execution.
                        val policyDenied = AgentModePolicy.check(ctx.mode, fname)
                            ?: if (!ctx.perms.toolAllowed(fname)) "DENIED (agent profile): tool '$fname' is outside this Agent profile capability set." else null
                        val result = if (policyDenied != null) {
                            policyDenied
                        } else {
                            val doomDenied = call.repeatOrdinal >= 3 && AgentApprovalSupport.doomLoopDenied(
                                approvals, perms, permissionScope(), fname, rawArgs, requestProvenance(runSessionId))
                            if (doomDenied) {
                                "DENIED (doom-loop guard): stop repetition $fname"
                            } else {
                                ensureRunCurrent(generation, runSessionId)
                                lifecycleHooks.tool(fname, rawArgs) {
                                    ensureRunCurrent(generation, runSessionId)
                                    AgentTools.execute(fname, fargs, ctx)
                                }.also { ensureRunCurrent(generation, runSessionId) }
                            }
                        }
                        val evidence = AgentToolResultSemantics.classify(fname, result, AgentToolResultSemantics.operationFor(fname, fargs))
                        val evidencedResult = evidence.annotate(fname, result)
                        updateStreamPart(call.partId) {
                            it.copy(state = if (evidence.isFailure) AgentStreamPartState.ERROR else AgentStreamPartState.COMPLETED,
                                output = evidencedResult.take(12_000), detail = evidence.uiDetail, endedAtMs = System.currentTimeMillis())
                        }
                        ToolOutcome(call, evidencedResult, failed = evidence.isFailure)
                    } catch (toolCancelled: CancellationException) {
                        updateStreamPart(call.partId) {
                            it.copy(state = AgentStreamPartState.CANCELLED, detail = "Cancelled by user", endedAtMs = System.currentTimeMillis())
                        }
                        throw toolCancelled
                    } catch (toolError: Throwable) {
                        val message = "ERROR (${toolError::class.java.simpleName}): ${toolError.message ?: "Tool failed"}".take(12_000)
                        val evidence = AgentToolResultSemantics.classify(fname, message, AgentToolResultSemantics.operationFor(fname, fargs))
                        val evidencedMessage = evidence.annotate(fname, message)
                        updateStreamPart(call.partId) {
                            it.copy(state = AgentStreamPartState.ERROR, output = evidencedMessage, detail = evidence.uiDetail, endedAtMs = System.currentTimeMillis())
                        }
                        ToolOutcome(call, evidencedMessage, failed = true)
                    }
                }
                suspend fun commitOutcome(outcome: ToolOutcome) {
                    ensureRunCurrent(generation, runSessionId)
                    val call = outcome.call
                    val id = call.id
                    val toolDisplay = "TOOL|${call.name}|${call.rawArgs}|${outcome.result.take(2000)}"
                    val durablePart = _streamState.value.parts.firstOrNull { it.id == call.partId }
                    uiHistory += AgentMessage("assistant", toolDisplay, streamPart = durablePart)
                    conversationHistory.append(buildJsonObject {
                        put("role", "tool"); put("tool_call_id", id)
                        put("content", outcome.result.take(12_000))
                    })
                    unresolvedApiToolCalls.remove(id)
                    _messages.value = uiHistory.toList()
                    lastSig = call.signature
                    repeat = call.repeatOrdinal
                }
                for (batch in AgentToolScheduler.batches(schedulingFacts)) {
                    currentCoroutineContext().ensureActive()
                    val calls = batch.indices.map { prepared[it] }
                    val outcomes = if (batch.parallel) {
                        coroutineScope { calls.map { call -> async { executePrepared(call) } }.awaitAll() }
                    } else {
                        calls.map { call -> executePrepared(call) }
                    }
                    outcomes.sortedBy { it.call.index }.forEach { commitOutcome(it) }
                }
                finishStep(turn, "Tools complete").let { stepPart ->
                    uiHistory += AgentMessage("assistant", "", streamPart = stepPart)
                    _messages.value = uiHistory.toList()
                }
                
            }
        } catch (e: CancellationException) {
            if (isRunCurrent(generation, runSessionId)) {
                terminateOpenStreamParts(AgentStreamPartState.CANCELLED, "Cancelled by user")
                closeUnresolvedApiToolCalls(unresolvedApiToolCalls, "Tool execution was interrupted by the user", AgentStreamPartState.CANCELLED)
                _streamState.value = _streamState.value.copy(
                    status = AgentStreamState.Status.CANCELLED,
                    endedAtMs = System.currentTimeMillis(),
                )
                _messages.value = _messages.value + AgentMessage("assistant", "Stopped by user.")
                conversationHistory.append(buildJsonObject { put("role", "assistant"); put("content", "Stopped by user.") })
            }
            throw e
        } catch (e: Exception) {
            if (isRunCurrent(generation, runSessionId)) {
                val msg = e.message ?: "Unknown error"
                terminateOpenStreamParts(AgentStreamPartState.ERROR, msg.take(240))
                closeUnresolvedApiToolCalls(unresolvedApiToolCalls, "Tool execution was interrupted by agent error: ${msg.take(240)}", AgentStreamPartState.ERROR)
                
                val quotaFailure = (e is LlmHttpException && e.statusCode in setOf(402, 429)) ||
                    msg.contains("quota", true) || msg.contains("billing", true)
                if (quotaFailure) {
                    tokens.warnQuota("Quota/billing exceeded: $msg — check the provider dashboard or switch provider/model")
                }
                upsertStreamPart(
                    AgentStreamPart(
                        id = "error:${generation}",
                        step = 0,
                        kind = AgentStreamPartKind.STATUS,
                        state = AgentStreamPartState.ERROR,
                        title = "Agent error",
                        detail = msg.take(500),
                        endedAtMs = System.currentTimeMillis(),
                    )
                )
                _streamState.value = _streamState.value.copy(status = AgentStreamState.Status.ERROR, endedAtMs = System.currentTimeMillis())
                _messages.value = _messages.value + AgentMessage("assistant", "Error: $msg")
                conversationHistory.append(buildJsonObject { put("role", "assistant"); put("content", "Error: $msg") })
            }
        } finally {
            if (isRunCurrent(generation, runSessionId)) {
                withContext(NonCancellable) { persistCurrent(userText.take(40), runSessionId, generation) }
            }
            if (isRunCurrent(generation, runSessionId)) {
                withContext(NonCancellable) { promptCoordinator.markRunInactive(runLease) }
                _streamState.value = AgentStreamState(runId = generation, sessionId = runSessionId, status = AgentStreamState.Status.IDLE)
                _busy.value = false
            }
        }
    }
    private suspend fun promotePendingAtBoundary(
        runLease: Long, allowQueue: Boolean, uiHistory: MutableList<AgentMessage>, activeConfig: AgentConfig,
        runSessionId: String, generation: Long, step: Int,
    ): AgentConfig? {
        ensureRunCurrent(generation, runSessionId)
        val batch = promptCoordinator.promoteAtBoundary(runLease, allowQueue, activeConfig, step, MAX_API_MESSAGE_CHARS) ?: return null
        uiHistory += batch.userMessages
        _messages.value = uiHistory.toList()
        conversationHistory.append(batch.apiEntries)
        batch.streamParts.forEach(::upsertStreamPart)
        _streamState.value = _streamState.value.copy(providerId = batch.nextConfig.providerId, modelId = batch.nextConfig.model)
        val persisted = withContext(NonCancellable) { persistCurrent(batch.fallbackTitle, runSessionId, generation) }
        if (persisted) subagentBridge.markDelivered(batch.deliveredSourceIds)
        return batch.nextConfig
    }
    private fun closeUnresolvedApiToolCalls(
        unresolved: MutableMap<String, String>,
        reason: String,
        terminalState: AgentStreamPartState,
    ) {
        if (unresolved.isEmpty()) return
        val closure = AgentHistory.closeInterruptedTools(
            unresolved, reason, terminalState, _streamState.value.parts, _streamState.value.activeStep,
        )
        conversationHistory.append(closure.apiEntries)
        if (closure.messages.isNotEmpty()) _messages.value = _messages.value + closure.messages
        unresolved.clear()
    }
    private fun terminateOpenStreamParts(state: AgentStreamPartState, detail: String) =
        streamProjector.terminateOpen(state, detail)
    private fun upsertStreamPart(part: AgentStreamPart) = streamProjector.upsert(part)
    private fun updateStreamPart(id: String, transform: (AgentStreamPart) -> AgentStreamPart) = streamProjector.update(id, transform)
    private fun removeStreamPart(id: String) = streamProjector.remove(id)
    private fun appendTextPart(id: String, step: Int, delta: String, buffered: Boolean) = streamProjector.appendText(id, step, delta, buffered)
    private fun applyToolCallDelta(step: Int, event: LlmStreamEvent.ToolCallDelta) = streamProjector.applyToolCallDelta(step, event)
    private fun streamPart(id: String): AgentStreamPart? = streamProjector.part(id)
    private fun finishStep(step: Int, reason: String): AgentStreamPart = streamProjector.finishStep(step, reason)
    private suspend fun compactActiveContext(config: AgentConfig, sid: String, generation: Long, overflowRecovery: Boolean): Boolean =
        lifecycleHooks.compact(if (overflowRecovery) "overflow" else "auto") {
            AgentRequestSupport.compact(
                sessions, tokens, todos, _mode.value, pendingInputs.value, _messages.value, _apiHistory,
                exactForkHistory = conversationHistory.completeExactOrNull(), config, sid, overflowRecovery,
                ensureCurrent = { ensureRunCurrent(generation, sid) },
            )
        }.changed
    private fun completeApiHistoryForModel(): List<JsonObject> = conversationHistory.completeActive()
    private fun isRunCurrent(generation: Long, sessionId: String): Boolean = runGeneration.get() == generation && currentSessionId == sessionId
    private fun ensureRunCurrent(generation: Long, sessionId: String) {
        if (!isRunCurrent(generation, sessionId)) throw CancellationException("Agent session changed")
    }
    private suspend fun persistCurrent(fallbackTitle: String, sid: String, generation: Long): Boolean {
        if (!isRunCurrent(generation, sid)) return false
        // Snapshot before suspension so a later session switch cannot redirect this write.
        val activeHistory = completeApiHistoryForModel()
        val exactFork = conversationHistory.completeExactOrNull()
        val annotatedMessages = if (exactFork != null) SessionForkHistory.annotate(_messages.value, exactFork)
        else _messages.value.map { it.copy(forkHistoryEnd = null) }
        val compactMessages = AgentHistory.boundedUi(annotatedMessages, MAX_UI_MESSAGES, MAX_UI_MESSAGE_CHARS)
        val todoSnapshot = todos.get(sid)
        val modeSnapshot = _mode.value
        try {
            val existing = sessions.get(sid)
            val toSave = (existing ?: DroideSession(id = sid, title = fallbackTitle.ifBlank { "Session" })).copy(
                messages = compactMessages,
                apiHistory = activeHistory,
                forkHistory = exactFork ?: emptyList(),
                forkHistoryVersion = if (exactFork != null) SessionForkHistory.SCHEMA_VERSION else 0,
                todos = todoSnapshot,
                mode = modeSnapshot,
                pendingInputs = pendingInputs.value,
            )
            sessions.save(toSave)
            if (isRunCurrent(generation, sid)) {
                _apiHistory.clear()
                _apiHistory.addAll(activeHistory)
                _messages.value = compactMessages
                _persistenceError.value = null
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (isRunCurrent(generation, sid)) {
                _persistenceError.value = "Session could not be saved: ${t.message ?: t.javaClass.simpleName}"
            }
            return false
        }
    }


    internal fun boundedApiHistory(
        source: List<JsonObject> = _apiHistory,
        maxEntries: Int = 96,
        maxChars: Int = 240_000,
    ): List<JsonObject> = AgentHistory.bounded(source, maxEntries, maxChars)
     
    private suspend fun loadProjectBrief(): String = AgentProjectInstructions.load(files, workDir, agentPluginSource)
    companion object {
        const val MAX_USER_MESSAGE_CHARS = 64_000
        const val MAX_API_MESSAGE_CHARS = 32_000
        const val MAX_ASSISTANT_CHARS = 100_000
        const val MAX_TOOL_ARGUMENT_CHARS = 128_000
        const val MAX_STREAM_TOOL_OUTPUT_CHARS = 20_000
        const val MAX_TOOL_CALLS_PER_TURN = 16
        const val MAX_AGENT_TURNS = 32
        const val MAX_UI_MESSAGES = 500
        const val MAX_UI_MESSAGE_CHARS = 1_000_000
        const val FINAL_STEP_INSTRUCTION = """
            FINAL AGENT STEP: Tools are intentionally unavailable on this step. Do not request or imply another tool call.
            Give the user a concise, truthful handoff that states what was completed, what verification/evidence was observed,
            and any remaining work or blocker. Never claim work or verification that did not actually happen.
        """
    }
}
