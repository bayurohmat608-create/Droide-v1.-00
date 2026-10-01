package com.baystudio.droide.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.baystudio.droide.core.DevicePtySessionHandle
import com.baystudio.droide.core.CodingKeyboardMode
import com.baystudio.droide.core.DeviceTerminalEmulator
import com.baystudio.droide.core.DroideTerminalPalette
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalRenderer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt








class DeviceTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), DeviceTerminalEmulator.UiListener {

    private var session: DevicePtySessionHandle? = null
    private var model: DeviceTerminalEmulator? = null
    private var textSizeSp = 14f
    private var renderer = createRenderer()
    private var fontWidth = renderer.fontWidth
    private var lineSpacing = renderer.fontLineSpacing
    private var lineSpacingAndAscent = measureLineSpacingAndAscent()
    private var topRow = 0
    private var scrollRemainder = 0f
    private var selection: DeviceTerminalEmulator.Selection? = null
    private var actionMode: ActionMode? = null
    private var cursorVisible = true
    @Volatile private var screenGeneration = 0
    private var terminalBackgroundColor = Color.BLACK
    internal var accessoryModifiers: AccessoryModifierController? = null
    private val codingKeyboard = CodingKeyboardController()

    fun applyKeyboardMode(mode: CodingKeyboardMode) = codingKeyboard.applyMode(this, mode)

    private val clipboard: ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val inputMethodManager: InputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (textSizeSp * detector.scaleFactor).coerceIn(9f, 26f)
            if (abs(next - textSizeSp) >= 0.2f) setTextSizeSp(next)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            clearSelection()
            requestTerminalFocus()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val (column, row) = terminalCoordinates(e.x, e.y)
            selection = DeviceTerminalEmulator.Selection(column, row, column, row)
            invalidate()
            showSelectionActions()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (selection != null) {
                val start = selection ?: return true
                val (column, row) = terminalCoordinates(e2.x, e2.y)
                selection = start.copy(endX = column, endY = row)
                invalidate()
                return true
            }
            scrollRemainder += distanceY
            val rowDelta = (scrollRemainder / lineSpacing.coerceAtLeast(1)).toInt()
            if (rowDelta != 0) {
                scrollRemainder -= rowDelta * lineSpacing
                scrollRows(rowDelta, e2)
            }
            return true
        }
    })

    private val cursorBlink = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            cursorVisible = !cursorVisible
            model?.setCursorBlinkState(cursorVisible)
            postDelayed(this, 600L)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isVerticalScrollBarEnabled = true
        setBackgroundColor(terminalBackgroundColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    fun applyTheme(palette: DroideTerminalPalette) {
        terminalBackgroundColor = palette.background
        setBackgroundColor(terminalBackgroundColor)
        invalidate()
    }

    fun attachSession(value: DevicePtySessionHandle) {
        if (session === value) {
            model?.setUiListener(this)
            updateTerminalSize()
            return
        }
        screenGeneration++
        model?.setUiListener(null)
        session = value
        model = value.terminalEmulator
        model?.setUiListener(this)
        topRow = 0
        selection = null
        updateTerminalSize()
        requestTerminalFocus()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        model?.setUiListener(this)
        onTitleChanged(session?.terminalTitle?.value)
        removeCallbacks(cursorBlink)
        post(cursorBlink)
    }

    override fun onDetachedFromWindow() {
        screenGeneration++
        removeCallbacks(cursorBlink)
        model?.setUiListener(null)
        actionMode?.finish()
        actionMode = null
        super.onDetachedFromWindow()
    }

    override fun onScreenChanged() {
        val generation = screenGeneration
        post {
            if (generation != screenGeneration || !isAttachedToWindow) return@post
            val terminal = model ?: return@post
            val shift = terminal.consumeScrollCounter()
            val history = terminal.activeTranscriptRows()
            topRow = if (topRow < 0) (topRow - shift).coerceIn(-history, 0) else 0
            selection = selection?.clamp(terminal.columns(), history, terminal.rows())
            awakenScrollBars()
            invalidate()
        }
    }

    override fun onTitleChanged(title: String?) {
        val generation = screenGeneration
        post {
            if (generation == screenGeneration && isAttachedToWindow) {
                contentDescription = title?.takeIf { it.isNotBlank() } ?: "Device Workstation terminal"
            }
        }
    }

    override fun onCopyTextRequested(text: String) {
        val generation = screenGeneration
        post {
            if (generation == screenGeneration && isAttachedToWindow) copyToClipboard(text)
        }
    }

    override fun onPasteRequested() {
        val generation = screenGeneration
        post {
            if (generation == screenGeneration && isAttachedToWindow) pasteFromClipboard()
        }
    }

    override fun onBell() {
        val generation = screenGeneration
        post {
            if (generation == screenGeneration && isAttachedToWindow) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(terminalBackgroundColor)
        model?.render(renderer, canvas, topRow, selection)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTerminalSize()
    }

    override fun computeVerticalScrollRange(): Int {
        val terminal = model ?: return 1
        return terminal.activeTranscriptRows() + terminal.rows()
    }

    override fun computeVerticalScrollExtent(): Int = model?.rows() ?: 1

    override fun computeVerticalScrollOffset(): Int {
        val terminal = model ?: return 0
        return terminal.activeTranscriptRows() + topRow
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        val connection = object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flushImeEditable()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flushImeEditable()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                sendImeDeletion(beforeLength, afterLength)
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                


                sendImeDeletion(beforeLength, afterLength)
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = this@DeviceTerminalView.dispatchKeyEvent(event)

            private fun flushImeEditable() {
                val pending = TerminalImeProtocol.normalizeCommittedText(editable)
                if (pending.isNotEmpty()) writeImeText(pending)
                editable?.clear()
            }

            private fun sendImeDeletion(beforeLength: Int, afterLength: Int) {
                repeat(TerminalImeProtocol.boundedDeleteCount(beforeLength)) { writeText("\u007f") }
                val forwardDelete = model?.keySequence(KeyEvent.KEYCODE_FORWARD_DEL, 0)
                if (!forwardDelete.isNullOrEmpty()) {
                    repeat(TerminalImeProtocol.boundedDeleteCount(afterLength)) { writeText(forwardDelete) }
                }
            }
        }
        codingKeyboard.configure(connection, outAttrs, multiline = false)
        return connection
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val terminal = model ?: return super.onKeyDown(keyCode, event)
        if (selection != null) clearSelection()
        if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            event.characters?.let(::writeText)
            return true
        }
        if (event.isSystem && keyCode != KeyEvent.KEYCODE_ESCAPE) return super.onKeyDown(keyCode, event)

        val accessory = accessoryModifiers
        val control = event.isCtrlPressed || accessory?.isActive(AccessoryModifier.CTRL) == true
        val leftAlt = (event.metaState and KeyEvent.META_ALT_LEFT_ON) != 0 || accessory?.isActive(AccessoryModifier.ALT) == true
        val shift = event.isShiftPressed || accessory?.isActive(AccessoryModifier.SHIFT) == true
        var modifiers = 0
        if (control) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || leftAlt) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        if (shift) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) modifiers = modifiers or KeyHandler.KEYMOD_NUM_LOCK

        terminal.keySequence(keyCode, modifiers)?.let {
            writeText(it)
            accessory?.consumeArmed()
            return true
        }

        var effectiveMetaState = event.metaState and KeyEvent.META_CTRL_MASK.inv()
        if ((event.metaState and KeyEvent.META_ALT_RIGHT_ON) == 0) {
            effectiveMetaState = effectiveMetaState and (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON).inv()
        }
        if (shift) effectiveMetaState = effectiveMetaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val unicode = event.getUnicodeChar(effectiveMetaState)
        if (unicode == 0) return super.onKeyDown(keyCode, event)
        writeCodePoint(unicode, control, leftAlt)
        accessory?.consumeArmed()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && event.action == MotionEvent.ACTION_SCROLL) {
            val up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0f
            scrollRows(if (up) -3 else 3, event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val terminal = model
        if (terminal != null && event.isFromSource(InputDevice.SOURCE_MOUSE) && terminal.isMouseTrackingActive()) {
            val (column, visibleRow) = visibleCoordinates(event.x, event.y)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) {
                    terminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, column + 1, visibleRow + 1, true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> if (event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) {
                    terminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, column + 1, visibleRow + 1, true)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    terminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, column + 1, visibleRow + 1, false)
                    return true
                }
            }
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun scrollRows(delta: Int, event: MotionEvent?) {
        if (delta == 0) return
        val terminal = model ?: return
        val steps = abs(delta).coerceAtMost(20)
        if (terminal.isMouseTrackingActive()) {
            val (column, visibleRow) = event?.let { visibleCoordinates(it.x, it.y) } ?: (0 to 0)
            val button = if (delta < 0) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
            repeat(steps) { terminal.sendMouseEvent(button, column + 1, visibleRow + 1, true) }
            return
        }
        if (terminal.isAlternateBufferActive()) {
            val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            terminal.keySequence(keyCode, 0)?.let { sequence -> repeat(steps) { writeText(sequence) } }
            return
        }
        val history = terminal.activeTranscriptRows()
        topRow = (topRow + delta).coerceIn(-history, 0)
        awakenScrollBars()
        invalidate()
    }

    private fun writeImeText(text: String) {
        val accessory = accessoryModifiers
        if (accessory != null && (accessory.hasCtrlOrAlt() || accessory.isActive(AccessoryModifier.SHIFT))) {
            text.codePoints().forEach { codePoint ->
                writeCodePoint(
                    codePoint,
                    accessory.isActive(AccessoryModifier.CTRL),
                    accessory.isActive(AccessoryModifier.ALT),
                )
            }
            accessory.consumeArmed()
        } else {
            writeText(text)
        }
    }

    internal fun sendAccessoryText(text: String) {
        if (text.isEmpty()) return
        writeImeText(text)
        requestFocus()
    }

    internal fun sendAccessoryKey(keyCode: Int, forcedMetaState: Int = 0) {
        val accessory = accessoryModifiers
        dispatchAccessoryKey(this, keyCode, accessory?.androidMetaState(forcedMetaState) ?: forcedMetaState)
        accessory?.consumeArmed()
        requestFocus()
    }

    private fun writeText(text: String) {
        if (text.isEmpty()) return
        followLiveCursorForInput()
        cursorVisible = true
        model?.setCursorBlinkState(true)
        session?.writeRaw(text)
    }

     
    private fun followLiveCursorForInput() {
        if (topRow == 0) return
        topRow = 0
        awakenScrollBars()
        invalidate()
    }

    private fun writeCodePoint(original: Int, control: Boolean, alt: Boolean) {
        var codePoint = original
        if (control) {
            codePoint = when (codePoint) {
                in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
                in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
                ' '.code, '2'.code -> 0
                '['.code, '3'.code -> 27
                '\\'.code, '4'.code -> 28
                ']'.code, '5'.code -> 29
                '^'.code, '6'.code -> 30
                '_'.code, '7'.code, '/'.code -> 31
                '8'.code -> 127
                else -> codePoint
            }
        }
        val value = String(Character.toChars(codePoint))
        writeText((if (alt) "\u001b" else "") + value)
    }

    private fun requestTerminalFocus() {
        requestFocus()
        post { inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun pasteFromClipboard() {
        val clip = clipboard.primaryClip ?: return
        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (text.length > MAX_PASTE_CHARS) {
            Toast.makeText(context, "Clipboard text is too large to paste safely", Toast.LENGTH_SHORT).show()
            return
        }
        if (text.isNotEmpty()) {
            followLiveCursorForInput()
            model?.paste(text)
        }
    }

    private fun copySelection() {
        val current = selection ?: return
        val text = model?.selectedText(current).orEmpty()
        if (text.isNotEmpty()) copyToClipboard(text)
        clearSelection()
    }

    private fun copyToClipboard(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    private fun selectAll() {
        val terminal = model ?: return
        selection = DeviceTerminalEmulator.Selection(
            startX = 0,
            startY = -terminal.activeTranscriptRows(),
            endX = terminal.columns() - 1,
            endY = terminal.rows() - 1,
        )
        invalidate()
    }

    private fun showSelectionActions() {
        actionMode?.finish()
        actionMode = startActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, ACTION_COPY, 0, "Copy")
                menu.add(0, ACTION_PASTE, 1, "Paste")
                menu.add(0, ACTION_SELECT_ALL, 2, "Select all")
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    ACTION_COPY -> copySelection()
                    ACTION_PASTE -> pasteFromClipboard()
                    ACTION_SELECT_ALL -> selectAll()
                    else -> return false
                }
                if (item.itemId != ACTION_SELECT_ALL) mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                if (selection != null) clearSelection()
            }
        })
    }

    private fun clearSelection() {
        selection = null
        actionMode?.finish()
        actionMode = null
        invalidate()
    }

    private fun terminalCoordinates(x: Float, y: Float): Pair<Int, Int> {
        val terminal = model ?: return 0 to 0
        val column = (x / fontWidth.coerceAtLeast(1f)).toInt().coerceIn(0, terminal.columns() - 1)
        val visibleRow = ((y - lineSpacingAndAscent) / lineSpacing.coerceAtLeast(1)).toInt().coerceIn(0, terminal.rows() - 1)
        val row = (visibleRow + topRow).coerceIn(-terminal.activeTranscriptRows(), terminal.rows() - 1)
        return column to row
    }

    private fun visibleCoordinates(x: Float, y: Float): Pair<Int, Int> {
        val terminal = model ?: return 0 to 0
        val column = (x / fontWidth.coerceAtLeast(1f)).toInt().coerceIn(0, terminal.columns() - 1)
        val row = ((y - lineSpacingAndAscent) / lineSpacing.coerceAtLeast(1)).toInt().coerceIn(0, terminal.rows() - 1)
        return column to row
    }

    private fun updateTerminalSize() {
        if (width <= 0 || height <= 0) return
        val columns = (width / fontWidth.coerceAtLeast(1f)).toInt().coerceAtLeast(2)
        val rows = (height / lineSpacing.coerceAtLeast(1)).coerceAtLeast(2)
        session?.resize(columns, rows)
    }

    private fun setTextSizeSp(value: Float) {
        textSizeSp = value
        renderer = createRenderer()
        fontWidth = renderer.fontWidth
        lineSpacing = renderer.fontLineSpacing
        lineSpacingAndAscent = measureLineSpacingAndAscent()
        topRow = 0
        updateTerminalSize()
        invalidate()
    }

    private fun textSizePx(): Int = (textSizeSp * resources.displayMetrics.scaledDensity).roundToInt().coerceAtLeast(8)
    private fun createPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = textSizePx().toFloat()
    }
    private fun createRenderer(): TerminalRenderer = TerminalRenderer(textSizePx(), Typeface.MONOSPACE)
    private fun measureLineSpacingAndAscent(): Int {
        val paint = createPaint()
        return ceil(paint.fontSpacing.toDouble()).toInt() + ceil(paint.ascent().toDouble()).toInt()
    }

    private fun DeviceTerminalEmulator.Selection.clamp(columns: Int, transcriptRows: Int, visibleRows: Int) = copy(
        startX = startX.coerceIn(0, columns - 1),
        startY = startY.coerceIn(-transcriptRows, visibleRows - 1),
        endX = endX.coerceIn(0, columns - 1),
        endY = endY.coerceIn(-transcriptRows, visibleRows - 1),
    )

    companion object {
        private const val ACTION_COPY = 1
        private const val ACTION_PASTE = 2
        private const val ACTION_SELECT_ALL = 3
        private const val MAX_PASTE_CHARS = 1_000_000
    }
}
