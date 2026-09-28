package com.baystudio.droide.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.launch






@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPane(
    agent: AgentService,
    providerConnections: ProviderConnectionBackend,
    localLlamaModels: LocalLlamaAgentModelController,
    agentPlugins: WorkspaceAgentPluginHost? = null,
    approvals: ApprovalManager = agent.approvals,
    captureIdeContext: () -> AgentIdeContextSnapshot? = { null },
    onSessions: () -> Unit = {},
    onUiError: (String) -> Unit = {},
    onComposerFocusChanged: (Boolean) -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by agent.messages.collectAsState()
    val busy by agent.busy.collectAsState()
    val mode by agent.mode.collectAsState()
    val persistenceError by agent.persistenceError.collectAsState()
    val streamState by agent.streamState.collectAsState()
    val localLlamaState by localLlamaModels.state.collectAsState()
    val parallelAgents by agent.subagentDashboard.collectAsState()
    val pendingInputs by agent.pendingInputs.collectAsState()
    val streamParts = streamState.parts
    val baseDisplayedMessages = if (busy && streamState.baseMessageCount in 1..messages.size) {
        messages.take(streamState.baseMessageCount)
    } else messages
    val pendingQuestion by agent.questions.pending.collectAsState()
    val pendingApproval by approvals.pending.collectAsState()
    val tokens by agent.tokens.usage.collectAsState()
    val todos by agent.todos.flow(agent.currentSessionId).collectAsState()
    val listState = rememberLazyListState()
    var agentBackendId by rememberSaveable { mutableStateOf("droide") }
    var showAgentBackendMenu by remember { mutableStateOf(false) }
    val externalController = remember(scope) { ExternalAgentSessionController(scope) }
    val pluginAvailability by produceState<List<WorkspaceAgentAvailability>>(emptyList(), agentPlugins) {
        value = agentPlugins?.let { runCatching { it.availability() }.getOrDefault(emptyList()) }.orEmpty()
    }
    val selectedPlugin = pluginAvailability.firstOrNull { it.spec.id == agentBackendId }
    val usingExternalAgent = agentBackendId != "droide" && selectedPlugin != null
    val externalState = externalController[agentBackendId]
    val externalBusy = externalController.busy
    val effectiveBusy = if (usingExternalAgent) externalBusy else busy
    var composerFocused by remember { mutableStateOf(false) }
    var landscapeComposerImeSession by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onComposerFocusChanged(false) } }

    var input by rememberSaveable(agent.currentSessionId) { mutableStateOf("") }
    var providerId by rememberSaveable { mutableStateOf("pollinations") }
    var model by rememberSaveable { mutableStateOf(ProviderRegistry.byId("pollinations").model) }
    var reasoningEffort by remember { mutableStateOf<ReasoningEffort?>(null) }
    var taskCollapsed by rememberSaveable(agent.currentSessionId) { mutableStateOf(false) }
    var taskHidden by rememberSaveable(agent.currentSessionId) { mutableStateOf(false) }
    var customAnswer by rememberSaveable(pendingQuestion?.id) { mutableStateOf("") }
    var sessionTitle by remember(agent.currentSessionId) { mutableStateOf("Agent session") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    var connectProviderId by rememberSaveable { mutableStateOf("pollinations") }
    var showModeMenu by remember { mutableStateOf(false) }
    var showReasoningMenu by remember { mutableStateOf(false) }
    var showUsageMenu by remember { mutableStateOf(false) }
    var showBillingSheet by remember { mutableStateOf(false) }
    var agentPreflightJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var showStreamDetails by rememberSaveable(agent.currentSessionId) { mutableStateOf(false) }
    var promptDelivery by rememberSaveable(agent.currentSessionId) { mutableStateOf(AgentPromptDelivery.STEER) }
    val displayedMessages = baseDisplayedMessages.filter { message ->
        val part = message.streamPart
        part == null || part.kind != AgentStreamPartKind.STEP || showStreamDetails
    }
    val transcriptMessages = if (usingExternalAgent) externalState.messages else displayedMessages
    var newActivityCount by remember(agent.currentSessionId) { mutableIntStateOf(0) }
    var knownMessageCount by remember(agent.currentSessionId) { mutableIntStateOf(displayedMessages.size) }
    var knownStreamPartCount by remember(agent.currentSessionId) { mutableIntStateOf(streamParts.size) }
    var knownStreamActivityBucket by remember(agent.currentSessionId) { mutableIntStateOf(0) }
    var knownParallelActivityBucket by remember(agent.currentSessionId) { mutableIntStateOf(0) }
    var attachedContexts by remember(agent.currentSessionId) { mutableStateOf<List<AgentAttachedContext>>(emptyList()) }

    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val attachment = runCatching { readAgentTextAttachment(ctx, uri) }.getOrNull()
                if (attachment != null) {
                    attachedContexts = (attachedContexts + attachment).takeLast(4)
                }
            }
        }
    }

    LaunchedEffect(localLlamaModels) { localLlamaModels.refresh() }
    LaunchedEffect(Unit) {
        AgentPreferences.observe(ctx).collect { snapshot ->
            providerId = snapshot.providerId
            model = snapshot.model
            reasoningEffort = snapshot.reasoningEffort
        }
    }
    LaunchedEffect(agent.currentSessionId, messages.size) {
        sessionTitle = agent.currentSessionId?.let { agent.sessions.get(it)?.title } ?: "New agent session"
    }
    val streamTextChars = streamParts.sumOf { if (it.kind == AgentStreamPartKind.TEXT) it.text.length else 0 }
    val streamOutputChars = streamParts.sumOf { if (it.kind == AgentStreamPartKind.TOOL) it.output.length else 0 }
    val streamTerminalCount = streamParts.count { it.state == AgentStreamPartState.COMPLETED || it.state == AgentStreamPartState.ERROR || it.state == AgentStreamPartState.CANCELLED }
    val liveScrollTick = streamTextChars / 160
    val streamActivityBucket = (streamTextChars / 320) + (streamOutputChars / 1024) + streamTerminalCount + streamParts.size
    val parallelActivityBucket = parallelAgents.tasks.sumOf { task ->
        ((task.updatedAtMs / 1_000L) % 10_000L).toInt() + task.activityStep + task.activity.hashCode() + task.state.ordinal * 17 + task.integration.ordinal * 31
    }
    val streamTail = streamParts.lastOrNull()
    LaunchedEffect(agent.currentSessionId, displayedMessages.size, busy, streamParts.size, streamTail?.id, streamTail?.state, liveScrollTick, streamActivityBucket, parallelActivityBucket) {
        val info = listState.layoutInfo
        val totalItems = info.totalItemsCount
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
        val nearBottom = lastVisible?.let { totalItems == 0 || it >= totalItems - 3 } ?: true
        val addedMessages = (displayedMessages.size - knownMessageCount).coerceAtLeast(0)
        val addedParts = (streamParts.size - knownStreamPartCount).coerceAtLeast(0)
        val streamUpdatedActivity = if (streamActivityBucket > knownStreamActivityBucket && addedParts == 0) 1 else 0
        val parallelUpdatedActivity = if (parallelActivityBucket != knownParallelActivityBucket && parallelAgents.tasks.isNotEmpty()) 1 else 0
        val updatedActivity = streamUpdatedActivity + parallelUpdatedActivity
        if (nearBottom && totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
            newActivityCount = 0
        } else if (addedMessages + addedParts + updatedActivity > 0) {
            newActivityCount += addedMessages + addedParts + updatedActivity
        } else if (displayedMessages.isEmpty() && streamParts.isEmpty()) {
            newActivityCount = 0
        }
        knownMessageCount = displayedMessages.size
        knownStreamPartCount = streamParts.size
        knownStreamActivityBucket = streamActivityBucket
        knownParallelActivityBucket = parallelActivityBucket
    }
    LaunchedEffect(listState, displayedMessages.size, streamParts.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastVisible != null && lastIndex >= 0 && lastVisible >= lastIndex - 1) newActivityCount = 0
            }
    }

    val localProviderSelected = providerId.equals(LocalLlamaServerPolicy.PROVIDER_ID, true)
    val provider = ProviderRegistry.findById(providerId) ?: if (localProviderSelected) {
        AiProvider(
            id = LocalLlamaServerPolicy.PROVIDER_ID,
            name = "Droide Local llama.cpp",
            baseUrl = "http://127.0.0.1",
            model = model,
            free = true,
            needsKey = true,
            helpUrl = "https://github.com/ggml-org/llama.cpp/tree/master/tools/server",
            note = "Certified local GGUF; endpoint exists only while Droide owns the 08ay lifecycle.",
            networkScope = ProviderNetworkScope.LOOPBACK_ONLY,
        )
    } else ProviderRegistry.byId(providerId)
    val providerConnected = providerConnections.snapshot(provider.id).status == ProviderConnectionBackend.Status.CONNECTED
    val localModelSelectable = localProviderSelected && localLlamaState.models.any { it.id == model }
    val configured = model.isNotBlank() && (providerConnected || localModelSelectable)
    val preflightBusy = agentPreflightJob?.isActive == true
    val canSendWithBackend = if (usingExternalAgent) selectedPlugin?.available == true else configured && !preflightBusy

    fun send() {
        if (input.isBlank() && attachedContexts.isEmpty()) return
        val text = composeAgentPrompt(input, attachedContexts)
        if (usingExternalAgent) {
            val host = agentPlugins ?: return
            val plugin = selectedPlugin ?: return
            if (!plugin.available || externalController.busy) return
            input = ""
            attachedContexts = emptyList()
            externalController.submit(
                host = host,
                spec = plugin.spec,
                prompt = text,
                mode = if (mode == AgentMode.PLAN || mode == AgentMode.EXPLORE || mode == AgentMode.REVIEW) WorkspaceAgentMode.PLAN else WorkspaceAgentMode.EDIT,
            )
            return
        }
        if (!configured) {
            if (localProviderSelected) {
                showModelPicker = true
            } else {
                connectProviderId = provider.id
                showConnect = true
            }
            return
        }
        if (agentPreflightJob?.isActive == true) return
        

        val ideContext = captureIdeContext()
        agentPreflightJob = scope.launchUiCatching(
            onError = { onUiError("Could not prepare ${if (localProviderSelected) "local llama.cpp" else provider.name}: ${it.message ?: it::class.java.simpleName}") },
        ) {
            val config = localLlamaModels.resolveAgentConfig(providerId, model, reasoningEffort)
            input = ""
            attachedContexts = emptyList()
            agent.submitAsync(
                scope,
                text,
                config,
                if (busy) promptDelivery else AgentPromptDelivery.STEER,
                ideContext = ideContext,
            )
        }
    }

    


    val physicalLandscape = droidePhysicalLandscape()
    val imeVisible = rememberDroideImeVisible()
    LaunchedEffect(physicalLandscape, imeVisible, composerFocused) {
        if (!physicalLandscape || !imeVisible) landscapeComposerImeSession = false
        else if (composerFocused) landscapeComposerImeSession = true
    }
    val landscapeImeTyping = physicalLandscape && imeVisible && landscapeComposerImeSession
    val shouldApplyAgentImeInset = !physicalLandscape || landscapeImeTyping
    val agentImeInsetModifier =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && shouldApplyAgentImeInset) Modifier.imePadding() else Modifier
    BoxWithConstraints(Modifier.fillMaxSize().background(DroideColors.Surface).then(agentImeInsetModifier)) {
        val denseComposer = maxHeight < 560.dp || landscapeImeTyping
        val compactControls = denseComposer || maxWidth < 430.dp
        val paneWidth = maxWidth
        val composerOuterPadding = if (denseComposer) PaddingValues(horizontal = 7.dp, vertical = 5.dp) else PaddingValues(horizontal = 9.dp, vertical = 7.dp)
        val composerInnerPadding = if (denseComposer) 6.dp else 8.dp
        val controlHeight = 24.dp
        val sendSize = 31.dp
        val logoSize = if (compactControls) 15.dp else 18.dp
        val taskDockMaxHeight = maxHeight * if (denseComposer) .10f else .13f
        val interactionDockMaxHeight = maxHeight * if (denseComposer) .16f else .20f

        Column(Modifier.fillMaxSize()) {
            if (!landscapeImeTyping) DroidePanelHeader(
                title = sessionTitle,
                subtitle = "${agentModeLabel(mode)} · ${selectedPlugin?.spec?.displayName ?: "Droide Agent"} · current project",
                onClose = onClose,
                actions = {
                    Box {
                        AgentUsageHeaderButton(
                            usage = tokens,
                            paneWidth = paneWidth,
                            onClick = {
                                showModeMenu = false
                                showReasoningMenu = false
                                showUsageMenu = !showUsageMenu
                            },
                        )
                        DropdownMenu(
                            expanded = showUsageMenu,
                            onDismissRequest = { showUsageMenu = false },
                            modifier = Modifier.widthIn(min = 224.dp, max = 280.dp),
                        ) {
                            AgentUsageQuickMenu(
                                usage = tokens,
                                canCompact = configured && agent.currentSessionId != null && !busy,
                                onViewBilling = {
                                    showUsageMenu = false
                                    showBillingSheet = true
                                },
                                onCompact = {
                                    showUsageMenu = false
                                    scope.launchUiCatching(onError = { onUiError("Could not compact session: ${it.message ?: it::class.java.simpleName}") }) {
                                        val config = localLlamaModels.resolveAgentConfig(providerId, model, reasoningEffort)
                                        agent.compactCurrent(config)
                                    }
                                },
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (usingExternalAgent) {
                                val host = agentPlugins
                                val plugin = selectedPlugin
                                if (host != null && plugin != null) externalController.newSession(host, plugin.spec)
                            } else agent.clear()
                            input = ""
                            customAnswer = ""
                            attachedContexts = emptyList()
                            taskCollapsed = false
                            taskHidden = false
                        },
                        enabled = !effectiveBusy,
                    ) { Icon(Icons.Default.Add, "New agent session") }
                    IconButton(onClick = onSessions) { Icon(Icons.Default.History, "Agent sessions") }
                },
            )

            persistenceError?.let {
                Text(it, Modifier.fillMaxWidth().background(DroideColors.Error.copy(alpha = .08f)).padding(horizontal = 12.dp, vertical = 7.dp), color = DroideColors.Error, style = MaterialTheme.typography.labelSmall)
            }

            Box(Modifier.weight(1f).fillMaxWidth().background(DroideColors.Background)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = if (denseComposer) 11.dp else 14.dp, vertical = if (denseComposer) 8.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (denseComposer) 10.dp else 14.dp),
                ) {
                    if (transcriptMessages.isEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = if (denseComposer) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text((selectedPlugin?.spec?.displayName ?: "DROIDE AGENT").uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
                                Text("Ask about the project, edit files, or run tools.", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
                            }
                        }
                    }
                    itemsIndexed(transcriptMessages, key = { index, item -> "$index:${item.role}:${item.content.hashCode()}" }) { _, message ->
                        AgentTranscriptItem(message, showStreamDetails)
                    }
                    if (!usingExternalAgent && parallelAgents.tasks.isNotEmpty()) {
                        item(key = "parallel-agents:${parallelAgents.parentSessionId}") {
                            AgentParallelAgentsPanel(parallelAgents, agent::controlSubagentFromUi)
                        }
                    }
                    if (!usingExternalAgent && (busy || streamParts.isNotEmpty())) {
                        item(key = "stream-run-header:${streamState.runId}") {
                            AgentStreamRunHeader(
                                state = streamState,
                                tokenTotal = tokens.total,
                                showDetails = showStreamDetails,
                                onToggleDetails = { showStreamDetails = !showStreamDetails },
                            )
                        }
                    }
                    if (!usingExternalAgent) itemsIndexed(streamParts, key = { _, part -> "stream:${part.id}" }) { _, part ->
                        AgentStreamPartItem(part, showDetails = showStreamDetails)
                    }
                    if (usingExternalAgent && externalController.activeRun?.agentId == agentBackendId) {
                        item(key = "external-agent-live") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                                    Text("${selectedPlugin?.spec?.displayName ?: "Agent"} working…", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                                }
                                externalState.activity.takeLast(4).forEach { activity ->
                                    Text("• $activity", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (externalState.liveText.isNotBlank()) AgentMarkdownContent(externalState.liveText, Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if (!usingExternalAgent && busy && streamParts.isEmpty()) {
                        item(key = "live-working") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                                Text("Working…", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                            }
                        }
                    }
                }
                if (newActivityCount > 0) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).clip(MaterialTheme.shapes.small).clickable {
                            scope.launch {
                                val last = listState.layoutInfo.totalItemsCount - 1
                                if (last >= 0) listState.animateScrollToItem(last)
                                newActivityCount = 0
                            }
                        },
                        color = DroideColors.Surface3,
                        border = BorderStroke(1.dp, DroideColors.BorderStrong),
                        tonalElevation = 2.dp,
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.ArrowDownward, null, Modifier.size(14.dp), tint = DroideColors.Primary)
                            Text("$newActivityCount new activit${if (newActivityCount == 1) "y" else "ies"}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (todos.isNotEmpty()) {
                val done = todos.count { it.status == "completed" }
                val autoCollapsed = pendingQuestion != null || pendingApproval != null || landscapeImeTyping
                val effectiveCollapsed = taskCollapsed || autoCollapsed
                if (taskHidden) {
                    Row(
                        Modifier.fillMaxWidth().height(30.dp).background(DroideColors.Surface)
                            .clickable { taskHidden = false }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(14.dp), tint = DroideColors.Muted)
                        Spacer(Modifier.width(5.dp))
                        Text("Tasks", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("$done/${todos.size}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    }
                } else {
                    Column(Modifier.fillMaxWidth().background(DroideColors.Surface)) {
                        HorizontalDivider(color = DroideColors.Border)
                        Row(
                            Modifier.fillMaxWidth().height(if (denseComposer) 31.dp else 34.dp).padding(start = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.extraSmall)
                                    .clickable { taskCollapsed = !taskCollapsed }.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (effectiveCollapsed) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (effectiveCollapsed) "Expand tasks" else "Collapse tasks", Modifier.size(15.dp), tint = DroideColors.Muted)
                                Spacer(Modifier.width(4.dp))
                                Text("Tasks", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text("$done/${todos.size}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                            }
                            IconButton(onClick = { taskHidden = true }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, "Close tasks", Modifier.size(14.dp), tint = DroideColors.Muted)
                            }
                        }
                        if (!effectiveCollapsed) {
                            Column(
                                Modifier
                                    .heightIn(max = taskDockMaxHeight)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(if (denseComposer) 3.dp else 5.dp),
                            ) {
                                todos.forEach { todo ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        Icon(
                                            when (todo.status) {
                                                "completed" -> Icons.Default.CheckCircle
                                                "in_progress" -> Icons.Default.RadioButtonChecked
                                                else -> Icons.Default.RadioButtonUnchecked
                                            },
                                            todo.status,
                                            Modifier.size(14.dp),
                                            tint = when (todo.status) {
                                                "completed" -> DroideColors.Success
                                                "in_progress" -> DroideColors.Primary
                                                else -> DroideColors.Muted
                                            },
                                        )
                                        Text(todo.content, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = if (denseComposer) 1 else 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            pendingQuestion?.let { question ->
                InteractionDock(title = question.header.ifBlank { "Question" }, maxHeight = interactionDockMaxHeight) {
                    Text(question.question, style = MaterialTheme.typography.bodySmall)
                    question.options.forEach { option ->
                        OutlinedButton(onClick = { agent.questions.answer(option) }, Modifier.fillMaxWidth()) { Text(option) }
                    }
                    OutlinedTextField(customAnswer, { customAnswer = it.take(1_000) }, Modifier.fillMaxWidth(), placeholder = { Text("Custom answer") }, singleLine = true)
                    Button(onClick = { agent.questions.answer(customAnswer.ifBlank { "—" }) }, Modifier.fillMaxWidth()) { Text("Send answer") }
                }
            }

            pendingApproval?.let { request ->
                InteractionDock(title = "Permission required", maxHeight = interactionDockMaxHeight) {
                    Text(request.summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    if (request.action.isNotBlank() || request.resource.isNotBlank()) {
                        Text("${request.action.ifBlank { request.kind }} · ${request.resource}".take(260), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, fontFamily = FontFamily.Monospace)
                    }
                    if (request.kind == "write_file" || request.kind == "edit_file" || request.summary.startsWith("Terapkan patch")) {
                        Surface(border = BorderStroke(1.dp, DroideColors.Border), color = DroideColors.Background, shape = MaterialTheme.shapes.small) {
                            DiffText(request.detail.take(2_000))
                        }
                    } else {
                        Text(request.detail.take(2_000), Modifier.fillMaxWidth().background(DroideColors.Background).padding(8.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { approvals.resolve(ApprovalResolution.DENY) }, Modifier.weight(1f)) { Text("Reject") }
                        Button(onClick = { approvals.resolve(ApprovalResolution.ALLOW_ONCE) }, Modifier.weight(1f)) { Text("Allow once") }
                    }
                    if (request.canAllowForSession) {
                        TextButton(
                            onClick = { approvals.resolve(ApprovalResolution.ALLOW_SESSION) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Always allow for this session") }
                    }
                }
            }

            HorizontalDivider(color = DroideColors.Border)
            Column(Modifier.fillMaxWidth().background(DroideColors.Surface).padding(composerOuterPadding)) {
                if (pendingInputs.isNotEmpty()) {
                    AgentPendingPromptStrip(
                        inputs = pendingInputs,
                        onRemove = { id -> scope.launch { agent.removePendingInput(id) } },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Surface(
                    Modifier.fillMaxWidth().heightIn(min = 74.dp),
                    color = DroideColors.Background,
                    border = BorderStroke(1.dp, DroideColors.BorderStrong),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(Modifier.padding(composerInnerPadding)) {
                        if (attachedContexts.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                attachedContexts.forEachIndexed { index, attachment ->
                                    Surface(color = DroideColors.Surface, border = BorderStroke(1.dp, DroideColors.Border), shape = MaterialTheme.shapes.extraSmall) {
                                        Row(
                                            Modifier.height(23.dp).clickable { attachedContexts = attachedContexts.filterIndexed { i, _ -> i != index } }.padding(horizontal = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            FileTypeBrandIcon(attachment.name, size = 13.dp)
                                            Text(attachment.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Icon(Icons.Default.Close, "Remove ${attachment.name}", Modifier.size(11.dp), tint = DroideColors.Muted)
                                        }
                                    }
                                }
                            }
                        }

                        val attachControl: @Composable () -> Unit = {
                            IconButton(
                                onClick = { attachLauncher.launch(arrayOf("text/*", "application/json", "application/xml", "application/yaml")) },
                                modifier = Modifier.size(controlHeight),
                            ) { Icon(Icons.Default.Add, "Add context", Modifier.size(16.dp), tint = DroideColors.Muted) }
                        }
                        val modeControl: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                                AgentControlButton(
                                    label = agentModeLabel(mode),
                                    icon = agentModeIcon(mode),
                                    height = controlHeight,
                                    compact = compactControls,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    showReasoningMenu = false
                                    showUsageMenu = false
                                    showModeMenu = !showModeMenu
                                }
                                DropdownMenu(expanded = showModeMenu, onDismissRequest = { showModeMenu = false }) {
                                    listOf(AgentMode.BUILD, AgentMode.PLAN, AgentMode.EXPLORE, AgentMode.REVIEW).forEach { option ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(agentModeLabel(option))
                                                    Text(agentModeHint(option), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                                                }
                                            },
                                            leadingIcon = { Icon(agentModeIcon(option), null) },
                                            trailingIcon = if (mode == option) {{ Icon(Icons.Default.Check, null) }} else null,
                                            onClick = { agent.setMode(option); showModeMenu = false },
                                        )
                                    }
                                }
                            }
                        }
                        val modelControl: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                                AgentModelControl(
                                    providerId = provider.id,
                                    model = model,
                                    height = controlHeight,
                                    logoSize = logoSize,
                                    compact = compactControls,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    showModeMenu = false
                                    showReasoningMenu = false
                                    showUsageMenu = false
                                    showModelPicker = !showModelPicker
                                }
                                AgentModelPickerMenu(
                                    expanded = showModelPicker,
                                    currentProviderId = providerId,
                                    currentModel = model,
                                    connectionBackend = providerConnections,
                                    localModels = localLlamaModels,
                                    onPick = { selectedProvider, selectedModel ->
                                        scope.launchUiCatching(onError = { onUiError("Could not select model: ${it.message ?: it::class.java.simpleName}") }) {
                                            localLlamaModels.selectRemote(selectedProvider, selectedModel)
                                        }
                                    },
                                    onConnectProvider = { requestedProviderId ->
                                        connectProviderId = requestedProviderId
                                        showModelPicker = false
                                        showConnect = true
                                    },
                                    onDismiss = { showModelPicker = false },
                                )
                            }
                        }
                        val reasoningControl: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                                AgentControlButton(
                                    label = "Reason ${reasoningEffort?.label ?: "Auto"}",
                                    icon = Icons.Default.Psychology,
                                    height = controlHeight,
                                    compact = compactControls,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    showModeMenu = false
                                    showUsageMenu = false
                                    showReasoningMenu = !showReasoningMenu
                                }
                                DropdownMenu(expanded = showReasoningMenu, onDismissRequest = { showReasoningMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Default") },
                                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                        trailingIcon = if (reasoningEffort == null) {{ Icon(Icons.Default.Check, null) }} else null,
                                        onClick = {
                                            reasoningEffort = null
                                            scope.launchUiCatching(onError = { onUiError("Could not update reasoning: ${it.message ?: it::class.java.simpleName}") }) { AgentPreferences.saveReasoningEffort(ctx, null) }
                                            showReasoningMenu = false
                                        },
                                    )
                                    ReasoningEffort.entries.forEach { effort ->
                                        val wire = ReasoningSupport.wire(provider.id, model, effort)
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(effort.label)
                                                    if (wire == null) Text("Not supported by this model/provider", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                                                }
                                            },
                                            enabled = wire != null,
                                            trailingIcon = if (reasoningEffort == effort) {{ Icon(Icons.Default.Check, null) }} else null,
                                            onClick = {
                                                reasoningEffort = effort
                                                scope.launchUiCatching(onError = { onUiError("Could not update reasoning: ${it.message ?: it::class.java.simpleName}") }) { AgentPreferences.saveReasoningEffort(ctx, effort) }
                                                showReasoningMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        val backendControl: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                                AgentControlButton(
                                    label = selectedPlugin?.spec?.displayName ?: "Droide",
                                    icon = Icons.Default.SmartToy,
                                    height = controlHeight,
                                    compact = compactControls,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { showAgentBackendMenu = !showAgentBackendMenu }
                                DropdownMenu(expanded = showAgentBackendMenu, onDismissRequest = { showAgentBackendMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Column { Text("Droide Agent"); Text("Built-in", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) } },
                                        trailingIcon = if (agentBackendId == "droide") {{ Icon(Icons.Default.Check, null) }} else null,
                                        onClick = { agentBackendId = "droide"; showAgentBackendMenu = false },
                                    )
                                    pluginAvailability.forEach { item ->
                                        DropdownMenuItem(
                                            text = { Column { Text(item.spec.displayName); Text(if (item.available) "ACP · installed" else "Install from Extensions", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) } },
                                            enabled = item.available,
                                            trailingIcon = if (agentBackendId == item.spec.id) {{ Icon(Icons.Default.Check, null) }} else null,
                                            onClick = { agentBackendId = item.spec.id; showAgentBackendMenu = false },
                                        )
                                    }
                                }
                            }
                        }

                        val sendControl: @Composable (Modifier) -> Unit = { modifier ->
                            FilledIconButton(
                                onClick = ::send,
                                enabled = (input.isNotBlank() || attachedContexts.isNotEmpty()) && canSendWithBackend && !externalBusy,
                                modifier = modifier,
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = DroideColors.Primary),
                            ) { Icon(Icons.Default.Send, if (effectiveBusy) "Send instruction" else "Send", Modifier.size(if (denseComposer) 16.dp else 18.dp)) }
                        }
                        val stopControl: @Composable () -> Unit = {
                            if (effectiveBusy || preflightBusy) {
                                IconButton(
                                    onClick = {
                                        when {
                                            preflightBusy -> agentPreflightJob?.cancel()
                                            usingExternalAgent -> externalController.cancel(agentPlugins)
                                            else -> agent.cancel()
                                        }
                                    },
                                    modifier = Modifier.size(controlHeight),
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = DroideColors.Error),
                                ) { Icon(Icons.Default.Stop, if (preflightBusy) "Cancel provider preflight" else "Stop active agent run", Modifier.size(16.dp)) }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it.take(AgentService.MAX_USER_MESSAGE_CHARS) },
                                modifier = Modifier.weight(1f).heightIn(min = if (denseComposer) 24.dp else 27.dp, max = if (denseComposer) 38.dp else 46.dp)
                                    .onFocusChanged { state ->
                                        composerFocused = state.isFocused
                                        onComposerFocusChanged(state.isFocused)
                                    }
                                    .padding(horizontal = 2.dp, vertical = 4.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = DroideColors.Text),
                                cursorBrush = SolidColor(DroideColors.Text),
                                decorationBox = { inner ->
                                    Box(Modifier.fillMaxWidth()) {
                                        if (input.isEmpty()) Text(
                                            if (effectiveBusy) "Add instruction…" else if (usingExternalAgent) "Ask ${selectedPlugin?.spec?.displayName ?: "agent"}…" else "Ask Droide…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = DroideColors.Muted,
                                        )
                                        inner()
                                    }
                                },
                            )
                            if (effectiveBusy || preflightBusy) stopControl()
                            sendControl(Modifier.size(sendSize))
                        }
                        Spacer(Modifier.height(if (denseComposer) 2.dp else 4.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            attachControl()
                            if (!usingExternalAgent) {
                                modelControl(Modifier.widthIn(min = 104.dp, max = 176.dp))
                                reasoningControl(Modifier.widthIn(min = 86.dp, max = 128.dp))
                            }
                            modeControl(Modifier.widthIn(min = 70.dp, max = 108.dp))
                            backendControl(Modifier.widthIn(min = 82.dp, max = 140.dp))
                            if (busy && !usingExternalAgent) AgentDeliveryControl(promptDelivery, controlHeight, compactControls) { promptDelivery = it }
                        }
                    }
                }
            }
        }
    }

    if (showConnect) {
        ModalBottomSheet(onDismissRequest = { showConnect = false }) {
            ConnectSheet(
                connectionBackend = providerConnections,
                initialProviderId = connectProviderId,
                onConnected = { id, validatedModel ->
                    scope.launch {
                        localLlamaModels.selectRemote(id, validatedModel)
                        ProviderAuthManager.cleanupLegacyWorkspaceSecrets(agent.workDir)
                    }
                },
                onDismiss = { showConnect = false },
            )
        }
    }


    if (showBillingSheet) {
        ModalBottomSheet(onDismissRequest = { showBillingSheet = false }) {
            BillingSheet(tracker = agent.tokens, onDismiss = { showBillingSheet = false })
        }
    }
}

@Composable
private fun AgentModelControl(providerId: String, model: String, height: Dp, logoSize: Dp, compact: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = if (compact) 4.dp else 6.dp, vertical = 0.dp),
        modifier = modifier.height(height).widthIn(max = if (compact) 120.dp else 180.dp),
    ) {
        ModelBrandMark(providerId, model, null, logoSize)
        Spacer(Modifier.width(if (compact) 3.dp else 5.dp))
        Text(model.ifBlank { "Model" }, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(14.dp))
    }
}

@Composable
private fun AgentControlButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, height: Dp, compact: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = if (compact) 4.dp else 6.dp, vertical = 0.dp),
        modifier = modifier.height(height),
    ) {
        if (!compact) {
            Icon(icon, null, Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(if (compact) 11.dp else 13.dp))
    }
}

@Composable
private fun AgentTranscriptItem(message: AgentMessage, showStreamDetails: Boolean) {
    message.streamPart?.let { part ->
        AgentStreamPartItem(part, showDetails = showStreamDetails)
        return
    }
    if (message.content.startsWith("TOOL|")) {
        val parts = message.content.split("|", limit = 4)
        val tool = parts.getOrNull(1) ?: "tool"
        val args = parts.getOrNull(2).orEmpty()
        val result = parts.getOrNull(3).orEmpty()
        AgentCompletedToolPart(tool, args, result)
        return
    }

    val isUser = message.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Text(if (isUser) "YOU" else "DROIDE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (isUser) DroideColors.Primary else DroideColors.Muted)
        Spacer(Modifier.height(5.dp))
        if (isUser) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = minOf(maxWidth * .80f, 540.dp)),
                    shape = MaterialTheme.shapes.small,
                    color = DroideColors.Surface,
                    border = BorderStroke(1.dp, DroideColors.Border),
                ) { Text(message.content, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            AgentMarkdownContent(message.content, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun InteractionDock(title: String, maxHeight: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).background(DroideColors.Surface).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Surface(Modifier.fillMaxWidth(), color = DroideColors.Background, border = BorderStroke(1.dp, DroideColors.BorderStrong), shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
                content()
            }
        }
    }
}
