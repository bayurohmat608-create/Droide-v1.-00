package com.baystudio.droide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baystudio.droide.core.DiffLine
import com.baystudio.droide.core.DiffLineKind
import com.baystudio.droide.core.UnifiedDiffParser

 
@Composable
fun DiffText(text: String) {
    val parsed = remember(text) { UnifiedDiffParser.parse(text) }
    if (parsed.isEmpty()) {
        Text(text.ifBlank { "(no diff)" }, Modifier.padding(8.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        return
    }
    val rows = remember(parsed) {
        buildList {
            parsed.forEach { file ->
                add(DiffRow.FileHeader(file.newPath ?: file.oldPath ?: "diff"))
                file.headers.filterNot { it.startsWith("diff --git ") || it.startsWith("--- ") || it.startsWith("+++ ") }
                    .forEach { add(DiffRow.Meta(it)) }
                file.hunks.forEach { h ->
                    add(DiffRow.Hunk(h.header))
                    h.lines.forEach { add(DiffRow.Line(it)) }
                }
            }
        }
    }
    LazyColumn(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        items(rows) { row ->
            when (row) {
                is DiffRow.FileHeader -> Text(
                    row.path,
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                )
                is DiffRow.Hunk -> Text(
                    row.header,
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 3.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                )
                is DiffRow.Meta -> Text(
                    row.text.ifEmpty { " " }, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                )
                is DiffRow.Line -> DiffLineRow(row.line)
            }
        }
    }
}

private sealed interface DiffRow {
    data class FileHeader(val path: String) : DiffRow
    data class Hunk(val header: String) : DiffRow
    data class Meta(val text: String) : DiffRow
    data class Line(val line: DiffLine) : DiffRow
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val bg = when (line.kind) {
        DiffLineKind.ADD -> DroideColors.Success.copy(alpha = .16f)
        DiffLineKind.REMOVE -> DroideColors.Error.copy(alpha = .16f)
        DiffLineKind.META -> MaterialTheme.colorScheme.surfaceVariant
        DiffLineKind.CONTEXT -> Color.Transparent
    }
    val prefix = when (line.kind) {
        DiffLineKind.ADD -> "+"
        DiffLineKind.REMOVE -> "-"
        DiffLineKind.CONTEXT -> " "
        DiffLineKind.META -> " "
    }
    Row(Modifier.fillMaxWidth().background(bg).padding(vertical = 1.dp)) {
        Text(
            line.oldLine?.toString()?.padStart(4) ?: "    ",
            Modifier.width(40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
        )
        Text(
            line.newLine?.toString()?.padStart(4) ?: "    ",
            Modifier.width(40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
        )
        Text(prefix + line.text.ifEmpty { " " }, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}
