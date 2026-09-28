package com.baystudio.droide.core

import java.io.File








class AgentWorkspaceReader(
    private val files: FileRepository,
    private val documents: WorkspaceDocumentAuthority? = null,
) {
    suspend fun read(path: String, start: Int? = null, end: Int? = null): String {
        val normalizedPath = canonicalRelativePath(path)
        val snapshot = documents?.snapshot(normalizedPath)
        if (snapshot?.loaded == true && snapshot.kind == WorkspaceDocumentKind.LARGE && snapshot.dirty) {
            return "[DROIDE_DOCUMENT_STATE source=large_file_performance_buffer dirty=true]\n" +
                "This large file has unsaved editor changes. Save it before Agent file reads so Droide never exposes stale disk content."
        }
        val live = snapshot?.takeIf { it.loaded && it.kind == WorkspaceDocumentKind.TEXT }

        if (live != null) {
            val body = if (start != null || end != null) {
                renderRange(live.content, start ?: 1, end ?: ((start ?: 1) + 199))
            } else {
                live.content.take(MAX_FULL_READ_CHARS)
            }
            return buildString {
                append("[DROIDE_DOCUMENT_STATE source=editor_buffer dirty=")
                append(live.dirty)
                append(" revision=")
                append(live.revision)
                append(" change_version=")
                append(live.changeVersion)
                append(" saved_baseline_diverged=")
                append(live.divergedFromSavedBaseline)
                appendLine("]")
                append(body)
            }
        }

        return if (start != null || end != null) {
            files.readRange(normalizedPath, start ?: 1, end ?: ((start ?: 1) + 199))
        } else {
            files.readText(normalizedPath).take(MAX_FULL_READ_CHARS)
        }
    }

     
    private fun canonicalRelativePath(path: String): String {
        val root = files.root.canonicalFile
        val target = PathSecurity.resolveWithin(root, path).canonicalFile
        return root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
    }

     
    private fun renderRange(content: String, start: Int, end: Int): String {
        val s = start.coerceAtLeast(1)
        val e = end.coerceIn(s, s + MAX_RANGE_SPAN)
        if (content.isEmpty()) return "(range $s–$e is beyond end of file)"

        val split = content.split('\n')
        val logicalLineCount = if (content.endsWith('\n')) split.size - 1 else split.size
        if (s > logicalLineCount) return "(range $s–$e is beyond end of file)"
        val last = minOf(e, logicalLineCount)
        val hasMore = last < logicalLineCount

        return buildString {
            appendLine("… showing lines $s–$last${if (hasMore) "; more lines follow" else ""} …")
            for (lineNumber in s..last) {
                val line = split[lineNumber - 1]
                val rendered = if (line.length <= MAX_RENDERED_LINE_CHARS) line
                else line.take(MAX_RENDERED_LINE_CHARS) + "…"
                appendLine(String.format("%4d│ %s", lineNumber, rendered))
            }
        }
    }

    private companion object {
        const val MAX_FULL_READ_CHARS = 20_000
        const val MAX_RANGE_SPAN = 500
        const val MAX_RENDERED_LINE_CHARS = 2_000
    }
}
