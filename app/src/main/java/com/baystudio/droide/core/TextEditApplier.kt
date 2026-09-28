package com.baystudio.droide.core

 
data class TextRangeEdit(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val newText: String,
)

object TextEditApplier {
    fun apply(text: String, edits: List<TextRangeEdit>): String {
        if (edits.isEmpty()) return text
        data class OffsetEdit(val start: Int, val end: Int, val newText: String)
        val offsetEdits = edits.map { edit ->
            require(edit.startLine >= 1 && edit.startColumn >= 1 && edit.endLine >= 1 && edit.endColumn >= 1) { "Invalid text edit range" }
            val start = offset(text, edit.startLine, edit.startColumn)
            val end = offset(text, edit.endLine, edit.endColumn)
            require(start <= end) { "Text edit range is reversed" }
            OffsetEdit(start, end, edit.newText)
        }.sortedWith(compareByDescending<OffsetEdit> { it.start }.thenByDescending { it.end })

        for (i in 0 until offsetEdits.lastIndex) {
            val later = offsetEdits[i]
            val earlier = offsetEdits[i + 1]
            require(earlier.end <= later.start) { "Overlapping text edits are not safe to apply" }
        }

        val out = StringBuilder(text)
        offsetEdits.forEach { e -> out.replace(e.start, e.end, e.newText) }
        return out.toString()
    }

    private fun offset(text: String, line: Int, column: Int): Int {
        var currentLine = 1
        var lineStart = 0
        while (currentLine < line) {
            val nl = text.indexOf('\n', lineStart)
            require(nl >= 0) { "Text edit line is outside the document" }
            lineStart = nl + 1
            currentLine++
        }
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val result = lineStart + (column - 1)
        require(result in lineStart..lineEnd) { "Text edit column is outside the document" }
        return result
    }
}
