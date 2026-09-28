package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.TokenTracker

@Composable
fun BillingSheet(tracker: TokenTracker, onDismiss: () -> Unit) {
    val usage by tracker.usage.collectAsState()
    val history by tracker.history.collectAsState()
    val warning by tracker.warning.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        DroidePanelHeader(title = "Token Usage", onClose = onDismiss)
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        warning?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Total tokens: ${usage.total} (prompt ${usage.prompt} + completion ${usage.completion})", style = MaterialTheme.typography.bodyMedium)
                Text("Prices can change. Check your provider dashboard for billing.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("History per request", style = MaterialTheme.typography.labelLarge)
        LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(history.takeLast(20).reversed()) { (title, u) ->
                ListItem(
                    headlineContent = { Text(title.take(40), style = MaterialTheme.typography.bodySmall) },
                    supportingContent = { Text("prompt ${u.prompt} + completion ${u.completion} = ${u.total}", style = MaterialTheme.typography.labelSmall) }
                )
                HorizontalDivider()
            }
            if (history.isEmpty()) item { Text("No usage yet. Chat with Agent first.", style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedButton(onClick = { tracker.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Reset usage history") }
        }
    }
}
