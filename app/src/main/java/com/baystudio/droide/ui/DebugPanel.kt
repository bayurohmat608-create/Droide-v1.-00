package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.launch

@Composable
fun DebugPanel(
    debugger: DebugManager,
    activeFile: String,
    onEnsureSaved: suspend () -> Boolean,
    onOpenLocation: (String, Int, Int) -> Unit,
    onOpenExtensions: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by debugger.state.collectAsState()
    val status by debugger.status.collectAsState()
    val output by debugger.output.collectAsState()
    val breakpoints by debugger.breakpoints.collectAsState()
    val threads by debugger.threads.collectAsState()
    val frames by debugger.frames.collectAsState()
    val variables by debugger.variables.collectAsState()
    val watches by debugger.watches.collectAsState()
    val selectedThread by debugger.selectedThread.collectAsState()
    val selectedFrame by debugger.selectedFrame.collectAsState()

    var refresh by remember { mutableIntStateOf(0) }
    var configs by remember(activeFile) { mutableStateOf<List<DebugConfiguration>>(emptyList()) }
    var loadingConfigs by remember(activeFile) { mutableStateOf(false) }
    LaunchedEffect(activeFile, refresh, state) {
        if (activeFile.isBlank()) {
            configs = emptyList()
        } else {
            loadingConfigs = true
            configs = runSuspendCatching { debugger.configurations(activeFile) }.getOrDefault(emptyList())
            loadingConfigs = false
        }
    }
    var selectedName by remember(activeFile) { mutableStateOf<String?>(null) }
    val selectedConfig = configs.firstOrNull { it.name == selectedName } ?: configs.firstOrNull()
    LaunchedEffect(selectedConfig?.name) { selectedName = selectedConfig?.name }

    var configMenu by remember { mutableStateOf(false) }
    var addBreakpoint by remember { mutableStateOf(false) }
    var breakpointLine by remember { mutableStateOf("1") }
    var addWatch by remember { mutableStateOf(false) }
    var watchExpression by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    var replResult by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf<String?>(null) }
    var showAndroidAttach by remember { mutableStateOf(false) }
    var loadingAndroidProcesses by remember { mutableStateOf(false) }
    var androidProcesses by remember { mutableStateOf<List<AndroidDebugProcess>>(emptyList()) }

    fun runAction(block: suspend () -> Unit) {
        scope.launch {
            actionError = null
            runSuspendCatching { block() }.onFailure { actionError = it.message ?: "Debug action failed" }
        }
    }

    if (showAndroidAttach) {
        AlertDialog(
            onDismissRequest = { showAndroidAttach = false },
            title = { Text("Attach Android process") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Shows JDWP processes. Attach requires a compatible DAP adapter.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    when {
                        loadingAndroidProcesses -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        androidProcesses.isEmpty() -> Text("No debuggable JDWP process found.", style = MaterialTheme.typography.bodyMedium)
                        else -> androidProcesses.take(32).forEach { process ->
                            ListItem(
                                headlineContent = { Text(process.processName ?: "PID ${process.pid}", maxLines = 1) },
                                supportingContent = { Text("PID ${process.pid}", fontFamily = FontFamily.Monospace) },
                                trailingContent = {
                                    TextButton(onClick = {
                                        runAction {
                                            check(activeFile.isNotBlank()) { "Open a Kotlin/Java source file before attaching" }
                                            check(onEnsureSaved()) { "Save failed; debugger was not started" }
                                            val attachConfigs = debugger.androidAttachConfigurations(activeFile, process.pid)
                                            val config = attachConfigs.firstOrNull()
                                                ?: error("No Android JDWP adapter. Install one or add an androidJdwp launch configuration.")
                                            showAndroidAttach = false
                                            debugger.start(config)
                                        }
                                    }) { Text("Attach") }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            loadingAndroidProcesses = true
                            androidProcesses = runSuspendCatching { debugger.androidJdwpProcesses() }.getOrElse {
                                actionError = it.message ?: "Could not query JDWP processes"
                                emptyList()
                            }
                            loadingAndroidProcesses = false
                        }
                    },
                ) { Text("Refresh") }
            },
            dismissButton = { TextButton(onClick = { showAndroidAttach = false }) { Text("Close") } },
        )
    }

    if (addBreakpoint) {
        AlertDialog(
            onDismissRequest = { addBreakpoint = false },
            title = { Text("Add breakpoint") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeFile.isNotBlank()) FileIdentityLabel(activeFile, activeFile, iconSize = 16.dp)
                    else Text("No active file", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = breakpointLine,
                        onValueChange = { breakpointLine = it.filter(Char::isDigit).take(7) },
                        label = { Text("Line") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = activeFile.isNotBlank() && (breakpointLine.toIntOrNull() ?: 0) > 0,
                    onClick = {
                        debugger.addBreakpoint(activeFile, breakpointLine.toInt())
                        addBreakpoint = false
                    },
                ) { Text("Add") }
            },
            dismissButton = { OutlinedButton(onClick = { addBreakpoint = false }) { Text("Cancel") } },
        )
    }

    if (addWatch) {
        AlertDialog(
            onDismissRequest = { addWatch = false },
            title = { Text("Add watch expression") },
            text = {
                OutlinedTextField(
                    value = watchExpression,
                    onValueChange = { watchExpression = it.take(20_000) },
                    label = { Text("Expression") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = watchExpression.isNotBlank(),
                    onClick = {
                        debugger.addWatch(watchExpression)
                        watchExpression = ""
                        addWatch = false
                    },
                ) { Text("Add") }
            },
            dismissButton = { OutlinedButton(onClick = { addWatch = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { configMenu = true },
                            enabled = state == DebugState.IDLE || state == DebugState.TERMINATED || state == DebugState.ERROR,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Settings, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (loadingConfigs) "Detecting debuggers…" else selectedConfig?.name ?: "No debugger available", maxLines = 1)
                        }
                        DropdownMenu(expanded = configMenu, onDismissRequest = { configMenu = false }) {
                            configs.forEach { cfg ->
                                DropdownMenuItem(
                                    text = { Text(cfg.name) },
                                    onClick = { selectedName = cfg.name; configMenu = false },
                                )
                            }
                            if (configs.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Open Extensions") },
                                    onClick = { configMenu = false; onOpenExtensions() },
                                    leadingIcon = { Icon(Icons.Default.Extension, null) },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, "Refresh debugger list") }
                    OutlinedButton(onClick = {
                        showAndroidAttach = true
                        scope.launch {
                            loadingAndroidProcesses = true
                            androidProcesses = runSuspendCatching { debugger.androidJdwpProcesses() }.getOrElse {
                                actionError = it.message ?: "Could not query JDWP processes"
                                emptyList()
                            }
                            loadingAndroidProcesses = false
                        }
                    }) {
                        Icon(Icons.Default.BugReport, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Attach")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    when (state) {
                        DebugState.IDLE, DebugState.TERMINATED, DebugState.ERROR -> {
                            FilledTonalButton(
                                enabled = selectedConfig != null,
                                onClick = {
                                    val cfg = selectedConfig ?: return@FilledTonalButton
                                    runAction {
                                        check(onEnsureSaved()) { "Save failed; debugging was not started" }
                                        debugger.start(cfg)
                                    }
                                },
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Start")
                            }
                        }
                        DebugState.STARTING -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        DebugState.RUNNING -> {
                            IconButton(onClick = { runAction { debugger.pause() } }) { Icon(Icons.Default.Pause, "Pause") }
                            IconButton(onClick = { runAction { debugger.stop() } }) { Icon(Icons.Default.Stop, "Stop") }
                        }
                        DebugState.STOPPED -> {
                            IconButton(onClick = { runAction { debugger.continueExecution() } }) { Icon(Icons.Default.PlayArrow, "Continue") }
                            IconButton(onClick = { runAction { debugger.next() } }) { Icon(Icons.Default.SkipNext, "Step over") }
                            IconButton(onClick = { runAction { debugger.stepIn() } }) { Icon(Icons.Default.SubdirectoryArrowRight, "Step into") }
                            IconButton(onClick = { runAction { debugger.stepOut() } }) { Icon(Icons.Default.KeyboardReturn, "Step out") }
                            IconButton(onClick = { runAction { debugger.stop() } }) { Icon(Icons.Default.Stop, "Stop") }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(state.name.lowercase().replaceFirstChar(Char::uppercase), Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (configs.isEmpty() && !loadingConfigs) Text("Install a compatible debugger extension, or define a custom configuration in .droide/launch.json.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                DebugSection("Breakpoints") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = activeFile.isNotBlank(), onClick = { addBreakpoint = true }) {
                            Icon(Icons.Default.AddCircle, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Add")
                        }
                    }
                    val allBreakpoints = breakpoints.values.flatten().sortedWith(compareBy<DebugBreakpoint> { it.path }.thenBy { it.line })
                    if (allBreakpoints.isEmpty()) {
                        DebugEmpty("No breakpoints")
                    } else {
                        allBreakpoints.forEach { bp ->
                            ListItem(
                                headlineContent = { FileIdentityLabel(bp.path, "${bp.path}:${bp.line}", iconSize = 15.dp, monospaced = true) },
                                supportingContent = {
                                    Text(if (bp.verified) "Verified" else bp.message ?: "Pending verification")
                                },
                                leadingContent = {
                                    Icon(
                                        if (bp.verified) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (bp.verified) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { debugger.clearBreakpoint(bp.path, bp.line) }) { Icon(Icons.Default.Close, "Remove breakpoint") }
                                },
                                modifier = Modifier.clickable { onOpenLocation(bp.path, bp.line, 1) },
                            )
                        }
                    }
                }
            }

            item {
                DebugSection("Threads") {
                    if (threads.isEmpty()) DebugEmpty("No threads") else threads.forEach { thread ->
                        ListItem(
                            headlineContent = { Text(thread.name) },
                            supportingContent = { Text("Thread ${thread.id}") },
                            leadingContent = { if (thread.id == selectedThread) Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable { runAction { debugger.selectThread(thread.id) } },
                        )
                    }
                }
            }

            item {
                DebugSection("Call Stack") {
                    if (frames.isEmpty()) DebugEmpty("No stack frames") else frames.forEach { frame ->
                        ListItem(
                            headlineContent = { Text(frame.name, maxLines = 1) },
                            supportingContent = {
                                val framePath = frame.path
                                if (framePath != null) FileIdentityLabel(framePath, "$framePath:${frame.line}:${frame.column}", iconSize = 14.dp, monospaced = true)
                                else Text("external:${frame.line}:${frame.column}", fontFamily = FontFamily.Monospace)
                            },
                            leadingContent = { if (frame.id == selectedFrame) Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable {
                                runAction { debugger.selectFrame(frame.id) }
                                frame.path?.let { onOpenLocation(it, frame.line, frame.column) }
                            },
                        )
                    }
                }
            }

            item {
                DebugSection("Watch") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedButton(onClick = { addWatch = true }) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Add expression")
                        }
                        if (watches.isNotEmpty()) {
                            TextButton(onClick = { debugger.clearWatches() }) { Text("Clear") }
                        }
                        if (state == DebugState.STOPPED && watches.isNotEmpty()) {
                            IconButton(onClick = { runAction { debugger.refreshWatches() } }) { Icon(Icons.Default.Refresh, "Refresh watches") }
                        }
                    }
                    if (watches.isEmpty()) {
                        DebugEmpty("No watch expressions")
                    } else {
                        watches.forEach { watch ->
                            ListItem(
                                headlineContent = { Text(watch.expression, fontFamily = FontFamily.Monospace, maxLines = 2) },
                                supportingContent = {
                                    val text = when {
                                        watch.error != null -> watch.error
                                        state != DebugState.STOPPED -> "Available when paused"
                                        else -> watch.value
                                    }
                                    Text(
                                        text.ifBlank { "(no result)" },
                                        fontFamily = FontFamily.Monospace,
                                        color = if (watch.error != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                                        maxLines = 4,
                                    )
                                },
                                trailingContent = {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        watch.type?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                        IconButton(onClick = { debugger.removeWatch(watch.expression) }) { Icon(Icons.Default.Close, "Remove watch") }
                                    }
                                },
                                modifier = if (watch.variablesReference > 0 && state == DebugState.STOPPED) Modifier.clickable {
                                    runAction {
                                        val children = debugger.variables(watch.variablesReference)
                                        replResult = children.joinToString("\n") { "${it.name} = ${it.value}" }.take(20_000)
                                    }
                                } else Modifier,
                            )
                        }
                    }
                }
            }

            item {
                DebugSection("Variables") {
                    if (variables.isEmpty()) DebugEmpty("No variables") else variables.take(500).forEach { variable ->
                        ListItem(
                            headlineContent = { Text(variable.name, fontFamily = FontFamily.Monospace, maxLines = 1) },
                            supportingContent = { Text(variable.value.take(2_000), fontFamily = FontFamily.Monospace, maxLines = 4) },
                            trailingContent = { variable.type?.let { Text(it, style = MaterialTheme.typography.labelSmall) } },
                            modifier = if (variable.variablesReference > 0) Modifier.clickable {
                                runAction {
                                    val children = debugger.variables(variable.variablesReference)
                                    replResult = children.joinToString("\n") { "${it.name} = ${it.value}" }.take(20_000)
                                }
                            } else Modifier,
                        )
                    }
                }
            }

            item {
                DebugSection("Debug Console") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = repl,
                            onValueChange = { repl = it.take(20_000) },
                            modifier = Modifier.weight(1f),
                            label = { Text("Evaluate") },
                            singleLine = true,
                            enabled = state == DebugState.STOPPED,
                        )
                        FilledTonalButton(
                            enabled = state == DebugState.STOPPED && repl.isNotBlank(),
                            onClick = { runAction { replResult = debugger.evaluate(repl) } },
                        ) { Text("Run") }
                    }
                    if (replResult.isNotBlank()) Text(
                        replResult,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    if (output.isNotBlank()) {
                        Text("Adapter / program output", style = MaterialTheme.typography.labelMedium)
                        Surface(Modifier.fillMaxWidth().heightIn(max = 220.dp), tonalElevation = 1.dp) {
                            Text(output.takeLast(20_000), Modifier.padding(8.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(title, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.titleSmall)
        HorizontalDivider()
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun DebugEmpty(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
