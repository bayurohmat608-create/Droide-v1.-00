package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.McpHealthPhase
import com.baystudio.droide.core.McpHealthSnapshot
import com.baystudio.droide.core.McpHealthState

@Composable
fun McpHeaderStatusButton(
    health: List<McpHealthSnapshot>,
    onRetry: (String) -> Unit,
    onManage: () -> Unit,
) {
    if (health.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    val summary = remember(health) { mcpAggregate(health) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(34.dp)) {
            BadgedBox(
                badge = {
                    when (summary) {
                        McpAggregate.ERROR -> Badge(containerColor = MaterialTheme.colorScheme.error) { Text("!") }
                        McpAggregate.ATTENTION -> Badge(containerColor = DroideColors.Warning) { Text("!") }
                        else -> Unit
                    }
                },
            ) {
                Icon(
                    Icons.Default.Hub,
                    contentDescription = when (summary) {
                        McpAggregate.ERROR -> "MCP has errors"
                        McpAggregate.ATTENTION -> "MCP needs attention"
                        McpAggregate.HEALTHY -> "MCP connected"
                        McpAggregate.IDLE -> "MCP configured"
                    },
                    modifier = Modifier.size(19.dp),
                    tint = when (summary) {
                        McpAggregate.ERROR -> MaterialTheme.colorScheme.error
                        McpAggregate.ATTENTION -> DroideColors.Warning
                        McpAggregate.HEALTHY -> DroideColors.Muted
                        McpAggregate.IDLE -> DroideColors.Muted
                    },
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false; selected = null },
            modifier = Modifier.widthIn(min = 284.dp, max = 330.dp),
        ) {
            val selectedHealth = selected?.let { name -> health.firstOrNull { it.serverName == name } }
            if (selectedHealth == null) {
                McpMenuHeader("MCP", onClose = { expanded = false; selected = null })
                Text(
                    "${health.count { !it.workspaceIssue }} server${if (health.count { !it.workspaceIssue } == 1) "" else "s"} · ${health.sumOf { it.toolsCount ?: 0 }} discovered tools",
                    Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = DroideColors.Muted,
                )
                HorizontalDivider()
                health.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(item.serverName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.message, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        onClick = { selected = item.serverName },
                        leadingIcon = { McpStateIcon(item) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Manage MCP") },
                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                    onClick = { expanded = false; selected = null; onManage() },
                )
            } else {
                McpMenuHeader(
                    selectedHealth.serverName,
                    onBack = { selected = null },
                    onClose = { expanded = false; selected = null },
                )
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    McpStatusLabel(selectedHealth)
                    Text("${selectedHealth.phase.pretty()} · ${selectedHealth.message}", style = MaterialTheme.typography.bodySmall)
                    selectedHealth.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    selectedHealth.toolsCount?.let { Text("$it discovered tool${if (it == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) }
                    Text("${selectedHealth.source} · checked ${relativeAge(selectedHealth.lastCheckedAtMs)}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                }
                HorizontalDivider()
                if (!selectedHealth.workspaceIssue) {
                    DropdownMenuItem(
                        text = { Text("Retry check") },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        onClick = { onRetry(selectedHealth.serverName) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Details") },
                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                    onClick = { expanded = false; selected = null; onManage() },
                )
            }
        }
    }
}

@Composable
private fun McpMenuHeader(title: String, onBack: (() -> Unit)? = null, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.ArrowBack, "Back") }
        else Spacer(Modifier.width(6.dp))
        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Close MCP panel") }
    }
}

@Composable
fun McpSettingsSection(
    health: List<McpHealthSnapshot>,
    onRetry: (String) -> Unit,
    onOpenConfig: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    var expandedServer by rememberSaveable { mutableStateOf<String?>(null) }
    val serverCount = health.count { !it.workspaceIssue }
    val errors = health.count { it.isError }
    val attention = health.count { it.requiresAttention }
    Text("MCP servers", style = MaterialTheme.typography.labelLarge)
    Text(
        "Supported here: local stdio with command/args. Remote HTTP and env injection are unsupported; configuration errors are shown separately from missing executables and server failures.",
        style = MaterialTheme.typography.bodySmall,
        color = DroideColors.Muted,
    )
    Text(
        when {
            serverCount == 0 && health.none { it.workspaceIssue } -> "No workspace MCP server is configured."
            errors > 0 -> "$serverCount configured · $errors error${if (errors == 1) "" else "s"}"
            attention > 0 -> "$serverCount configured · $attention need attention"
            else -> "$serverCount configured · ${health.sumOf { it.toolsCount ?: 0 }} discovered tools"
        },
        style = MaterialTheme.typography.bodySmall,
        color = DroideColors.Muted,
    )
    Spacer(Modifier.height(6.dp))
    health.forEach { item ->
        ListItem(
            headlineContent = { Text(item.serverName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(item.message, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = { McpStateIcon(item) },
            trailingContent = { Text(item.state.pretty(), style = MaterialTheme.typography.labelSmall, color = McpStateColor(item.state)) },
            modifier = Modifier.fillMaxWidth().clickable { expandedServer = if (expandedServer == item.serverName) null else item.serverName },
        )
        if (expandedServer == item.serverName) {
            Column(Modifier.fillMaxWidth().padding(start = 54.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("${item.phase.pretty()} · ${item.source}", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                item.command?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                item.protocolVersion?.let { Text("Protocol $it", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) }
                item.toolsCount?.let { Text("$it discovered tools", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) }
                item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (item.isError) MaterialTheme.colorScheme.error else DroideColors.Text) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!item.workspaceIssue) OutlinedButton(onClick = { onRetry(item.serverName) }) { Text("Retry check") }
                    if (item.source == "Workspace" || item.workspaceIssue) TextButton(onClick = onOpenConfig) { Text("Open config") }
                }
            }
        }
        HorizontalDivider()
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onOpenConfig) { Text("Open .droide/mcp.json") }
        TextButton(onClick = onOpenPermissions) { Text("Agent Permissions") }
    }
    Text(
        "MCP stdio sessions start on demand and stay available for subsequent calls until configuration changes, idle expiry, failure or Agent shutdown.",
        style = MaterialTheme.typography.labelSmall,
        color = DroideColors.Muted,
    )
}

@Composable
private fun McpStateIcon(item: McpHealthSnapshot) {
    val icon = when {
        item.isError -> Icons.Default.ErrorOutline
        item.requiresAttention -> Icons.Default.WarningAmber
        else -> Icons.Default.Hub
    }
    Icon(icon, null, modifier = Modifier.size(18.dp), tint = McpStateColor(item.state))
}

@Composable
private fun McpStatusLabel(item: McpHealthSnapshot) {
    Text(item.state.pretty(), color = McpStateColor(item.state), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun McpStateColor(state: McpHealthState): Color = when (state) {
    McpHealthState.ERROR, McpHealthState.UNAVAILABLE -> MaterialTheme.colorScheme.error
    McpHealthState.STARTING, McpHealthState.NEEDS_AUTH, McpHealthState.DEGRADED -> DroideColors.Warning
    McpHealthState.CONNECTED -> MaterialTheme.colorScheme.primary
    McpHealthState.CONFIGURED, McpHealthState.DISABLED -> DroideColors.Muted
}

private enum class McpAggregate { IDLE, HEALTHY, ATTENTION, ERROR }
private fun mcpAggregate(items: List<McpHealthSnapshot>): McpAggregate = when {
    items.any { it.isError } -> McpAggregate.ERROR
    items.any { it.requiresAttention } -> McpAggregate.ATTENTION
    items.any { it.state == McpHealthState.CONNECTED } -> McpAggregate.HEALTHY
    else -> McpAggregate.IDLE
}

private fun McpHealthState.pretty(): String = when (this) {
    McpHealthState.CONFIGURED -> "Configured"
    McpHealthState.STARTING -> "Starting"
    McpHealthState.CONNECTED -> "Connected"
    McpHealthState.NEEDS_AUTH -> "Needs auth"
    McpHealthState.DEGRADED -> "Degraded"
    McpHealthState.UNAVAILABLE -> "Unavailable"
    McpHealthState.ERROR -> "Error"
    McpHealthState.DISABLED -> "Disabled"
}

private fun McpHealthPhase.pretty(): String = when (this) {
    McpHealthPhase.CONFIGURATION -> "Configuration"
    McpHealthPhase.ENVIRONMENT -> "Environment"
    McpHealthPhase.PERMISSION -> "Permission"
    McpHealthPhase.PROCESS -> "Process"
    McpHealthPhase.PROTOCOL -> "Protocol"
    McpHealthPhase.DISCOVERY -> "Discovery"
    McpHealthPhase.READY -> "Ready"
    McpHealthPhase.TOOL_CALL -> "Tool call"
}

private fun relativeAge(atMs: Long): String {
    val seconds = ((System.currentTimeMillis() - atMs).coerceAtLeast(0L) / 1_000L)
    return when {
        seconds < 5 -> "now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3_600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3_600}h ago"
    }
}
