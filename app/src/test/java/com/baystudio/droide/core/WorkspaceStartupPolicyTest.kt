package com.baystudio.droide.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceStartupPolicyTest {
    @Test
    fun safRuntimeGetsMoreTimeThanPrivateRuntimeButAllBudgetsStayBounded() {
        val local = DroideProject("local", "Local", "/tmp/local")
        val saf = DroideProject("saf", "SAF", "/tmp/saf", "content://example/tree")

        assertEquals(WorkspaceStartupPolicy.LOCAL_RUNTIME_TIMEOUT_MS, WorkspaceStartupPolicy.runtimeTimeoutMs(local))
        assertEquals(WorkspaceStartupPolicy.SAF_RUNTIME_TIMEOUT_MS, WorkspaceStartupPolicy.runtimeTimeoutMs(saf))
        assertTrue(WorkspaceStartupPolicy.SAF_RUNTIME_TIMEOUT_MS > WorkspaceStartupPolicy.LOCAL_RUNTIME_TIMEOUT_MS)
        listOf(
            WorkspaceStartupPolicy.RUNTIME_REGISTRIES_TIMEOUT_MS,
            WorkspaceStartupPolicy.PROJECT_CATALOG_TIMEOUT_MS,
            WorkspaceStartupPolicy.LOCAL_RUNTIME_TIMEOUT_MS,
            WorkspaceStartupPolicy.SAF_RUNTIME_TIMEOUT_MS,
            WorkspaceStartupPolicy.FALLBACK_RUNTIME_TIMEOUT_MS,
            WorkspaceStartupPolicy.PUBLISH_RUNTIME_TIMEOUT_MS,
        ).forEach { timeout -> assertTrue(timeout in 1_000L..60_000L) }
    }

    @Test
    fun timeoutBecomesExplicitStartupFailureInsteadOfCancellationLookingLikeSuccess() = runBlocking {
        val failure = runCatching {
            WorkspaceStartupPolicy.bounded(
                phase = WorkspaceStartupPhase.ACTIVE_RUNTIME,
                timeoutMs = 1_000L,
                projectId = "slow-project",
            ) {
                delay(1_500L)
            }
        }.exceptionOrNull()

        assertTrue(failure is WorkspaceStartupTimeoutException)
        failure as WorkspaceStartupTimeoutException
        assertEquals(WorkspaceStartupPhase.ACTIVE_RUNTIME, failure.phase)
        assertEquals("slow-project", failure.projectId)
        assertEquals(1_000L, failure.timeoutMs)
    }
}
