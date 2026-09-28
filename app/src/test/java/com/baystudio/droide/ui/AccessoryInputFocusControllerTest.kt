package com.baystudio.droide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessoryInputFocusControllerTest {
    @Test
    fun staleOwnerReleaseCannotClearReplacementWithSameTarget() {
        val controller = AccessoryInputFocusController()
        val oldOwner = controller.owner(AccessoryInputTarget.EDITOR)
        val newOwner = controller.owner(AccessoryInputTarget.EDITOR)

        controller.onFocusChanged(oldOwner, true)
        controller.onFocusChanged(newOwner, true)
        controller.onFocusChanged(oldOwner, false)

        assertTrue(controller.owns(newOwner))
        assertFalse(controller.owns(oldOwner))
        assertEquals(AccessoryInputTarget.EDITOR, controller.activeTarget)
    }

    @Test
    fun staleDisposeCannotClearReplacementWithSameTarget() {
        val controller = AccessoryInputFocusController()
        val oldOwner = controller.owner(AccessoryInputTarget.TERMINAL_NATIVE)
        val newOwner = controller.owner(AccessoryInputTarget.TERMINAL_NATIVE)

        controller.onFocusChanged(oldOwner, true)
        controller.onFocusChanged(newOwner, true)
        controller.clearIf(oldOwner)

        assertTrue(controller.owns(newOwner))
        assertEquals(AccessoryInputTarget.TERMINAL_NATIVE, controller.activeTarget)
    }

    @Test
    fun currentOwnerReleaseClearsFocus() {
        val controller = AccessoryInputFocusController()
        val owner = controller.owner(AccessoryInputTarget.TERMINAL_DEVICE)

        controller.onFocusChanged(owner, true)
        controller.onFocusChanged(owner, false)

        assertFalse(controller.owns(owner))
        assertEquals(AccessoryInputTarget.NONE, controller.activeTarget)
    }
}
