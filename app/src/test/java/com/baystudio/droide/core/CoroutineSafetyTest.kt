package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoroutineSafetyTest {
    @Test fun ordinaryFailureBecomesResultFailure() = runBlocking {
        val result = runSuspendCatching<String> { error("boom") }
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test fun cancellationIsNeverSwallowed() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                runSuspendCatching<Unit> { throw CancellationException("stop") }
            }
        }
    }
}
