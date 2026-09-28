package com.baystudio.droide.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessoryOrientationPresentationPolicyTest {
    @Test fun editorImeOwnsLowerEdge() {
        assertTrue(droideTypingImeActive(true, AccessoryInputTarget.EDITOR))
    }

    @Test fun everyTerminalProfileOwnsLowerEdge() {
        assertTrue(droideTypingImeActive(true, AccessoryInputTarget.TERMINAL_NATIVE))
        assertTrue(droideTypingImeActive(true, AccessoryInputTarget.TERMINAL_DEVICE))
        assertTrue(droideTypingImeActive(true, AccessoryInputTarget.TERMINAL_FALLBACK))
    }

    @Test fun hiddenImeOrNonInputSurfaceKeepsWorkspaceChrome() {
        assertFalse(droideTypingImeActive(false, AccessoryInputTarget.EDITOR))
        assertFalse(droideTypingImeActive(true, AccessoryInputTarget.NONE))
    }
}
