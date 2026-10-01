package com.baystudio.droide.core

internal object AgentWorkspacePathPolicy {
    private val excluded = setOf(".git", ".gradle", "build", ".droide", "node_modules")
    fun visible(path: String): Boolean {
        if (path.isBlank() || path.length > 500 || path.startsWith('/') || '\\' in path || path.any { it.code < 32 || it.code == 127 }) return false
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it in excluded }) return false
        return path != ".droide-sync-manifest.tsv" && !SensitivePathPolicy.isSensitive(path)
    }

    fun manifest(text: String): Map<String, String> = WorkspaceSyncManifest.parseTrustedOrEmpty(text, 20_000)
        .filterKeys(::visible).mapValues { it.value.sha256 }

    fun hashListing(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            require(line.length >= 67 && line.substring(0, 64).matches(Regex("[0-9a-f]{64}")) &&
                line[64] == ' ' && line[65] in setOf(' ', '*')) { "Malformed agent checksum listing; source is preserved" }
            val path = line.substring(66).removePrefix("./")
            if (visible(path)) {
                require(result.put(path, line.substring(0, 64)) == null) { "Duplicate agent checksum path" }
                require(result.size <= 20_000) { "Agent workspace contains too many files" }
            }
        }
        return result
    }
}
