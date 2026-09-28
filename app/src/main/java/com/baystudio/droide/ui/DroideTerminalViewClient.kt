package com.baystudio.droide.ui

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

 
internal class DroideTerminalViewClient(
    private val context: Context,
    private val viewProvider: () -> TerminalView?,
    initialTextSize: Int = 14,
    private val accessoryModifiers: AccessoryModifierController? = null,
) : TerminalViewClient {
    private var textSize = initialTextSize

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            textSize = (textSize + if (scale > 1f) 1 else -1).coerceIn(8, 32)
            viewProvider()?.setTextSize(textSize)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        viewProvider()?.let { view ->
            view.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        accessoryModifiers?.consumeArmed()
        return false
    }
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = accessoryModifiers?.isActive(AccessoryModifier.CTRL) == true
    override fun readAltKey(): Boolean = accessoryModifiers?.isActive(AccessoryModifier.ALT) == true
    override fun readShiftKey(): Boolean = accessoryModifiers?.isActive(AccessoryModifier.SHIFT) == true
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        accessoryModifiers?.consumeArmed()
        return false
    }

    override fun onEmulatorSet() {
        viewProvider()?.let { view ->
            view.setTerminalCursorBlinkerRate(600)
            view.setTerminalCursorBlinkerState(true, true)
        }
    }

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message ?: "terminal view error", e) }
}
