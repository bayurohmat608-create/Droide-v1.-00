package com.baystudio.droide.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorFocusChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.widget.CodeEditor

internal fun CodeEditor.setSelectionOffsets(startOffset: Int, endOffset: Int = startOffset) {
    val safeStart = startOffset.coerceIn(0, text.length)
    val safeEnd = endOffset.coerceIn(safeStart, text.length)
    val start = text.indexer.getCharPosition(safeStart)
    val end = text.indexer.getCharPosition(safeEnd)
    if (safeStart == safeEnd) setSelection(start.line, start.column)
    else setSelectionRegion(start.line, start.column, end.line, end.column)
}


internal fun CodeEditor.replaceAllBounded(query: String, replacement: String, maxMatches: Int = 50_000): Int? {
    if (query.isEmpty()) return 0
    val source: CharSequence = text
    val matches = ArrayList<Int>()
    var from = 0
    while (from <= source.length - query.length) {
        val idx = source.indexOf(query, from)
        if (idx < 0) break
        if (matches.size >= maxMatches) return null
        matches += idx
        from = idx + query.length
    }
    for (idx in matches.asReversed()) {
        setSelectionOffsets(idx, idx + query.length)
        commitText(replacement, false)
    }
    return matches.size
}

internal fun countOccurrences(text: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var from = 0
    while (true) {
        val i = text.indexOf(needle, from)
        if (i < 0) return count
        count++
        from = i + needle.length
    }
}

internal class EditorEventSubscriptions {
    var content: SubscriptionReceipt<ContentChangeEvent>? = null
    var selection: SubscriptionReceipt<SelectionChangeEvent>? = null
    var focus: SubscriptionReceipt<EditorFocusChangeEvent>? = null
    fun clear() {
        content?.unsubscribe(); selection?.unsubscribe(); focus?.unsubscribe()
        content = null; selection = null; focus = null
    }
}

internal fun EditorDocument.requireFullIntelligence(feature: String): Boolean {
    if (fullIntelligence) return true
    status = "$feature is paused in Large File Performance Mode"
    return false
}

internal fun editorCursorLineColumn(editor: CodeEditor?, document: EditorDocument): Pair<Int, Int> {
    val cursor = editor?.cursor
    if (cursor != null) return (cursor.leftLine + 1) to (cursor.leftColumn + 1)
    val offset = document.selectionStart.coerceIn(0, document.content.length)
    var line = 1
    var lastNewline = -1
    for (i in 0 until offset) if (document.content[i] == '\n') { line++; lastNewline = i }
    return line to (offset - lastNewline)
}

@Composable
internal fun EditorLoadError(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Could not open file", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry) { Text("Retry") }
    }
}
