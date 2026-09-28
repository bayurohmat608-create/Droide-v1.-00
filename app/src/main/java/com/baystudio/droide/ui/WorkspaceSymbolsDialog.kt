package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.LspManager
import com.baystudio.droide.core.LspSymbol
import com.baystudio.droide.core.runSuspendCatching
import kotlinx.coroutines.delay

 
@Composable
fun WorkspaceSymbolsDialog(
    lsp: LspManager,
    pathHint: String,
    onOpen: (String, Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(pathHint) { mutableStateOf("") }
    var results by remember(pathHint) { mutableStateOf<List<LspSymbol>>(emptyList()) }
    var loading by remember(pathHint) { mutableStateOf(false) }
    var error by remember(pathHint) { mutableStateOf<String?>(null) }

    LaunchedEffect(pathHint, query) {
        val q = query.trim()
        if (pathHint.isBlank() || q.isBlank()) {
            results = emptyList()
            error = null
            loading = false
            return@LaunchedEffect
        }
        delay(140)
        loading = true
        error = null
        val response = runSuspendCatching { lsp.workspaceSymbols(pathHint, q) }
        results = response.getOrElse {
            error = it.message ?: it::class.java.simpleName
            emptyList()
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Workspace Symbols", Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close workspace symbols") }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search symbols…") },
                    singleLine = true,
                    enabled = pathHint.isNotBlank(),
                )
                when {
                    pathHint.isBlank() -> Text("Open a source file first so Droide can select the matching language server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    query.isNotBlank() && results.isEmpty() -> Text("No symbols found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results.take(200), key = { "${it.path}:${it.line}:${it.column}:${it.name}" }) { symbol ->
                        ListItem(
                            headlineContent = { Text(symbol.name, maxLines = 1) },
                            supportingContent = {
                                FileIdentityLabel(
                                    path = symbol.path,
                                    label = "${symbol.path}:${symbol.line}:${symbol.column}",
                                    iconSize = 14.dp,
                                    monospaced = true,
                                )
                            },
                            modifier = Modifier.clickable {
                                onOpen(symbol.path, symbol.line, symbol.column)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}
