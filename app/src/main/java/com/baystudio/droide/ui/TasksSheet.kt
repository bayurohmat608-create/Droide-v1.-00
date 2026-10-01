package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
fun TasksSheet(manager: TaskManager, onDismiss: () -> Unit, beforeRun: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var tasks by remember(manager) { mutableStateOf<List<IdeTask>>(emptyList()) }
    var loading by remember(manager) { mutableStateOf(true) }
    var running by remember(manager) { mutableStateOf<IdeTask?>(null) }
    var job by remember(manager) { mutableStateOf<Job?>(null) }
    var result by remember(manager) { mutableStateOf<TaskResult?>(null) }
    var error by remember(manager) { mutableStateOf<String?>(null) }
    var stopping by remember(manager) { mutableStateOf(false) }

    LaunchedEffect(manager) {
        loading = true
        try {
            tasks = manager.list()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            tasks = emptyList()
            error = "Could not load tasks: ${failure.message ?: failure::class.java.simpleName}"
        } finally { loading = false }
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f)) {
        DroidePanelHeader(title = "Tasks", onClose = onDismiss, actions = {
            running?.let {
                OutlinedButton(onClick = {
                    val stopped = job ?: return@OutlinedButton
                    if (stopping) return@OutlinedButton
                    stopping = true; error = "Stopping task…"
                    stopped.cancel()
                    scope.launch {
                        stopped.join()
                        if (job === stopped) {
                            running = null; job = null; error = "Task stopped"; stopping = false
                        }
                    }
                }, enabled = !stopping) {
                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(4.dp)); Text(if (stopping) "Stopping…" else "Stop")
                }
            }
        })
        HorizontalDivider()
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                if (tasks.isEmpty() && error?.startsWith("Could not load tasks:") != true) item {
                    Text("No tasks detected. Add literal argv tasks to .droide/tasks.json.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(tasks) { task ->
                    ListItem(
                        leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                        headlineContent = { Text(task.label) },
                        supportingContent = {
                            Column {
                                Text(task.argv.joinToString(" ").take(300), fontFamily = FontFamily.Monospace, maxLines = 2)
                                Text(
                                    when (task.executionScope) {
                                        TaskExecutionScope.AUTO -> "Auto execution environment"
                                        TaskExecutionScope.LOCAL -> "Local"
                                        TaskExecutionScope.LOCAL_LINUX_ARM64 -> "Local Linux ARM64"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingContent = { Text(task.group, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.clickable(enabled = running == null) {
                            running = task
                            result = null
                            error = null
                            job = scope.launch(start = CoroutineStart.LAZY) {
                                val mine = currentCoroutineContext()[Job]
                                try {
                                    beforeRun()
                                    val completed = manager.run(task)
                                    currentCoroutineContext().ensureActive()
                                    if (!stopping && job === mine) result = completed
                                } catch (cancelled: CancellationException) {
                                    if (job === mine) error = "Task stopped"
                                    throw cancelled
                                } catch (failure: Exception) {
                                    if (job === mine) error = failure.message ?: "Task failed"
                                } finally {
                                    if (job === mine && !stopping) {
                                        running = null; job = null
                                    }
                                }
                            }
                            job?.start()
                        },
                    )
                    HorizontalDivider()
                }
            }
            result?.let { r ->
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${r.task.label}  •  exit ${r.exitCode}  •  ${r.durationMs} ms", style = MaterialTheme.typography.labelLarge)
                    Surface(Modifier.fillMaxWidth().heightIn(max = 240.dp), tonalElevation = 1.dp) {
                        Text(r.output.ifBlank { "(no output)" }, Modifier.verticalScroll(rememberScrollState()).padding(8.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        }
    }
}
