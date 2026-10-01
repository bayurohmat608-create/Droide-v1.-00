package com.baystudio.droide.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UiActionSafetyTest {
    @Test fun userActionFailureIsReportedWithoutCancellingScreenScope() = runBlocking {
        var message: String? = null
        launchUiCatching(onError = { message = it.message }) { error("provider unavailable") }.join()
        assertEquals("provider unavailable", message)
        assertTrue(isActive)
    }

    @Test fun cancellationDoesNotShowAnError() = runBlocking {
        var reported = false
        val action = launchUiCatching(onError = { reported = true }) { throw CancellationException("closed screen") }
        action.join()
        assertTrue(action.isCancelled)
        assertFalse(reported)
        assertTrue(isActive)
    }
}
