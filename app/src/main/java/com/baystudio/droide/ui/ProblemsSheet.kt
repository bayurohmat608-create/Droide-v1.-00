package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.BuildDiagnostic
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.LspCodeAction
import com.baystudio.droide.core.LspDiagnostic
import com.baystudio.droide.core.LspManager
import com.baystudio.droide.core.LspWorkspaceEdit
import com.baystudio.droide.core.runSuspendCatching
import com.baystudio.droide.core.PathSecurity
import java.io.File
import java.net.URI
import kotlinx.coroutines.launch

private data class ProblemRow(
    val path: String,
    val line: Int,
    val column: Int,
    val severity: Int?,
    val message: String,
    val source: String?,
    val lspDiagnostic: LspDiagnostic? = null,
)

@Composable
fun ProblemsSheet(
    files: FileRepository,
    lsp: LspManager,
    buildDiagnostics: List<BuildDiagnostic> = emptyList(),
    onOpen: (String, Int, Int) -> Unit,
    onApplyWorkspaceEdit: (LspWorkspaceEdit) -> Unit,
    onClose: (() -> Unit)? = null,
) {
    val diagnostics by lsp.diagnostics.collectAsState()
    val scope = rememberCoroutineScope()
    var actionTarget by remember { mutableStateOf<ProblemRow?>(null) }
    var actions by remember { mutableStateOf<List<LspCodeAction>>(emptyList()) }
    var loadingActions by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val rows = remember(diagnostics, buildDiagnostics, files.root.absolutePath) {
        val lspRows = diagnostics.entries.flatMap { (uri, list) ->
            val path = runCatching {
                val f = File(URI(uri)).canonicalFile
                if (!PathSecurity.contains(files.root, f)) return@runCatching null
                f.relativeTo(files.root.canonicalFile).invariantSeparatorsPath
            }.getOrNull() ?: return@flatMap emptyList()
            list.map { d -> ProblemRow(path, d.line, d.column, d.severity, d.message, d.source, d) }
        }
        val buildRows = buildDiagnostics.map { d ->
            ProblemRow(d.path, d.line, d.column, d.severity, d.message, d.source, null)
        }
        (lspRows + buildRows).distinctBy { "${it.path}:${it.line}:${it.column}:${it.message}:${it.source}" }
            .sortedWith(compareBy<ProblemRow>({ it.severity ?: 99 }, { it.path }, { it.line }))
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
        if (onClose != null) {
            DroidePanelHeader(title = "Problems", subtitle = "${rows.size} issue${if (rows.size == 1) "" else "s"}", onClose = onClose)
        } else {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Problems", style = MaterialTheme.typography.titleLarge)
                Text("${rows.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No problems", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows.take(2_000)) { row ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                when (row.severity) {
                                    1 -> Icons.Default.ErrorOutline
                                    2 -> Icons.Default.WarningAmber
                                    else -> Icons.Default.Info
                                },
                                contentDescription = null,
                                tint = when (row.severity) {
                                    1 -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        headlineContent = { Text(row.message, maxLines = 3) },
                        supportingContent = {
                            FileIdentityLabel(
                                path = row.path,
                                label = "${row.path}:${row.line}:${row.column}${row.source?.let { "  [$it]" } ?: ""}",
                                iconSize = 14.dp,
                                monospaced = true,
                            )
                        },
                        trailingContent = row.lspDiagnostic?.let { diagnostic ->
                            {
                                IconButton(onClick = {
                                    actionTarget = row
                                    actions = emptyList()
                                    actionError = null
                                    loadingActions = true
                                    scope.launch {
                                        val result = runSuspendCatching { lsp.codeActions(row.path, diagnostic) }
                                        actions = result.getOrElse {
                                            actionError = it.message ?: it::class.java.simpleName
                                            emptyList()
                                        }
                                        loadingActions = false
                                    }
                                }) { Icon(Icons.Default.Build, contentDescription = "Quick Fix") }
                            }
                        },
                        modifier = Modifier.clickable { onOpen(row.path, row.line, row.column) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    val target = actionTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text("Quick Fix") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target.message, maxLines = 3)
                    if (loadingActions) LinearProgressIndicator(Modifier.fillMaxWidth())
                    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!loadingActions && actionError == null && actions.isEmpty()) {
                        Text("No code actions available for this diagnostic.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(actions, key = { "${it.title}:${it.kind}:${it.preferred}" }) { action ->
                            val applicable = action.disabledReason == null && (action.edit != null || !action.rawJson.isNullOrBlank())
                            ListItem(
                                headlineContent = { Text(action.title) },
                                supportingContent = {
                                    Text(
                                        action.disabledReason ?: when {
                                            action.edit != null -> listOfNotNull(action.kind, if (action.preferred) "preferred" else null).joinToString(" • ").ifBlank { "Workspace edit" }
                                            !action.rawJson.isNullOrBlank() -> "Resolve on selection"
                                            action.commandTitle != null -> "Server command requires explicit command support and was not executed"
                                            else -> "No transparent workspace edit returned"
                                        },
                                    )
                                },
                                modifier = if (applicable) Modifier.clickable {
                                    scope.launch {
                                        loadingActions = true
                                        val resolved = runSuspendCatching { lsp.resolveCodeAction(target.path, action) }.getOrNull() ?: action
                                        loadingActions = false
                                        val edit = resolved.edit
                                        if (edit != null) {
                                            onApplyWorkspaceEdit(edit)
                                            actionTarget = null
                                        } else {
                                            actionError = resolved.disabledReason
                                                ?: if (resolved.commandTitle != null) "This action requires a server command; no opaque command was executed."
                                                else "Language server returned no applicable workspace edit."
                                        }
                                    }
                                } else Modifier,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actionTarget = null }) { Text("Close") } },
        )
    }
}
