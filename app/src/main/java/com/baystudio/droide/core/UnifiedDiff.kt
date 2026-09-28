package com.baystudio.droide.core

 
enum class DiffLineKind { CONTEXT, ADD, REMOVE, META }

data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

data class DiffHunk(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
)

data class DiffFile(
    val oldPath: String?,
    val newPath: String?,
    val headers: List<String>,
    val hunks: List<DiffHunk>,
)

object UnifiedDiffParser {
    private val hunk = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")

    fun parse(text: String, maxLines: Int = 4_000): List<DiffFile> {
        if (text.isBlank()) return emptyList()
        val source = text.lineSequence().take(maxLines.coerceIn(1, 20_000)).toList()
        val result = mutableListOf<DiffFile>()
        var oldPath: String? = null
        var newPath: String? = null
        var headers = mutableListOf<String>()
        var hunks = mutableListOf<DiffHunk>()
        var i = 0

        fun flushFile() {
            if (headers.isNotEmpty() || hunks.isNotEmpty() || oldPath != null || newPath != null) {
                result += DiffFile(oldPath, newPath, headers.toList(), hunks.toList())
            }
            oldPath = null
            newPath = null
            headers = mutableListOf()
            hunks = mutableListOf()
        }

        while (i < source.size) {
            val line = source[i]
            if (line.startsWith("diff --git ")) {
                if (headers.isNotEmpty() || hunks.isNotEmpty()) flushFile()
                headers += line
                val parts = line.removePrefix("diff --git ").split(' ', limit = 2)
                oldPath = parts.getOrNull(0)?.removePrefix("a/")
                newPath = parts.getOrNull(1)?.removePrefix("b/")
                i++
                continue
            }
            if (line.startsWith("--- ")) {
                oldPath = normalizePatchPath(line.removePrefix("--- ").substringBefore('\t'))
                headers += line
                i++
                continue
            }
            if (line.startsWith("+++ ")) {
                newPath = normalizePatchPath(line.removePrefix("+++ ").substringBefore('\t'))
                headers += line
                i++
                continue
            }
            val match = hunk.matchEntire(line)
            if (match != null) {
                val oldStart = match.groupValues[1].toInt()
                val oldCount = match.groupValues[2].ifBlank { "1" }.toInt()
                val newStart = match.groupValues[3].toInt()
                val newCount = match.groupValues[4].ifBlank { "1" }.toInt()
                var oldLine = oldStart
                var newLine = newStart
                val lines = mutableListOf<DiffLine>()
                val header = line
                i++
                while (i < source.size) {
                    val body = source[i]
                    if (body.startsWith("diff --git ") || hunk.matches(body)) break
                    when {
                        body.startsWith("+") && !body.startsWith("+++") -> {
                            lines += DiffLine(DiffLineKind.ADD, body.drop(1), null, newLine++)
                        }
                        body.startsWith("-") && !body.startsWith("---") -> {
                            lines += DiffLine(DiffLineKind.REMOVE, body.drop(1), oldLine++, null)
                        }
                        body.startsWith(" ") -> {
                            lines += DiffLine(DiffLineKind.CONTEXT, body.drop(1), oldLine++, newLine++)
                        }
                        else -> lines += DiffLine(DiffLineKind.META, body)
                    }
                    i++
                }
                hunks += DiffHunk(header, oldStart, oldCount, newStart, newCount, lines)
                continue
            }
            headers += line
            i++
        }
        flushFile()
        return result
    }

    private fun normalizePatchPath(path: String): String? = when (path) {
        "/dev/null" -> null
        else -> path.removePrefix("a/").removePrefix("b/")
    }
}
