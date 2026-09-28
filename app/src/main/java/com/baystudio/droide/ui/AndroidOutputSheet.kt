package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

 
@Composable
fun AndroidOutputSheet(title: String, output: String, onDismiss: () -> Unit) {
    val lines = remember(output) { output.lineSequence().takeLastBounded(5_000) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text("${lines.size} line(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close output") }
        }
        HorizontalDivider()
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(lines) { line ->
                    Text(
                        line.ifEmpty { " " },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun Sequence<String>.takeLastBounded(limit: Int): List<String> {
    if (limit <= 0) return emptyList()
    val ring = ArrayDeque<String>(limit)
    for (line in this) {
        if (ring.size == limit) ring.removeFirst()
        ring.addLast(line.take(8_000))
    }
    return ring.toList()
}
