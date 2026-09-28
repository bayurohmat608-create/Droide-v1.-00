package com.baystudio.droide.core

import java.io.File

 
class AgentWorkspaceSearch(
    private val files: FileRepository,
    private val documents: WorkspaceDocumentAuthority? = null,
) {
    suspend fun search(query: String, maxResults: Int = 100): String {
        val q = query.trim()
        if (q.length < 2) return "(query too short)"
        require(q.length <= 256) { "query too long" }
        val limit = maxResults.coerceIn(1, MAX_RESULTS)

        val overlayByPath = linkedMapOf<String, WorkspaceDocumentSnapshot>()
        for (snap in documents?.dirtySnapshots().orEmpty()) {
            if (!snap.loaded || snap.kind != WorkspaceDocumentKind.TEXT || snap.content.length > MAX_OVERLAY_CHARS) continue
            val path = runCatching { canonicalRelativePath(snap.path) }.getOrNull() ?: continue
            if (!files.isSearchVisible(path)) continue
            overlayByPath[path] = snap
        }
        val overlays = overlayByPath.entries.sortedBy { it.key }.map { it.key to it.value }

        val excluded = overlays.mapTo(linkedSetOf()) { it.first }
        val liveResults = ArrayList<String>(minOf(limit, 32))
        for ((path, snap) in overlays) {
            collectMatches(path, snap.content, q, limit - liveResults.size, liveResults)
            if (liveResults.size >= limit) break
        }

        val remaining = limit - liveResults.size
        val disk = if (remaining > 0) files.search(q, remaining, excluded) else ""
        if (overlays.isEmpty()) return disk

        return buildString {
            appendLine("[DROIDE_SEARCH_STATE dirty_overlays=${overlays.size} disk_excluded=${excluded.size}]")
            liveResults.forEach(::appendLine)
            if (disk.isNotBlank()) append(disk)
            if (liveResults.isEmpty() && disk.isBlank()) appendLine("(tidak ada hasil)")
            if (liveResults.size >= limit) appendLine("… results limited to $limit …")
        }.trimEnd()
    }

    private fun collectMatches(path: String, content: String, query: String, remaining: Int, out: MutableList<String>) {
        if (remaining <= 0) return
        var lineNumber = 0
        var added = 0
        for (raw in content.lineSequence()) {
            lineNumber++
            if (raw.take(MAX_LINE_SCAN_CHARS).contains(query, ignoreCase = true)) {
                out += "$path:$lineNumber: ${raw.trim().take(MAX_RENDERED_MATCH_CHARS)}"
                added++
                if (added >= remaining) return
            }
        }
    }

    private fun canonicalRelativePath(path: String): String {
        val root = files.root.canonicalFile
        val target = PathSecurity.resolveWithin(root, path).canonicalFile
        return root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
    }

    private companion object {
        const val MAX_RESULTS = 100
        const val MAX_OVERLAY_CHARS = 500_000
        const val MAX_LINE_SCAN_CHARS = 8_000
        const val MAX_RENDERED_MATCH_CHARS = 220
    }
}
