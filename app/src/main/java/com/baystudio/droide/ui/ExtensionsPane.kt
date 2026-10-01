package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import com.baystudio.droide.core.AndroidDevelopmentManager
import com.baystudio.droide.core.DevelopmentExtensionsManager
import com.baystudio.droide.core.ExtensionCategory
import com.baystudio.droide.core.ExtensionState
import com.baystudio.droide.core.ExtensionVersionState
import com.baystudio.droide.core.DeviceBridgeManager
import com.baystudio.droide.core.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsPane(
    manager: DevelopmentExtensionsManager,
    androidDevelopment: AndroidDevelopmentManager,
    deviceBridge: DeviceBridgeManager,
    activeFile: String,
) {
    val snapshot by manager.snapshot.collectAsState()
    val marketplaceSnapshot by manager.marketplace.snapshot.collectAsState()
    val bridgeState by deviceBridge.state.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<ExtensionCategory?>(null) }
    var selected by remember { mutableStateOf<ExtensionVersionState?>(null) }
    var selectedMarketplace by remember { mutableStateOf<com.baystudio.droide.core.MarketplaceExtensionSummary?>(null) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(manager, activeFile, bridgeState.connected) {
        runSuspendCatching { manager.refresh(activeFile) }
            .onFailure { refreshMessage = it.message ?: "Extensions refresh failed" }
    }

    LaunchedEffect(manager, query, category) {
        val remoteQuery = query.trim()
        if (category == null && remoteQuery.length >= com.baystudio.droide.core.ExtensionMarketplaceManager.MIN_REMOTE_QUERY_CHARS) {
            delay(350)
            manager.marketplace.search(remoteQuery)
        }
    }

    val filtered = remember(snapshot.items, query, category) {
        snapshot.items.filter { item ->
            val searchable = buildList {
                add(item.family.id)
                add(item.family.name)
                add(item.family.publisher)
                add(item.family.description)
                add(item.version.version)
                add(item.version.channel)
                add(item.family.category.label)
                addAll(item.family.keywords)
                addAll(item.version.provides)
                addAll(item.version.requires)
            }
            (category == null || item.family.category == category) &&
                (query.isBlank() || searchable.any { it.contains(query, ignoreCase = true) })
        }
    }
    val remoteQuery = query.trim()
    val remoteResults = marketplaceSnapshot.page?.extensions
        ?.takeIf { category == null && marketplaceSnapshot.query.equals(remoteQuery, ignoreCase = true) }
        .orEmpty()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(120) },
                modifier = Modifier.weight(1f),
                label = { Text("Search extensions") },
                singleLine = true,
            )
            IconButton(onClick = {
                scope.launch {
                    runSuspendCatching { manager.refresh(activeFile) }
                        .onSuccess {
                            refreshMessage = null
                            if (category == null && remoteQuery.length >= com.baystudio.droide.core.ExtensionMarketplaceManager.MIN_REMOTE_QUERY_CHARS) {
                                manager.marketplace.search(remoteQuery)
                            }
                        }
                        .onFailure { refreshMessage = it.message ?: "Extensions refresh failed" }
                }
            }) { Icon(Icons.Default.Refresh, "Refresh extensions") }
        }

        val categories = listOf<ExtensionCategory?>(null) + ExtensionCategory.entries
        ScrollableTabRow(
            selectedTabIndex = categories.indexOf(category).coerceAtLeast(0),
            edgePadding = 8.dp,
        ) {
            categories.forEach { item ->
                Tab(
                    selected = category == item,
                    onClick = { category = item },
                    text = { Text(item?.label ?: "All", maxLines = 1) },
                )
            }
        }

        val marketplaceNotice = if (
            category == null &&
            remoteQuery.length >= com.baystudio.droide.core.ExtensionMarketplaceManager.MIN_REMOTE_QUERY_CHARS &&
            (marketplaceSnapshot.loading || marketplaceSnapshot.offlineCache)
        ) marketplaceSnapshot.message else null
        val notice = refreshMessage ?: marketplaceNotice
        if (!notice.isNullOrBlank()) {
            Text(
                notice,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (marketplaceSnapshot.offlineCache) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }

        when {
            selectedMarketplace != null -> MarketplaceExtensionDetail(
                summary = selectedMarketplace!!,
                manager = manager,
                onBack = { selectedMarketplace = null },
            )
            selected != null -> ExtensionDetail(
                item = selected!!,
                manager = manager,
                onBack = { selected = null },
                onRefresh = {
                    scope.launch {
                        runSuspendCatching { manager.refresh(activeFile) }
                            .onSuccess { updated ->
                                selected = updated.items.firstOrNull {
                                    it.family.id == selected?.family?.id && it.version.version == selected?.version?.version
                                }
                            }
                    }
                },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (filtered.isNotEmpty()) {
                    items(filtered, key = { "local:${it.family.id}@${it.version.version}" }) { item ->
                        ExtensionRow(item = item, onClick = { selected = item })
                    }
                }
                if (category == null && remoteQuery.length >= com.baystudio.droide.core.ExtensionMarketplaceManager.MIN_REMOTE_QUERY_CHARS) {
                    item(key = "open-vsx-heading") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Open VSX · inspect compatibility before installing", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            if (marketplaceSnapshot.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    items(remoteResults, key = { "ovsx:${it.id}@${it.version}" }) { item ->
                        MarketplaceExtensionRow(item = item, manager = manager, onClick = { selectedMarketplace = item })
                    }
                    if (!marketplaceSnapshot.loading && remoteResults.isEmpty()) {
                        item(key = "open-vsx-empty") {
                            Text(
                                marketplaceSnapshot.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionRow(item: ExtensionVersionState, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ExtensionBrandIcon(family = item.family, size = 36.dp)
                ExtensionStateBadge(
                    state = item.state,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.family.name, style = MaterialTheme.typography.titleSmall)
                    Text(item.version.version, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                }
                val provenance = item.family.publisher.takeIf(String::isNotBlank)
                    ?.let { "$it · ${item.family.category.label}" }
                    ?: item.family.category.label
                Text(provenance, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                extensionListStatus(item)?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun extensionListStatus(item: ExtensionVersionState): String? = when (item.state) {
    ExtensionState.BUILT_IN -> null
    ExtensionState.INSTALLED -> "Installed"
    ExtensionState.EXTERNAL -> "Detected on workstation"
    ExtensionState.AVAILABLE -> "Ready to install"
    ExtensionState.UNAVAILABLE -> "Unavailable"
}

private fun extensionDetailStatus(item: ExtensionVersionState): String {
    val detail = item.detail.trim()
    val concise = detail.takeIf { it.isNotBlank() && it.length <= 80 }
    return when (item.state) {
        ExtensionState.BUILT_IN -> "Built into Droide"
        ExtensionState.INSTALLED -> concise ?: "Installed"
        ExtensionState.EXTERNAL -> concise ?: "Detected on workstation"
        ExtensionState.AVAILABLE -> concise ?: "Ready to install"
        ExtensionState.UNAVAILABLE -> concise ?: "Not available for this device or setup"
    }
}

@Composable
private fun MarketplaceExtensionRow(
    item: com.baystudio.droide.core.MarketplaceExtensionSummary,
    manager: DevelopmentExtensionsManager,
    onClick: () -> Unit,
) {
    val installedVersion = manager.marketplace.installedVersion(item.id)
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                MarketplaceBrandIcon(item, manager, 36.dp)
                ExtensionStateBadge(
                    state = if (installedVersion != null) ExtensionState.INSTALLED else ExtensionState.AVAILABLE,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(item.version, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                }
                val publisher = buildString {
                    append(item.namespace)
                    append(" · Open VSX")
                    if (item.verifiedPublisher) append(" · verified")
                }
                Text(publisher, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Text(
                    if (installedVersion != null && installedVersion != item.version) "Update available from $installedVersion · ${item.description}" else item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun MarketplaceBrandIcon(
    item: com.baystudio.droide.core.MarketplaceExtensionSummary,
    manager: DevelopmentExtensionsManager,
    size: androidx.compose.ui.unit.Dp,
) {
    val iconPath by produceState<String?>(initialValue = item.iconCachePath, item.id, item.iconUrl) {
        if (value == null) value = manager.marketplace.icon(item)
    }
    val path = iconPath
    if (path != null && File(path).isFile) {
        AsyncImage(
            model = File(path),
            contentDescription = "${item.displayName} icon",
            modifier = Modifier.size(size),
        )
    } else {
        Surface(
            modifier = Modifier.size(size),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp,
        ) {
            Icon(Icons.Default.Extension, null, Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MarketplaceExtensionDetail(
    summary: com.baystudio.droide.core.MarketplaceExtensionSummary,
    manager: DevelopmentExtensionsManager,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val operation by manager.marketplace.operation.collectAsState()
    var inspection by remember(summary.id) { mutableStateOf<com.baystudio.droide.core.MarketplaceInspection?>(null) }
    var loading by remember(summary.id) { mutableStateOf(true) }
    var message by remember(summary.id) { mutableStateOf<String?>(null) }
    var confirmInstall by remember(summary.id) { mutableStateOf(false) }
    var confirmUninstall by remember(summary.id) { mutableStateOf(false) }

    LaunchedEffect(summary.id) {
        loading = true
        runSuspendCatching { manager.marketplace.inspect(summary.id) }
            .onSuccess { inspection = it }
            .onFailure { message = it.message ?: "Could not inspect marketplace extension" }
        loading = false
    }

    val current = inspection
    val installedVersion = remember(summary.id, operation.phase, operation.message) { manager.marketplace.installedVersion(summary.id) }
    val rollbackAvailable = remember(summary.id, operation.phase, operation.message) { manager.marketplace.rollbackAvailable(summary.id) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back to Extensions") }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MarketplaceBrandIcon(summary, manager, 48.dp)
            Column(Modifier.weight(1f)) {
                Text(current?.detail?.displayName ?: summary.displayName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${current?.detail?.namespace ?: summary.namespace} · Open VSX",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Checking extension…", style = MaterialTheme.typography.bodySmall)
        }

        current?.let { inspected ->
            val detail = inspected.detail
            Text(detail.description, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            DetailLine("Version", detail.version)
            installedVersion?.let { DetailLine("Installed", it) }
            DetailLine("Publisher", detail.namespace)
            DetailLine("Publisher status", if (detail.verifiedPublisher) "Verified" else "Not verified")
            DetailLine("Trusted publishing", if (detail.trustedPublishing) "Yes" else "Not reported")
            detail.license?.let { DetailLine("License", it) }
            detail.targetPlatform?.let { DetailLine("Registry target", it) }
            inspected.engineConstraint?.let { DetailLine("VS Code engine", it) }
            DetailLine(
                "Compatibility",
                inspected.compatibility.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
            )
            DetailLine("Install mode", if (inspected.installable) "Declarative data only" else "Unavailable in Droide")
            Text(
                "Open VSX packages are inspected before Install. This marketplace accepts only supported declarative languages, grammars, snippets and themes. VS Code executable activation requires its extension host/API and is unavailable on this path. Managed tools and reviewed Droide executables use separate installers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inspected.supportedContributionKinds.isNotEmpty()) {
                DetailLine("Contributes", inspected.supportedContributionKinds.sorted().joinToString(", "))
            }
            if (inspected.extensionDependencies.isNotEmpty()) {
                DetailLine("Dependencies", inspected.extensionDependencies.sorted().joinToString(", "))
            }
            if (detail.deprecated) {
                Text("Deprecated by the registry.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!detail.verifiedPublisher) {
                Text(
                    "Publisher not verified by Open VSX. Only supported declarative resources can be installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            inspected.reasons.forEach { reason ->
                Text("• $reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (operation.extensionId == summary.id && operation.message.isNotBlank()) {
                Text(operation.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (operation.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            val installLabel = if (installedVersion == null) "Install" else if (installedVersion != detail.version) "Update" else null
            if (installLabel != null && inspected.installable) {
                Button(
                    enabled = !operation.running,
                    onClick = {
                        if (detail.verifiedPublisher) {
                            scope.launch {
                                message = null
                                runSuspendCatching { manager.marketplace.install(summary.id) }
                                    .onSuccess { record ->
                                        message = "${record.displayName} ${record.version} is active"
                                        inspection = runSuspendCatching { manager.marketplace.inspect(summary.id, forceRefresh = true) }.getOrNull() ?: inspection
                                    }
                                    .onFailure { message = it.message ?: "Marketplace install failed" }
                            }
                        } else confirmInstall = true
                    },
                ) { Text(installLabel) }
            }
            if (!inspected.installable) {
                Text(
                    if (inspected.executable) "Install disabled: this package needs VS Code executable activation, which this Droide marketplace cannot run." else "Install disabled: package is blocked or outside Droide's supported declarative subset. See compatibility reasons above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (installedVersion != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rollbackAvailable) {
                        OutlinedButton(
                            enabled = !operation.running,
                            onClick = {
                                scope.launch {
                                    message = null
                                    runSuspendCatching { manager.marketplace.rollback(summary.id) }
                                        .onSuccess { message = "Rolled back to ${it.version}" }
                                        .onFailure { message = it.message ?: "Rollback failed" }
                                }
                            },
                        ) { Text("Rollback") }
                    }
                    TextButton(enabled = !operation.running, onClick = { confirmUninstall = true }) { Text("Uninstall") }
                }
            }
        }
    }

    if (confirmInstall) {
        AlertDialog(
            onDismissRequest = { if (!operation.running) confirmInstall = false },
            title = { Text("Install unverified publisher extension?") },
            text = {
                Text("Publisher not verified. Executable code is blocked.")
            },
            confirmButton = {
                Button(
                    enabled = !operation.running,
                    onClick = {
                        confirmInstall = false
                        scope.launch {
                            message = null
                            runSuspendCatching { manager.marketplace.install(summary.id) }
                                .onSuccess { message = "${it.displayName} ${it.version} is active" }
                                .onFailure { message = it.message ?: "Marketplace install failed" }
                        }
                    },
                ) { Text("Install data-only") }
            },
            dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text("Cancel") } },
        )
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { if (!operation.running) confirmUninstall = false },
            title = { Text("Uninstall ${summary.displayName}?") },
            text = { Text("Remove this extension? Rollback history is kept temporarily.") },
            confirmButton = {
                Button(
                    enabled = !operation.running,
                    onClick = {
                        confirmUninstall = false
                        scope.launch {
                            message = null
                            runSuspendCatching { manager.marketplace.uninstall(summary.id) }
                                .onSuccess { message = "Extension removed" }
                                .onFailure { message = it.message ?: "Uninstall failed" }
                        }
                    },
                ) { Text("Uninstall") }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExtensionStateBadge(state: ExtensionState, modifier: Modifier = Modifier) {
    val tint = when (state) {
        ExtensionState.BUILT_IN, ExtensionState.INSTALLED -> MaterialTheme.colorScheme.primary
        ExtensionState.EXTERNAL, ExtensionState.AVAILABLE -> MaterialTheme.colorScheme.secondary
        ExtensionState.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val image = when (state) {
        ExtensionState.BUILT_IN, ExtensionState.INSTALLED -> Icons.Default.CheckCircle
        ExtensionState.EXTERNAL, ExtensionState.AVAILABLE -> Icons.Default.Info
        ExtensionState.UNAVAILABLE -> Icons.Default.Warning
    }
    Surface(
        modifier = modifier.size(17.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Icon(image, state.name, modifier = Modifier.padding(1.dp), tint = tint)
    }
}

@Composable
private fun ExtensionDetail(
    item: ExtensionVersionState,
    manager: DevelopmentExtensionsManager,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val installState by manager.installer.state.collectAsState()
    val androidProviderState by manager.installer.androidProviderState.collectAsState()
    val packageProviderState by manager.installer.packageProviderState.collectAsState()
    var plan by remember(item.family.id, item.version.version) { mutableStateOf<com.baystudio.droide.core.ExtensionInstallPlan?>(null) }
    var installJob by remember { mutableStateOf<Job?>(null) }
    var installMessage by remember { mutableStateOf<String?>(null) }
    var confirmUninstall by remember(item.family.id, item.version.version) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back to Extensions") }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ExtensionBrandIcon(family = item.family, size = 48.dp)
                ExtensionStateBadge(item.state, Modifier.align(Alignment.BottomEnd))
            }
            Column(Modifier.weight(1f)) {
                Text(item.family.name, style = MaterialTheme.typography.headlineSmall)
                if (item.family.publisher.isNotBlank()) {
                    Text(item.family.publisher, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text(item.family.description, style = MaterialTheme.typography.bodyMedium)
        if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) {
            Text(
                "Managed Android components share one JDK/SDK/Build Tools pack. Removing any component removes the entire pack and its managed records; individual versions cannot be removed here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        HorizontalDivider()
        DetailLine("Category", item.family.category.label)
        DetailLine("Version", item.version.version)
        DetailLine("Channel", item.version.channel)
        DetailLine("Install mode", when (item.version.installKind) {
            com.baystudio.droide.core.ExtensionInstallKind.BUILT_IN -> "Bundled Droide feature"
            com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN -> "Managed Android pack · shared removal"
            com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT -> "Reviewed Android SDK component · local Ubuntu"
            com.baystudio.droide.core.ExtensionInstallKind.MANAGED_PACKAGE,
            com.baystudio.droide.core.ExtensionInstallKind.GUEST_PACKAGE,
            com.baystudio.droide.core.ExtensionInstallKind.REVIEWED_RECIPE -> "Reviewed managed tool"
            com.baystudio.droide.core.ExtensionInstallKind.CATALOG_ONLY -> "Catalog only · no install provider"
        })
        DetailLine("Execution", when (item.version.scope) {
            com.baystudio.droide.core.ExecutionScope.EDITOR -> "Editor"
            com.baystudio.droide.core.ExecutionScope.LOCAL -> "Local app environment"
            com.baystudio.droide.core.ExecutionScope.LOCAL_LINUX_ARM64 -> "Local Linux ARM64"
        })
        DetailLine("Status", item.state.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
        if (item.version.provides.isNotEmpty()) DetailLine("Provides", item.version.provides.sorted().joinToString(", "))
        if (item.version.requires.isNotEmpty()) DetailLine("Requires", item.version.requires.sorted().joinToString(", "))
        Text(
            extensionDetailStatus(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )

        installMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (installState.familyId == item.family.id && installState.version == item.version.version && installState.message.isNotBlank()) {
            Text(installState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        val providerFraction = androidProviderState.fraction ?: packageProviderState.fraction
        if (installState.running && providerFraction != null) {
            LinearProgressIndicator(progress = { providerFraction }, modifier = Modifier.fillMaxWidth())
            Text("${(100 * providerFraction).toInt()}%", style = MaterialTheme.typography.labelSmall)
        }

        when (item.state) {
            ExtensionState.AVAILABLE -> {
                Button(
                    enabled = installJob == null && !installState.running,
                    onClick = {
                        installJob = scope.launch {
                            try {
                                val result = runSuspendCatching { manager.installer.prepare(item) }
                                result.onSuccess { prepared -> plan = prepared }
                                    .onFailure { installMessage = it.message ?: "Could not prepare installation" }
                            } finally {
                                installJob = null
                            }
                        }
                    },
                ) { Text("Install") }
            }
            ExtensionState.INSTALLED -> {
                val record = manager.registry.find(item.family.id, item.version.version)
                val workspaceSelected = manager.workspaceVersion(item.family.id) == item.version.version
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh, enabled = installJob == null && !installState.running) { Text("Verify status") }
                    if (item.version.installKind in setOf(
                            com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
                            com.baystudio.droide.core.ExtensionInstallKind.MANAGED_PACKAGE,
                            com.baystudio.droide.core.ExtensionInstallKind.GUEST_PACKAGE,
                            com.baystudio.droide.core.ExtensionInstallKind.REVIEWED_RECIPE,
                        ) && record != null && !record.active) {
                        Button(
                            enabled = installJob == null && !installState.running,
                            onClick = {
                                installJob = scope.launch {
                                    try {
                                        runSuspendCatching { manager.unifiedAuthority.activate(item.family.id, item.version.version) }
                                            .onSuccess { installMessage = it.message; if (it.success) onRefresh() }
                                            .onFailure { installMessage = it.message ?: "Activation failed" }
                                    } finally {
                                        installJob = null
                                    }
                                }
                            },
                        ) { Text("Set global default") }
                    }
                }
                if (item.version.installKind in setOf(
                        com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
                        com.baystudio.droide.core.ExtensionInstallKind.MANAGED_PACKAGE,
                        com.baystudio.droide.core.ExtensionInstallKind.GUEST_PACKAGE,
                        com.baystudio.droide.core.ExtensionInstallKind.REVIEWED_RECIPE,
                    ) && record != null) {
                    OutlinedButton(
                        enabled = installJob == null && !installState.running,
                        onClick = {
                            installJob = scope.launch {
                                try {
                                    runSuspendCatching {
                                        if (workspaceSelected) {
                                            manager.clearWorkspaceVersion(item.family.id)
                                            "Workspace override cleared."
                                        } else {
                                            val result = manager.unifiedAuthority.activate(item.family.id, item.version.version, manager.workspaceId)
                                            check(result.success) { result.message }
                                            result.message
                                        }
                                    }.onSuccess { installMessage = it; onRefresh() }
                                        .onFailure { installMessage = it.message ?: "Workspace toolchain selection failed" }
                                } finally {
                                    installJob = null
                                }
                            }
                        },
                    ) { Text(if (workspaceSelected) "Use global default" else "Use in workspace") }
                }
                if (item.version.installKind in setOf(
                        com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
                        com.baystudio.droide.core.ExtensionInstallKind.MANAGED_PACKAGE,
                        com.baystudio.droide.core.ExtensionInstallKind.GUEST_PACKAGE,
                        com.baystudio.droide.core.ExtensionInstallKind.REVIEWED_RECIPE,
                        com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN,
                    )
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = installJob == null && !installState.running,
                            onClick = {
                                installJob = scope.launch {
                                    try {
                                        runSuspendCatching { manager.unifiedAuthority.repairVersion(item.family.id, item.version.version) }
                                            .onSuccess { txResult -> 
                                                if (txResult.success) {
                                                    installMessage = txResult.message
                                                    onRefresh()
                                                } else {
                                                    installMessage = txResult.message
                                                }
                                            }
                                            .onFailure { installMessage = it.message ?: "Repair failed" }
                                    } finally {
                                        installJob = null
                                    }
                                }
                            },
                        ) { Text("Repair") }
                        TextButton(
                            enabled = installJob == null && !installState.running,
                            onClick = { confirmUninstall = true },
                        ) { Text(if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) "Remove Android pack" else "Uninstall") }
                    }
                }
            }
            else -> Unit
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { if (installJob == null && !installState.running) confirmUninstall = false },
            title = { Text(if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) "Remove entire Android toolchain pack?" else "Uninstall ${item.family.name}?") },
            text = {
                Text(
                    if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN)
                        "This removes the managed Android JDK, SDK and Build Tools together, including records for other Android components. Projects using this pack will need it installed again. Continue?"
                    else
                        "Remove ${item.family.name} ${item.version.version}? Required dependencies block removal."
                )
            },
            confirmButton = {
                Button(
                    enabled = installJob == null && !installState.running,
                    onClick = {
                        installJob = scope.launch {
                            try {
                                runSuspendCatching { manager.unifiedAuthority.uninstall(item.family.id, item.version.version) }
                                    .onSuccess { txResult ->
                                        if (txResult.success) {
                                            installMessage = txResult.message
                                            confirmUninstall = false
                                            onRefresh()
                                        } else {
                                            installMessage = txResult.message
                                        }
                                    }
                                    .onFailure { installMessage = it.message ?: "Uninstall failed" }
                            } finally {
                                installJob = null
                            }
                        }
                    },
                ) { Text(if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) "Remove entire pack" else "Uninstall") }
            },
            dismissButton = {
                TextButton(
                    enabled = installJob == null && !installState.running,
                    onClick = { confirmUninstall = false },
                ) { Text("No") }
            },
        )
    }

    plan?.let { currentPlan ->
        val androidPlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.Android
        val localAndroidPlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.AndroidLocalComponent
        val packagePlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.Package
        val recipePlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.Recipe
        AlertDialog(
            onDismissRequest = { if (installJob == null && !installState.running) plan = null },
            title = { Text("Install ${currentPlan.familyName}") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version ${currentPlan.requestedVersion}", style = MaterialTheme.typography.bodyMedium)
                    Text("Will install:", style = MaterialTheme.typography.labelMedium)
                    currentPlan.components.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    androidPlan?.let { ap ->
                        Text("${ap.toolchainEntry.abi} · SHA-256 ${ap.toolchainEntry.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(ap.toolchainEntry.provenance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        if (ap.licenseAccepted) {
                            Text("Android SDK license accepted for this revision.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Android SDK License", style = MaterialTheme.typography.labelLarge)
                            Text(ap.license.text, style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256 ${ap.license.sha256}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    localAndroidPlan?.let { ap ->
                        Text("${ap.entry.kind.name.replace('_', ' ')} · SHA-256 ${ap.entry.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(ap.entry.provenance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Ubuntu target: ${com.baystudio.droide.core.LocalAndroidSdkComponentEnvironment.GUEST_SDK_ROOT}/${ap.entry.guestTarget}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        if (ap.licenseAccepted) {
                            Text("Android SDK license accepted for this component install.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Android SDK License", style = MaterialTheme.typography.labelLarge)
                            Text(ap.license.text, style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256 ${ap.license.sha256}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    packagePlan?.entries?.lastOrNull()?.let { entry ->
                        Text("${entry.abi} · SHA-256 ${entry.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(entry.provenance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    recipePlan?.let { rp ->
                        Text("Exact npm recipe: ${rp.recipe.packageName}@${rp.recipe.packageVersion}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text("Source-reviewed install. Integrity and health checks run before activation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(rp.recipe.provenanceUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (installState.running) {
                        Text(installState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        val fraction = androidProviderState.fraction ?: packageProviderState.fraction
                        fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {
                val needsLicense = (androidPlan != null && !androidPlan.licenseAccepted) ||
                    (localAndroidPlan != null && !localAndroidPlan.licenseAccepted)
                Button(
                    enabled = installJob == null && !installState.running,
                    onClick = {
                        if (needsLicense) {
                            installJob = scope.launch {
                                try {
                                    val result = runSuspendCatching {
                                        when {
                                            androidPlan != null -> manager.installer.acceptLicense(androidPlan)
                                            localAndroidPlan != null -> manager.installer.acceptLicense(localAndroidPlan)
                                            else -> error("No Android license plan is active")
                                        }
                                    }
                                    result.onSuccess {
                                        plan = when {
                                            androidPlan != null -> androidPlan.copy(licenseAccepted = true)
                                            localAndroidPlan != null -> localAndroidPlan.copy(licenseAccepted = true)
                                            else -> currentPlan
                                        }
                                    }.onFailure { installMessage = it.message ?: "License acceptance failed" }
                                } finally {
                                    installJob = null
                                }
                            }
                        } else {
                            installJob = scope.launch {
                                try {
                                    val result = runSuspendCatching { manager.unifiedAuthority.installPrepared(currentPlan) }
                                    result.onSuccess { txResult ->
                                        if (txResult.success) {
                                            installMessage = txResult.message
                                            plan = null
                                            onRefresh()
                                        } else {
                                            installMessage = txResult.message
                                        }
                                    }.onFailure { installMessage = it.message ?: "Installation failed" }
                                } finally {
                                    installJob = null
                                }
                            }
                        }
                    },
                ) { Text(if (needsLicense) "Accept license" else "Install") }
            },
            dismissButton = {
                if (installState.running) {
                    TextButton(onClick = { installJob?.cancel() }) { Text("Cancel") }
                } else {
                    TextButton(onClick = { plan = null }) { Text("Close") }
                }
            },
        )
    }

}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
