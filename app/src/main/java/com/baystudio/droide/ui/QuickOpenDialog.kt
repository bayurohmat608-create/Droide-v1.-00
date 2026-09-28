package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.FileQueryPage
import com.baystudio.droide.core.runSuspendCatching
import kotlinx.coroutines.delay

 
@Composable
fun QuickOpenDialog(
    files: FileRepository,
    onOpen: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable(files.root.absolutePath) { mutableStateOf("") }
    var results by remember(files.root.absolutePath) { mutableStateOf<FileQueryPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(files, query) {
        loading = true
        error = null
        results = null
        delay(70)
        results = runSuspendCatching { files.findFilesPage(query, maxResults = 120) }
            .onFailure { error = it.message ?: "Quick Open failed" }
            .getOrNull()
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Quick Open", Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close quick open") }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        val next = it.take(256)
                        if (next != query) { loading = true; results = null; error = null }
                        query = next
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("File name or path") },
                    singleLine = true,
                )
                when {
                    loading && results == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(results?.paths.orEmpty(), key = { it }) { path ->
                        ListItem(
                            headlineContent = { Text(path.substringAfterLast('/'), maxLines = 1) },
                            supportingContent = { Text(path, fontFamily = FontFamily.Monospace, maxLines = 1) },
                            leadingContent = { FileTypeBrandIcon(path, size = 18.dp) },
                            modifier = Modifier.clickable { if (onOpen(path)) onDismiss() },
                        )
                    }
                    results?.let { page ->
                        if (page.scanLimitReached) item { Text("Scan stopped after ${page.scannedEntries} entries; results may be incomplete.", Modifier.padding(12.dp)) }
                        else if (page.resultLimitReached) item { Text("Showing first ${page.paths.size} files; refine the name to search further.", Modifier.padding(12.dp)) }
                        if (page.paths.isEmpty()) item {
                            Text("No matching files in the first 20 folder levels", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}
