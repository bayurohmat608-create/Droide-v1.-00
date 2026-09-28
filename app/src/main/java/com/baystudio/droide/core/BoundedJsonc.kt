package com.baystudio.droide.core

 
internal object BoundedJsonc {
    fun stripComments(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        var inString = false
        var escaped = false
        var lineComment = false
        var blockComment = false
        while (i < input.length) {
            val c = input[i]
            val next = input.getOrNull(i + 1)
            when {
                lineComment -> {
                    if (c == '\n' || c == '\r') { lineComment = false; out.append(c) } else out.append(' ')
                }
                blockComment -> {
                    if (c == '*' && next == '/') { out.append("  "); blockComment = false; i++ }
                    else if (c == '\n' || c == '\r') out.append(c) else out.append(' ')
                }
                inString -> {
                    out.append(c)
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                }
                c == '"' -> { inString = true; out.append(c) }
                c == '/' && next == '/' -> { out.append("  "); lineComment = true; i++ }
                c == '/' && next == '*' -> { out.append("  "); blockComment = true; i++ }
                else -> out.append(c)
            }
            i++
        }
        require(!blockComment && !inString) { "Unterminated JSONC construct" }
        return out.toString()
    }
}
