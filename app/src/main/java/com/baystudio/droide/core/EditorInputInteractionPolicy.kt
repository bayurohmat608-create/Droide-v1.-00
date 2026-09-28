package com.baystudio.droide.core

import kotlin.math.abs






object EditorInputInteractionPolicy {
    fun movedBeyondSlop(deltaX: Float, deltaY: Float, touchSlop: Float): Boolean =
        abs(deltaX) > touchSlop || abs(deltaY) > touchSlop

    fun shouldRetryImeAfterTouch(
        handledByEditor: Boolean,
        moved: Boolean,
        usedMultiplePointers: Boolean,
        gestureDurationMs: Long,
        longPressTimeoutMs: Long,
    ): Boolean = handledByEditor &&
        !moved &&
        !usedMultiplePointers &&
        gestureDurationMs in 0 until longPressTimeoutMs
}
