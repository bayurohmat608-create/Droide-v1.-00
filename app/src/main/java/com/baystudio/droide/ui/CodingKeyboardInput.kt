package com.baystudio.droide.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import com.baystudio.droide.core.CodingKeyboardMode


internal fun applyCodingKeyboardMetadata(
    info: EditorInfo,
    mode: CodingKeyboardMode,
    multiline: Boolean,
) {
    info.inputType = if (mode.requestsRawInput()) {
        InputType.TYPE_NULL
    } else {
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL or
            (if (mode.allowsWordCorrections()) InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) or
            (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
    }
    // Preserve the original action (Enter/Send) and prohibit fullscreen extraction.
    info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
        EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
}

// Restart an active IME once per preference change, never per keystroke/recomposition.
internal class CodingKeyboardController {
    var mode: CodingKeyboardMode = CodingKeyboardMode.DEFAULT
        private set
    private var connection: InputConnection? = null

    fun applyMode(view: View, selected: CodingKeyboardMode) {
        if (mode == selected) return
        mode = selected
        if (!view.isAttachedToWindow || !view.isFocused) return
        val manager = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        if (!manager.isActive(view)) return
        // Terminal connections flush pending composed text here before being replaced.
        connection?.finishComposingText()
        manager.restartInput(view)
    }

    fun configure(connection: InputConnection, info: EditorInfo, multiline: Boolean) {
        this.connection = connection
        applyCodingKeyboardMetadata(info, mode, multiline)
    }
}


@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CodingKeyboardInput(
    mode: CodingKeyboardMode,
    content: @Composable () -> Unit,
) {
    val interceptor = remember(mode) {
        PlatformTextInputInterceptor { request, nextHandler ->
            val configuredRequest = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    applyCodingKeyboardMetadata(outAttributes, mode, multiline = false)
                    return connection
                }
            }
            nextHandler.startInputMethod(configuredRequest)
        }
    }
    InterceptPlatformTextInput(interceptor = interceptor, content = content)
}
