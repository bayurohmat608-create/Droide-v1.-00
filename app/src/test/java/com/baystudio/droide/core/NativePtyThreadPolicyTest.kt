package com.baystudio.droide.core

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativePtyThreadPolicyTest {
    @Test fun mainLooperConstructionIsAccepted() {
        NativePtyThreadPolicy.requireMainThread(isMainThread = true)
    }

    @Test fun workerThreadConstructionIsRejectedWithExplicitContract() {
        try {
            NativePtyThreadPolicy.requireMainThread(isMainThread = false)
            fail("Expected native PTY thread-affinity guard to reject worker construction")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("main Looper"))
        }
    }
}
