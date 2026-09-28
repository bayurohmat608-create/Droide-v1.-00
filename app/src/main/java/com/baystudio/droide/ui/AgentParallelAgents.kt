package com.baystudio.droide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch






@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentParallelAgentsPanel(
    state: SubagentDashboardState,
    onAction: suspend (action: String, taskId: String, message: String?) -> String,
) {
    if (state.tasks.isEmpty()) return
    val scope = rememberCoroutineScope()
    var actionNotice by remember(state.parentSessionId) { mutableStateOf<String?>(null) }
    var pendingDiscardTaskId by remember(state.parentSessionId) { mutableStateOf<String?>(null) }
    val activeTasks = state.tasks.filter { it.active }.sortedWith(compareBy<SubagentTaskView> { it.queuePosition.takeIf { pos -> pos > 0 } ?: Int.MAX_VALUE }.thenBy { it.createdAtMs })
    val attentionTasks = state.tasks.filter { task ->
        !task.active && (
            task.state in setOf(SubagentTaskState.ERROR, SubagentTaskState.INTERRUPTED) ||
                (task.state == SubagentTaskState.IDLE && task.workspaceMode == SubagentWorkspaceMode.ISOLATED && task.integration in setOf(SubagentIntegrationState.PENDING, SubagentIntegrationState.CONFLICT))
            )
    }.sortedByDescending { it.endedAtMs }
    val finishedTasks = state.tasks.filter { it !in activeTasks && it !in attentionTasks }.sortedByDescending { maxOf(it.endedAtMs, it.createdAtMs) }
    val hasActive = activeTasks.isNotEmpty()
    val needsAttention = attentionTasks.size
    var collapsed by rememberSaveable(state.parentSessionId) { mutableStateOf(!hasActive && needsAttention == 0) }
    var showFinished by rememberSaveable(state.parentSessionId) { mutableStateOf(false) }
    var expandedTaskId by rememberSaveable(state.parentSessionId) { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hasActive, needsAttention) {
        if (hasActive || needsAttention > 0) collapsed = false
    }
    LaunchedEffect(hasActive) {
        while (hasActive) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    pendingDiscardTaskId?.let { taskId ->
        AlertDialog(
            onDismissRequest = { pendingDiscardTaskId = null },
            title = { Text("Discard isolated agent changes?") },
            text = { Text("This permanently removes the child workspace and its unmerged changes.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDiscardTaskId = null
                    scope.launch { actionNotice = onAction("discard", taskId, null).take(5_000) }
                }) { Text("Discard", color = DroideColors.Error) }
            },
            dismissButton = { TextButton(onClick = { pendingDiscardTaskId = null }) { Text("Cancel") } },
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DroideColors.Surface,
        border = BorderStroke(1.dp, DroideColors.Border),
        shape = MaterialTheme.shapes.small,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { collapsed = !collapsed }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(Icons.Default.AccountTree, null, Modifier.size(16.dp), tint = if (hasActive) DroideColors.Primary else DroideColors.Muted)
                Column(Modifier.weight(1f)) {
                    Text("Parallel agents", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    val summary = buildList {
                        if (state.running > 0) add("${state.running} running")
                        if (state.queued > 0) add("${state.queued} queued")
                        if (needsAttention > 0) add("$needsAttention needs review")
                        if (isEmpty() && finishedTasks.isNotEmpty()) add("${finishedTasks.size} finished")
                    }.joinToString(" · ")
                    if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.labelSmall, color = if (needsAttention > 0) DroideColors.Error else DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (hasActive) AgentCountBadge("${activeTasks.size}", DroideColors.Primary)
                Icon(if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null, Modifier.size(16.dp), tint = DroideColors.Muted)
            }
            if (!collapsed) {
                HorizontalDivider(color = DroideColors.Border)
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    val visibleFinished = if (showFinished) finishedTasks.take(8) else emptyList()
                    val visibleTasks = activeTasks + attentionTasks + visibleFinished
                    visibleTasks.forEachIndexed { index, task ->
                        ParallelAgentRow(
                            task = task,
                            nowMs = nowMs,
                            expanded = expandedTaskId == task.id,
                            onToggle = { expandedTaskId = if (expandedTaskId == task.id) null else task.id },
                            onAction = { action, taskId, message ->
                                if (action == "discard") pendingDiscardTaskId = taskId
                                else scope.launch { actionNotice = onAction(action, taskId, message).take(5_000) }
                            },
                        )
                        if (index < visibleTasks.lastIndex) HorizontalDivider(Modifier.padding(start = 31.dp), color = DroideColors.Border.copy(alpha = .55f))
                    }
                    if (finishedTasks.isNotEmpty()) {
                        TextButton(
                            onClick = { showFinished = !showFinished },
                            modifier = Modifier.padding(start = 24.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Icon(if (showFinished) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (showFinished) "Hide finished" else "Show finished (${finishedTasks.size})", style = MaterialTheme.typography.labelSmall)
                        }
                        if (showFinished && finishedTasks.size > 8) {
                            Text("+${finishedTasks.size - 8} older agents not shown", Modifier.padding(start = 31.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                        }
                    }
                    actionNotice?.let { notice ->
                        Surface(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            color = DroideColors.Background, border = BorderStroke(1.dp, DroideColors.Border),
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(start = 7.dp, end = 2.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
                                Text(
                                    notice, Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (notice.startsWith("ERROR", true)) DroideColors.Error else DroideColors.Muted,
                                    fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(onClick = { actionNotice = null }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(13.dp), tint = DroideColors.Muted) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentCountBadge(text: String, tint: Color) {
    Surface(color = tint.copy(alpha = .08f), shape = MaterialTheme.shapes.extraSmall) {
        Text(text, Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParallelAgentRow(
    task: SubagentTaskView,
    nowMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAction: (String, String, String?) -> Unit,
) {
    var steerDraft by rememberSaveable(task.id) { mutableStateOf("") }
    val tint = when (task.state) {
        SubagentTaskState.RUNNING, SubagentTaskState.STARTING -> DroideColors.Primary
        SubagentTaskState.IDLE -> DroideColors.Success
        SubagentTaskState.ERROR, SubagentTaskState.INTERRUPTED -> DroideColors.Error
        SubagentTaskState.KILLED -> DroideColors.Muted
        SubagentTaskState.QUEUED -> DroideColors.Muted
    }
    val status = parallelAgentStateLabel(task)
    val elapsed = when {
        task.startedAtMs <= 0L -> ""
        task.endedAtMs > 0L -> formatAgentDuration((task.endedAtMs - task.startedAtMs).coerceAtLeast(0L))
        else -> formatAgentDuration((nowMs - task.startedAtMs).coerceAtLeast(0L))
    }
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ParallelAgentStateGlyph(task.state, tint)
            Column(Modifier.weight(1f)) {
                Text(task.description, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val activity = when {
                    task.activity.isNotBlank() && task.active -> task.activity
                    task.state == SubagentTaskState.IDLE && task.workspaceMode == SubagentWorkspaceMode.ISOLATED && task.integration == SubagentIntegrationState.PENDING -> "Awaiting review"
                    task.integration == SubagentIntegrationState.CONFLICT -> "Merge conflict · review required"
                    task.state in setOf(SubagentTaskState.ERROR, SubagentTaskState.INTERRUPTED) -> task.errorPreview.ifBlank { status }
                    task.state == SubagentTaskState.QUEUED -> status
                    else -> status
                }
                Text(activity, style = MaterialTheme.typography.labelSmall, color = if (task.state in setOf(SubagentTaskState.ERROR, SubagentTaskState.INTERRUPTED) || task.integration == SubagentIntegrationState.CONFLICT) DroideColors.Error else DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (elapsed.isNotBlank()) Text(elapsed, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(15.dp), tint = DroideColors.Muted)
        }

        if (expanded) {
            Column(Modifier.padding(start = 23.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AgentCountBadge(status, tint)
                    AgentCountBadge(task.profile, DroideColors.Muted)
                    AgentCountBadge(task.workspaceMode.name.lowercase(), DroideColors.Muted)
                    if (task.activityStep > 0) AgentCountBadge("step ${task.activityStep}", DroideColors.Muted)
                    if (task.attempt > 1) AgentCountBadge("attempt ${task.attempt}", DroideColors.Muted)
                    if (task.modelId.isNotBlank()) AgentCountBadge(task.modelId.take(28), DroideColors.Muted)
                }
                Text("task ${task.id.takeLast(12)} · child ${task.childSessionId.takeLast(10)}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, fontFamily = FontFamily.Monospace)
                if (task.activityTool.isNotBlank()) Text("Tool: ${task.activityTool}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                if (task.errorPreview.isNotBlank()) {
                    Text(task.errorPreview, style = MaterialTheme.typography.labelSmall, color = DroideColors.Error)
                } else if (task.resultPreview.isNotBlank()) {
                    Text(task.resultPreview, style = MaterialTheme.typography.labelSmall, color = DroideColors.Text, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                if (task.state == SubagentTaskState.RUNNING && task.background) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = steerDraft,
                            onValueChange = { steerDraft = it.take(SubagentManager.MAX_STEER_MESSAGE_CHARS) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Steer worker…") },
                            textStyle = MaterialTheme.typography.labelSmall,
                            minLines = 1,
                            maxLines = 3,
                        )
                        IconButton(
                            enabled = steerDraft.isNotBlank(),
                            onClick = {
                                val text = steerDraft.trim()
                                if (text.isNotBlank()) {
                                    steerDraft = ""
                                    onAction("message", task.id, text)
                                }
                            },
                        ) { Icon(Icons.Default.Send, "Steer worker", Modifier.size(17.dp)) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (task.state !in setOf(SubagentTaskState.QUEUED, SubagentTaskState.STARTING)) {
                        TextButton(onClick = { onAction("transcript", task.id, null) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                            Icon(Icons.Default.History, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Transcript")
                        }
                    }
                    if (task.active && task.background) {
                        TextButton(onClick = { onAction("cancel", task.id, null) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                            Icon(Icons.Default.StopCircle, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Stop")
                        }
                    }
                    if (task.state == SubagentTaskState.IDLE && task.workspaceMode == SubagentWorkspaceMode.ISOLATED && task.integration in setOf(SubagentIntegrationState.PENDING, SubagentIntegrationState.CONFLICT)) {
                        TextButton(onClick = { onAction("review", task.id, null) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                            Icon(Icons.Default.Visibility, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Review")
                        }
                        TextButton(onClick = { onAction("merge", task.id, null) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                            Icon(Icons.Default.AccountTree, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(if (task.integration == SubagentIntegrationState.CONFLICT) "Retry merge" else "Merge")
                        }
                        TextButton(onClick = { onAction("discard", task.id, null) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                            Icon(Icons.Default.DeleteOutline, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Discard")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParallelAgentStateGlyph(state: SubagentTaskState, tint: Color) {
    when (state) {
        SubagentTaskState.RUNNING, SubagentTaskState.STARTING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        SubagentTaskState.QUEUED -> Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = tint)
        SubagentTaskState.IDLE -> Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = tint)
        SubagentTaskState.ERROR, SubagentTaskState.INTERRUPTED -> Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = tint)
        SubagentTaskState.KILLED -> Icon(Icons.Default.Cancel, null, Modifier.size(14.dp), tint = tint)
    }
}

private fun parallelAgentStateLabel(task: SubagentTaskView): String = when (task.state) {
    SubagentTaskState.QUEUED -> if (task.queuePosition > 0) "Queued #${task.queuePosition}" else "Queued"
    SubagentTaskState.STARTING -> "Starting"
    SubagentTaskState.RUNNING -> "Running"
    SubagentTaskState.IDLE -> when (task.integration) {
        SubagentIntegrationState.PENDING -> "Done · review changes"
        SubagentIntegrationState.MERGED -> "Merged"
        SubagentIntegrationState.CONFLICT -> "Merge conflict"
        SubagentIntegrationState.DISCARDED -> "Discarded"
        else -> "Done"
    }
    SubagentTaskState.ERROR -> "Failed"
    SubagentTaskState.KILLED -> "Stopped"
    SubagentTaskState.INTERRUPTED -> "Interrupted"
}

private fun formatAgentDuration(ms: Long): String = when {
    ms < 1_000L -> "${ms}ms"
    ms < 60_000L -> String.format(java.util.Locale.US, "%.1fs", ms / 1_000.0)
    else -> "${ms / 60_000}m ${((ms % 60_000) / 1_000)}s"
}
