package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import java.io.File
import kotlinx.coroutines.launch

data class CapabilityHealth(
    val label: String,
    val detail: String,
    val ready: Boolean,
    val icon: ImageVector,
)

@Composable
fun ProfessionalDiagnosticsSheet(
    android: AndroidDevelopmentManager,
    device: DeviceBridgeManager,
    git: GitManager,
    tasks: TaskManager,
    debugger: DebugManager,
    extensions: DevelopmentExtensionsManager,
    activeFile: String,
    apk: File?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var rows by remember(android, git, activeFile, apk?.absolutePath) { mutableStateOf<List<CapabilityHealth>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = true; error = null
        runSuspendCatching {
            val androidStatus = runSuspendCatching { android.refresh() }.getOrElse { android.status.value }
            val deviceState = device.state.value
            val repository = runSuspendCatching { git.isRepository() }.getOrDefault(false)
            val testTasks = runSuspendCatching { tasks.list() }.getOrDefault(emptyList()).count {
                it.group.equals("test", true) || it.label.contains("test", true) || it.label.contains("pytest", true)
            }
            val debugConfigs = if (activeFile.isBlank()) emptyList() else runSuspendCatching { debugger.configurations(activeFile) }.getOrDefault(emptyList())
            val androidAttach = if (activeFile.isBlank()) false else runSuspendCatching { debugger.hasAndroidAttachProvider(activeFile) }.getOrDefault(false)
            val extensionResult = runSuspendCatching { extensions.refresh(activeFile.ifBlank { null }) }
            val extensionSnapshot = extensionResult.getOrElse { extensions.snapshot.value }
            val localLinux = if (extensionResult.isSuccess) LocalExecutionSubstrate.state.value
                else LocalExecutionSubstrate.inspectLinuxState()
            rows = listOf(
                CapabilityHealth("Android project", androidStatus.message, androidStatus.androidProject, Icons.Default.Android),
                CapabilityHealth("Android toolchain", androidStatus.toolchainVersion?.let { "Toolchain $it" } ?: androidStatus.message, androidStatus.toolchainVersion != null, Icons.Default.Build),
                CapabilityHealth("Local Linux ARM64", when {
                    localLinux.ready -> "Ubuntu health check passed; toolchains are installed separately"
                    !localLinux.prootAvailable -> "Packaged PRoot is unavailable on this device"
                    localLinux.ubuntuAvailable -> "Ubuntu is present but its health check failed; existing data is preserved"
                    else -> "Open Terminal → + Linux to activate Ubuntu"
                }, localLinux.ready, Icons.Default.Terminal),
                CapabilityHealth("Device Workstation (ADB)", if (!deviceState.supported) "Wireless Debugging requires Android 11 or newer" else deviceState.connected?.let { "Connected · ${it.host}:${it.port}" } ?: (deviceState.lastError ?: "Not connected"), deviceState.connected != null, Icons.Default.PhoneAndroid),
                CapabilityHealth("Source control", if (repository) "Git repository ready" else "Initialize repository from Source Control", repository, Icons.Default.Source),
                CapabilityHealth("Tests", if (testTasks > 0) "$testTasks test task(s) discovered" else "No test tasks discovered", testTasks > 0, Icons.Default.Science),
                CapabilityHealth("Debugger", if (debugConfigs.isNotEmpty()) "${debugConfigs.size} compatible configuration(s)" else "No compatible debug adapter for active file", debugConfigs.isNotEmpty(), Icons.Default.BugReport),
                CapabilityHealth("Android JDWP", if (androidAttach) "JDWP-capable DAP provider available" else "Install/configure an Android JDWP-capable adapter", androidAttach, Icons.Default.Memory),
                CapabilityHealth("Development extensions", extensionSnapshot.message, extensionSnapshot.items.any { it.state == ExtensionState.BUILT_IN || it.state == ExtensionState.INSTALLED || it.state == ExtensionState.EXTERNAL }, Icons.Default.Extension),
                CapabilityHealth("APK artifact", apk?.takeIf { it.isFile }?.name ?: "Build an APK to enable analysis", apk?.isFile == true, Icons.Default.Inventory2),
            )
        }.onFailure { error = it.message ?: "Capability diagnostics failed" }
        loading = false
    }

    LaunchedEffect(android, git, activeFile, apk?.absolutePath) { refresh() }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
        DroidePanelHeader(title = "Capability Health", onClose = onDismiss, actions = {
            IconButton(onClick = { scope.launch { refresh() } }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh capability health") }
        })
        Text("Missing providers are shown as setup requirements.", Modifier.padding(horizontal = 16.dp), color = DroideColors.Muted)
        HorizontalDivider(Modifier.padding(top = 12.dp))
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        else LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.label }) { row ->
                ListItem(
                    leadingContent = { Icon(row.icon, null, tint = if (row.ready) MaterialTheme.colorScheme.primary else DroideColors.Muted) },
                    headlineContent = { Text(row.label) },
                    supportingContent = { Text(row.detail, maxLines = 3) },
                    trailingContent = {
                        Surface(shape = MaterialTheme.shapes.small, color = if (row.ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Text(if (row.ready) "Ready" else "Setup", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                )
                HorizontalDivider()
            }
        }
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
    }
}
