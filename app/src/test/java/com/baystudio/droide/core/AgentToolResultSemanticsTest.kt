package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolResultSemanticsTest {
    @Test fun nonZeroExitIsFailure() {
        val evidence = AgentToolResultSemantics.classify("run_command", "exit=1\ncompile failed")
        assertEquals(AgentToolEvidenceState.FAILED, evidence.state)
        assertTrue(evidence.operationStarted)
        assertEquals(false, evidence.success)
    }

    @Test fun zeroExitIsVerifiedSuccess() {
        val evidence = AgentToolResultSemantics.classify("run_command", "exit=0\nOK")
        assertEquals(AgentToolEvidenceState.SUCCEEDED, evidence.state)
        assertEquals(true, evidence.success)
    }

    @Test fun backgroundLaunchIsNotCompletion() {
        val evidence = AgentToolResultSemantics.classify("run_command", "BACKGROUND_JOB_STARTED id=bg-1 state=running")
        assertEquals(AgentToolEvidenceState.STARTED, evidence.state)
        assertNull(evidence.success)
        assertFalse(evidence.isFailure)
    }

    @Test fun requestedBackgroundKillIsSuccessfulTermination() {
        val evidence = AgentToolResultSemantics.classify("background_job", "id=bg-1 state=killed", operation = "kill")
        assertEquals(AgentToolEvidenceState.SUCCEEDED, evidence.state)
        assertEquals(true, evidence.success)
    }

    @Test fun deniedAndUnavailableNeverBecomeSuccess() {
        assertEquals(AgentToolEvidenceState.DENIED, AgentToolResultSemantics.classify("run_command", "DENIED (policy): shell").state)
        assertEquals(AgentToolEvidenceState.UNAVAILABLE, AgentToolResultSemantics.classify("custom_x", "ERROR: executable not found: node").state)
    }
}
