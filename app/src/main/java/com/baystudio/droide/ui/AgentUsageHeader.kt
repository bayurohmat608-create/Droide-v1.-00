package com.baystudio.droide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.TokenUsage
import com.baystudio.droide.core.UsageAuthority
import java.util.Locale

 
@Composable
internal fun AgentUsageHeaderButton(usage: TokenUsage, paneWidth: Dp, onClick: () -> Unit) {
    val showCost = paneWidth >= 320.dp
    val showTokens = paneWidth >= 280.dp
    val cost = formatUsageCostCompact(usage)
    Box(
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = 36.dp, max = 120.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.height(24.dp).widthIn(min = 24.dp, max = 112.dp),
            color = DroideColors.Surface2,
            border = BorderStroke(1.dp, DroideColors.Border),
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Row(
                Modifier.padding(horizontal = if (showTokens) 6.dp else 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.DataUsage, "Usage and billing", Modifier.size(13.dp), tint = DroideColors.Muted)
                if (showTokens) {
                    Spacer(Modifier.width(4.dp))
                    Text(formatUsageTokenCount(usage.total), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                if (showTokens && showCost && cost != null) {
                    Text(" · $cost", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun AgentUsageQuickMenu(
    usage: TokenUsage,
    canCompact: Boolean,
    onViewBilling: () -> Unit,
    onCompact: () -> Unit,
) {
    val cost = formatUsageCostCompact(usage)
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("This session", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(formatUsageTokenCount(usage.total), style = MaterialTheme.typography.titleSmall)
            Text(" tokens", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            Spacer(Modifier.weight(1f))
            if (cost != null) Text(cost, style = MaterialTheme.typography.titleSmall)
        }
        Text(usageCostAuthorityLabel(usage), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
        HorizontalDivider(color = DroideColors.Border)
        AgentUsageRow("Input", usage.prompt)
        AgentUsageRow("Output", usage.completion)
        if (usage.reasoning > 0) AgentUsageRow("Reasoning", usage.reasoning)
        if (usage.cacheRead > 0) AgentUsageRow("Cache read", usage.cacheRead)
        if (usage.cacheWrite > 0) AgentUsageRow("Cache write", usage.cacheWrite)
    }
    HorizontalDivider(color = DroideColors.Border)
    DropdownMenuItem(
        text = { Text("View Usage & Billing") },
        leadingIcon = { Icon(Icons.Default.ReceiptLong, null) },
        onClick = onViewBilling,
    )
    DropdownMenuItem(
        text = { Text("Compact session") },
        leadingIcon = { Icon(Icons.Default.Compress, null) },
        enabled = canCompact,
        onClick = onCompact,
    )
}

@Composable
private fun AgentUsageRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = DroideColors.Muted)
        Spacer(Modifier.weight(1f))
        Text(formatUsageTokenCount(value), style = MaterialTheme.typography.labelMedium, color = DroideColors.Text)
    }
}

private fun formatUsageCostCompact(usage: TokenUsage): String? {
    if (usage.costAuthority == UsageAuthority.UNKNOWN) return null
    val prefix = if (usage.costAuthority == UsageAuthority.AUTHORITATIVE) "$" else "≈$"
    return prefix + String.format(Locale.US, "%.2f", usage.costUsd.coerceAtLeast(0.0))
}

private fun usageCostAuthorityLabel(usage: TokenUsage): String = when (usage.costAuthority) {
    UsageAuthority.AUTHORITATIVE -> "Provider-reported cost"
    UsageAuthority.ESTIMATED -> "Estimated cost"
    UsageAuthority.MIXED -> "Mixed exact/estimated cost"
    UsageAuthority.UNKNOWN -> "Cost unavailable · token usage ${usage.tokenAuthority.name.lowercase()}"
}

private fun formatUsageTokenCount(tokens: Int): String = when {
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
    else -> tokens.toString()
}
