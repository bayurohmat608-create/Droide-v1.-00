package com.baystudio.droide.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitAgentPresentationPolicyTest {
    @Test
    fun portraitVisibleAgentTakesWholeContentDestination() {
        assertTrue(droidePortraitAgentFullscreen(landscape = false, agentVisible = true))
    }

    @Test
    fun portraitHiddenAgentKeepsWorkspaceDestination() {
        assertFalse(droidePortraitAgentFullscreen(landscape = false, agentVisible = false))
    }

    @Test
    fun landscapeKeepsCompanionPaneBehavior() {
        assertFalse(droidePortraitAgentFullscreen(landscape = true, agentVisible = true))
    }
}
