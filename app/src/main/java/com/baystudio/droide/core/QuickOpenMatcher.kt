package com.baystudio.droide.core

 
object QuickOpenMatcher {
    fun score(query: String, path: String): Int? {
        val q = query.trim().lowercase()
        if (q.length > 256) return null
        val normalized = path.replace('\\', '/').lowercase()
        val name = normalized.substringAfterLast('/')
        return when {
            q.isEmpty() -> 50 + normalized.count { it == '/' }
            name == q -> 0
            name.startsWith(q) -> 5
            name.contains(q) -> 10 + name.indexOf(q).coerceAtLeast(0)
            normalized.startsWith(q) -> 20
            normalized.contains(q) -> 30 + normalized.indexOf(q).coerceAtLeast(0) / 8
            isSubsequence(q, normalized) -> 80 + (normalized.length - q.length).coerceAtMost(200) / 8
            else -> null
        }
    }

    fun rank(query: String, paths: Collection<String>, limit: Int): List<String> {
        val safeLimit = limit.coerceIn(1, 300)
        return paths.asSequence()
            .mapNotNull { path -> score(query, path)?.let { score -> Triple(score, path.length, path) } }
            .sortedWith(compareBy<Triple<Int, Int, String>>({ it.first }, { it.second }, { it.third }))
            .take(safeLimit)
            .map { it.third }
            .toList()
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        if (needle.isEmpty()) return true
        var i = 0
        for (c in haystack) {
            if (c == needle[i] && ++i == needle.length) return true
        }
        return false
    }
}
