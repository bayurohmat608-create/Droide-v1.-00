package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.ApkAnalysis
import com.baystudio.droide.core.ApkAnalyzer
import com.baystudio.droide.core.runSuspendCatching
import java.io.File

@Composable
fun ApkAnalyzerSheet(apk: File, onDismiss: () -> Unit) {
    var analysis by remember(apk.absolutePath, apk.lastModified()) { mutableStateOf<ApkAnalysis?>(null) }
    var error by remember(apk.absolutePath, apk.lastModified()) { mutableStateOf<String?>(null) }
    LaunchedEffect(apk.absolutePath, apk.lastModified()) {
        runSuspendCatching { ApkAnalyzer.analyze(apk) }
            .onSuccess { analysis = it }
            .onFailure { error = it.message ?: "APK analysis failed" }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
        DroidePanelHeader(title = "APK Analyzer", subtitle = apk.name, onClose = onDismiss)
        HorizontalDivider()
        when {
            error != null -> Text(error.orEmpty(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            analysis == null -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            else -> ApkAnalysisContent(analysis!!)
        }
    }
}

@Composable
private fun ApkAnalysisContent(result: ApkAnalysis) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyzerMetric("APK size", formatBytes(result.archiveBytes), Modifier.weight(1f))
                AnalyzerMetric("Entries", result.entries.toString(), Modifier.weight(1f))
                AnalyzerMetric("DEX", result.dexFiles.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyzerMetric("Native libs", result.nativeLibraries.toString(), Modifier.weight(1f))
                AnalyzerMetric("Signing files", result.signingEntries.toString(), Modifier.weight(1f))
            }
        }
        item { Text("Package breakdown", style = MaterialTheme.typography.titleMedium) }
        items(result.categories, key = { it.name }) { category ->
            ListItem(
                headlineContent = { Text(category.name) },
                supportingContent = { Text("${category.files} files · ${formatBytes(category.uncompressedBytes)} unpacked") },
                trailingContent = { Text(formatBytes(category.compressedBytes), fontFamily = FontFamily.Monospace) },
            )
        }
        item { HorizontalDivider(); Text("Largest files", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium) }
        items(result.largestEntries, key = { it.path }) { entry ->
            ListItem(
                headlineContent = { FileIdentityLabel(entry.path, entry.path, iconSize = 14.dp) },
                supportingContent = { Text(if (entry.method == ApkAnalyzer.STORED) "Stored" else "Compressed", color = DroideColors.Muted) },
                trailingContent = { Text(formatBytes(entry.uncompressedBytes), fontFamily = FontFamily.Monospace) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AnalyzerMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, tonalElevation = 1.dp, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
