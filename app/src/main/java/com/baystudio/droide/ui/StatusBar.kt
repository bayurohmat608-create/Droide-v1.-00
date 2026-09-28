package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

 
@Composable
fun StatusBar(
    branch: String,
    language: String,
    environment: String,
    mode: String,
    providerId: String,
    provider: String,
    sessions: Int,
    tokens: Int,
    onCompact: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(DroideDimensions.StatusBar)
            .background(DroideColors.Status)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(language, color = DroideColors.StatusText, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            Text(environment, color = DroideColors.StatusText.copy(alpha = .92f), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Default.CallSplit, null, Modifier.size(13.dp), tint = DroideColors.StatusText.copy(alpha = .92f))
                Text(branch, color = DroideColors.StatusText.copy(alpha = .92f), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
            Text(mode, color = DroideColors.StatusText.copy(alpha = .92f), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            ProviderIdentityLabel(providerId, provider, iconSize = 13.dp, color = DroideColors.StatusText.copy(alpha = .92f))
            Text("$tokens tokens", color = DroideColors.StatusText.copy(alpha = .85f), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            Text("$sessions sessions", color = DroideColors.StatusText.copy(alpha = .85f), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        }
        Text(
            "Compact",
            modifier = Modifier.clickable(onClick = onCompact).padding(horizontal = 6.dp, vertical = 2.dp),
            color = DroideColors.StatusText,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
    }
}
