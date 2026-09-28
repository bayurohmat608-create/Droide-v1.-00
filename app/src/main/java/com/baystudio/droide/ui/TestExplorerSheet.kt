package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.IdeTask
import com.baystudio.droide.core.TaskManager
import com.baystudio.droide.core.TaskResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
fun TestExplorerSheet(manager: TaskManager, onDismiss: () -> Unit, beforeRun: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var tests by remember(manager) { mutableStateOf<List<IdeTask>>(emptyList()) }
    var loading by remember(manager) { mutableStateOf(true) }
    var running by remember(manager) { mutableStateOf<String?>(null) }
    var job by remember(manager) { mutableStateOf<Job?>(null) }
    var results by remember(manager) { mutableStateOf<Map<String, TaskResult>>(emptyMap()) }
    var error by remember(manager) { mutableStateOf<String?>(null) }
    var stopping by remember(manager) { mutableStateOf(false) }

    suspend fun refresh() {
        loading = true
        try {
            tests = manager.list().filter {
                it.group.equals("test", ignoreCase = true) || it.label.contains("test", ignoreCase = true) || it.label.contains("pytest", ignoreCase = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            tests = emptyList()
            error = "Could not load test tasks: ${failure.message ?: failure::class.java.simpleName}"
        } finally { loading = false }
    }
    fun runOne(test: IdeTask) {
        if (job != null) return
        running = test.label; error = null
        results = results - test.label
        job = scope.launch(start = CoroutineStart.LAZY) {
            val mine = currentCoroutineContext()[Job]
            try {
                beforeRun()
                val completed = manager.run(test)
                currentCoroutineContext().ensureActive()
                if (!stopping && job === mine) results = results + (test.label to completed)
            } catch (cancelled: CancellationException) {
                if (job === mine) error = "Test stopped"
                throw cancelled
            } catch (failure: Exception) {
                if (job === mine) error = failure.message ?: "Test task failed"
            } finally {
                if (job === mine && !stopping) {
                    running = null; job = null
                }
            }
        }
        job?.start()
    }
    fun runAll() {
        if (job != null || tests.isEmpty()) return
        running = tests.first().label; error = null
        results = emptyMap()
        val selectedTests = tests.toList()
        job = scope.launch(start = CoroutineStart.LAZY) {
            val mine = currentCoroutineContext()[Job]
            try {
                beforeRun()
                for (test in selectedTests) {
                    running = test.label
                    val completed = manager.run(test)
                    currentCoroutineContext().ensureActive()
                    if (!stopping && job === mine) results = results + (test.label to completed)
                }
            } catch (cancelled: CancellationException) {
                if (job === mine) error = "Tests stopped"
                throw cancelled
            } catch (failure: Exception) {
                if (job === mine) error = failure.message ?: "Test task failed"
            } finally {
                if (job === mine && !stopping) {
                    running = null; job = null
                }
            }
        }
        job?.start()
    }

    LaunchedEffect(manager) { refresh() }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
        DroidePanelHeader(title = "Test Tasks", onClose = onDismiss, actions = {
            if (running != null) TextButton(onClick = {
                val stopped = job ?: return@TextButton
                if (stopping) return@TextButton
                stopping = true; error = "Stopping tests…"
                stopped.cancel()
                scope.launch {
                    stopped.join()
                    if (job === stopped) {
                        running = null; job = null; error = "Tests stopped"; stopping = false
                    }
                }
            }, enabled = !stopping) { Text(if (stopping) "Stopping…" else "Stop") }
            else TextButton(onClick = ::runAll, enabled = tests.isNotEmpty()) { Text("Run all") }
        })
        HorizontalDivider()
        Text(
            "Runs project test tasks. Pass/fail reflects each task's process exit code; individual test cases and assertion locations are not discovered here.",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = DroideColors.Muted,
        )
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        else LazyColumn(Modifier.weight(1f)) {
            if (tests.isEmpty() && error?.startsWith("Could not load test tasks:") != true) item { Text("No test tasks found. Supports Gradle, npm, Cargo, Go, Pytest and project tasks.", Modifier.padding(16.dp), color = DroideColors.Muted) }
            items(tests, key = { it.label }) { test ->
                val result = results[test.label]
                ListItem(
                    headlineContent = { Text(test.label) },
                    supportingContent = {
                        Column {
                            Text(test.argv.joinToString(" ").take(300), fontFamily = FontFamily.Monospace, maxLines = 2)
                            val status = when {
                                running == test.label -> if (stopping) "Stopping" else "Running"
                                result == null -> "Not run"
                                result.exitCode == 0 && !result.timedOut -> "Passed · ${result.durationMs} ms"
                                result.timedOut -> "Timed out"
                                else -> "Failed · exit ${result.exitCode} · ${result.durationMs} ms"
                            }
                            Text(status, color = if (result?.let { it.exitCode == 0 && !it.timedOut } == true) MaterialTheme.colorScheme.primary else DroideColors.Muted)
                        }
                    },
                    trailingContent = { IconButton(onClick = { runOne(test) }, enabled = job == null) { Icon(Icons.Default.PlayArrow, "Run ${test.label}") } },
                )
                result?.output?.takeIf { it.isNotBlank() }?.let { output ->
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), tonalElevation = 1.dp) {
                        Text(output.takeLast(8_000), Modifier.padding(8.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
        }
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
    }
}
