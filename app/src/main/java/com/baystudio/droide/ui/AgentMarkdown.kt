package com.baystudio.droide.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val MAX_STREAMING_TAIL_MARKDOWN_CHARS = 12_000

// Native, bounded Markdown renderer for durable assistant transcript messages.
@Composable
internal fun AgentMarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { AgentMarkdownParser.parse(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        AgentMarkdownBlocks(blocks, streaming = false, keyPrefix = "final")
    }
}


@Composable
internal fun AgentStreamingMarkdownContent(
    markdown: String,
    streamId: String,
    completed: Boolean,
    modifier: Modifier = Modifier,
) {
    val projector = remember(streamId) { AgentStreamingMarkdownProjector() }
    val projection = remember(markdown, completed, projector) { projector.project(markdown, completed) }
    val liveTailBlocks = remember(projection.tail) {
        if (projection.tail.length <= MAX_STREAMING_TAIL_MARKDOWN_CHARS) AgentMarkdownParser.parse(projection.tail) else emptyList()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        AgentMarkdownBlocks(projection.stableBlocks, streaming = false, keyPrefix = "stable")
        if (projection.tail.isNotBlank()) {
            if (liveTailBlocks.isNotEmpty()) {
                AgentMarkdownBlocks(liveTailBlocks, streaming = true, keyPrefix = "tail")
            } else {
                SelectionContainer {
                    Text(
                        projection.tail,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD6DCE2),
                    )
                }
            }
        }
        if (!completed) Text("▍", style = MaterialTheme.typography.bodySmall, color = DroideColors.Primary)
    }
}

@Composable
private fun ColumnScope.AgentMarkdownBlocks(
    blocks: List<AgentMarkdownBlock>,
    streaming: Boolean,
    keyPrefix: String,
) {
    blocks.forEachIndexed { index, block ->
        key(keyPrefix, index, block::class, if (streaming) block.hashCode() else index) {
            when (block) {
                is AgentMarkdownBlock.Paragraph -> AgentMarkdownInlineText(block.text)
                is AgentMarkdownBlock.Heading -> AgentMarkdownHeading(block)
                is AgentMarkdownBlock.Quote -> AgentMarkdownQuote(block.text)
                is AgentMarkdownBlock.ListBlock -> AgentMarkdownList(block.items)
                is AgentMarkdownBlock.Table -> AgentMarkdownTable(block)
                is AgentMarkdownBlock.CodeBlock -> AgentMarkdownCodeBlock(block, streaming = streaming)
                AgentMarkdownBlock.Rule -> HorizontalDivider(color = DroideColors.Border.copy(alpha = .75f))
            }
        }
    }
}

@Composable
private fun AgentMarkdownHeading(block: AgentMarkdownBlock.Heading) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleMedium
        2 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.bodyMedium
    }
    AgentMarkdownInlineText(
        text = block.text,
        style = style.copy(fontWeight = FontWeight.SemiBold, color = DroideColors.Text),
    )
}

@Composable
private fun AgentMarkdownQuote(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(2.dp).heightIn(min = 22.dp).background(DroideColors.BorderStrong))
        Spacer(Modifier.width(8.dp))
        AgentMarkdownInlineText(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.copy(color = DroideColors.Muted),
        )
    }
}

@Composable
private fun AgentMarkdownList(items: List<AgentMarkdownListItem>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                val marker = when (item.task) {
                    true -> "☑"
                    false -> "☐"
                    null -> if (item.ordered) "${item.ordinal ?: 1}." else "•"
                }
                Text(
                    marker,
                    modifier = Modifier.widthIn(min = 22.dp).padding(end = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.task == true) DroideColors.Success else DroideColors.Muted,
                )
                AgentMarkdownInlineText(item.text, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AgentMarkdownTable(block: AgentMarkdownBlock.Table) {
    val scroll = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DroideColors.Surface.copy(alpha = .46f),
        border = BorderStroke(1.dp, DroideColors.Border.copy(alpha = .8f)),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(vertical = 5.dp)) {
            AgentMarkdownTableRow(block.headers, block.aligns, header = true)
            HorizontalDivider(color = DroideColors.Border.copy(alpha = .75f))
            block.rows.forEachIndexed { index, row ->
                AgentMarkdownTableRow(row, block.aligns, header = false)
                if (index != block.rows.lastIndex) HorizontalDivider(color = DroideColors.Border.copy(alpha = .35f))
            }
        }
    }
}

@Composable
private fun AgentMarkdownTableRow(cells: List<String>, aligns: List<AgentMarkdownTableAlign>, header: Boolean) {
    Row(Modifier.width(IntrinsicSize.Max)) {
        cells.forEachIndexed { index, cell ->
            val align = aligns.getOrNull(index) ?: AgentMarkdownTableAlign.START
            val textAlign = when (align) {
                AgentMarkdownTableAlign.START -> TextAlign.Start
                AgentMarkdownTableAlign.CENTER -> TextAlign.Center
                AgentMarkdownTableAlign.END -> TextAlign.End
            }
            Box(
                Modifier.width(148.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = when (align) {
                    AgentMarkdownTableAlign.START -> Alignment.CenterStart
                    AgentMarkdownTableAlign.CENTER -> Alignment.Center
                    AgentMarkdownTableAlign.END -> Alignment.CenterEnd
                },
            ) {
                AgentMarkdownInlineText(
                    text = cell,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (header) DroideColors.Text else Color(0xFFD6DCE2),
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = textAlign,
                    ),
                )
            }
        }
    }
}

@Composable
private fun AgentMarkdownInlineText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFD6DCE2)),
) {
    val annotated = remember(text) { markdownAnnotatedString(text) }
    SelectionContainer(modifier) {
        Text(text = annotated, style = style)
    }
}

internal fun markdownAnnotatedString(text: String): AnnotatedString = buildAnnotatedString {
    AgentMarkdownParser.parseInline(text).forEach { token ->
        val start = length
        append(token.text)
        val end = length
        if (end <= start) return@forEach
        when (token.kind) {
            AgentMarkdownInlineKind.TEXT -> Unit
            AgentMarkdownInlineKind.STRONG -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            AgentMarkdownInlineKind.EMPHASIS -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
            AgentMarkdownInlineKind.STRIKE -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
            AgentMarkdownInlineKind.CODE -> addStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFE6EDF3),
                    background = DroideColors.Surface3,
                ), start, end,
            )
            AgentMarkdownInlineKind.LINK -> {
                if (AgentMarkdownParser.isSafeHttpUrl(token.url)) {
                    addLink(
                        LinkAnnotation.Url(
                            token.url!!,
                            TextLinkStyles(
                                style = SpanStyle(color = DroideColors.Primary, textDecoration = TextDecoration.Underline),
                            ),
                        ),
                        start,
                        end,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentMarkdownCodeBlock(block: AgentMarkdownBlock.CodeBlock, streaming: Boolean = false) {
    val context = LocalContext.current
    val preview = remember(block.code) { compactCodePreview(block.code) }
    val oversized = preview.wasCompacted
    var expanded by remember(block.code) { mutableStateOf(false) }
    val shownCode = if (oversized && !expanded) preview.text else block.code
    val highlighted = remember(shownCode, block.language, streaming) { if (streaming) AnnotatedString(shownCode) else highlightedCode(shownCode, block.language) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DroideColors.Surface2.copy(alpha = .92f),
        border = BorderStroke(1.dp, DroideColors.Border),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 9.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    block.language.ifBlank { "code" }.lowercase(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = DroideColors.Muted,
                    fontFamily = FontFamily.Monospace,
                )
                IconButton(
                    onClick = { copyAgentCode(context, block.code) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, "Copy code", Modifier.size(15.dp), tint = DroideColors.Muted)
                }
            }
            HorizontalDivider(color = DroideColors.Border.copy(alpha = .65f))
            SelectionContainer {
                Text(
                    text = highlighted,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = DroideColors.Text,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (oversized) {
                HorizontalDivider(color = DroideColors.Border.copy(alpha = .45f))
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.Start).heightIn(min = 32.dp),
                ) {
                    Text(if (expanded) "Collapse code" else "Show full code", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private data class CodePreview(val text: String, val wasCompacted: Boolean)

private fun compactCodePreview(code: String): CodePreview {
    if (code.length <= 16_000 && code.count { it == '\n' } <= 160) return CodePreview(code, false)
    var lines = 0
    var cut = 0
    while (cut < code.length && lines < 80 && cut < 12_000) {
        if (code[cut] == '\n') lines++
        cut++
    }
    val preview = code.substring(0, cut).trimEnd() + "\n\n… ${code.length - cut} more characters"
    return CodePreview(preview, true)
}

internal fun highlightedCode(code: String, language: String): AnnotatedString = buildAnnotatedString {
    AgentMarkdownParser.highlightCode(code, language).forEach { token ->
        val start = length
        append(token.text)
        val color = when (token.kind) {
            AgentCodeTokenKind.PLAIN -> DroideColors.Text
            AgentCodeTokenKind.KEYWORD -> DroideColors.Purple
            AgentCodeTokenKind.STRING -> Color(0xFFA5D6A7)
            AgentCodeTokenKind.COMMENT -> DroideColors.Muted
            AgentCodeTokenKind.NUMBER -> Color(0xFFE6B450)
        }
        if (token.kind != AgentCodeTokenKind.PLAIN) addStyle(SpanStyle(color = color), start, length)
    }
}

private fun copyAgentCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Droide code", code))
}
