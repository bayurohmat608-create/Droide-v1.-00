package com.baystudio.droide.core

import java.util.ArrayDeque

 
data class IndentGuideSegment(
    val column: Int,
    val startLine: Int,
    val endLine: Int,
    val toBottomOfEndLine: Boolean,
)








object IndentGuidePlanner {
    const val MAX_LINES = 100_000
    const val MAX_SEGMENTS = 50_000

    private data class OpenGuide(val column: Int, val startLine: Int)

    fun plan(lines: List<String>, profile: CodeStyleProfile): List<IndentGuideSegment> {
        if (lines.isEmpty() || lines.size > MAX_LINES) return emptyList()
        val tabWidth = profile.tabWidth.coerceIn(1, 8)
        val guideStep = (if (profile.useTabs) profile.tabWidth else profile.indentSize).coerceIn(1, 8)
        val open = ArrayDeque<OpenGuide>()
        val result = ArrayList<IndentGuideSegment>(minOf(lines.size, 512))
        var previousContentLine = 0

        lines.forEachIndexed { lineIndex, line ->
            val indent = visualIndentColumns(line, tabWidth) ?: return@forEachIndexed

            while (open.isNotEmpty() && open.peekLast().column > indent) {
                closeGuide(open.removeLast(), lineIndex, false, result)
            }

            var nextColumn = (open.peekLast()?.column ?: 0) + guideStep
            while (nextColumn <= indent && result.size + open.size < MAX_SEGMENTS) {
                open.addLast(OpenGuide(nextColumn, previousContentLine.coerceAtMost(lineIndex)))
                nextColumn += guideStep
            }
            previousContentLine = lineIndex
        }

        val lastLine = lines.lastIndex.coerceAtLeast(0)
        while (open.isNotEmpty()) {
            closeGuide(open.removeLast(), lastLine, true, result)
        }
        return result
    }

    private fun closeGuide(
        guide: OpenGuide,
        endLine: Int,
        toBottom: Boolean,
        output: MutableList<IndentGuideSegment>,
    ) {
        if (output.size >= MAX_SEGMENTS || endLine <= guide.startLine) return
        output += IndentGuideSegment(
            column = guide.column,
            startLine = guide.startLine,
            endLine = endLine,
            toBottomOfEndLine = toBottom,
        )
    }

    private fun visualIndentColumns(line: String, tabWidth: Int): Int? {
        var columns = 0
        for (ch in line) {
            when (ch) {
                ' ' -> columns++
                '\t' -> columns += tabWidth - (columns % tabWidth)
                else -> return columns
            }
        }
        
        return null
    }
}
