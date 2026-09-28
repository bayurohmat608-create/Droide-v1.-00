package com.baystudio.droide.core

data class LspSemanticTokenLegend(
    val tokenTypes: List<String> = emptyList(),
    val tokenModifiers: List<String> = emptyList(),
)

data class LspSemanticToken(
    val line: Int,
    val startCharacter: Int,
    val length: Int,
    val type: String,
    val modifiers: Set<String> = emptySet(),
)

 
internal object LspSemanticTokenDecoder {
    const val MAX_TOKENS = 8_000
    const val MAX_TOKEN_LENGTH = 16_384

    fun decode(data: List<Long>, legend: LspSemanticTokenLegend): List<LspSemanticToken> {
        if (legend.tokenTypes.isEmpty() || data.size % 5 != 0 || data.size / 5 > MAX_TOKENS) return emptyList()
        val out = ArrayList<LspSemanticToken>(minOf(data.size / 5, MAX_TOKENS))
        var line = 0
        var start = 0
        var i = 0
        while (i + 4 < data.size && out.size < MAX_TOKENS) {
            val deltaLine = data[i]
            val deltaStart = data[i + 1]
            val length = data[i + 2]
            val typeIndex = data[i + 3]
            val modifierBits = data[i + 4]
            if (deltaLine !in 0..Int.MAX_VALUE.toLong() || deltaStart !in 0..Int.MAX_VALUE.toLong() ||
                length !in 1..MAX_TOKEN_LENGTH.toLong() || typeIndex < 0L || typeIndex >= legend.tokenTypes.size.toLong() ||
                modifierBits < 0L) {
                return emptyList()
            }
            if (deltaLine == 0L) {
                if (start > Int.MAX_VALUE - deltaStart.toInt()) return emptyList()
                start += deltaStart.toInt()
            } else {
                if (line > Int.MAX_VALUE - deltaLine.toInt()) return emptyList()
                line += deltaLine.toInt()
                start = deltaStart.toInt()
            }
            val modifiers = linkedSetOf<String>()
            legend.tokenModifiers.forEachIndexed { index, name ->
                if (index < 63 && (modifierBits and (1L shl index)) != 0L) modifiers += name
            }
            out += LspSemanticToken(line, start, length.toInt(), legend.tokenTypes[typeIndex.toInt()], modifiers)
            i += 5
        }
        return out
    }
}
