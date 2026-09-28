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
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.DroideSession
import com.baystudio.droide.core.SessionManager
import com.baystudio.droide.core.SessionSummary
import kotlinx.coroutines.launch

// Local session browser: reopen, fork, and delete durable agent sessions.


@Composable
fun SessionDrawer(
    manager: SessionManager,
    onPick: (DroideSession) -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    var sessions by remember(manager) { mutableStateOf<List<SessionSummary>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(manager) { sessions = manager.listSummaries() }

    fun refresh() { scope.launch { sessions = manager.listSummaries() } }

    Column(Modifier.fillMaxWidth()) {
        DroidePanelHeader(title = "Agent Sessions", onClose = onDismiss, actions = {
            IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Refresh sessions") }
        })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sessions) { s ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ListItem(
                            headlineContent = { Text(s.title, style = MaterialTheme.typography.titleSmall) },
                            supportingContent = {
                                Column {
                                    Text("${s.id} · ${s.messageCount} messages · ${s.mode}", style = MaterialTheme.typography.labelSmall)
                                    if (s.parentId != null) Text("fork of ${s.parentId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            modifier = Modifier.clickable { scope.launch { manager.get(s.id)?.let(onPick) } }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            IconButton(onClick = { scope.launch { manager.fork(s.id); refresh() } }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.CallSplit, "Fork", modifier = Modifier.size(18.dp)) }
                            IconButton(onClick = { scope.launch { manager.delete(s.id); refresh() } }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
            if (sessions.isEmpty()) item { Text("(no saved sessions)", style = MaterialTheme.typography.bodySmall) }
        }
        }
    }
}
