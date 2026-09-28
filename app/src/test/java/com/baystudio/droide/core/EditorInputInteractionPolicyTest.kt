package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInputInteractionPolicyTest {
    @Test
    fun tapRetriesImeButScrollPinchAndLongPressDoNot() {
        assertTrue(EditorInputInteractionPolicy.shouldRetryImeAfterTouch(true, false, false, 120, 500))
        assertFalse(EditorInputInteractionPolicy.shouldRetryImeAfterTouch(true, true, false, 120, 500))
        assertFalse(EditorInputInteractionPolicy.shouldRetryImeAfterTouch(true, false, true, 120, 500))
        assertFalse(EditorInputInteractionPolicy.shouldRetryImeAfterTouch(true, false, false, 500, 500))
        assertFalse(EditorInputInteractionPolicy.shouldRetryImeAfterTouch(false, false, false, 120, 500))
    }

    @Test
    fun movementUsesAndroidTouchSlopEnvelope() {
        assertFalse(EditorInputInteractionPolicy.movedBeyondSlop(7f, -7f, 8f))
        assertTrue(EditorInputInteractionPolicy.movedBeyondSlop(9f, 0f, 8f))
    }
}
