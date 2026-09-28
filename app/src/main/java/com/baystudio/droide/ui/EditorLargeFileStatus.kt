package com.baystudio.droide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.EditorLargeFilePolicy

 
@Composable
internal fun EditorLargeFileStatusBar(document: EditorDocument) {
    if (!document.largeFileOptimized) return
    val overSaveCeiling = document.fileBytes > EditorLargeFilePolicy.MAX_EDITABLE_BYTES
    val tint = if (overSaveCeiling) DroideColors.Warning else DroideColors.Primary
    val title = if (overSaveCeiling) {
        "Save paused · ${EditorLargeFilePolicy.formatBytes(document.fileBytes)}"
    } else {
        "Large file · ${EditorLargeFilePolicy.formatBytes(document.fileBytes)}"
    }
    val detail = if (overSaveCeiling) {
        "Editor stays active. Reduce below 20 MiB to save."
    } else {
        "Performance mode · code intelligence and wrap are reduced."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tint.copy(alpha = .055f),
        border = BorderStroke(1.dp, tint.copy(alpha = .18f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (overSaveCeiling) Icons.Default.WarningAmber else Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = tint,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = DroideColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
