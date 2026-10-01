package com.baystudio.droide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class MarkdownPreviewMode { EDITOR, PREVIEW, SPLIT }

internal fun isMarkdownPreviewPath(path: String): Boolean {
    val lower = path.substringAfterLast('/').lowercase()
    return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".mdown") || lower.endsWith(".mkd")
}

// Keep this path fail-closed at the trust boundary.


@Composable
internal fun MarkdownFilePreview(
    document: EditorDocument,
    modifier: Modifier = Modifier,
) {
    var blocks by remember(document.path) { mutableStateOf<List<AgentMarkdownBlock>>(emptyList()) }
    var parsedVersion by remember(document.path) { mutableIntStateOf(-1) }

    LaunchedEffect(document.loaded, document.changeVersion, document.path, document.performanceMode) {
        if (!document.loaded || document.kind != EditorDocumentKind.TEXT || document.largeFileOptimized) {
            blocks = emptyList()
            parsedVersion = document.changeVersion
            return@LaunchedEffect
        }
        val snapshot = document.content
        // collectLatest semantics through LaunchedEffect cancellation: rapid typing never queues parses.
        delay(90)
        blocks = withContext(Dispatchers.Default) { AgentMarkdownParser.parse(snapshot) }
        parsedVersion = document.changeVersion
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        when {
            !document.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            document.kind != EditorDocumentKind.TEXT -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Markdown preview is available for text documents only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            document.largeFileOptimized -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Preview paused in Large File Performance Mode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Markdown preview is paused for this large file.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            else -> {
                val stale = parsedVersion != document.changeVersion
                Column(Modifier.fillMaxSize()) {
                    if (stale) LinearProgressIndicator(Modifier.fillMaxWidth().height(1.dp))
                    Column(
                        Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (blocks.isEmpty() && document.content.isBlank()) {
                            Text("Nothing to preview", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            blocks.forEachIndexed { index, block ->
                                key(index) { MarkdownPreviewBlock(block) }
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownPreviewBlock(block: AgentMarkdownBlock) {
    when (block) {
        is AgentMarkdownBlock.Paragraph -> MarkdownPreviewInline(block.text)
        is AgentMarkdownBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            MarkdownPreviewInline(block.text, style = style)
        }
        is AgentMarkdownBlock.Quote -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
            tonalElevation = 0.dp,
            shape = MaterialTheme.shapes.small,
        ) {
            MarkdownPreviewInline(
                block.text,
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        is AgentMarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    val marker = when (item.task) {
                        true -> "☑"
                        false -> "☐"
                        null -> if (item.ordered) "${item.ordinal ?: 1}." else "•"
                    }
                    Text(
                        marker,
                        Modifier.widthIn(min = 26.dp).padding(end = 7.dp),
                        color = if (item.task == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MarkdownPreviewInline(item.text, Modifier.weight(1f))
                }
            }
        }
        is AgentMarkdownBlock.Table -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .28f),
            shape = MaterialTheme.shapes.small,
        ) {
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 5.dp)) {
                fun align(index: Int): TextAlign = when (block.aligns.getOrNull(index) ?: AgentMarkdownTableAlign.START) {
                    AgentMarkdownTableAlign.START -> TextAlign.Start
                    AgentMarkdownTableAlign.CENTER -> TextAlign.Center
                    AgentMarkdownTableAlign.END -> TextAlign.End
                }
                @Composable fun tableRow(cells: List<String>, header: Boolean) {
                    Row {
                        cells.forEachIndexed { index, cell ->
                            MarkdownPreviewInline(
                                cell,
                                Modifier.width(160.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                                    textAlign = align(index),
                                ),
                            )
                        }
                    }
                }
                tableRow(block.headers, true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                block.rows.forEachIndexed { index, row ->
                    tableRow(row, false)
                    if (index != block.rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                }
            }
        }
        is AgentMarkdownBlock.CodeBlock -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .58f),
            shape = MaterialTheme.shapes.small,
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (block.language.isNotBlank()) {
                    Text(
                        block.language.lowercase(),
                        Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                SelectionContainer {
                    Text(
                        highlightedCode(block.code, block.language),
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        AgentMarkdownBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun MarkdownPreviewInline(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
) {
    val annotated = remember(text) { markdownAnnotatedString(text) }
    SelectionContainer(modifier) {
        Text(annotated, style = style)
    }
}
