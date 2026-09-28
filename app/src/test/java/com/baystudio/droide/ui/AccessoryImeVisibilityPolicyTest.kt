package com.baystudio.droide.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessoryImeVisibilityPolicyTest {
    @Test
    fun focusedInputSurvivesTransientImeVisibilityDrop() {
        assertTrue(accessoryKeysShouldBeActive(ownsInputFocus = true, imeVisible = false, imeGraceVisible = true))
    }

    @Test
    fun realImeDismissalHidesAccessorySurface() {
        assertFalse(accessoryKeysShouldBeActive(ownsInputFocus = true, imeVisible = false, imeGraceVisible = false))
    }

    @Test
    fun staleImeSignalCannotShowKeysWithoutInputFocus() {
        assertFalse(accessoryKeysShouldBeActive(ownsInputFocus = false, imeVisible = true, imeGraceVisible = true))
    }
}
