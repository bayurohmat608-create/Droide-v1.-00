package com.baystudio.droide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AgentStreamPart
import com.baystudio.droide.core.AgentStreamPartKind
import com.baystudio.droide.core.AgentStreamPartState
import com.baystudio.droide.core.AgentStreamState
import com.baystudio.droide.core.AgentStreamTransport
import com.baystudio.droide.core.AgentToolEvidenceState
import com.baystudio.droide.core.AgentToolResultSemantics
import com.baystudio.droide.core.AgentService
import com.baystudio.droide.core.ProviderRegistry
import kotlinx.coroutines.delay








@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentStreamRunHeader(
    state: AgentStreamState,
    tokenTotal: Int,
    showDetails: Boolean,
    onToggleDetails: () -> Unit,
) {
    var clock by remember(state.runId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.runId, state.status) {
        while (state.status == AgentStreamState.Status.BUSY || state.status == AgentStreamState.Status.RETRYING) {
            delay(1_000)
            clock = System.currentTimeMillis()
        }
    }
    val elapsed = if (state.startedAtMs > 0L) ((state.endedAtMs ?: clock) - state.startedAtMs).coerceAtLeast(0L) else 0L
    val providerName = ProviderRegistry.findById(state.providerId)?.name ?: state.providerId.ifBlank { "Provider" }
    val tools = state.parts.filter { it.kind == AgentStreamPartKind.TOOL }
    val succeededTools = tools.count { toolUiState(it) == ToolUiState.SUCCEEDED }
    val startedTools = tools.count { toolUiState(it) == ToolUiState.STARTED }
    val failedTools = tools.count { toolUiState(it) in setOf(ToolUiState.FAILED, ToolUiState.DENIED, ToolUiState.UNAVAILABLE) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DroideColors.Surface.copy(alpha = .52f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModelBrandMark(state.providerId, state.modelId, null, 15.dp)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.modelId.ifBlank { "Agent model" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(providerName, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AgentTransportBadge(state.transport)
                    }
                }
                AgentRunStatusBadge(state.status, state.retryAttempt)
                Spacer(Modifier.width(3.dp))
                IconButton(onClick = onToggleDetails, modifier = Modifier.size(28.dp)) {
                    Icon(if (showDetails) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showDetails) "Hide stream details" else "Show stream details", Modifier.size(15.dp), tint = DroideColors.Muted)
                }
            }
            val runSummary = buildList {
                add("Step ${state.activeStep.coerceAtLeast(1)}")
                if (tools.isNotEmpty()) add(buildString {
                    append(succeededTools).append('/').append(tools.size).append(" succeeded")
                    if (startedTools > 0) append(" · ").append(startedTools).append(" started")
                    if (failedTools > 0) append(" · ").append(failedTools).append(" blocked/failed")
                })
                add(formatStreamDuration(elapsed))
                if (showDetails && tokenTotal > 0) add(formatStreamTokenCount(tokenTotal))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                runSummary.forEachIndexed { index, metric ->
                    Text((if (index > 0) "· " else "") + metric, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AgentRunStatusBadge(status: AgentStreamState.Status, retryAttempt: Int) {
    val (label, tint) = when (status) {
        AgentStreamState.Status.BUSY -> "Running" to DroideColors.Primary
        AgentStreamState.Status.RETRYING -> (if (retryAttempt > 0) "Retry $retryAttempt" else "Retrying") to Color(0xFFE6B450)
        AgentStreamState.Status.ERROR -> "Error" to DroideColors.Error
        AgentStreamState.Status.CANCELLED -> "Stopped" to DroideColors.Muted
        AgentStreamState.Status.IDLE -> "Idle" to DroideColors.Muted
    }
    Surface(color = tint.copy(alpha = .10f), border = BorderStroke(1.dp, tint.copy(alpha = .35f)), shape = MaterialTheme.shapes.extraSmall) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (status == AgentStreamState.Status.BUSY || status == AgentStreamState.Status.RETRYING) {
                CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.2.dp, color = tint)
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AgentTransportBadge(transport: AgentStreamTransport) {
    if (transport == AgentStreamTransport.UNKNOWN) return
    val label = if (transport == AgentStreamTransport.NATIVE) "Live" else "Buffered"
    val tint = if (transport == AgentStreamTransport.NATIVE) DroideColors.Success else DroideColors.Muted
    Surface(color = tint.copy(alpha = .08f), shape = MaterialTheme.shapes.extraSmall) {
        Text(label, Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
internal fun AgentStreamPartItem(part: AgentStreamPart, showDetails: Boolean) {
    when (part.kind) {
        AgentStreamPartKind.INPUT -> AgentLiveInputPart(part)
        AgentStreamPartKind.TEXT -> AgentLiveTextPart(part, showDetails)
        AgentStreamPartKind.TOOL -> AgentLiveToolPart(part, showDetails)
        AgentStreamPartKind.STEP -> AgentLiveStepPart(part, showDetails)
        AgentStreamPartKind.COMPACTION, AgentStreamPartKind.RETRY, AgentStreamPartKind.STATUS -> AgentLifecyclePart(part, showDetails)
    }
}


@Composable
private fun AgentLiveInputPart(part: AgentStreamPart) {
    if (part.text.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            color = DroideColors.Primary.copy(alpha = .10f),
            border = BorderStroke(1.dp, DroideColors.Primary.copy(alpha = .22f)),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(part.title.ifBlank { "Instruction" }, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Primary)
                Text(part.text, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD6DCE2))
            }
        }
    }
}

@Composable
private fun AgentLiveTextPart(part: AgentStreamPart, showDetails: Boolean) {
    if (part.text.isBlank()) return
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("DROIDE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
            if (part.detail.contains("buffered", ignoreCase = true)) {
                Surface(color = DroideColors.Surface3, shape = MaterialTheme.shapes.extraSmall) {
                    Text("buffered", Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        AgentStreamingMarkdownContent(
            markdown = part.text,
            streamId = part.id,
            completed = part.state != AgentStreamPartState.RUNNING && part.state != AgentStreamPartState.PENDING,
            modifier = Modifier.fillMaxWidth(),
        )
        if (showDetails && part.state != AgentStreamPartState.RUNNING) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (part.finishReason.isNotBlank()) Text("finish: ${part.finishReason}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                if (part.startedAtMs > 0L) Text(formatStreamDuration(part.durationMs), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
        }
    }
}

@Composable
private fun AgentLiveStepPart(part: AgentStreamPart, showDetails: Boolean) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AgentStreamStateGlyph(part.state)
        Text(part.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = DroideColors.Muted)
        if (part.detail.isNotBlank()) {
            Text(part.detail, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else Spacer(Modifier.weight(1f))
        if (showDetails && part.state != AgentStreamPartState.PENDING) {
            Column(horizontalAlignment = Alignment.End) {
                Text(formatStreamDuration(part.durationMs), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                if (part.totalTokens > 0) {
                    Text(formatStreamTokenCount(part.totalTokens), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                }
            }
        }
    }
    if (showDetails && part.state == AgentStreamPartState.COMPLETED && (
            part.promptTokens > 0 || part.completionTokens > 0 || part.reasoningTokens > 0 ||
                part.cacheReadTokens > 0 || part.costUsd > 0.0 || part.finishReason.isNotBlank()
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 26.dp, end = 5.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (part.promptTokens > 0) Text("in ${part.promptTokens}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            if (part.completionTokens > 0) Text("out ${part.completionTokens}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            if (part.reasoningTokens > 0) Text("reason ${part.reasoningTokens}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            if (part.cacheReadTokens > 0) Text("cache ${part.cacheReadTokens}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            if (part.costUsd > 0.0) Text(String.format(java.util.Locale.US, "$%.5f", part.costUsd), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            if (part.finishReason.isNotBlank()) Text("finish ${part.finishReason}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AgentLifecyclePart(part: AgentStreamPart, showDetails: Boolean) {
    val tint = when (part.state) {
        AgentStreamPartState.ERROR -> DroideColors.Error
        AgentStreamPartState.CANCELLED -> DroideColors.Muted
        else -> DroideColors.Muted
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (part.state == AgentStreamPartState.ERROR) DroideColors.Error.copy(alpha = .05f) else Color.Transparent,
        border = if (part.state == AgentStreamPartState.ERROR) BorderStroke(1.dp, DroideColors.Error.copy(alpha = .20f)) else null,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 5.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AgentStreamStateGlyph(part.state)
            Icon(
                when (part.kind) {
                    AgentStreamPartKind.RETRY -> Icons.Default.Replay
                    AgentStreamPartKind.COMPACTION -> Icons.Default.AutoFixHigh
                    else -> Icons.Default.Info
                },
                null,
                Modifier.size(14.dp),
                tint = tint,
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(part.title, style = MaterialTheme.typography.labelMedium)
                    if (part.kind == AgentStreamPartKind.RETRY && part.attempt > 0) {
                        Text("#${part.attempt}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    }
                }
                if (part.detail.isNotBlank()) {
                    Text(part.detail, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = if (showDetails) 4 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showDetails && part.startedAtMs > 0L) {
                Text(formatStreamDuration(part.durationMs), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
        }
    }
}

@Composable
internal fun AgentCompletedToolPart(tool: String, input: String, output: String) {
    val evidence = AgentToolResultSemantics.parseAnnotation(output)
    val legacyFailure = output.startsWith("DENIED", ignoreCase = true) || output.startsWith("ERROR", ignoreCase = true)
    AgentLiveToolPart(
        AgentStreamPart(
            id = "durable:${tool}:${input.hashCode()}:${output.hashCode()}",
            step = 0,
            kind = AgentStreamPartKind.TOOL,
            state = if (evidence?.isFailure == true || (evidence == null && legacyFailure)) AgentStreamPartState.ERROR else AgentStreamPartState.COMPLETED,
            title = tool.replace('_', ' '),
            toolName = tool,
            input = input,
            output = output,
            detail = evidence?.uiDetail ?: if (output.startsWith("DENIED", ignoreCase = true)) "Denied" else if (output.startsWith("ERROR", ignoreCase = true)) "Failed" else "Succeeded",
            startedAtMs = 0L,
            endedAtMs = 0L,
        ),
        showDetails = false,
    )
}

@Composable
private fun AgentLiveToolPart(part: AgentStreamPart, showDetails: Boolean) {
    var expanded by remember(part.id) { mutableStateOf(false) }
    val tool = part.toolName.ifBlank { part.title.replace(' ', '_') }
    val activityPath = extractStreamActivityPath(part.input)
    val primary = streamPrimaryToolDetail(tool, part.input, activityPath)
    val visibleOutput = AgentToolResultSemantics.stripAnnotation(part.output)
    val uiState = toolUiState(part)
    val canExpand = part.input.isNotBlank() || visibleOutput.isNotBlank() || part.detail.isNotBlank()
    val resultSummary = streamResultSummary(part, visibleOutput, uiState)
    val stateTint = toolUiTint(uiState)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (uiState in setOf(ToolUiState.RUNNING, ToolUiState.STARTED)) stateTint.copy(alpha = .035f) else Color.Transparent,
        border = if (uiState in setOf(ToolUiState.RUNNING, ToolUiState.STARTED, ToolUiState.FAILED, ToolUiState.DENIED, ToolUiState.UNAVAILABLE)) BorderStroke(1.dp, stateTint.copy(alpha = .20f)) else null,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(MaterialTheme.shapes.small)
                    .then(if (canExpand) Modifier.clickable { expanded = !expanded } else Modifier)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentToolStateGlyph(uiState)
                Spacer(Modifier.width(7.dp))
                Icon(streamToolIcon(tool), null, Modifier.size(15.dp), tint = DroideColors.Muted)
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${toolUiStateLabel(uiState)} · ${streamToolDisplayName(tool)}", style = MaterialTheme.typography.labelMedium)
                        if (showDetails && part.toolCallId.isNotBlank()) {
                            Text(part.toolCallId.takeLast(8), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (activityPath != null) {
                        FileIdentityLabel(path = activityPath, label = activityPath, iconSize = 13.dp, monospaced = true, color = DroideColors.Muted)
                    } else if (primary.isNotBlank()) {
                        Text(primary, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = if (tool in setOf("run_command", "bash")) FontFamily.Monospace else FontFamily.Default)
                    } else if (part.detail.isNotBlank()) {
                        Text(part.detail, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (showDetails && part.startedAtMs > 0L) {
                    Text(formatStreamDuration(part.durationMs), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    Spacer(Modifier.width(4.dp))
                }
                if (canExpand) {
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (expanded) "Collapse" else "Expand", Modifier.size(17.dp), tint = DroideColors.Muted)
                }
            }

            if (resultSummary.isNotBlank() && !expanded && (showDetails || uiState != ToolUiState.SUCCEEDED)) {
                Text(resultSummary, Modifier.padding(start = 36.dp, end = 8.dp, bottom = 5.dp), style = MaterialTheme.typography.labelSmall, color = if (uiState == ToolUiState.FAILED) DroideColors.Error else if (uiState in setOf(ToolUiState.DENIED, ToolUiState.UNAVAILABLE, ToolUiState.STARTED)) DroideColors.Warning else DroideColors.Muted, maxLines = if (showDetails) 2 else 1, overflow = TextOverflow.Ellipsis)
            }

            if (expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 36.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    StreamDetailRow("STATE", toolUiStateDetail(uiState, part.detail))
                    if (part.input.isNotBlank()) {
                        Text("INPUT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
                        Surface(color = DroideColors.Background, border = BorderStroke(1.dp, DroideColors.Border), shape = MaterialTheme.shapes.extraSmall) {
                            Text(part.input.take(6_000), Modifier.fillMaxWidth().padding(7.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (visibleOutput.isNotBlank()) {
                        Text("OUTPUT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
                        Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            if (tool.contains("edit") || tool.contains("write") || tool.contains("patch")) DiffText(visibleOutput.take(12_000))
                            else Surface(color = DroideColors.Background, border = BorderStroke(1.dp, DroideColors.Border), shape = MaterialTheme.shapes.extraSmall) {
                                Text(visibleOutput.take(12_000), Modifier.fillMaxWidth().padding(7.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    if (showDetails && part.finishReason.isNotBlank()) StreamDetailRow("FINISH", part.finishReason)
                }
            }
        }
    }
}

@Composable
private fun StreamDetailRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DroideColors.Muted)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
    }
}

@Composable
private fun AgentStreamStateGlyph(state: AgentStreamPartState) {
    when (state) {
        AgentStreamPartState.PENDING -> Icon(Icons.Default.MoreHoriz, null, Modifier.size(14.dp), tint = DroideColors.Muted)
        AgentStreamPartState.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        AgentStreamPartState.COMPLETED -> Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = DroideColors.Success)
        AgentStreamPartState.ERROR -> Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = DroideColors.Error)
        AgentStreamPartState.CANCELLED -> Icon(Icons.Default.Cancel, null, Modifier.size(14.dp), tint = DroideColors.Muted)
    }
}

private fun streamStateLabel(state: AgentStreamPartState): String = when (state) {
    AgentStreamPartState.PENDING -> "Preparing"
    AgentStreamPartState.RUNNING -> "Running"
    AgentStreamPartState.COMPLETED -> "Completed"
    AgentStreamPartState.ERROR -> "Failed"
    AgentStreamPartState.CANCELLED -> "Stopped"
}

private enum class ToolUiState {
    PREPARING, RUNNING, WAITING_APPROVAL, WAITING_INPUT, STARTED, SUCCEEDED, FAILED, DENIED, UNAVAILABLE, STOPPED,
}

private fun toolUiState(part: AgentStreamPart): ToolUiState {
    if (part.state == AgentStreamPartState.PENDING) return ToolUiState.PREPARING
    if (part.state == AgentStreamPartState.RUNNING) {
        if (part.detail.startsWith("Waiting for approval", ignoreCase = true)) return ToolUiState.WAITING_APPROVAL
        if (part.detail.startsWith("Waiting for user", ignoreCase = true)) return ToolUiState.WAITING_INPUT
        return ToolUiState.RUNNING
    }
    if (part.state == AgentStreamPartState.CANCELLED) return ToolUiState.STOPPED
    val evidence = AgentToolResultSemantics.parseAnnotation(part.output)?.state ?: when {
        part.detail.startsWith("Started", ignoreCase = true) -> AgentToolEvidenceState.STARTED
        part.detail.startsWith("Denied", ignoreCase = true) -> AgentToolEvidenceState.DENIED
        part.detail.startsWith("Unavailable", ignoreCase = true) -> AgentToolEvidenceState.UNAVAILABLE
        part.detail.startsWith("Succeeded", ignoreCase = true) -> AgentToolEvidenceState.SUCCEEDED
        part.state == AgentStreamPartState.ERROR -> AgentToolEvidenceState.FAILED
        else -> AgentToolEvidenceState.SUCCEEDED
    }
    return when (evidence) {
        AgentToolEvidenceState.SUCCEEDED -> ToolUiState.SUCCEEDED
        AgentToolEvidenceState.STARTED -> ToolUiState.STARTED
        AgentToolEvidenceState.FAILED -> ToolUiState.FAILED
        AgentToolEvidenceState.DENIED -> ToolUiState.DENIED
        AgentToolEvidenceState.UNAVAILABLE -> ToolUiState.UNAVAILABLE
    }
}

private fun toolUiStateLabel(state: ToolUiState): String = when (state) {
    ToolUiState.PREPARING -> "Preparing"
    ToolUiState.RUNNING -> "Running"
    ToolUiState.WAITING_APPROVAL -> "Waiting approval"
    ToolUiState.WAITING_INPUT -> "Waiting input"
    ToolUiState.STARTED -> "Started"
    ToolUiState.SUCCEEDED -> "Succeeded"
    ToolUiState.FAILED -> "Failed"
    ToolUiState.DENIED -> "Denied"
    ToolUiState.UNAVAILABLE -> "Unavailable"
    ToolUiState.STOPPED -> "Stopped"
}

private fun toolUiStateDetail(state: ToolUiState, rawDetail: String): String = when (state) {
    ToolUiState.STARTED -> "Running"
    ToolUiState.SUCCEEDED -> "Done"
    ToolUiState.FAILED -> rawDetail.ifBlank { "Failed" }
    ToolUiState.DENIED -> "Denied · execution did not start"
    ToolUiState.UNAVAILABLE -> "Unavailable"
    ToolUiState.STOPPED -> "Stopped before completion"
    else -> rawDetail.ifBlank { toolUiStateLabel(state) }
}

private fun toolUiTint(state: ToolUiState): Color = when (state) {
    ToolUiState.RUNNING, ToolUiState.WAITING_INPUT -> DroideColors.Primary
    ToolUiState.STARTED, ToolUiState.WAITING_APPROVAL, ToolUiState.DENIED, ToolUiState.UNAVAILABLE -> DroideColors.Warning
    ToolUiState.SUCCEEDED -> DroideColors.Success
    ToolUiState.FAILED -> DroideColors.Error
    ToolUiState.PREPARING, ToolUiState.STOPPED -> DroideColors.Muted
}

@Composable
private fun AgentToolStateGlyph(state: ToolUiState) {
    when (state) {
        ToolUiState.PREPARING -> Icon(Icons.Default.MoreHoriz, null, Modifier.size(14.dp), tint = DroideColors.Muted)
        ToolUiState.RUNNING, ToolUiState.WAITING_APPROVAL, ToolUiState.WAITING_INPUT -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = toolUiTint(state))
        ToolUiState.STARTED -> Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp), tint = DroideColors.Warning)
        ToolUiState.SUCCEEDED -> Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = DroideColors.Success)
        ToolUiState.FAILED -> Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = DroideColors.Error)
        ToolUiState.DENIED -> Icon(Icons.Default.Block, null, Modifier.size(14.dp), tint = DroideColors.Warning)
        ToolUiState.UNAVAILABLE -> Icon(Icons.Default.WarningAmber, null, Modifier.size(14.dp), tint = DroideColors.Warning)
        ToolUiState.STOPPED -> Icon(Icons.Default.Cancel, null, Modifier.size(14.dp), tint = DroideColors.Muted)
    }
}

private fun streamResultSummary(part: AgentStreamPart, visibleOutput: String, uiState: ToolUiState): String {
    if (uiState == ToolUiState.STARTED) return "Running"
    if (uiState == ToolUiState.DENIED) return "Execution blocked by permission or policy"
    if (uiState == ToolUiState.UNAVAILABLE) return "Required tool unavailable"
    if (uiState == ToolUiState.FAILED) return part.detail.ifBlank { visibleOutput.lineSequence().firstOrNull()?.take(240).orEmpty() }
    if (uiState == ToolUiState.STOPPED) return "Cancelled before completion"
    if (visibleOutput.isBlank()) return ""
    val lines = visibleOutput.count { it == '\n' } + 1
    val suffix = if (part.output.length >= AgentService.MAX_STREAM_TOOL_OUTPUT_CHARS) " · live tail capped" else ""
    if (uiState in setOf(ToolUiState.RUNNING, ToolUiState.WAITING_APPROVAL, ToolUiState.WAITING_INPUT)) {
        val tail = visibleOutput.lineSequence().filter { it.isNotBlank() }.lastOrNull()?.trim()?.take(140).orEmpty()
        return buildString {
            append("Live · ").append(lines).append(" line").append(if (lines == 1) "" else "s")
            if (tail.isNotBlank()) append(" · ").append(tail)
            append(suffix)
        }
    }
    return "$lines line${if (lines == 1) "" else "s"} · ${visibleOutput.length} chars$suffix"
}

private fun streamPrimaryToolDetail(tool: String, input: String, activityPath: String?): String {
    if (activityPath != null) return activityPath
    if (input.isBlank()) return ""
    fun jsonString(name: String): String? = Regex("\\\"$name\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
        .find(input)?.groupValues?.getOrNull(1)?.replace("\\n", " ")?.replace("\\\"", "\"")
    return when (tool) {
        "run_command", "bash" -> jsonString("command") ?: input.take(220)
        "search_files", "grep" -> jsonString("query") ?: jsonString("pattern") ?: input.take(220)
        "glob" -> jsonString("pattern") ?: input.take(220)
        "read_file", "read", "write_file", "write", "edit_file", "edit", "apply_patch" -> jsonString("path") ?: jsonString("file") ?: input.take(220)
        "background_job" -> listOfNotNull(jsonString("action"), jsonString("job_id")).joinToString(" · ").ifBlank { input.take(220) }
        "lsp_edit" -> listOfNotNull(jsonString("operation"), jsonString("path"), jsonString("title")).joinToString(" · ").ifBlank { input.take(220) }
        "toolchain" -> listOfNotNull(jsonString("operation"), jsonString("family"), jsonString("version")).joinToString(" · ").ifBlank { input.take(220) }
        "browser" -> listOfNotNull(jsonString("operation"), jsonString("url"), jsonString("target")).joinToString(" · ").ifBlank { input.take(220) }
        else -> input.take(220)
    }
}

private fun extractStreamActivityPath(args: String): String? {
    val quoted = Regex("""[\"']([^\"'\n\r]{1,512})[\"']""")
        .findAll(args)
        .map { it.groupValues[1].trim() }
        .firstOrNull(::looksLikeStreamFilePath)
    if (quoted != null) return quoted
    return Regex("""(?:^|[\s:=])((?:[A-Za-z0-9_.@+-]+[\\/])*[A-Za-z0-9_.@+-]+(?:\.[A-Za-z0-9_.+-]+)?)""")
        .findAll(args)
        .map { it.groupValues[1].trim().trimEnd(',', ';', ')', ']', '}') }
        .firstOrNull(::looksLikeStreamFilePath)
}

private fun looksLikeStreamFilePath(candidate: String): Boolean {
    if (candidate.isBlank() || candidate.length > 512) return false
    val name = candidate.substringAfterLast('/').substringAfterLast('\\')
    if (name in setOf(".", "..") || name.all(Char::isDigit)) return false
    if (
        name.equals("AndroidManifest.xml", true) || name.equals("Dockerfile", true) ||
        name.equals("Containerfile", true) || name.equals("Makefile", true) ||
        name.equals("GNUmakefile", true) || name.equals("CMakeLists.txt", true) ||
        name.equals("gradlew", true)
    ) return true
    if (candidate.contains('/') || candidate.contains('\\')) return name.contains('.') || name.startsWith('.')
    return name.contains('.') && !name.matches(Regex("""\d+\.\d+"""))
}

private fun streamToolDisplayName(tool: String): String = when (tool) {
    "read_file", "read" -> "Read"
    "search_files", "grep", "glob" -> "Search"
    "write_file", "write" -> "Write"
    "edit_file", "edit", "apply_patch" -> "Edit"
    "run_command", "bash" -> "Terminal"
    "background_job" -> "Background job"
    "environment_probe" -> "Environment check"
    "websearch", "webfetch" -> "Web"
    "browser" -> "Browser"
    "lsp" -> "Language service"
    "lsp_edit" -> "LSP fix"
    "toolchain" -> "Toolchain"
    "skill" -> "Skill"
    "mcp" -> "MCP"
    else -> tool.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun streamToolIcon(tool: String) = when (tool) {
    "read_file", "read" -> Icons.Default.Description
    "search_files", "grep" -> Icons.Default.Search
    "glob" -> Icons.Default.Folder
    "write_file", "write" -> Icons.Default.NoteAdd
    "edit_file", "edit", "apply_patch" -> Icons.Default.Edit
    "run_command", "bash", "background_job" -> Icons.Default.Terminal
    "environment_probe" -> Icons.Default.Info
    "websearch", "webfetch", "browser" -> Icons.Default.Language
    "lsp", "lsp_edit" -> Icons.Default.Code
    "toolchain" -> Icons.Default.Build
    "skill" -> Icons.Default.Extension
    "mcp" -> Icons.Default.Hub
    else -> Icons.Default.Build
}

private fun formatStreamTokenCount(tokens: Int): String = when {
    tokens >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM tokens", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format(java.util.Locale.US, "%.1fk tokens", tokens / 1_000.0)
    else -> "$tokens tokens"
}

private fun formatStreamDuration(ms: Long): String = when {
    ms < 1_000L -> "${ms}ms"
    ms < 60_000L -> String.format(java.util.Locale.US, "%.1fs", ms / 1_000.0)
    else -> "${ms / 60_000}m ${((ms % 60_000) / 1_000)}s"
}
