package com.baystudio.droide.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.ModelCapabilityRegistry
import com.baystudio.droide.core.ModelsDevCatalog
import com.baystudio.droide.core.ProviderConnectionBackend
import com.baystudio.droide.core.ProviderRegistry

 
@Composable
fun ModelPickerSheet(
    providerId: String,
    connectionBackend: ProviderConnectionBackend,
    onPick: (model: String) -> Unit,
    onConnectProvider: () -> Unit,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val backendRevision by connectionBackend.revision.collectAsState()
    val catalogRevision by ModelsDevCatalog.revision.collectAsState()
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val provider = remember(providerId, catalogRevision) { ProviderRegistry.findById(providerId) }
    var models by remember(providerId) { mutableStateOf<List<String>>(emptyList()) }
    var query by rememberSaveable(providerId) { mutableStateOf("") }
    var loading by remember(providerId) { mutableStateOf(true) }
    var error by remember(providerId) { mutableStateOf<String?>(null) }
    var retryNonce by remember(providerId) { mutableIntStateOf(0) }
    val snapshot = remember(providerId, backendRevision) { connectionBackend.snapshot(providerId) }
    val connected = provider != null && snapshot.status == ProviderConnectionBackend.Status.CONNECTED

    LaunchedEffect(providerId, catalogRevision, connected, retryNonce) {
        loading = connected
        error = null
        models = emptyList()
        val selectedProvider = provider
        if (selectedProvider == null) {
            loading = false
            error = "This provider is no longer available in the current catalog/workspace."
            return@LaunchedEffect
        }
        if (!connected) {
            loading = false
            error = "Connect ${selectedProvider.name} first."
            return@LaunchedEffect
        }
        val result = connectionBackend.revalidate(providerId)
        loading = false
        result.onSuccess {
            models = it.models.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        }.onFailure {
            error = it.message ?: "Could not load models."
        }
    }

    val visibleModels = remember(models, query, catalogRevision, providerId) {
        models.asSequence().filter { modelMatchesProviderScoped(providerId, it, query) }.toList()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .heightIn(max = configuration.screenHeightDp.dp * if (landscape) .82f else .88f)
            .padding(horizontal = 14.dp, vertical = if (landscape) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Models", style = MaterialTheme.typography.titleMedium)
                    provider?.let { ProviderIdentityLabel(it.id, it.name, iconSize = 20.dp) }
                }
                Text("Live models from the validated provider connection", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close model picker") }
        }

        if (connected) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(160) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search ${provider?.name ?: providerId} models…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                supportingText = { Text("Search by ID, display name, vision, tools, or reasoning") },
            )
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message ->
            Surface(color = DroideColors.Warning.copy(alpha = .08f), shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Warning)
                    if (provider != null && !connected) TextButton(onClick = onConnectProvider) { Text("Connect") }
                    else if (provider != null) TextButton(onClick = { retryNonce++ }) { Text("Retry") }
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = connected), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(visibleModels, key = { it }) { model ->
                val capability = ModelCapabilityRegistry.resolve(providerId, model)
                ListItem(
                    headlineContent = { ModelIdentityLabel(providerId, model, iconSize = 20.dp) },
                    supportingContent = {
                        val display = capability?.displayName?.takeIf { it.isNotBlank() && !it.equals(model, true) }
                        val summary = capabilitySummaryProviderScoped(providerId, model)
                        val text = listOfNotNull(display, summary).joinToString(" · ")
                        if (text.isNotBlank()) {
                            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                        }
                    },
                    modifier = Modifier.clickable { onPick(model); onDismiss() },
                )
                HorizontalDivider(color = DroideColors.Border.copy(alpha = .6f))
            }
        }
        if (connected && !loading && visibleModels.isEmpty() && error == null) {
            Text("No matching model returned by this provider.", style = MaterialTheme.typography.bodySmall, color = DroideColors.Muted)
        }
    }
}

private fun modelMatchesProviderScoped(providerId: String, modelId: String, query: String): Boolean {
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
        }
    }
    return tokens.all(searchable::contains)
}

private fun capabilitySummaryProviderScoped(providerId: String, modelId: String): String? {
    val capability = ModelCapabilityRegistry.resolve(providerId, modelId) ?: return null
    val parts = ArrayList<String>(4)
    if (capability.attachment == true || "image" in capability.inputModalities) parts += "Vision"
    if (capability.toolCall == true) parts += "Tools"
    if (capability.reasoning == true) parts += "Reasoning"
    capability.contextTokens?.takeIf { it > 0 }?.let { tokens ->
        parts += when {
            tokens >= 1_000_000 -> "${tokens / 1_000_000}m ctx"
            tokens >= 1_000 -> "${tokens / 1_000}k ctx"
            else -> "$tokens ctx"
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
