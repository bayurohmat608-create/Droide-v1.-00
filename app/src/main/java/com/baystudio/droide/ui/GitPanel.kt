package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.launch





@Composable
fun GitPanel(git: GitManager, onOpen: (String) -> Unit = {}, onWorkspaceChanged: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var changes by remember(git) { mutableStateOf<List<GitChange>>(emptyList()) }
    var stashes by remember(git) { mutableStateOf<List<GitStash>>(emptyList()) }
    var history by remember(git) { mutableStateOf<List<GitCommitSummary>>(emptyList()) }
    var statusText by remember(git) { mutableStateOf("Loading source control…") }
    var repositoryReady by remember(git) { mutableStateOf<Boolean?>(null) }
    var busy by remember(git) { mutableStateOf(false) }
    var branch by remember(git) { mutableStateOf("") }
    var commitMsg by remember(git) { mutableStateOf("") }
    var token by remember(git) { mutableStateOf("") }
    var identity by remember(git) { mutableStateOf<GitIdentity?>(null) }
    var identityName by remember(git) { mutableStateOf("") }
    var identityEmail by remember(git) { mutableStateOf("") }
    var identityEditing by remember(git) { mutableStateOf(false) }
    var pendingDiscard by remember(git) { mutableStateOf<GitChange?>(null) }
    var pendingResolve by remember(git) { mutableStateOf<GitChange?>(null) }
    var conflictBlocks by remember(git) { mutableStateOf<List<MergeConflictBlock>>(emptyList()) }
    var conflictBusy by remember(git) { mutableStateOf(false) }
    var conflictError by remember(git) { mutableStateOf<String?>(null) }
    var diffTitle by remember(git) { mutableStateOf<String?>(null) }
    var diffText by remember(git) { mutableStateOf("") }
    var diffBusy by remember(git) { mutableStateOf(false) }

    suspend fun refreshState(): String {
        repositoryReady = runSuspendCatching { git.isRepository() }.getOrDefault(false)
        if (repositoryReady != true) {
            changes = emptyList(); stashes = emptyList(); history = emptyList(); branch = ""
            return "This folder is not a Git repository."
        }
        changes = runSuspendCatching { git.changes() }.getOrDefault(emptyList())
        stashes = runSuspendCatching { git.stashes() }.getOrDefault(emptyList())
        history = runSuspendCatching { git.history(50) }.getOrDefault(emptyList())
        identity = runSuspendCatching { git.identity() }.getOrNull()
        if (!identityEditing) {
            identityName = identity?.name.orEmpty()
            identityEmail = identity?.email.orEmpty()
        }
        return runSuspendCatching { git.status() }.getOrElse { "git error: ${it.message}" }
    }

    fun runOperation(block: suspend () -> String) {
        scope.launch {
            if (busy) return@launch
            busy = true
            try {
                val operationOutcome = runSuspendCatching { block() }.getOrElse { "git error: ${it.message}" }
                onWorkspaceChanged()
                val refreshOutcome = refreshState()
                statusText = GitOperationOutcomePolicy.settle(operationOutcome, refreshOutcome)
            } finally {
                busy = false
            }
        }
    }

    fun showDiff(change: GitChange) {
        scope.launch {
            if (diffBusy) return@launch
            diffBusy = true
            diffTitle = "${if (change.staged) "Staged" else "Working tree"} · ${change.path}"
            diffText = "Loading diff…"
            diffText = if (change.kind == GitChangeKind.UNTRACKED) {
                "Untracked file. Stage it first to review its index diff, or open the file directly."
            } else runSuspendCatching { git.diffPath(change.path, staged = change.staged) }
                .getOrElse { "git error: ${it.message}" }
            diffBusy = false
        }
    }

    LaunchedEffect(git) { statusText = refreshState() }

    if (repositoryReady == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (repositoryReady == false) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Source, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Start Source Control", style = MaterialTheme.typography.titleMedium)
            Text("This folder is not a Git repository. Initialize creates .git only.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { runOperation { git.init() } }, enabled = !busy) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(if (busy) "Initializing…" else "Initialize Repository")
            }
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val staged = changes.filter { it.staged }
    val working = changes.filterNot { it.staged }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = { scope.launch { statusText = refreshState() } }, enabled = !busy) { Icon(Icons.Default.Refresh, "Refresh") }
                AssistChip(onClick = { runOperation { git.fetch(token = token.ifBlank { null }) } }, label = { Text("Fetch") }, enabled = !busy)
                AssistChip(onClick = { runOperation { git.pull(token = token.ifBlank { null }) } }, label = { Text("Pull") }, enabled = !busy)
                AssistChip(onClick = { runOperation { git.push(token = token.ifBlank { null }) } }, label = { Text("Push") }, enabled = !busy)
                AssistChip(onClick = { scope.launch { statusText = git.log() } }, label = { Text("Log") }, enabled = !busy)
                AssistChip(onClick = { scope.launch { statusText = git.diff() } }, label = { Text("Diff") }, enabled = !busy)
                AssistChip(onClick = { scope.launch { statusText = git.branches() } }, label = { Text("Branches") }, enabled = !busy)
            }
        }

        item {
            OutlinedTextField(
                token,
                { token = it.take(4_096) },
                Modifier.fillMaxWidth(),
                label = { Text("PAT override (optional)") },
                supportingText = { Text("GitHub account is used for HTTPS remotes. A PAT here is temporary.") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Commit identity", style = MaterialTheme.typography.titleSmall)
                            Text(
                                identity?.let { "${it.name} <${it.email}>" } ?: "Not configured for this repository",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (identity == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (identity != null) {
                            TextButton(onClick = {
                                identityEditing = !identityEditing
                                identityName = identity?.name.orEmpty()
                                identityEmail = identity?.email.orEmpty()
                            }, enabled = !busy) { Text(if (identityEditing) "Cancel" else "Edit") }
                        }
                    }
                    if (identityEditing || identity == null) {
                        OutlinedTextField(
                            identityName,
                            { identityName = it.take(200) },
                            Modifier.fillMaxWidth(),
                            label = { Text("Git user.name") },
                            singleLine = true,
                            enabled = !busy,
                        )
                        OutlinedTextField(
                            identityEmail,
                            { identityEmail = it.take(320) },
                            Modifier.fillMaxWidth(),
                            label = { Text("Git user.email") },
                            supportingText = { Text("This is commit author metadata, separate from GitHub sign-in credentials.") },
                            singleLine = true,
                            enabled = !busy,
                        )
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val name = identityName
                                    val email = identityEmail
                                    runOperation {
                                        val result = git.configureIdentity(name, email)
                                        identityEditing = false
                                        result
                                    }
                                },
                                enabled = identityName.isNotBlank() && identityEmail.isNotBlank() && !busy,
                            ) { Text("Save identity") }
                            if (identity != null) {
                                OutlinedButton(onClick = {
                                    runOperation {
                                        val result = git.clearIdentity()
                                        identityName = ""
                                        identityEmail = ""
                                        identityEditing = true
                                        result
                                    }
                                }, enabled = !busy) { Text("Clear") }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Changes (${working.size})", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { runOperation { git.stageAll() } }, enabled = working.isNotEmpty() && !busy) { Text("Stage all") }
            }
        }
        items(working, key = { "w:${it.path}:${it.kind}" }) { change ->
            GitChangeRow(
                change = change,
                onOpen = { if (change.kind != GitChangeKind.DELETED) onOpen(change.path) },
                primaryAction = {
                    if (change.kind == GitChangeKind.CONFLICT) {
                        pendingResolve = change
                        conflictError = null
                        conflictBlocks = emptyList()
                        scope.launch {
                            conflictBusy = true
                            conflictBlocks = runSuspendCatching { git.conflictBlocks(change.path) }
                                .getOrElse { conflictError = it.message ?: "Unable to parse conflict"; emptyList() }
                            conflictBusy = false
                        }
                    } else runOperation { git.stage(change.path) }
                },
                primaryLabel = if (change.kind == GitChangeKind.CONFLICT) "Resolve" else "Stage",
                onDiff = { showDiff(change) },
                onDiscard = if (change.kind == GitChangeKind.MODIFIED || change.kind == GitChangeKind.DELETED) {
                    { pendingDiscard = change }
                } else null,
                enabled = !busy,
            )
        }

        item { Text("Staged Changes (${staged.size})", style = MaterialTheme.typography.titleMedium) }
        items(staged, key = { "s:${it.path}:${it.kind}" }) { change ->
            GitChangeRow(
                change = change,
                onOpen = { if (change.kind != GitChangeKind.DELETED) onOpen(change.path) },
                primaryAction = { runOperation { git.unstage(change.path) } },
                primaryLabel = "Unstage",
                onDiff = { showDiff(change) },
                onDiscard = null,
                enabled = !busy,
            )
        }

        item {
            OutlinedTextField(
                commitMsg,
                { commitMsg = it.take(500) },
                Modifier.fillMaxWidth(),
                label = { Text("Commit message") },
                placeholder = { Text("feat: update") },
                singleLine = true,
            )
        }
        item {
            Button(
                onClick = {
                    val message = commitMsg
                    runOperation {
                        val result = git.commitStaged(message)
                        if (!result.startsWith("git error:")) commitMsg = ""
                        result
                    }
                },
                enabled = staged.isNotEmpty() && commitMsg.isNotBlank() && identity != null && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Commit, null)
                Spacer(Modifier.width(8.dp))
                Text("Commit staged changes")
            }
        }

        item { HorizontalDivider() }
        item { Text("Branches", style = MaterialTheme.typography.titleMedium) }
        item {
            OutlinedTextField(
                branch,
                { branch = it.take(200) },
                Modifier.fillMaxWidth(),
                label = { Text("Branch") },
                singleLine = true,
            )
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runOperation { git.checkout(branch) } }, enabled = branch.isNotBlank() && !busy) { Text("Switch") }
                OutlinedButton(onClick = { runOperation { git.checkout(branch, create = true) } }, enabled = branch.isNotBlank() && !busy) { Text("Create & switch") }
            }
        }

        item { HorizontalDivider() }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Stashes (${stashes.size})", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { runOperation { git.stash(includeUntracked = true) } }, enabled = changes.isNotEmpty() && !busy) { Text("Stash changes") }
            }
        }
        items(stashes, key = { it.index }) { stash ->
            ListItem(
                headlineContent = { Text("stash@{${stash.index}} · ${stash.id}", fontFamily = FontFamily.Monospace) },
                supportingContent = { Text(stash.message, maxLines = 2) },
                trailingContent = {
                    Row {
                        TextButton(onClick = { runOperation { git.applyStash(stash.index) } }, enabled = !busy) { Text("Apply") }
                        IconButton(onClick = { runOperation { git.dropStash(stash.index) } }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, "Drop stash") }
                    }
                },
            )
        }

        item { HorizontalDivider() }
        item { Text("History (${history.size})", style = MaterialTheme.typography.titleMedium) }
        items(history, key = { it.id }) { commit ->
            ListItem(
                headlineContent = { Text(commit.subject.ifBlank { "(no subject)" }, maxLines = 2) },
                supportingContent = {
                    val whenText = remember(commit.epochMillis) {
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(commit.epochMillis))
                    }
                    Text("${commit.shortId} · ${commit.author} · $whenText${if (commit.parentCount > 1) " · merge" else ""}", fontFamily = FontFamily.Monospace, maxLines = 2)
                },
                modifier = Modifier.clickable { statusText = "${commit.id}\n${commit.subject}\nAuthor: ${commit.author}" },
            )
        }

        item { HorizontalDivider() }
        item {
            Text("Git output", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Card(Modifier.fillMaxWidth()) {
                Text(
                    statusText.take(16_000),
                    Modifier.padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    diffTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { if (!diffBusy) diffTitle = null },
            title = { Text(title, maxLines = 2) },
            text = {
                Surface(Modifier.fillMaxWidth().heightIn(max = 520.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        DiffText(diffText)
                    }
                }
            },
            confirmButton = { TextButton(enabled = !diffBusy, onClick = { diffTitle = null }) { Text("Close") } },
        )
    }

    pendingResolve?.let { change ->
        AlertDialog(
            onDismissRequest = { if (!conflictBusy) pendingResolve = null },
            title = { Text("Resolve merge conflict", maxLines = 2) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FileIdentityLabel(change.path, change.path, iconSize = 16.dp, monospaced = true)
                    conflictError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (conflictBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!conflictBusy && conflictBlocks.isEmpty() && conflictError == null) {
                        Text("No conflict markers remain. Review the file, then mark it resolved and stage it.")
                    }
                    conflictBlocks.forEach { block ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Conflict ${block.index + 1}", style = MaterialTheme.typography.titleSmall)
                                ConflictVersion("Current · ${block.currentLabel}", block.current)
                                block.base?.let { ConflictVersion("Base", it) }
                                ConflictVersion("Incoming · ${block.incomingLabel}", block.incoming)
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    listOf(
                                        ConflictChoice.CURRENT to "Accept Current",
                                        ConflictChoice.INCOMING to "Accept Incoming",
                                        ConflictChoice.BOTH to "Accept Both",
                                    ).forEach { (choice, label) ->
                                        OutlinedButton(
                                            enabled = !conflictBusy,
                                            onClick = {
                                                scope.launch {
                                                    conflictBusy = true
                                                    conflictError = null
                                                    val result = runSuspendCatching { git.resolveConflictBlock(change.path, block.index, choice) }
                                                    if (result.isFailure) conflictError = result.exceptionOrNull()?.message
                                                    conflictBlocks = runSuspendCatching { git.conflictBlocks(change.path) }.getOrDefault(emptyList())
                                                    conflictBusy = false
                                                    refreshState()
                                                }
                                            },
                                        ) { Text(label) }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !conflictBusy && conflictBlocks.isEmpty() && conflictError == null,
                    onClick = {
                        pendingResolve = null
                        runOperation { git.stage(change.path, allowConflictResolution = true) }
                    },
                ) { Text("Mark resolved & stage") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onOpen(change.path); pendingResolve = null }, enabled = !conflictBusy) { Text("Open file") }
                    TextButton(onClick = { pendingResolve = null }, enabled = !conflictBusy) { Text("Close") }
                }
            },
        )
    }

    pendingDiscard?.let { change ->
        AlertDialog(
            onDismissRequest = { pendingDiscard = null },
            title = { Text("Discard changes?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileIdentityLabel(change.path, change.path, iconSize = 18.dp, monospaced = true)
                    Text("Discard this working-tree change? It cannot be recovered.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingDiscard = null
                    runOperation { git.discardWorkingTree(change.path) }
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { pendingDiscard = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConflictVersion(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Text(
                text.ifEmpty { "(empty)" }.take(8_000),
                Modifier.padding(8.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GitChangeRow(
    change: GitChange,
    onOpen: () -> Unit,
    primaryAction: () -> Unit,
    primaryLabel: String,
    onDiff: () -> Unit,
    onDiscard: (() -> Unit)?,
    enabled: Boolean,
) {
    val marker = when (change.kind) {
        GitChangeKind.ADDED -> "A"
        GitChangeKind.MODIFIED -> "M"
        GitChangeKind.DELETED -> "D"
        GitChangeKind.UNTRACKED -> "U"
        GitChangeKind.CONFLICT -> "!"
    }
    ListItem(
        headlineContent = { FileIdentityLabel(change.path, change.path, iconSize = 15.dp, maxLines = 2, monospaced = true) },
        leadingContent = { SuggestionChip(onClick = onOpen, enabled = change.kind != GitChangeKind.DELETED, label = { Text(marker) }) },
        trailingContent = {
            Row {
                IconButton(onClick = onDiff, enabled = enabled) { Icon(Icons.Default.Difference, "View diff") }
                if (onDiscard != null) IconButton(onClick = onDiscard, enabled = enabled) { Icon(Icons.Default.Restore, "Discard") }
                TextButton(onClick = primaryAction, enabled = enabled) { Text(primaryLabel) }
            }
        },
        modifier = Modifier.clickable(enabled = change.kind != GitChangeKind.DELETED, onClick = onOpen),
    )
}
