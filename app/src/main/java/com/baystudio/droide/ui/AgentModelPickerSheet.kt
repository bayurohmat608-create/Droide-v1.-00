package com.baystudio.droide.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.AiProvider
import com.baystudio.droide.core.LocalLlamaAgentModelController
import com.baystudio.droide.core.LocalLlamaServerPolicy
import com.baystudio.droide.core.ModelCapabilityRegistry
import com.baystudio.droide.core.ModelsDevCatalog
import com.baystudio.droide.core.ProviderConnectionBackend
import com.baystudio.droide.core.ProviderNetworkScope
import com.baystudio.droide.core.ProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val PICKER_QUERY_MAX_CHARS = 256









@Composable
fun AgentModelPickerMenu(
    expanded: Boolean,
    currentProviderId: String,
    currentModel: String,
    connectionBackend: ProviderConnectionBackend,
    localModels: LocalLlamaAgentModelController,
    onPick: (providerId: String, model: String) -> Unit,
    onConnectProvider: (providerId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val backendRevision by connectionBackend.revision.collectAsState()
    val catalogRevision by ModelsDevCatalog.revision.collectAsState()
    val localState by localModels.state.collectAsState()
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var selectedProviderId by remember(currentProviderId) { mutableStateOf(currentProviderId) }
    var browsingProviders by remember(expanded) { mutableStateOf(false) }
    var browsingLocal by remember(expanded, currentProviderId) {
        mutableStateOf(currentProviderId.equals(LocalLlamaServerPolicy.PROVIDER_ID, true))
    }
    var providerQuery by remember(expanded) { mutableStateOf("") }
    var query by remember(expanded) { mutableStateOf("") }
    var localQuery by remember(expanded) { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    val modelCache = remember { mutableStateMapOf<String, List<String>>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryNonce by remember { mutableIntStateOf(0) }
    var localJob by remember { mutableStateOf<Job?>(null) }

    val allProviders = remember(catalogRevision) { ProviderRegistry.all.filterNot { it.id == LocalLlamaServerPolicy.PROVIDER_ID } }
    val connectedProviders = remember(catalogRevision, backendRevision) {
        allProviders.associate { provider ->
            provider.id to (connectionBackend.snapshot(provider.id).status == ProviderConnectionBackend.Status.CONNECTED)
        }
    }
    val selectedProvider = allProviders.firstOrNull { it.id.equals(selectedProviderId, true) }
        ?: allProviders.firstOrNull { it.id.equals(currentProviderId, true) }
        ?: allProviders.firstOrNull() ?: ProviderRegistry.all.firstOrNull() ?: ProviderRegistry.byId(currentProviderId)
    val visibleProviders = remember(allProviders, providerQuery, connectedProviders, currentProviderId) {
        filterProviders(allProviders, providerQuery, currentProviderId, connectedProviders)
    }
    val visibleModels = remember(models, query, catalogRevision, selectedProvider.id) {
        models.asSequence().filter { modelMatches(selectedProvider.id, it, query) }.toList()
    }
    val visibleLocalModels = remember(localState.models, localQuery) {
        val needle = localQuery.trim().lowercase()
        localState.models.filter { model ->
            needle.isBlank() || model.displayName.lowercase().contains(needle) || model.id.contains(needle) || model.target.lowercase().contains(needle)
        }
    }

    LaunchedEffect(expanded) {
        if (expanded) localModels.refresh()
    }
    DisposableEffect(expanded) {
        onDispose { localJob?.cancel() }
    }
    LaunchedEffect(expanded, catalogRevision) {
        if (!expanded || browsingLocal) return@LaunchedEffect
        if (allProviders.none { it.id.equals(selectedProviderId, true) }) {
            selectedProviderId = allProviders.firstOrNull { it.id.equals(currentProviderId, true) }?.id
                ?: allProviders.firstOrNull()?.id ?: ProviderRegistry.byId(currentProviderId).id
        }
    }

    LaunchedEffect(expanded, browsingLocal, selectedProvider.id, connectedProviders[selectedProvider.id], retryNonce) {
        if (!expanded || browsingLocal) return@LaunchedEffect
        query = ""
        error = null
        models = modelCache[selectedProvider.id].orEmpty()
        if (connectionBackend.snapshot(selectedProvider.id).status != ProviderConnectionBackend.Status.CONNECTED) {
            loading = false
            models = emptyList()
            error = "${selectedProvider.name} is not connected yet."
            return@LaunchedEffect
        }
        loading = true
        val result = connectionBackend.revalidate(selectedProvider.id)
        loading = false
        result.onSuccess {
            val live = it.models.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
            modelCache[selectedProvider.id] = live
            models = live
        }.onFailure {
            error = it.message ?: "Could not load models from ${selectedProvider.name}."
        }
    }

    val menuMaxWidth = if (landscape && configuration.screenWidthDp >= 600) 430.dp else 326.dp
    val menuWidth = minOf(menuMaxWidth, (configuration.screenWidthDp.dp - 24.dp).coerceAtLeast(260.dp))
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = {
            localJob?.cancel()
            onDismiss()
        },
        modifier = Modifier.width(menuWidth).heightIn(max = if (landscape) 310.dp else 370.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CHOOSE MODEL", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            TextButton(
                onClick = {
                    browsingLocal = !browsingLocal
                    browsingProviders = false
                    localQuery = ""
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                ProviderBrandMark(LocalLlamaServerPolicy.PROVIDER_ID, null, 14.dp)
                Spacer(Modifier.width(4.dp))
                Text(if (browsingLocal) "Cloud" else "Local ${localState.models.size}", style = MaterialTheme.typography.labelSmall)
            }
            if (!browsingLocal) {
                TextButton(
                    onClick = {
                        browsingProviders = !browsingProviders
                        providerQuery = ""
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    ProviderIdentityLabel(selectedProvider.id, selectedProvider.name, iconSize = 15.dp)
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Default.ArrowDropDown, "Choose provider", Modifier.size(15.dp))
                }
            }
        }

        if (browsingLocal) {
            OutlinedTextField(
                value = localQuery,
                onValueChange = { localQuery = it.take(PICKER_QUERY_MAX_CHARS) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp),
                placeholder = { Text("Search certified local GGUF…") },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(17.dp)) },
                singleLine = true,
                enabled = !localState.busy,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            if (localState.busy) {
                localState.progress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth().padding(horizontal = 7.dp)) }
                    ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 7.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(localState.message ?: "Preparing local llama.cpp", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 2)
                    TextButton(onClick = { localJob?.cancel() }) { Text("Cancel") }
                }
            } else if (localState.message != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(localState.message.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = if (localState.phase == LocalLlamaAgentModelController.Phase.FAILED) DroideColors.Warning else DroideColors.Muted, maxLines = 2)
                    IconButton(onClick = { scope.launch { localModels.refresh() } }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Refresh, "Refresh certified local models", Modifier.size(16.dp))
                    }
                }
            }
            if (!localState.busy && visibleLocalModels.isEmpty()) {
                Text(
                    "No certified local model. Import one in Settings → Providers & Models.",
                    Modifier.padding(10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = DroideColors.Muted,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (landscape) 190.dp else 245.dp)) {
                items(visibleLocalModels,  ) { local ->
                    val selected = currentProviderId == LocalLlamaServerPolicy.PROVIDER_ID && currentModel == local.id
                    DropdownMenuItem(
                        enabled = !localState.busy,
                        text = {
                            Column(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ProviderBrandMark(LocalLlamaServerPolicy.PROVIDER_ID, null, 16.dp, foreground = DroideColors.Primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(local.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    "${formatLocalModelBytes(local.sizeBytes)} · ${local.target} · ${local.id.take(12)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DroideColors.Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        trailingIcon = if (selected) {{ Icon(Icons.Default.Check, "Selected local model", tint = DroideColors.Primary) }} else null,
                        onClick = {
                            if (localJob?.isActive == true) return@DropdownMenuItem
                            localJob = scope.launch {
                                try {
                                    localModels.selectLocal(local.id)
                                    onDismiss()
                                } catch (_: CancellationException) {
                                    // 08ay owns rollback/cleanup; keep the picker open after cancellation.
                                } catch (_: Throwable) {
                                    
                                }
                            }
                        },
                    )
                }
            }
        } else if (browsingProviders) {
            OutlinedTextField(
                value = providerQuery,
                onValueChange = { providerQuery = it.take(PICKER_QUERY_MAX_CHARS) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp),
                placeholder = { Text("Search providers…") },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(17.dp)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (landscape) 185.dp else 235.dp)) {
                items(visibleProviders,  ) { provider ->
                    val connected = connectedProviders[provider.id] == true
                    DropdownMenuItem(
                        text = {
                            Column(Modifier.fillMaxWidth()) {
                                ProviderIdentityLabel(provider.id, provider.name, iconSize = 16.dp)
                                Text(
                                    providerStatusLabel(provider, connected),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (connected) DroideColors.Success else DroideColors.Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        trailingIcon = when {
                            provider.id == selectedProvider.id -> {{ Icon(Icons.Default.Check, "Selected provider", tint = DroideColors.Primary) }}
                            !connected -> {{ Icon(Icons.Default.Lock, "Not connected", Modifier.size(14.dp), tint = DroideColors.Muted) }}
                            else -> null
                        },
                        onClick = {
                            selectedProviderId = provider.id
                            browsingProviders = false
                            providerQuery = ""
                        },
                    )
                }
            }
            if (visibleProviders.isEmpty()) {
                Text("No matching provider.", Modifier.padding(10.dp), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(PICKER_QUERY_MAX_CHARS) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp),
                placeholder = { Text("Search models…") },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(17.dp)) },
                singleLine = true,
                enabled = connectedProviders[selectedProvider.id] == true,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 7.dp))
            error?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Warning)
                    if (connectedProviders[selectedProvider.id] != true) {
                        TextButton(onClick = { onConnectProvider(selectedProvider.id) }) { Text("Connect") }
                    } else {
                        TextButton(onClick = { retryNonce++ }) { Text("Retry") }
                    }
                }
            }
            if (!loading && error == null && visibleModels.isEmpty()) {
                Text("No matching model returned by this provider.", Modifier.padding(10.dp), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (landscape) 175.dp else 225.dp)) {
                items(visibleModels,  ) { model ->
                    val selected = selectedProvider.id == currentProviderId && model == currentModel
                    DropdownMenuItem(
                        text = { ModelPickerRow(selectedProvider, model) },
                        trailingIcon = if (selected) {{ Icon(Icons.Default.Check, "Selected model", tint = DroideColors.Primary) }} else null,
                        onClick = { onPick(selectedProvider.id, model); onDismiss() },
                    )
                }
            }
        }

        HorizontalDivider(color = DroideColors.Border)
        if (browsingLocal) {
            DropdownMenuItem(
                text = { Text("Refresh certified local models") },
                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                enabled = !localState.busy,
                onClick = { scope.launch { localModels.refresh() } },
            )
        } else {
            DropdownMenuItem(
                text = { Text("Connect Provider") },
                leadingIcon = { Icon(Icons.Default.AddLink, null) },
                onClick = { onConnectProvider(selectedProvider.id) },
            )
        }
    }
}

private fun formatLocalModelBytes(bytes: Long): String {
    val mib = bytes / (1024.0 * 1024.0)
    return if (mib < 1024.0) java.lang.String.format(java.util.Locale.US, "%.0f MiB", mib)
    else java.lang.String.format(java.util.Locale.US, "%.1f GiB", mib / 1024.0)
}

@Composable
fun AgentModelPickerSheet(
    currentProviderId: String,
    currentModel: String,
    connectionBackend: ProviderConnectionBackend,
    onPick: (providerId: String, model: String) -> Unit,
    onConnectProvider: (providerId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val backendRevision by connectionBackend.revision.collectAsState()
    val catalogRevision by ModelsDevCatalog.revision.collectAsState()
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val twoPane = landscape && configuration.screenWidthDp >= 600

    var selectedProviderId by rememberSaveable(currentProviderId) { mutableStateOf(currentProviderId) }
    var providerQuery by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    val modelCache = remember { mutableStateMapOf<String, List<String>>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryNonce by remember { mutableIntStateOf(0) }

    val allProviders = remember(catalogRevision) { ProviderRegistry.all }
    val connectedProviders = remember(catalogRevision, backendRevision) {
        allProviders.associate { provider ->
            provider.id to (connectionBackend.snapshot(provider.id).status == ProviderConnectionBackend.Status.CONNECTED)
        }
    }
    val selectedProvider = allProviders.firstOrNull { it.id.equals(selectedProviderId, true) }
        ?: allProviders.firstOrNull { it.id.equals(currentProviderId, true) }
        ?: allProviders.firstOrNull() ?: ProviderRegistry.byId(currentProviderId)
    val visibleProviders = remember(allProviders, providerQuery, connectedProviders, currentProviderId) {
        filterProviders(allProviders, providerQuery, currentProviderId, connectedProviders)
    }
    val visibleModels = remember(models, query, catalogRevision, selectedProvider.id) {
        models.asSequence().filter { modelMatches(selectedProvider.id, it, query) }.toList()
    }

    LaunchedEffect(catalogRevision) {
        if (allProviders.none { it.id.equals(selectedProviderId, true) }) {
            selectedProviderId = allProviders.firstOrNull { it.id.equals(currentProviderId, true) }?.id
                ?: allProviders.firstOrNull()?.id ?: ProviderRegistry.byId(currentProviderId).id
        }
    }

    LaunchedEffect(selectedProvider.id, connectedProviders[selectedProvider.id], retryNonce) {
        query = ""
        error = null
        models = modelCache[selectedProvider.id].orEmpty()
        if (connectionBackend.snapshot(selectedProvider.id).status != ProviderConnectionBackend.Status.CONNECTED) {
            loading = false
            models = emptyList()
            error = "${selectedProvider.name} is not connected yet."
            return@LaunchedEffect
        }
        loading = true
        val result = connectionBackend.revalidate(selectedProvider.id)
        loading = false
        result.onSuccess {
            val live = it.models.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
            modelCache[selectedProvider.id] = live
            models = live
        }.onFailure {
            error = it.message ?: "Could not load models from ${selectedProvider.name}."
        }
    }

    val providerSelector: @Composable ColumnScope.() -> Unit = {
        OutlinedTextField(
            value = providerQuery,
            onValueChange = { providerQuery = it.take(PICKER_QUERY_MAX_CHARS) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search provider…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(
            Modifier.fillMaxWidth().then(if (twoPane) Modifier.weight(1f) else Modifier.heightIn(max = 180.dp)),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(visibleProviders,  ) { provider ->
                val connected = connectedProviders[provider.id] == true
                ListItem(
                    headlineContent = { ProviderIdentityLabel(provider.id, provider.name, iconSize = 17.dp) },
                    supportingContent = {
                        Text(
                            providerStatusLabel(provider, connected),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connected) DroideColors.Success else DroideColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        when {
                            selectedProvider.id == provider.id -> Icon(Icons.Default.Check, "Selected provider", tint = DroideColors.Primary)
                            !connected -> Icon(Icons.Default.Lock, "Not connected", Modifier.size(14.dp), tint = DroideColors.Muted)
                        }
                    },
                    modifier = Modifier.clickable { selectedProviderId = provider.id },
                )
                HorizontalDivider(color = DroideColors.Border.copy(alpha = .6f))
            }
        }
        if (visibleProviders.isEmpty()) {
            Text("No matching provider.", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
        }
    }

    val modelPane: @Composable ColumnScope.() -> Unit = {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(PICKER_QUERY_MAX_CHARS) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search ${selectedProvider.name} models…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            enabled = connectedProviders[selectedProvider.id] == true,
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message ->
            Surface(color = DroideColors.Warning.copy(alpha = .08f), shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Warning)
                    if (connectedProviders[selectedProvider.id] != true) {
                        TextButton(onClick = { onConnectProvider(selectedProvider.id) }) { Text("Connect") }
                    } else {
                        TextButton(onClick = { retryNonce++ }) { Text("Retry") }
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = true)) {
            items(visibleModels,  ) { model ->
                val selected = selectedProvider.id == currentProviderId && model == currentModel
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(selectedProvider.id, model); onDismiss() }.padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModelPickerRow(selectedProvider, model, Modifier.weight(1f))
                    if (selected) Icon(Icons.Default.Check, "Selected model", tint = DroideColors.Primary)
                }
                HorizontalDivider(color = DroideColors.Border.copy(alpha = .6f))
            }
        }
        if (!loading && error == null && visibleModels.isEmpty()) {
            Text("No matching model returned by this provider.", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
        }
    }

    Column(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .heightIn(max = configuration.screenHeightDp.dp * if (landscape) .84f else .88f)
            .padding(horizontal = 14.dp, vertical = if (landscape) 8.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Choose model", style = MaterialTheme.typography.titleMedium)
                Text("Live models from connected providers", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
            OutlinedButton(onClick = { onConnectProvider(selectedProvider.id) }) {
                Icon(Icons.Default.AddLink, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Connect Provider")
            }
        }
        if (twoPane) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.widthIn(min = 190.dp, max = 250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp), content = providerSelector)
                VerticalDivider(color = DroideColors.Border)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp), content = modelPane)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), content = providerSelector)
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = modelPane)
        }
        TextButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Close") }
    }
}

@Composable
private fun ModelPickerRow(provider: AiProvider, model: String, modifier: Modifier = Modifier) {
    val capability = ModelCapabilityRegistry.resolve(provider.id, model)
    Column(modifier) {
        ModelIdentityLabel(provider.id, model, iconSize = 20.dp)
        val displayName = capability?.displayName?.takeIf { it.isNotBlank() && !it.equals(model, true) }
        val summary = capabilitySummary(provider.id, model)
        when {
            displayName != null && summary != null -> Text(
                "$displayName · $summary",
                style = MaterialTheme.typography.labelSmall,
                color = DroideColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            displayName != null -> Text(displayName, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            summary != null -> Text(summary, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> ProviderIdentityLabel(provider.id, provider.name, iconSize = 12.dp, color = DroideColors.Muted)
        }
    }
}

private fun filterProviders(
    providers: List<AiProvider>,
    query: String,
    currentProviderId: String,
    connected: Map<String, Boolean>,
): List<AiProvider> {
    val needle = query.trim()
    return providers.asSequence()
        .filter { provider ->
            needle.isBlank() || provider.name.contains(needle, true) || provider.id.contains(needle, true) ||
                provider.catalogPackage?.contains(needle, true) == true
        }
        .sortedWith(
            compareByDescending<AiProvider> { it.id.equals(currentProviderId, true) }
                .thenByDescending { connected[it.id] == true }
                .thenBy { it.name.lowercase() }
                .thenBy { it.id.lowercase() },
        )
        .toList()
}

private fun modelMatches(providerId: String, modelId: String, query: String): Boolean {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
    if (tokens.isEmpty()) return true
    val capability = ModelCapabilityRegistry.resolve(providerId, modelId)
    val searchable = buildString {
        append(modelId.lowercase())
        capability?.let { model ->
            append(' '); append(model.displayName.lowercase())
            if (model.reasoning == true) append(" reasoning")
            if (model.toolCall == true) append(" tools tool-call")
            if (model.attachment == true || "image" in model.inputModalities) append(" vision image")
            if (model.structuredOutput == true) append(" structured-output json")
            model.contextTokens?.let { append(" context "); append(it) }
        }
    }
    return tokens.all(searchable::contains)
}

private fun capabilitySummary(providerId: String, modelId: String): String? {
    val capability = ModelCapabilityRegistry.resolve(providerId, modelId) ?: return null
    val parts = ArrayList<String>(4)
    if (capability.attachment == true || "image" in capability.inputModalities) parts += "Vision"
    if (capability.toolCall == true) parts += "Tools"
    if (capability.reasoning == true) parts += "Reasoning"
    capability.contextTokens?.takeIf { it > 0 }?.let { parts += "${formatContextTokens(it)} ctx" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatContextTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}m"
    tokens >= 1_000 -> "${tokens / 1_000}k"
    else -> tokens.toString()
}

private fun providerStatusLabel(provider: AiProvider, connected: Boolean): String = when {
    connected -> "Connected"
    provider.networkScope == ProviderNetworkScope.LOOPBACK_ONLY -> "Local provider · not connected"
    provider.catalogDiscovered -> "Models.dev · not connected"
    else -> "Not connected"
}
