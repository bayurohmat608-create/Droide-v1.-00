package com.baystudio.droide.ui

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.baystudio.droide.core.CodingKeyboardMode
import com.termux.view.TerminalView


internal class DroideNativeTerminalView(context: Context) : TerminalView(context, null) {
    private val codingKeyboard = CodingKeyboardController()

    fun applyKeyboardMode(mode: CodingKeyboardMode) = codingKeyboard.applyMode(this, mode)

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        codingKeyboard.configure(connection, outAttrs, multiline = false)
        return connection
    }
}
