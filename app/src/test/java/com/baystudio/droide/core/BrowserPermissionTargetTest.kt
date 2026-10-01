package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserPermissionTargetTest {
    @Test fun pageOperationsIgnoreAnUnrelatedSuppliedUrl() {
        for (operation in listOf("read", "click", "type", "status", "back", "reload", "wait", "screenshot")) {
            assertEquals("http://localhost:8080/private", AgentRepairCompletionPolicy.browserPermissionTarget(operation, "https://example.com", "http://localhost:8080/private"))
        }
    }
    @Test fun navigationUsesTheRequestedDestination() {
        for (operation in listOf("open", "navigate")) {
            assertEquals("https://example.com", AgentRepairCompletionPolicy.browserPermissionTarget(operation, "https://example.com", "http://localhost:8080"))
        }
    }
    @Test fun missingNavigationUrlDoesNotInheritAnUnrelatedPage() {
        assertEquals("", AgentRepairCompletionPolicy.browserPermissionTarget("open", null, "http://localhost:8080"))
    }
}
