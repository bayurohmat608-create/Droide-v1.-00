package com.baystudio.droide.ui

import com.baystudio.droide.core.ProfessionalSmartTyping
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.SymbolPairMatch

internal fun droideSymbolPairs(languageId: String): SymbolPairMatch {
    val profile = ProfessionalSmartTyping.profile(languageId)
    val pairs = SymbolPairMatch()
    if (profile.structuralPairs) {
        pairs.putPair('(', surroundPair("(", ")"))
        pairs.putPair('[', surroundPair("[", "]"))
        pairs.putPair('{', surroundPair("{", "}"))
    }
    if (profile.doubleQuote) pairs.putPair('"', surroundPair("\"", "\""))
    if (profile.singleQuote) pairs.putPair('\'', surroundPair("'", "'"))
    if (profile.backtick) pairs.putPair('`', surroundPair("`", "`"))
    

    return pairs
}

private fun surroundPair(open: String, close: String) = SymbolPairMatch.SymbolPair(
    open,
    close,
    object : SymbolPairMatch.SymbolPair.SymbolPairEx {
        override fun shouldDoAutoSurround(content: Content): Boolean = content.cursor.isSelected
    },
)

internal class DroideSmartNewlineHandler(
    private val languageId: String,
    private val codeStyle: com.baystudio.droide.core.CodeStyleProfile,
) : NewlineHandler {
    override fun matchesRequirement(text: Content, position: CharPosition, style: Styles?): Boolean {
        val line = text.getLine(position.line)
        val before = line.subSequence(0, position.column)
        val after = line.subSequence(position.column, line.length)
        return ProfessionalSmartTyping.betweenMatchingPair(languageId, before, after)
    }

    override fun handleNewline(
        text: Content,
        position: CharPosition,
        style: Styles?,
        tabSize: Int,
    ): NewlineHandleResult {
        val line = text.getLine(position.line).toString()
        val prefix = line.substring(0, position.column)
        val baseIndent = prefix.takeWhile { it == ' ' || it == '\t' }
        val opener = prefix.lastOrNull { !it.isWhitespace() }
        val indentUnit = if (opener == '(' || opener == '[') codeStyle.continuationUnit else codeStyle.indentUnit
        val inserted = "\n$baseIndent$indentUnit\n$baseIndent"
        return NewlineHandleResult(inserted, baseIndent.length + 1)
    }
}
