package com.baystudio.droide.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.DeviceBridgeManager
import com.baystudio.droide.core.LocalLlamaModelCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch








@Composable
fun LocalModelsSettingsSection(deviceBridge: DeviceBridgeManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coordinator = remember(context, deviceBridge) { LocalLlamaModelCoordinator(context, deviceBridge) }
    val state by coordinator.state.collectAsState()
    var operationJob by remember { mutableStateOf<Job?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && operationJob?.isActive != true) {
            operationJob = scope.launch {
                try {
                    coordinator.import(uri)
                } finally {
                    operationJob = null
                }
            }
        }
    }

    LaunchedEffect(coordinator) { coordinator.refresh() }

    Text("Local models", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(
        "Import GGUF files, then certify them with a short device decode.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { importer.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*")) },
            enabled = !state.loading && state.operation == null && operationJob?.isActive != true,
        ) {
            Icon(Icons.Default.Memory, contentDescription = null)
            Text("Import GGUF", Modifier.padding(start = 6.dp))
        }
        TextButton(
            onClick = { operationJob = scope.launch { try { coordinator.refresh() } finally { operationJob = null } } },
            enabled = !state.loading && state.operation == null && operationJob?.isActive != true,
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Text("Refresh", Modifier.padding(start = 4.dp))
        }
    }

    state.operation?.let { operation ->
        Spacer(Modifier.height(8.dp))
        Text(operation.phase, style = MaterialTheme.typography.labelMedium)
        val fraction = operation.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Text(
                "${LocalLlamaModelCoordinator.formatBytes(operation.completedBytes)} / ${LocalLlamaModelCoordinator.formatBytes(operation.totalBytes ?: 0L)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        TextButton(onClick = { operationJob?.cancel() }) { Text("Cancel") }
    }

    state.message?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.failedModelId == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }

    if (state.loading && state.models.isEmpty()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 10.dp))
            Text("Checking local models…", style = MaterialTheme.typography.bodySmall)
        }
    } else if (state.models.isEmpty()) {
        Text(
            "No local models yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    } else {
        Spacer(Modifier.height(8.dp))
        state.models.forEach { model ->
            LocalModelRow(
                model = model,
                busy = state.operation != null || operationJob?.isActive == true,
                onCertify = {
                    operationJob = scope.launch {
                        try {
                            coordinator.certify(model.id)
                        } finally {
                            operationJob = null
                        }
                    }
                },
            )
        }
    }

    Text(
        "Agent use requires a certified model and managed local server.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun LocalModelRow(
    model: LocalLlamaModelCoordinator.Model,
    busy: Boolean,
    onCertify: () -> Unit,
) {
    val status = when (model.availability) {
        LocalLlamaModelCoordinator.Availability.CERTIFIED -> model.certifiedTarget?.let { "Certified · $it" } ?: "Certified"
        LocalLlamaModelCoordinator.Availability.REJECTED -> "Certification failed"
        LocalLlamaModelCoordinator.Availability.IMPORTED -> "Needs certification"
    }
    ListItem(
        headlineContent = {
            Text(model.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text("${LocalLlamaModelCoordinator.formatBytes(model.sizeBytes)} · GGUF v${model.ggufVersion} · ${model.id.take(12)}")
                Text(status)
            }
        },
        trailingContent = {
            when (model.availability) {
                LocalLlamaModelCoordinator.Availability.CERTIFIED -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("Certified") },
                    leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                )
                LocalLlamaModelCoordinator.Availability.IMPORTED,
                LocalLlamaModelCoordinator.Availability.REJECTED -> OutlinedButton(
                    onClick = onCertify,
                    enabled = !busy,
                ) {
                    Text(if (model.availability == LocalLlamaModelCoordinator.Availability.REJECTED) "Retry" else "Certify")
                }
            }
        },
    )
    HorizontalDivider()
}
