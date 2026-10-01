package com.baystudio.droide.core

// V2 rows bind both content and projected POSIX mode so a chmod-only change cannot leave the remote mirror stale.


internal object WorkspaceSyncManifest {
    private const val REGULAR_MODE = 420
    private const val EXECUTABLE_MODE = 493

    data class Entry(
        val sha256: String,
        val mode: Int?,
    ) {
        init {
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid sync-manifest hash" }
            require(mode == null || mode == REGULAR_MODE || mode == EXECUTABLE_MODE) {
                "Invalid sync-manifest mode"
            }
        }
    }

    fun parseTrustedOrEmpty(text: String, maxEntries: Int): Map<String, Entry> = runCatching {
        require(maxEntries > 0) { "Invalid sync-manifest entry limit" }
        val result = linkedMapOf<String, Entry>()
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val firstTab = line.indexOf('\t')
            require(firstTab == 64 && line.length > firstTab + 1) { "Malformed sync-manifest line" }
            val hash = line.substring(0, firstTab)
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid sync-manifest hash" }

            val remainder = line.substring(firstTab + 1)
            val secondTab = remainder.indexOf('\t')
            val mode: Int?
            val rel: String
            if (secondTab < 0) {
                

                mode = null
                rel = remainder
            } else {
                val modeText = remainder.substring(0, secondTab)
                mode = modeText.toIntOrNull()
                require(mode == REGULAR_MODE || mode == EXECUTABLE_MODE) {
                    "Invalid sync-manifest mode"
                }
                rel = remainder.substring(secondTab + 1)
            }
            validateRelativePath(rel)
            require(result.put(rel, Entry(hash, mode)) == null) { "Duplicate sync-manifest path: $rel" }
            require(result.size <= maxEntries) { "Sync manifest exceeds entry limit" }
        }
        result
    }.getOrDefault(emptyMap())

    fun serialize(entries: Map<String, Entry>): String = buildString {
        entries.forEach { (rel, entry) ->
            validateRelativePath(rel)
            val mode = requireNotNull(entry.mode) { "Cannot serialize mode-unknown sync entry: $rel" }
            append(entry.sha256).append('\t').append(mode).append('\t').append(rel).append('\n')
        }
    }

    fun validateRelativePath(path: String) {
        require(path.isNotBlank() && path.length <= 600) { "Invalid workspace path" }
        require(!path.startsWith('/') && !path.startsWith('\\')) { "Absolute workspace path" }
        require(path.none { it == '\u0000' || it == '\n' || it == '\r' || it == '\t' }) { "Control character in workspace path" }
        val parts = path.replace('\\', '/').split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe workspace path: $path" }
    }
}
