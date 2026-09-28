package com.baystudio.droide.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

 
internal object WorkspaceTextMatcher {
    suspend fun appendLine(
        path: String,
        line: Int,
        raw: String,
        query: String,
        options: TextSearchOptions,
        limit: Int,
        hits: MutableList<TextSearchHit>,
    ) {
        var from = 0
        while (from <= raw.length - query.length && hits.size < limit) {
            currentCoroutineContext().ensureActive()
            val at = raw.indexOf(query, from, ignoreCase = !options.caseSensitive)
            if (at < 0) break
            val end = at + query.length
            if (!options.wholeWord ||
                (at == 0 || !isWordCodePoint(Character.codePointBefore(raw, at))) &&
                (end == raw.length || !isWordCodePoint(Character.codePointAt(raw, end)))) {
                val startSnippet = (at - 60).coerceAtLeast(0)
                val endSnippet = (end + 120).coerceAtMost(raw.length)
                val excerpt = (if (startSnippet > 0) "…" else "") + raw.substring(startSnippet, endSnippet) +
                    (if (endSnippet < raw.length) "…" else "")
                hits += TextSearchHit(path, line, at + 1, excerpt)
            }
            from = end
        }
    }

    private fun isWordCodePoint(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return Character.isLetterOrDigit(codePoint) || codePoint == '_'.code ||
            type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }
}
