package com.baystudio.droide.ui

import android.content.res.Configuration
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ExplorerTreeRow(val entry: FileEntry?, val depth: Int, val parent: String = "")
private data class ExplorerImportUiState(
    val name: String,
    val progress: StreamWriteProgress,
    val cancelling: Boolean = false,
)

 
@Composable
fun FileExplorer(
    files: FileRepository,
    state: ExplorerWorkspaceState,
    onOpen: (String) -> Unit,
    canMutatePath: (String) -> Boolean = { true },
    onRenamed: (String, String) -> Unit = { _, _ -> },
    onDeleted: (String) -> Unit = {},
    safLinked: Boolean = false,
    onReconcileSaf: suspend () -> String? = { null },
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val configuration = LocalConfiguration.current
    val portrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val treeRowHeight = if (portrait) 40.dp else 32.dp
    val treeIconSize = if (portrait) 20.dp else 17.dp
    val treeChevronSize = if (portrait) 17.dp else 15.dp
    val treeActionSize = if (portrait) 40.dp else 30.dp
    val treeActionIconSize = if (portrait) 18.dp else 16.dp
    val treeLabelStyle = if (portrait) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium
    val searchResultMinHeight = if (portrait) 40.dp else 32.dp
    val workspaceKey = files.root.absolutePath
    val children = state.children
    val treeListState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.treeFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = state.treeFirstVisibleItemScrollOffset,
    )
    val searchListState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.searchFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = state.searchFirstVisibleItemScrollOffset,
    )
    var searchBusy by remember(workspaceKey) { mutableStateOf(false) }
    var err by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var syncMessage by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var syncBusy by remember(workspaceKey) { mutableStateOf(false) }
    var showNewFile by remember(workspaceKey) { mutableStateOf(false) }
    var showNewFolder by remember(workspaceKey) { mutableStateOf(false) }
    var showRename by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var showDelete by remember(workspaceKey) { mutableStateOf<FileEntry?>(null) }
    var actionTarget by remember(workspaceKey) { mutableStateOf<FileEntry?>(null) }
    var newName by remember(workspaceKey) { mutableStateOf("") }
    var newLang by remember(workspaceKey) { mutableStateOf("kt") }
    var importUi by remember(workspaceKey) { mutableStateOf<ExplorerImportUiState?>(null) }
    var importJob by remember(workspaceKey) { mutableStateOf<Job?>(null) }
    val paging = remember(workspaceKey) { mutableStateMapOf<String, Boolean>() }

    suspend fun reloadTree() {
        try {
            val keep = state.expandedPaths.sortedBy { it.count { c -> c == '/' } }
            val next = linkedMapOf<String, List<FileEntry>>()
            val cursors = linkedMapOf<String, DirectoryPageCursor>()
            val rootPage = files.listFilesPage("")
            next[""] = rootPage.entries
            rootPage.nextCursor?.let { cursors[""] = it }
            keep.forEach { path ->
                if (files.isDirectory(path)) {
                    val page = files.listFilesPage(path)
                    next[path] = page.entries
                    page.nextCursor?.let { cursors[path] = it }
                }
            }


            children.keys.filterNot(next::containsKey).forEach { children.remove(it) }
            children.putAll(next)
            state.moreAfter.clear()
            state.moreAfter.putAll(cursors)
            state.pageGeneration++
            state.loadedRefreshToken = state.refreshToken
            err = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) { err = failure.message ?: "Could not load Explorer" }
    }

    fun loadMore(parent: String) {
        val cursor = state.moreAfter[parent] ?: return
        if (paging[parent] == true) return
        val generation = state.pageGeneration
        paging[parent] = true
        scope.launch {
            try {
                val page = files.listFilesPage(parent, cursor)
                if (generation == state.pageGeneration && state.moreAfter[parent] == cursor) {
                    val existing = children[parent].orEmpty()
                    val known = existing.mapTo(hashSetOf()) { it.path }
                    children[parent] = existing + page.entries.filter { known.add(it.path) }
                    if (page.nextCursor == null) state.moreAfter.remove(parent)
                    else state.moreAfter[parent] = page.nextCursor
                    err = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) { err = "Could not load more files: ${failure.message ?: "unknown error"}" }
            finally { paging.remove(parent) }
        }
    }

    fun refresh() { scope.launch {
        if (safLinked) {
            syncBusy = true
            syncMessage = "Synchronizing linked folder…"
            try { syncMessage = onReconcileSaf() ?: "Linked folder unavailable" }
            catch (cancelled: CancellationException) { syncMessage = "Linked folder sync pending · refresh again"; throw cancelled }
            catch (failure: Exception) { syncMessage = "Linked folder sync pending/conflict: ${failure.message ?: "unknown error"}" }
            finally { syncBusy = false }
        }
        reloadTree()
    } }

    fun toggleDirectory(entry: FileEntry) {
        state.activeDirectory = entry.path
        val open = entry.path in state.expandedPaths
        state.expandedPaths = if (open) state.expandedPaths - entry.path else (state.expandedPaths + entry.path).distinct()
        if (!open && children[entry.path] == null) {
            scope.launch {
                try {
                    val page = files.listFilesPage(entry.path)
                    children[entry.path] = page.entries
                    if (page.nextCursor == null) state.moreAfter.remove(entry.path)
                    else state.moreAfter[entry.path] = page.nextCursor
                    err = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { err = failure.message ?: "Could not open directory" }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && importJob?.isActive != true) {
            importJob = scope.launch {
                err = null
                importUi = ExplorerImportUiState("Selected file", StreamWriteProgress(StreamWritePhase.PREPARING))
                try {
                // Keep UI and native ownership work on the main thread.


                val source = withContext(Dispatchers.IO) {
                    var displayName: String? = null
                    var expectedBytes: Long? = null
                    val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
                    ctx.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) expectedBytes = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                        }
                    }
                    (displayName ?: "imported_${System.currentTimeMillis()}") to expectedBytes
                }
                val safeName = PathSecurity.safeLeafName(source.first)
                importUi = ExplorerImportUiState(
                    safeName,
                    StreamWriteProgress(StreamWritePhase.PREPARING, totalBytes = source.second),
                )
                val path = if (state.activeDirectory.isBlank()) safeName else "${state.activeDirectory}/$safeName"
                check(!files.exists(path)) { "A file named $safeName already exists here" }
                withContext(Dispatchers.IO) {
                    val input = ctx.contentResolver.openInputStream(uri) ?: error("Cannot read file")
                    files.writeStream(path, input, expectedBytes = source.second) { progress ->
                        withContext(Dispatchers.Main.immediate) {
                            importUi = ExplorerImportUiState(
                                safeName,
                                progress,
                                cancelling = importUi?.cancelling == true,
                            )
                        }
                    }
                }
                reloadTree()
                } catch (cancelled: CancellationException) {
                    // Repository cleanup owns temp/rollback disposal and the previous file tree remains authoritative.

                } catch (failure: Throwable) {
                    err = failure.message ?: "File import failed"
                } finally {
                    importUi = null
                    importJob = null
                }
            }
        }
    }

    LaunchedEffect(treeListState) {
        snapshotFlow { treeListState.firstVisibleItemIndex to treeListState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                state.treeFirstVisibleItemIndex = index
                state.treeFirstVisibleItemScrollOffset = offset
            }
    }
    LaunchedEffect(searchListState) {
        snapshotFlow { searchListState.firstVisibleItemIndex to searchListState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                state.searchFirstVisibleItemIndex = index
                state.searchFirstVisibleItemScrollOffset = offset
            }
    }
    LaunchedEffect(workspaceKey, state.refreshToken) {
        if (children[""] == null || state.loadedRefreshToken != state.refreshToken) reloadTree()
    }
    LaunchedEffect(state.query) {
        val requestQuery = state.query
        val clean = requestQuery.trim().filterNot { it == '*' || it == '?' || it == '[' || it == ']' }.take(80)
        if (clean.length < 2) {
            state.searchResults = emptyList(); state.searchNotice = null; searchBusy = false
            return@LaunchedEffect
        }
        searchBusy = true
        try {
            delay(220)
            val page = files.globPage("**/*$clean*", 200)
            if (state.query == requestQuery) {
                state.searchResults = page.paths
                state.searchNotice = when {
                    page.scanLimitReached -> "Scan stopped after ${page.scannedEntries} entries; results may be incomplete."
                    page.resultLimitReached -> "Showing first ${page.paths.size} files; narrow the name for more."
                    else -> null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (state.query == requestQuery) {
                state.searchResults = emptyList()
                state.searchNotice = "File search failed: ${failure.message ?: failure::class.java.simpleName}"
            }
        } finally { if (state.query == requestQuery) searchBusy = false }
    }
    LaunchedEffect(showRename) { newName = showRename?.substringAfterLast('/') ?: newName }

    fun flattenedRows(): List<ExplorerTreeRow> {
        val out = mutableListOf<ExplorerTreeRow>()
        fun visit(parent: String, depth: Int) {
            children[parent].orEmpty().forEach { entry ->
                out += ExplorerTreeRow(entry, depth)
                if (entry.isDir && entry.path in state.expandedPaths) visit(entry.path, depth + 1)
            }
            if (parent in state.moreAfter) out += ExplorerTreeRow(null, depth, parent)
        }
        visit("", 0)
        return out
    }

    Column(Modifier.fillMaxSize().background(DroideColors.Surface)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = {
                if (state.query != it) {
                    state.searchResults = emptyList(); state.searchNotice = null
                    state.searchFirstVisibleItemIndex = 0; state.searchFirstVisibleItemScrollOffset = 0
                    scope.launch { searchListState.scrollToItem(0) }
                }
                state.query = it
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            placeholder = { Text("Search files") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
            trailingIcon = {
                when {
                    searchBusy -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                    state.query.isNotBlank() -> IconButton(onClick = { state.query = "" }) { Icon(Icons.Default.Close, "Clear file search", Modifier.size(17.dp)) }
                }
            },
        )
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.activeDirectory.isBlank()) "/" else "/${state.activeDirectory}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = importJob?.isActive != true,
                modifier = Modifier.size(34.dp),
            ) { Icon(Icons.Default.Upload, "Import file", Modifier.size(18.dp)) }
            IconButton(onClick = { showNewFile = true }, Modifier.size(34.dp)) { Icon(Icons.Default.NoteAdd, "New file", Modifier.size(18.dp)) }
            IconButton(onClick = { showNewFolder = true }, Modifier.size(34.dp)) { Icon(Icons.Default.CreateNewFolder, "New folder", Modifier.size(18.dp)) }
            IconButton(onClick = ::refresh, Modifier.size(34.dp), enabled = !syncBusy) { Icon(Icons.Default.Refresh, "Refresh Explorer and linked folder", Modifier.size(18.dp)) }
        }
        if (safLinked) Text(syncMessage ?: "Linked folder · Refresh reconciles external and terminal changes", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = if (syncMessage?.contains("pending/conflict") == true) MaterialTheme.colorScheme.error else DroideColors.Muted)
        HorizontalDivider(color = DroideColors.Border)
        importUi?.let { state ->
            val progress = state.progress
            val label = when {
                state.cancelling -> "Cancelling import…"
                progress.phase == StreamWritePhase.PREPARING -> "Preparing ${state.name}…"
                progress.phase == StreamWritePhase.COPYING -> "Importing ${state.name}"
                progress.phase == StreamWritePhase.COMMITTING -> "Committing ${state.name}…"
                else -> "Finishing import safely…"
            }
            Column(
                Modifier.fillMaxWidth().background(DroideColors.Surface2).padding(horizontal = 9.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val total = progress.totalBytes
                        val detail = if (total != null && total > 0L) {
                            val percent = ((progress.completedBytes.toDouble() / total.toDouble()) * 100.0).coerceIn(0.0, 100.0).toInt()
                            "$percent% · ${formatTransferBytes(progress.completedBytes)} / ${formatTransferBytes(total)}"
                        } else if (progress.completedBytes > 0L) {
                            "${formatTransferBytes(progress.completedBytes)} copied"
                        } else {
                            "Checking source and available storage"
                        }
                        Text(detail, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    }
                    if (progress.cancellable && !state.cancelling) {
                        TextButton(
                            onClick = {
                                importUi = state.copy(cancelling = true)
                                importJob?.cancel()
                            },
                        ) { Text("Cancel") }
                    }
                }
                if (progress.phase == StreamWritePhase.COPYING && progress.fraction != null && progress.completedBytes > 0L) {
                    LinearProgressIndicator(progress = { progress.fraction!! }, modifier = Modifier.fillMaxWidth())
                } else {
                    

                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            HorizontalDivider(color = DroideColors.Border)
        }
        err?.let { Text(it, Modifier.fillMaxWidth().background(DroideColors.Error.copy(alpha = .08f)).padding(8.dp), color = DroideColors.Error, style = MaterialTheme.typography.labelSmall) }

        if (state.query.trim().filterNot { it == '*' || it == '?' || it == '[' || it == ']' }.length >= 2) {
            LazyColumn(Modifier.fillMaxSize(), state = searchListState) {
                state.searchNotice?.let { notice -> item { Text(notice, Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall, color = if (notice.startsWith("File search failed")) DroideColors.Error else DroideColors.Muted) } }
                items(state.searchResults, key = { it }) { path ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = searchResultMinHeight).clickable { onOpen(path) }.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FileTypeBrandIcon(path = path, size = treeIconSize)
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(path.substringAfterLast('/'), style = treeLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(path, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                if (!searchBusy && state.searchResults.isEmpty() && state.searchNotice?.startsWith("File search failed") != true) item { Text("No matching files in the first 20 folder levels", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) }
            }
        } else {
            val rows = flattenedRows()
            LazyColumn(Modifier.fillMaxSize(), state = treeListState) {
                items(rows, key = { row -> row.entry?.let { "entry:${it.path}" } ?: "more:${row.parent}" }) { row ->
                    val entry = row.entry
                    if (entry == null) {
                        TextButton(
                            onClick = { loadMore(row.parent) },
                            enabled = paging[row.parent] != true,
                            modifier = Modifier.padding(start = (8 + row.depth * 14).dp),
                        ) { Text(if (paging[row.parent] == true) "Loading…" else "Load more files in ${row.parent.ifBlank { "/" }}") }
                    } else {
                    val open = entry.path in state.expandedPaths
                    Row(
                        Modifier.fillMaxWidth().height(treeRowHeight).clickable { if (entry.isDir) toggleDirectory(entry) else onOpen(entry.path) }
                            .padding(start = (6 + row.depth * 14).dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (entry.isDir) {
                            Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight, if (open) "Collapse" else "Expand", Modifier.size(treeChevronSize), tint = DroideColors.Muted)
                        } else Spacer(Modifier.width(treeChevronSize))
                        if (entry.isDir) {
                            Icon(Icons.Default.Folder, null, Modifier.size(treeIconSize), tint = DroideColors.Warning)
                        } else {
                            FileTypeBrandIcon(path = entry.path, size = treeIconSize)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(entry.path.substringAfterLast('/'), Modifier.weight(1f), style = treeLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { actionTarget = entry }, Modifier.size(treeActionSize)) { Icon(Icons.Default.MoreVert, "File actions", Modifier.size(treeActionIconSize), tint = DroideColors.Muted) }
                    }
                    }
                }
                if (rows.isEmpty()) item { Text("Empty project", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) }
            }
        }
    }

    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = {
                if (target.isDir) Text(target.path.substringAfterLast('/'))
                else FileIdentityLabel(target.path, target.path.substringAfterLast('/'), iconSize = 18.dp)
            },
            text = {
                if (target.isDir) Text(target.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                else FileIdentityLabel(target.path, target.path, iconSize = 15.dp, monospaced = true)
            },
            confirmButton = { TextButton(onClick = { showRename = target.path; actionTarget = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { showDelete = target; actionTarget = null }) { Text("Delete") } },
        )
    }

    if (showNewFile) AlertDialog(
        onDismissRequest = { showNewFile = false },
        title = { Text("New File") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { newName = it }, label = { Text("File name") }, placeholder = { Text("Main.kt") }, singleLine = true)
                var langMenu by remember { mutableStateOf(false) }
                val langDef = LanguageRegistry.forExtension(newLang) ?: LanguageRegistry.all.first()
                Box {
                    OutlinedButton(onClick = { langMenu = true }) { Text(langDef.name); Icon(Icons.Default.ArrowDropDown, null) }
                    DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                        LanguageRegistry.all.forEach { lang -> DropdownMenuItem({ Text(lang.name) }, onClick = { newLang = lang.extensions.first(); langMenu = false }) }
                    }
                }
                Text("Location: /${state.activeDirectory}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching {
                        var name = PathSecurity.safeLeafName(newName.trim().ifBlank { "untitled.txt" })
                        if (!name.contains('.')) name = PathSecurity.safeLeafName("$name.$newLang")
                        val path = if (state.activeDirectory.isBlank()) name else "${state.activeDirectory}/$name"
                        check(!files.exists(path)) { "A file named $name already exists here" }
                        files.writeText(path, LanguageRegistry.forFile(name)?.template.orEmpty())
                        reloadTree(); showNewFile = false; newName = ""; onOpen(path)
                    }.onFailure { err = it.message }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = { showNewFile = false }) { Text("Cancel") } },
    )

    if (showNewFolder) AlertDialog(
        onDismissRequest = { showNewFolder = false },
        title = { Text("New Folder") },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text("Folder name") }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching {
                        val name = PathSecurity.safeLeafName(newName.trim().ifBlank { "new_folder" })
                        val path = if (state.activeDirectory.isBlank()) name else "${state.activeDirectory}/$name"
                        check(!files.exists(path)) { "A file or folder named $name already exists here" }
                        check(files.createDirectory(path)) { "Cannot create folder" }
                        reloadTree(); showNewFolder = false; newName = ""; state.expandedPaths = (state.expandedPaths + path).distinct()
                    }.onFailure { err = it.message }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancel") } },
    )

    if (showRename != null) AlertDialog(
        onDismissRequest = { showRename = null },
        title = { Text("Rename") },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text("New name") }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                val old = showRename!!
                scope.launch {
                    runCatching {
                        check(canMutatePath(old)) { "Save or discard unsaved editor changes before renaming this path" }
                        val leaf = PathSecurity.safeLeafName(newName)
                        val newPath = if (old.contains('/')) old.substringBeforeLast('/') + "/" + leaf else leaf
                        if (old != newPath) {
                            check(files.move(old, newPath)) { "Rename failed" }
                            state.expandedPaths = state.expandedPaths.map { if (it == old || it.startsWith("$old/")) newPath + it.removePrefix(old) else it }.distinct()
                            if (state.activeDirectory == old || state.activeDirectory.startsWith("$old/")) state.activeDirectory = newPath + state.activeDirectory.removePrefix(old)
                            onRenamed(old, newPath)
                            reloadTree()
                        }
                        showRename = null; newName = ""
                    }.onFailure { err = it.message }
                }
            }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = { showRename = null }) { Text("Cancel") } },
    )

    showDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { showDelete = null },
            title = { Text(if (target.isDir) "Delete folder?" else "Delete file?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!target.isDir) FileIdentityLabel(target.path, target.path, iconSize = 16.dp, monospaced = true)
                    Text(if (target.isDir) "Delete ${target.path} and all of its contents? This is not part of editor undo history." else "This file will be deleted. This is not part of editor undo history.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching {
                            check(canMutatePath(target.path)) { "Save or discard unsaved editor changes before deleting this path" }
                            check(files.delete(target.path)) { "Delete failed" }
                            state.expandedPaths = state.expandedPaths.filterNot { it == target.path || it.startsWith("${target.path}/") }
                            if (state.activeDirectory == target.path || state.activeDirectory.startsWith("${target.path}/")) state.activeDirectory = target.path.substringBeforeLast('/', "")
                            onDeleted(target.path); reloadTree()
                        }.onFailure { err = it.message }
                        showDelete = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun formatTransferBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> java.lang.String.format(java.util.Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> java.lang.String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> java.lang.String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
