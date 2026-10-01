package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

 
@Composable
internal fun DroideEditorTabs(
    openFiles: List<String>,
    activeFile: String,
    isDirty: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    if (openFiles.isEmpty()) return
    val selected = openFiles.indexOf(activeFile).coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = selected,
        edgePadding = 0.dp,
        containerColor = DroideColors.Surface,
        contentColor = DroideColors.Text,
        divider = { HorizontalDivider(color = DroideColors.Border) },
    ) {
        openFiles.forEach { path ->
            val dirty = isDirty(path)
            Box(
                Modifier
                    .height(DroideDimensions.EditorTabBar)
                    .selectable(
                        selected = path == activeFile,
                        onClick = { onSelect(path) },
                        role = Role.Tab,
                    )
                    .padding(start = 10.dp, end = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FileTypeBrandIcon(path, size = 16.dp)
                    if (dirty) Icon(Icons.Default.Circle, "Unsaved", Modifier.size(7.dp), tint = DroideColors.Primary)
                    Text(path.substringAfterLast('/'), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    Box(
                        Modifier.size(32.dp).clickable { onClose(path) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Close, "Close ${path.substringAfterLast('/')}", Modifier.size(16.dp)) }
                }
            }
        }
    }
}
