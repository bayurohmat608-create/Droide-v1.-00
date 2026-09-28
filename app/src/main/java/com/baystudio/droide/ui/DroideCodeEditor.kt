package com.baystudio.droide.ui

import android.content.Context
import android.graphics.Rect
import android.text.InputType
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.EditorInputInteractionPolicy
import com.baystudio.droide.core.ProfessionalSmartTyping
import io.github.rosemoe.sora.widget.CodeEditor








internal class DroideCodeEditor(
    context: Context,
    private val languageId: String,
    private val accessoryModifiers: AccessoryModifierController? = null,
    codeStyle: CodeStyleProfile,
) : CodeEditor(context) {

    private var codeStyle: CodeStyleProfile = codeStyle

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchMoved = false
    private var touchUsedMultiplePointers = false
    private var pendingImeFromUserTap = false
    private var caretRevealPosted = false
    internal var themeFingerprint: String? = null

    init {
        

        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setEditable(true)
        setSoftKeyboardEnabled(true)
        



        setDisableSoftKbdIfHardKbdAvailable(false)

        
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_NORMAL or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE

        

        props.disallowSuggestions = false
        props.adjustToSelectionOnResize = true
        props.useICULibToSelectWords = true
        setTabWidth(codeStyle.tabWidth)

        

        setBlockLineEnabled(true)
        setBlockLineWidth(0.75f)
        setHighlightCurrentBlock(true)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        



        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return connection
    }

    fun applyCodeStyle(profile: CodeStyleProfile) {
        codeStyle = profile
        if (tabWidth != profile.tabWidth) setTabWidth(profile.tabWidth)
    }

    fun applyReviewMode(enabled: Boolean) {
        setEditable(!enabled)
        setSoftKeyboardEnabled(!enabled)
        if (enabled) {
            pendingImeFromUserTap = false
            ViewCompat.getWindowInsetsController(this)?.hide(WindowInsetsCompat.Type.ime())
        }
    }

    



    fun restoreInputFocus(showKeyboard: Boolean = true) {
        if (!isEnabled || !isEditable) return
        acquireInputFocus()
        if (showKeyboard) requestImeAfterUserInteraction()
    }

    





    internal fun revealSelectionForActiveIme() {
        if (!isAttachedToWindow || !isFocused || !isEnabled || !isEditable) return
        val imeVisible = ViewCompat.getRootWindowInsets(this)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (!imeVisible || caretRevealPosted) return
        caretRevealPosted = true
        postOnAnimation {
            caretRevealPosted = false
            if (!isAttachedToWindow || !isFocused || !isEnabled || !isEditable) return@postOnAnimation
            val stillVisible = ViewCompat.getRootWindowInsets(this)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (!stillVisible) return@postOnAnimation
            val caret = cursor.right()
            ensurePositionVisible(caret.line, caret.column, true)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h < oldh || w != oldw) revealSelectionForActiveIme()
    }

    private fun acquireInputFocus(): Boolean {
        if (!isEnabled || !isEditable) return false
        if (!isFocused && isInTouchMode) requestFocusFromTouch()
        if (!isFocused) requestFocus()
        return isFocused
    }

    private fun requestImeAfterUserInteraction() {
        if (!isEnabled || !isEditable) {
            pendingImeFromUserTap = false
            return
        }
        pendingImeFromUserTap = true
        post {
            if (!isAttachedToWindow || !isEnabled || !isEditable) return@post
            if (!acquireInputFocus()) return@post
            

            if (!hasWindowFocus()) return@post
            pendingImeFromUserTap = false
            showSoftInput()
            

            ViewCompat.getWindowInsetsController(this)?.show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            // Never carry an old tap-triggered IME request across panel/dialog focus changes.

            pendingImeFromUserTap = false
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && pendingImeFromUserTap && isFocused) {
            requestImeAfterUserInteraction()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchMoved = false
                touchUsedMultiplePointers = false
                


                parent?.requestDisallowInterceptTouchEvent(true)
                acquireInputFocus()
            }
            MotionEvent.ACTION_POINTER_DOWN -> touchUsedMultiplePointers = true
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) touchUsedMultiplePointers = true
                if (!touchMoved && EditorInputInteractionPolicy.movedBeyondSlop(
                        event.x - touchDownX,
                        event.y - touchDownY,
                        touchSlop,
                    )
                ) {
                    touchMoved = true
                }
            }
        }

        

        val handled = super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                


                if (EditorInputInteractionPolicy.shouldRetryImeAfterTouch(
                        handledByEditor = handled,
                        moved = touchMoved,
                        usedMultiplePointers = touchUsedMultiplePointers,
                        gestureDurationMs = event.eventTime - event.downTime,
                        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong(),
                    )
                ) {
                    requestImeAfterUserInteraction()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                pendingImeFromUserTap = false
            }
        }
        return handled
    }

    private fun setSelectionOffset(offset: Int) {
        val safe = offset.coerceIn(0, text.length)
        val position = text.indexer.getCharPosition(safe)
        setSelection(position.line, position.column)
    }

    override fun commitText(text: CharSequence, applyAutoIndent: Boolean) {
        

        if (text.isEmpty()) {
            super.commitText(text, applyAutoIndent)
            return
        }

        


        val accessory = accessoryModifiers
        if (text.length == 1 && accessory?.hasCtrlOrAlt() == true) {
            val keyCode = android.view.KeyEvent.keyCodeFromString("KEYCODE_${text[0].uppercaseChar()}")
            if (keyCode != android.view.KeyEvent.KEYCODE_UNKNOWN) {
                dispatchAccessoryKey(this, keyCode, accessory.androidMetaState())
                accessory.consumeArmed()
                restoreInputFocus(showKeyboard = true)
                return
            }
        }
        val cursor = this.cursor
        if (!cursor.isSelected && (text == "\n" || text == "\r" || text == "\r\n")) {
            val line = this.text.getLine(cursor.leftLine).toString()
            val before = line.substring(0, cursor.leftColumn)
            val after = line.substring(cursor.leftColumn)
            if (ProfessionalSmartTyping.betweenMatchingPair(languageId, before, after)) {
                val baseIndent = before.takeWhile { it == ' ' || it == '\t' }
                val opener = before.lastOrNull { !it.isWhitespace() }
                val indentUnit = if (opener == '(' || opener == '[') codeStyle.continuationUnit else codeStyle.indentUnit
                val separator = lineSeparator.content.ifEmpty { "\n" }
                val inserted = separator + baseIndent + indentUnit + separator + baseIndent
                insertText(inserted, separator.length + baseIndent.length + indentUnit.length)
                return
            }
        }
        if (!cursor.isSelected && text.length == 1) {
            val typed = text[0]
            val cursorIndex = cursor.left
            if (ProfessionalSmartTyping.shouldOvertypeClosing(this.text, cursorIndex, typed, languageId)) {
                setSelectionOffset(cursorIndex + 1)
                return
            }
        }

        

        val allowSymbolCompletion = if (text.length == 1 && text[0] in charArrayOf('"', '\'', '`')) {
            val line = this.text.getLine(cursor.leftLine)
            ProfessionalSmartTyping.shouldAutoPairQuote(
                line = line,
                cursorColumn = cursor.leftColumn,
                quote = text[0],
                languageId = languageId,
                hasSelection = cursor.isSelected,
            )
        } else {
            true
        }
        super.commitText(text, applyAutoIndent, allowSymbolCompletion)
    }

    override fun deleteText() {
        val cursor = this.cursor
        if (!cursor.isSelected) {
            val range = ProfessionalSmartTyping.pairedDeleteRange(this.text, cursor.left, languageId)
            if (range != null) {
                this.text.beginBatchEdit()
                try {
                    this.text.delete(range.first, range.last + 1)
                } finally {
                    this.text.endBatchEdit()
                }
                setSelectionOffset(range.first)
                return
            }
        }
        super.deleteText()
    }
}
