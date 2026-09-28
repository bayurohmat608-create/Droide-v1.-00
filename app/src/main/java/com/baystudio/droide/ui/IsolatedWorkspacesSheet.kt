package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.WorktreeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun IsolatedWorkspacesSheet(manager: WorktreeManager, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var workspaces by remember(manager) { mutableStateOf(manager.list()) }
    var name by remember(manager) { mutableStateOf("") }
    var branch by remember(manager) { mutableStateOf("") }
    var busy by remember(manager) { mutableStateOf(false) }
    var status by remember(manager) { mutableStateOf<String?>(null) }
    var reviewing by remember(manager) { mutableStateOf<String?>(null) }
    var reviewText by remember(manager) { mutableStateOf("") }
    var pendingRemove by remember(manager) { mutableStateOf<String?>(null) }

    fun create() {
        val workspaceName = name.trim()
        if (workspaceName.isBlank() || busy) return
        scope.launch {
            busy = true
            try {
                status = manager.create(workspaceName, branch.trim().ifBlank { workspaceName })
                workspaces = manager.list()
                if (status?.startsWith("Isolated workspace ") == true) { name = ""; branch = "" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = "Workspace create failed: ${failure.message}" }
            finally { busy = false }
        }
    }

    fun review(workspace: String) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                reviewText = manager.reviewChanges(workspace)
                reviewing = workspace
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = "Review failed: ${failure.message}" }
            finally { busy = false }
        }
    }

    fun remove(workspace: String) {
        if (busy) return
        scope.launch {
            busy = true
            pendingRemove = null
            try {
                status = manager.remove(workspace, force = false)
                workspaces = manager.list()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = "Remove refused: ${failure.message}" }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight(.86f)) {
        DroidePanelHeader(title = "Isolated Workspaces", onClose = onDismiss)
        Text("Create an isolated local Git workspace for parallel or branch work.", Modifier.padding(horizontal = 16.dp), color = DroideColors.Muted)
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, Modifier.weight(1f), label = { Text("Workspace name") }, singleLine = true)
            OutlinedTextField(branch, { branch = it.take(200) }, Modifier.weight(1f), label = { Text("Branch (optional)") }, singleLine = true)
        }
        Button(onClick = ::create, enabled = name.isNotBlank() && !busy, modifier = Modifier.padding(horizontal = 16.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(if (busy) "Creating…" else "Create isolated workspace")
        }
        status?.let { Text(it, Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        Text("Existing isolated workspaces (${workspaces.size})", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f)) {
            if (workspaces.isEmpty()) item { Text("None yet.", Modifier.padding(horizontal = 16.dp), color = DroideColors.Muted) }
            items(workspaces, key = { it }) { workspace ->
                ListItem(
                    leadingContent = { Icon(Icons.Default.AccountTree, null) },
                    headlineContent = { Text(workspace) },
                    supportingContent = { Text(".droide/worktrees/$workspace", fontFamily = FontFamily.Monospace) },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { review(workspace) }, enabled = !busy) { Text("Review") }
                            IconButton(onClick = { pendingRemove = workspace }, enabled = !busy) {
                                Icon(Icons.Default.DeleteOutline, "Remove $workspace")
                            }
                        }
                    },
                )
            }
        }
    }

    reviewing?.let { workspace ->
        AlertDialog(
            onDismissRequest = { reviewing = null },
            title = { Text("Review · $workspace") },
            text = { Text(reviewText, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { reviewing = null }) { Text("Close") } },
        )
    }
    pendingRemove?.let { workspace ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove isolated workspace?") },
            text = { Text("Remove $workspace only if it has no uncommitted changes and all its commits are already in the parent branch. Otherwise removal is refused.") },
            confirmButton = { TextButton(onClick = { remove(workspace) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Cancel") } },
        )
    }
}
