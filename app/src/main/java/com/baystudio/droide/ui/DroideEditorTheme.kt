package com.baystudio.droide.ui

import androidx.compose.ui.graphics.toArgb
import com.baystudio.droide.core.DroideThemeSnapshot
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

 
internal class DroideEditorColorScheme(snapshot: DroideThemeSnapshot) : EditorColorScheme(snapshot.editor.isDark) {
    init {
        val editor = snapshot.editor
        val ui = snapshot.ui
        setColor(WHOLE_BACKGROUND, editor.background)
        setColor(LINE_NUMBER_BACKGROUND, editor.background)
        setColor(TEXT_NORMAL, editor.foreground)
        setColor(LINE_NUMBER, editor.lineNumber)
        setColor(LINE_NUMBER_CURRENT, editor.activeLineNumber)
        setColor(LINE_DIVIDER, ui.border.toArgb())
        setColor(CURRENT_LINE, editor.currentLine)
        setColor(SELECTED_TEXT_BACKGROUND, editor.selection)
        setColor(SELECTION_INSERT, ui.primary.toArgb())
        setColor(SELECTION_HANDLE, ui.primary.toArgb())
        setColor(KEYWORD, editor.keyword)
        setColor(COMMENT, editor.comment)
        setColor(LITERAL, editor.literal)
        setColor(OPERATOR, editor.operator)
        setColor(FUNCTION_NAME, editor.function)
        setColor(IDENTIFIER_NAME, editor.type)
        setColor(IDENTIFIER_VAR, editor.variable)
        setColor(ANNOTATION, ui.primary.toArgb())
        setColor(HTML_TAG, editor.keyword)
        setColor(ATTRIBUTE_NAME, editor.function)
        setColor(ATTRIBUTE_VALUE, editor.literal)
        setColor(COMPLETION_WND_BACKGROUND, ui.surface2.toArgb())
        setColor(COMPLETION_WND_CORNER, ui.surface2.toArgb())
        setColor(COMPLETION_WND_TEXT_PRIMARY, editor.foreground)
        setColor(COMPLETION_WND_TEXT_SECONDARY, ui.muted.toArgb())
        setColor(COMPLETION_WND_TEXT_MATCHED, ui.primary.toArgb())
        setColor(COMPLETION_WND_ITEM_CURRENT, ui.surface3.toArgb())
        setColor(SIGNATURE_BACKGROUND, ui.surface2.toArgb())
        setColor(SIGNATURE_TEXT_NORMAL, editor.foreground)
        setColor(SIGNATURE_TEXT_HIGHLIGHTED_PARAMETER, ui.primary.toArgb())
        setColor(HOVER_BACKGROUND, ui.surface2.toArgb())
        setColor(HOVER_TEXT_NORMAL, editor.foreground)
        setColor(HOVER_TEXT_HIGHLIGHTED, ui.primary.toArgb())
        setColor(DIAGNOSTIC_TOOLTIP_BACKGROUND, ui.surface2.toArgb())
        setColor(DIAGNOSTIC_TOOLTIP_BRIEF_MSG, editor.foreground)
        setColor(DIAGNOSTIC_TOOLTIP_DETAILED_MSG, ui.muted.toArgb())
        setColor(DIAGNOSTIC_TOOLTIP_ACTION, ui.primary.toArgb())
        setColor(PROBLEM_ERROR, ui.error.toArgb())
        setColor(PROBLEM_WARNING, ui.warning.toArgb())
        setColor(NON_PRINTABLE_CHAR, ui.muted.toArgb())
        setColor(BLOCK_LINE, editor.indentGuide)
        setColor(BLOCK_LINE_CURRENT, editor.activeIndentGuide)
        setColor(SIDE_BLOCK_LINE, editor.activeIndentGuide)
        setColor(SCROLL_BAR_THUMB, ui.borderStrong.toArgb())
        setColor(SCROLL_BAR_THUMB_PRESSED, ui.primary.toArgb())
        setColor(STICKY_SCROLL_DIVIDER, ui.border.toArgb())
        setColor(TEXT_ACTION_WINDOW_BACKGROUND, ui.surface2.toArgb())
        setColor(TEXT_ACTION_WINDOW_ICON_COLOR, editor.foreground)
        setColor(MINIMAP_BACKGROUND, editor.background)
    }
}

internal fun DroideCodeEditor.applyDroideTheme(snapshot: DroideThemeSnapshot) {
    

    setBackgroundColor(snapshot.editor.background)
    if (themeFingerprint == snapshot.fingerprint) return
    setColorScheme(DroideEditorColorScheme(snapshot))
    themeFingerprint = snapshot.fingerprint
}
