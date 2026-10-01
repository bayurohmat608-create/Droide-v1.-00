package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AndroidDebugLaunchGuardTest {
    @Test fun aSuccessfulAttachKeepsTheTargetRunning() { runBlocking {
        var stopped = false
        assertEquals(42, AndroidDebugLaunchGuard.run({ stopped = true }) { 42 })
        assertFalse(stopped)
    } }

    @Test fun failedAttachStopsTheTargetAndPreservesItsFailure() {
        var stopped = false
        val failure = IllegalStateException("attach failed")
        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking {
            AndroidDebugLaunchGuard.run({ stopped = true }) { throw failure }
        } }
        assertSame(failure, thrown)
        assertTrue(stopped)
    }

    @Test fun cleanupFailureDoesNotHideTheAttachFailure() {
        val failure = IllegalStateException("attach failed")
        val cleanup = IllegalArgumentException("bridge disconnected")
        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking {
            AndroidDebugLaunchGuard.run({ throw cleanup }) { throw failure }
        } }
        assertSame(failure, thrown)
        assertEquals(listOf(cleanup), thrown.suppressed.toList())
    }

    @Test fun cancellationWaitsForCleanupInAnActiveContext() { runBlocking {
        val launched = CompletableDeferred<Unit>()
        var stopped = false
        val task = launch {
            AndroidDebugLaunchGuard.run(stopOnFailure = {
                currentCoroutineContext().ensureActive()
                delay(10)
                stopped = true
            }) {
                launched.complete(Unit)
                awaitCancellation()
            }
        }
        launched.await()
        task.cancelAndJoin()
        assertTrue(stopped)
        assertTrue(task.isCancelled)
    } }
}
