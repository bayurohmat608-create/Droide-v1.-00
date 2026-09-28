package com.baystudio.droide.ui

import com.baystudio.droide.core.AgentIdeContextSnapshot
import com.baystudio.droide.core.AgentIdeDirtyDocumentContext
import com.baystudio.droide.core.AgentIdeDocumentContext
import com.baystudio.droide.core.AgentIdeProblemOrigin
import com.baystudio.droide.core.AgentIdeProblemContext
import com.baystudio.droide.core.AgentIdeProblemRelevance
import com.baystudio.droide.core.BuildDiagnostic
import com.baystudio.droide.core.LspDiagnostic
import com.baystudio.droide.core.PathSecurity
import java.io.File
import java.net.URI

 
internal fun captureAgentIdeContext(
    workspaceRoot: File,
    activeFile: String,
    activeLanguage: String,
    openFiles: List<String>,
    editorState: EditorWorkspaceState,
    cursorLocation: Pair<Int, Int>?,
    lspDiagnostics: Map<String, List<LspDiagnostic>>,
    buildDiagnostics: List<BuildDiagnostic>,
    capturedAtMs: Long = System.currentTimeMillis(),
): AgentIdeContextSnapshot {
    val active = activeFile.takeIf { it.isNotBlank() }?.let { path ->
        val doc = editorState.peek(path)
        val (line, column) = cursorLocation ?: offsetToLineColumn(doc?.content.orEmpty(), doc?.selectionEnd ?: 0)
        val start = doc?.selectionStart?.coerceAtLeast(0) ?: 0
        val end = doc?.selectionEnd?.coerceAtLeast(0) ?: start
        val low = minOf(start, end).coerceAtMost(doc?.content?.length ?: 0)
        val high = maxOf(start, end).coerceAtMost(doc?.content?.length ?: 0)
        val selected = if (doc?.loaded == true && high > low) doc.content.substring(low, high) else ""
        val selectedBounded = selected.take(AgentIdeContextSnapshot.MAX_SELECTED_TEXT_CHARS)
        AgentIdeDocumentContext(
            path = path,
            language = activeLanguage,
            cursorLine = line.coerceAtLeast(1),
            cursorColumn = column.coerceAtLeast(1),
            selectionStart = low,
            selectionEnd = high,
            selectedText = selectedBounded,
            selectedTextTruncated = selected.length > selectedBounded.length,
            dirty = doc?.dirty == true,
            revision = doc?.revision ?: 0,
            changeVersion = doc?.changeVersion ?: 0,
        )
    }

    val dirtyDocuments = editorState.dirtyDocuments()
    val dirty = dirtyDocuments
        .sortedWith(compareBy<EditorDocument>({ it.path != activeFile }, { it.path }))
        .take(AgentIdeContextSnapshot.MAX_DIRTY_DOCUMENTS)
        .map { AgentIdeDirtyDocumentContext(it.path, it.revision, it.changeVersion) }

    val activeEditorDocument = activeFile.takeIf { it.isNotBlank() }?.let(editorState::peek)
    val focus = activeEditorDocument?.takeIf { it.loaded }?.let { doc ->
        val cursor = cursorLocation ?: offsetToLineColumn(doc.content, doc.selectionEnd)
        val low = minOf(doc.selectionStart, doc.selectionEnd).coerceIn(0, doc.content.length)
        val high = maxOf(doc.selectionStart, doc.selectionEnd).coerceIn(0, doc.content.length)
        val selectionStart = offsetToLineColumn(doc.content, low)
        val selectionEnd = offsetToLineColumn(doc.content, if (high > low) high - 1 else high)
        AgentProblemFocus(cursor.first, cursor.second, low != high, selectionStart, selectionEnd)
    }
    val openFileSet = openFiles.map(::normalizeWorkspacePath).filter { it.isNotBlank() }.toSet()
    val dirtyPathSet = dirtyDocuments.map { normalizeWorkspacePath(it.path) }.toSet()

    val problems = buildList {
        lspDiagnostics.forEach { (uri, items) ->
            val path = workspaceRelativePath(workspaceRoot, uri) ?: return@forEach
            items.forEach { item -> add(item.toAgentProblem(path, problemRelevance(path, item.line, item.column, item.endLine, item.endColumn, activeFile, openFileSet, focus))) }
        }
        buildDiagnostics.forEach { item ->
            val path = normalizeWorkspacePath(item.path)
            if (path.isNotBlank()) add(AgentIdeProblemContext(
                path = path,
                line = item.line.coerceAtLeast(1),
                column = item.column.coerceAtLeast(1),
                severity = item.severity,
                message = item.message.take(AgentIdeContextSnapshot.MAX_PROBLEM_MESSAGE_CHARS),
                source = item.source,
                origin = AgentIdeProblemOrigin.BUILD,
                relevance = problemRelevance(path, item.line, item.column, item.line, item.column, activeFile, openFileSet, focus),
                diskBacked = true,
                dirtyBufferDiverged = path in dirtyPathSet,
            ))
        }
    }.distinctBy { "${it.path}:${it.line}:${it.column}:${it.message}:${it.source}" }
        .sortedWith(compareBy<AgentIdeProblemContext>(
            { AgentIdeProblemRelevance.rank(it.relevance) },
            { if (it.dirtyBufferDiverged) 1 else 0 },
            { severityRank(it.severity) },
            { problemLineDistance(it, focus, activeFile) },
            { if (it.origin == AgentIdeProblemOrigin.LSP) 0 else 1 },
            { it.path }, { it.line }, { it.column },
        ))
        .take(AgentIdeContextSnapshot.MAX_PROBLEMS)

    return AgentIdeContextSnapshot(
        activeDocument = active,
        openFiles = openFiles.filter { it.isNotBlank() }.distinct().takeLast(AgentIdeContextSnapshot.MAX_OPEN_FILES),
        dirtyDocuments = dirty,
        problems = problems,
        capturedAtMs = capturedAtMs,
    )
}

private data class AgentProblemFocus(
    val cursorLine: Int,
    val cursorColumn: Int,
    val hasSelection: Boolean,
    val selectionStart: Pair<Int, Int>,
    val selectionEnd: Pair<Int, Int>,
)

private fun LspDiagnostic.toAgentProblem(path: String, relevance: String): AgentIdeProblemContext {
    val safeLine = line.coerceAtLeast(1)
    val safeColumn = column.coerceAtLeast(1)
    val safeEndLine = endLine.coerceAtLeast(safeLine)
    val safeEndColumn = if (safeEndLine == safeLine) endColumn.coerceAtLeast(safeColumn) else endColumn.coerceAtLeast(1)
    return AgentIdeProblemContext(
        path = path,
        line = safeLine,
        column = safeColumn,
        severity = severity,
        message = message.take(AgentIdeContextSnapshot.MAX_PROBLEM_MESSAGE_CHARS),
        source = source,
        endLine = safeEndLine,
        endColumn = safeEndColumn,
        origin = AgentIdeProblemOrigin.LSP,
        relevance = relevance,
    )
}

private fun problemRelevance(
    path: String,
    line: Int,
    column: Int,
    endLine: Int,
    endColumn: Int,
    activeFile: String,
    openFiles: Set<String>,
    focus: AgentProblemFocus?,
): String {
    val normalizedPath = normalizeWorkspacePath(path)
    val normalizedActive = normalizeWorkspacePath(activeFile)
    if (normalizedPath != normalizedActive) {
        return if (normalizedPath in openFiles) AgentIdeProblemRelevance.OPEN_FILE else AgentIdeProblemRelevance.WORKSPACE
    }
    focus ?: return AgentIdeProblemRelevance.ACTIVE_FILE
    val start = line.coerceAtLeast(1) to column.coerceAtLeast(1)
    val safeEndLine = endLine.coerceAtLeast(start.first)
    val end = safeEndLine to if (safeEndLine == start.first) endColumn.coerceAtLeast(start.second) else endColumn.coerceAtLeast(1)
    if (focus.hasSelection && rangesOverlap(start, end, focus.selectionStart, focus.selectionEnd)) return AgentIdeProblemRelevance.SELECTION
    if (pointInRange(focus.cursorLine to focus.cursorColumn, start, end)) return AgentIdeProblemRelevance.CURSOR
    val distance = lineDistance(focus.cursorLine, start.first, end.first)
    return when {
        distance == 0 -> AgentIdeProblemRelevance.CURSOR_LINE
        distance <= 3 -> AgentIdeProblemRelevance.NEAR_CURSOR
        else -> AgentIdeProblemRelevance.ACTIVE_FILE
    }
}

private fun problemLineDistance(problem: AgentIdeProblemContext, focus: AgentProblemFocus?, activeFile: String): Int {
    if (focus == null || normalizeWorkspacePath(problem.path) != normalizeWorkspacePath(activeFile)) return Int.MAX_VALUE
    return lineDistance(focus.cursorLine, problem.line, problem.endLine)
}

private fun severityRank(severity: Int?): Int = when (severity) {
    1 -> 0
    2 -> 1
    3 -> 2
    4 -> 3
    else -> 4
}

private fun lineDistance(line: Int, startLine: Int, endLine: Int): Int = when {
    line < startLine -> startLine - line
    line > endLine -> line - endLine
    else -> 0
}

private fun rangesOverlap(aStart: Pair<Int, Int>, aEnd: Pair<Int, Int>, bStart: Pair<Int, Int>, bEnd: Pair<Int, Int>): Boolean =
    comparePosition(aStart, bEnd) <= 0 && comparePosition(bStart, aEnd) <= 0

private fun pointInRange(point: Pair<Int, Int>, start: Pair<Int, Int>, end: Pair<Int, Int>): Boolean =
    comparePosition(point, start) >= 0 && comparePosition(point, end) <= 0

private fun comparePosition(a: Pair<Int, Int>, b: Pair<Int, Int>): Int = when {
    a.first != b.first -> a.first.compareTo(b.first)
    else -> a.second.compareTo(b.second)
}

private fun normalizeWorkspacePath(path: String): String = path.replace('\\', '/').removePrefix("./").trimStart('/')

private fun workspaceRelativePath(root: File, uri: String): String? = runCatching {
    val file = File(URI(uri)).canonicalFile
    if (!PathSecurity.contains(root, file)) return@runCatching null
    file.relativeTo(root.canonicalFile).invariantSeparatorsPath
}.getOrNull()

private fun offsetToLineColumn(text: String, offset: Int): Pair<Int, Int> {
    val safe = offset.coerceIn(0, text.length)
    var line = 1
    var column = 1
    for (index in 0 until safe) {
        if (text[index] == '\n') {
            line++
            column = 1
        } else {
            column++
        }
    }
    return line to column
}
