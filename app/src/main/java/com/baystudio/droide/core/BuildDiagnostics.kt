package com.baystudio.droide.core

 
data class BuildDiagnostic(
    val path: String,
    val line: Int,
    val column: Int,
    val severity: Int,
    val message: String,
    val source: String = "Gradle",
)






object GradleProblemParser {
    private val kotlinUri = Regex("^(e|w):\\s+file://(.+?):(\\d+):(\\d+):\\s*(.+)$")
    private val fileDiagnostic = Regex("^(.+?\\.(?:kt|kts|java|xml|aidl|gradle)):(\\d+)(?::(\\d+))?:\\s*(?:(error|warning):\\s*)?(.+)$", RegexOption.IGNORE_CASE)

    fun parse(output: String, remoteWorkspace: String): List<BuildDiagnostic> {
        if (output.isBlank()) return emptyList()
        val normalizedRoot = remoteWorkspace.trimEnd('/') + "/"
        val seen = linkedSetOf<String>()
        val result = ArrayList<BuildDiagnostic>()

        output.lineSequence().forEach { raw ->
            val lineText = stripAnsi(raw).trim()
            if (lineText.isBlank()) return@forEach
            val parsed = parseLine(lineText, normalizedRoot) ?: return@forEach
            val key = "${parsed.path}:${parsed.line}:${parsed.column}:${parsed.severity}:${parsed.message}"
            if (seen.add(key)) result += parsed
        }
        return result.take(2_000)
    }

    private fun parseLine(text: String, remoteRoot: String): BuildDiagnostic? {
        kotlinUri.matchEntire(text)?.let { m ->
            val severity = if (m.groupValues[1] == "e") 1 else 2
            val path = normalizePath(m.groupValues[2], remoteRoot) ?: return null
            return BuildDiagnostic(
                path = path,
                line = m.groupValues[3].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                column = m.groupValues[4].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                severity = severity,
                message = m.groupValues[5].trim(),
                source = if (severity == 1) "Kotlin" else "Kotlin warning",
            )
        }

        fileDiagnostic.matchEntire(text)?.let { m ->
            val path = normalizePath(m.groupValues[1], remoteRoot) ?: return null
            val level = m.groupValues[4].lowercase()
            val severity = if (level == "warning") 2 else 1
            val message = m.groupValues[5].trim()
            
            if (level.isBlank() && !message.contains("error", ignoreCase = true) && !message.contains("warning", ignoreCase = true)) return null
            return BuildDiagnostic(
                path = path,
                line = m.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                column = m.groupValues[3].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                severity = severity,
                message = message.removePrefix("error: ").removePrefix("warning: ").trim(),
                source = "Gradle",
            )
        }
        return null
    }

    private fun normalizePath(raw: String, remoteRoot: String): String? {
        var path = raw.trim().removePrefix("file://").replace('\\', '/')
        while (path.startsWith("//")) path = path.removePrefix("/")
        if (path.startsWith(remoteRoot)) path = path.removePrefix(remoteRoot)
        path = path.trimStart('/')
        if (path.isBlank() || path.contains("../") || path == "..") return null
        return path
    }

    private fun stripAnsi(text: String): String = text.replace(Regex("\\u001B\\[[;\\d?]*[ -/]*[@-~]"), "")
}
