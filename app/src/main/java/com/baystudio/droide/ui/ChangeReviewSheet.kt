package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.GitChange
import com.baystudio.droide.core.GitChangeKind
import com.baystudio.droide.core.GitManager
import com.baystudio.droide.core.runSuspendCatching
import kotlinx.coroutines.launch

@Composable
fun ChangeReviewSheet(git: GitManager, onOpenSourceControl: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var repositoryReady by remember(git) { mutableStateOf<Boolean?>(null) }
    var changes by remember(git) { mutableStateOf<List<GitChange>>(emptyList()) }
    var diff by remember(git) { mutableStateOf("") }
    var busy by remember(git) { mutableStateOf(false) }
    var error by remember(git) { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        busy = true; error = null
        repositoryReady = runSuspendCatching { git.isRepository() }.getOrDefault(false)
        if (repositoryReady == true) {
            changes = runSuspendCatching { git.changes() }.getOrDefault(emptyList())
            val tracked = changes.filter { it.kind != GitChangeKind.UNTRACKED }.take(80)
            diff = buildString {
                for (change in tracked) {
                    val part = runSuspendCatching { git.diffPath(change.path, change.staged) }.getOrElse { "git error: ${it.message}" }
                    if (part.isNotBlank() && !part.startsWith("(no ")) appendLine(part)
                    if (length >= 80_000) { appendLine("[review truncated]"); break }
                }
            }.take(82_000)
        } else { changes = emptyList(); diff = "" }
        busy = false
    }
    LaunchedEffect(git) { refresh() }

    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
        DroidePanelHeader(title = "Review Changes", onClose = onDismiss, actions = {
            IconButton(onClick = { scope.launch { refresh() } }, enabled = !busy) { Icon(Icons.Default.Refresh, "Refresh review") }
        })
        HorizontalDivider()
        when {
            repositoryReady == null || busy && repositoryReady == null -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            repositoryReady == false -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This project is not a Git repository yet.")
                Button(onClick = onOpenSourceControl) { Text("Open Source Control") }
            }
            else -> {
                LazyColumn(Modifier.weight(.34f).fillMaxWidth()) {
                    item { Text("${changes.size} changed file(s)", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
                    items(changes, key = { "${it.staged}:${it.kind}:${it.path}" }) { change ->
                        ListItem(
                            headlineContent = { FileIdentityLabel(change.path, change.path, iconSize = 14.dp) },
                            supportingContent = { Text("${if (change.staged) "Staged" else "Working tree"} · ${change.kind}", color = DroideColors.Muted) },
                        )
                    }
                }
                HorizontalDivider()
                Text("Unified diff", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                Box(Modifier.weight(.66f).fillMaxWidth()) { DiffText(diff.ifBlank { if (changes.isEmpty()) "(working tree clean)" else "(no tracked diff; untracked files can be opened from Source Control)" }) }
                TextButton(onClick = onOpenSourceControl, Modifier.padding(horizontal = 8.dp)) { Text("Open full Source Control") }
            }
        }
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
    }
}
