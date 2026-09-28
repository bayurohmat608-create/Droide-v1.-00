package com.baystudio.droide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.LspDiagnostic
import com.baystudio.droide.core.LspInlayHint
import com.baystudio.droide.core.LspManager
import com.baystudio.droide.core.runSuspendCatching
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.lang.styling.inlayHint.InlayHintsContainer
import io.github.rosemoe.sora.lang.styling.inlayHint.TextInlayHint
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.delay


@Composable
internal fun BindDroideSoraFeatures(
    editor: DroideCodeEditor?,
    document: EditorDocument,
    lsp: LspManager,
    files: FileRepository,
    path: String,
) {
    val diagnosticsByUri by lsp.diagnostics.collectAsState()
    val localUri = remember(files, path) {
        runCatching { files.resolveChecked(path).canonicalFile.toURI().toString() }.getOrNull()
    }
    val activeDiagnostics = remember(diagnosticsByUri, localUri) {
        localUri?.let { diagnosticsByUri[it] }.orEmpty()
    }
    LaunchedEffect(editor, activeDiagnostics, document.loaded, document.kind, document.performanceMode) {
        val target = editor ?: return@LaunchedEffect
        if (!document.loaded || document.kind != EditorDocumentKind.TEXT || document.largeFileOptimized) {
            target.applyDroideDiagnostics(emptyList())
        } else {
            target.applyDroideDiagnostics(activeDiagnostics)
        }
    }
    LaunchedEffect(editor, document.changeVersion, document.loaded, document.kind, document.performanceMode, lsp, path) {
        val target = editor ?: return@LaunchedEffect
        if (!document.fullIntelligence || !document.loaded || document.kind != EditorDocumentKind.TEXT || document.largeFileOptimized) {
            target.applyDroideInlayHints(emptyList())
            return@LaunchedEffect
        }
        delay(400)
        val hints = runSuspendCatching { lsp.inlayHints(path, document.content) }.getOrDefault(emptyList())
        if (editor === target) target.applyDroideInlayHints(hints)
    }
}

 
internal fun CodeEditor.configureDroideProfessionalEditorFeatures(largeFileOptimized: Boolean) {
    props.stickyScroll = !largeFileOptimized
    props.stickyScrollMaxLines = 3
    props.stickyScrollPreferInnerScope = false
    props.stickyScrollAutoCollapse = true
}

 
internal fun CodeEditor.applyDroideDiagnostics(items: List<LspDiagnostic>) {
    if (items.isEmpty()) {
        if (diagnostics != null) setDiagnostics(null)
        return
    }
    val content = text
    if (content.lineCount <= 0) {
        setDiagnostics(null)
        return
    }
    val regions = items.asSequence().take(MAX_EDITOR_DIAGNOSTICS).mapNotNull { diagnostic ->
        runCatching {
            val startLine = (diagnostic.line - 1).coerceIn(0, content.lineCount - 1)
            val startColumn = (diagnostic.column - 1).coerceIn(0, content.getColumnCount(startLine))
            val endLine = (diagnostic.endLine - 1).coerceIn(startLine, content.lineCount - 1)
            val rawEndColumn = if (endLine == startLine) {
                (diagnostic.endColumn - 1).coerceAtLeast(startColumn)
            } else {
                (diagnostic.endColumn - 1).coerceAtLeast(0)
            }
            val endColumn = rawEndColumn.coerceIn(0, content.getColumnCount(endLine))
            val start = content.getCharIndex(startLine, startColumn)
            var end = content.getCharIndex(endLine, endColumn)
            if (end <= start && start < content.length) end = start + 1
            if (end <= start && start > 0) {
                return@runCatching DiagnosticRegion(
                    start - 1,
                    start,
                    diagnostic.toSoraSeverity(),
                    diagnostic.stableEditorDiagnosticId(),
                    diagnostic.toSoraDetail(),
                )
            }
            if (end <= start) return@runCatching null
            DiagnosticRegion(
                start,
                end,
                diagnostic.toSoraSeverity(),
                diagnostic.stableEditorDiagnosticId(),
                diagnostic.toSoraDetail(),
            )
        }.getOrNull()
    }.toList()
    if (regions.isEmpty()) setDiagnostics(null)
    else setDiagnostics(DiagnosticsContainer(true).apply { addDiagnostics(regions) })
}


 
internal fun CodeEditor.applyDroideInlayHints(items: List<LspInlayHint>) {
    if (items.isEmpty()) {
        if (inlayHints != null) setInlayHints(null)
        return
    }
    val content = text
    val container = InlayHintsContainer()
    items.asSequence().take(MAX_EDITOR_INLAY_HINTS).forEach { hint ->
        val line = (hint.line - 1).coerceIn(0, (content.lineCount - 1).coerceAtLeast(0))
        val column = (hint.column - 1).coerceIn(0, content.getColumnCount(line))
        val rendered = buildString {
            if (hint.paddingLeft) append(' ')
            append(hint.label.trim().take(80))
            if (hint.paddingRight) append(' ')
        }.takeIf { it.isNotBlank() } ?: return@forEach
        container.add(TextInlayHint(line, column, rendered))
    }
    setInlayHints(container)
}

private fun LspDiagnostic.toSoraSeverity(): Short = when (severity) {
    1 -> DiagnosticRegion.SEVERITY_ERROR
    2 -> DiagnosticRegion.SEVERITY_WARNING
    3, 4 -> DiagnosticRegion.SEVERITY_TYPO
    else -> DiagnosticRegion.SEVERITY_NONE
}

private fun LspDiagnostic.toSoraDetail(): DiagnosticDetail {
    val prefix = source?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it: " }.orEmpty()
    val clean = message.trim().ifEmpty { "Diagnostic" }
    return DiagnosticDetail(
        briefMessage = (prefix + clean).take(240),
        detailedMessage = clean.take(2_000),
    )
}

private fun LspDiagnostic.stableEditorDiagnosticId(): Long {
    var hash = 17L
    hash = hash * 31 + line
    hash = hash * 31 + column
    hash = hash * 31 + endLine
    hash = hash * 31 + endColumn
    hash = hash * 31 + (severity ?: 0)
    hash = hash * 31 + message.hashCode()
    hash = hash * 31 + (source?.hashCode() ?: 0)
    return hash
}

private const val MAX_EDITOR_DIAGNOSTICS = 500
private const val MAX_EDITOR_INLAY_HINTS = 400
