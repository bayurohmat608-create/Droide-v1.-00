package com.baystudio.droide.core

enum class ConflictChoice { CURRENT, INCOMING, BOTH }

data class MergeConflictBlock(
    val index: Int,
    val currentLabel: String,
    val incomingLabel: String,
    val current: String,
    val incoming: String,
    val base: String? = null,
)

object MergeConflictResolver {
    private data class ParsedBlock(
        val public: MergeConflictBlock,
        val startLine: Int,
        val endLineInclusive: Int,
    )

    fun blocks(text: String, maxBlocks: Int = 200): List<MergeConflictBlock> =
        parse(text, maxBlocks).map { it.public }

    fun hasMarkers(text: String): Boolean = text.replace("\r\n", "\n").lineSequence().any { line ->
        line.startsWith("<<<<<<<") || line.startsWith("=======") || line.startsWith(">>>>>>>") || line.startsWith("|||||||")
    }

    fun resolve(text: String, blockIndex: Int, choice: ConflictChoice): String {
        val parsed = parse(text, 500)
        val block = parsed.firstOrNull { it.public.index == blockIndex }
            ?: throw IllegalArgumentException("Conflict block $blockIndex no longer exists")
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        val normalized = text.replace("\r\n", "\n")
        val trailingNewline = normalized.endsWith('\n')
        val lines = normalized.split('\n').toMutableList().apply {
            if (trailingNewline && isNotEmpty() && last().isEmpty()) removeAt(lastIndex)
        }
        val replacementText = when (choice) {
            ConflictChoice.CURRENT -> block.public.current
            ConflictChoice.INCOMING -> block.public.incoming
            ConflictChoice.BOTH -> listOf(block.public.current, block.public.incoming)
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        }
        val replacement = if (replacementText.isEmpty()) emptyList() else replacementText.split('\n')
        lines.subList(block.startLine, block.endLineInclusive + 1).clear()
        lines.addAll(block.startLine, replacement)
        val rendered = lines.joinToString(newline)
        return if (trailingNewline) rendered + newline else rendered
    }

    private fun parse(text: String, maxBlocks: Int): List<ParsedBlock> {
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        val out = mutableListOf<ParsedBlock>()
        var i = 0
        while (i < lines.size && out.size < maxBlocks.coerceIn(1, 500)) {
            val start = lines[i]
            if (!start.startsWith("<<<<<<<")) { i++; continue }
            val startLine = i
            val currentLabel = start.removePrefix("<<<<<<<").trim().ifBlank { "Current" }
            i++
            val current = mutableListOf<String>()
            val base = mutableListOf<String>()
            val incoming = mutableListOf<String>()
            while (i < lines.size && !lines[i].startsWith("=======") && !lines[i].startsWith("|||||||")) current += lines[i++]
            if (i < lines.size && lines[i].startsWith("|||||||")) {
                i++
                while (i < lines.size && !lines[i].startsWith("=======")) base += lines[i++]
            }
            if (i >= lines.size || !lines[i].startsWith("=======")) break
            i++
            while (i < lines.size && !lines[i].startsWith(">>>>>>>")) incoming += lines[i++]
            if (i >= lines.size) break
            val incomingLabel = lines[i].removePrefix(">>>>>>>").trim().ifBlank { "Incoming" }
            val endLine = i
            out += ParsedBlock(
                MergeConflictBlock(
                    index = out.size,
                    currentLabel = currentLabel,
                    incomingLabel = incomingLabel,
                    current = current.joinToString("\n"),
                    incoming = incoming.joinToString("\n"),
                    base = base.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                ),
                startLine,
                endLine,
            )
            i++
        }
        return out
    }
}
