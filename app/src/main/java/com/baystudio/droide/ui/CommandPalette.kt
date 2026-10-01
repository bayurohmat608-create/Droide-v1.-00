package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.CommandManager
import com.baystudio.droide.core.DroideCommand
import com.baystudio.droide.core.FileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext





@Composable
fun CommandPalette(
    files: FileRepository,
    commands: CommandManager,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var all by remember(commands) { mutableStateOf<List<DroideCommand>>(emptyList()) }
    var loading by remember(commands) { mutableStateOf(true) }
    var error by remember(commands) { mutableStateOf<String?>(null) }
    LaunchedEffect(commands) {
        try { all = withContext(Dispatchers.IO) { commands.list() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not load commands" }
        finally { loading = false }
    }
    val filtered = remember(all, query) {
        if (query.isBlank()) all else all.filter { it.name.contains(query.trimStart('/'), true) || it.description.contains(query, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Command Palette", Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close command palette") }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Search commands…") }, singleLine = true)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(filtered.take(12)) { c ->
                        ListItem(
                            headlineContent = { Text(c.name.replace('-', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }) },
                            supportingContent = { Text(c.description.ifBlank { c.template.take(60) }) },
                            modifier = Modifier.clickable { onPick("/${c.name}"); onDismiss() }
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}
