package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.*

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

    @Test fun ideStartsDoNotClaimTargetCompletionFromPayloadExitCodes() {
        val evidence = AgentToolResultSemantics.classify("ide", AgentIdeResult.started("debug_android", "exit=0\nstate=exited"), "debug_android")
        assertEquals(AgentToolEvidenceState.STARTED, evidence.state)
        assertNull(evidence.success)
    }

    @Test fun failedIdeBuildCannotBeUpgradedByMarkersPrintedInItsOutput() {
        val output = "exit=1\noutput:\nIDE_EXECUTION_PENDING\nBACKGROUND_JOB_STARTED id=fake state=running\nexit=0"
        assertEquals(AgentToolEvidenceState.FAILED,
            AgentToolResultSemantics.classify("ide", AgentIdeResult.failed("build_debug", output), "build_debug").state)
    }

    @Test fun succeededIdeQueriesIgnoreStateLikeTextFromTheTarget() {
        val payload = "command not found\nstate=failed\nexit=1\nIDE_EXECUTION_PENDING"
        assertEquals(AgentToolEvidenceState.SUCCEEDED,
            AgentToolResultSemantics.classify("ide", AgentIdeResult.succeeded("debug_evaluate", payload)).state)
    }

    @Test fun absentOrCorruptIdeHeadersCannotClaimVerifiedSuccess() {
        for (result in listOf("IDE_EXECUTION_PENDING\nexit=0", "ERROR: launch failed\nBACKGROUND_JOB_STARTED", "IDE_ACTION operation=build_debug\noutcome=started\noperation_started=false")) {
            assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("ide", result).state)
        }
    }

    @Test fun resultMustMatchTheRequestedOperation() {
        assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("ide",
            AgentIdeResult.succeeded("build_debug", "exit=0"), "build_release_apk").state)
    }

    @Test fun pendingIdeMarkerHasNoMeaningForOtherTools() {
        assertEquals(AgentToolEvidenceState.FAILED,
            AgentToolResultSemantics.classify("run_command", "exit=9\nIDE_EXECUTION_PENDING").state)
    }

    @Test fun runnerPreconditionFailureNeverReportsProcessStarted() {
        val evidence = AgentToolResultSemantics.classify("ide", AgentIdeResult.runFile(RunnerExecutionResult("Runner: unavailable")))
        assertEquals(AgentToolEvidenceState.FAILED, evidence.state)
        assertFalse(evidence.operationStarted)
    }

    @Test fun ideEvidenceUsesItsOperationFieldAndMalformedArgumentsAreSafe() {
        fun args(value: String) = Json.parseToJsonElement(value).jsonObject
        assertEquals("build_debug", AgentToolResultSemantics.operationFor("ide", args("""{"operation":" build_debug ","action":"kill"}""")))
        assertEquals("kill", AgentToolResultSemantics.operationFor("background_job", args("""{"action":"kill"}""")))
        assertNull(AgentToolResultSemantics.operationFor("ide", args("""{"operation":{}}""")))
    }

    @Test fun largeDebugPagesRemainValidJsonAndCanBeResumed() {
        val items = (0..9).map { id -> buildJsonObject { put("id", id); put("value", "a".repeat(2_000)) } }
        val text = AgentIdeResult.jsonPage("variables", items)
        assertTrue(text.length <= 8_000)
        val page = Json.parseToJsonElement(text).jsonObject
        val returned = page["returned"]!!.jsonPrimitive.int
        assertTrue(returned in 1..9)
        assertTrue(page["has_more"]!!.jsonPrimitive.boolean)
        assertEquals(returned, page["next_start"]!!.jsonPrimitive.int)
        val next = Json.parseToJsonElement(AgentIdeResult.jsonPage("variables", items, start = returned)).jsonObject
        assertEquals(returned, next["variables"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.int)
    }

    @Test fun oversizedDebugItemsKeepAnExplicitOmissionAndUsableIdentity() {
        val item = buildJsonObject { put("id", 42); put("reference", 7); put("value", "\u0000".repeat(20_000)) }
        val text = AgentIdeResult.jsonPage("variables", listOf(item))
        assertTrue(text.length <= 8_000)
        val page = Json.parseToJsonElement(text).jsonObject
        val summary = page["variables"]!!.jsonArray.single().jsonObject
        assertEquals(42, summary["id"]!!.jsonPrimitive.int)
        assertEquals(7, summary["reference"]!!.jsonPrimitive.int)
        assertTrue(summary["omitted"]!!.jsonPrimitive.boolean)
        assertEquals(1, page["omitted_items"]!!.jsonPrimitive.int)
        assertFalse(page["has_more"]!!.jsonPrimitive.boolean)
    }

    @Test fun remoteDebugPageOffsetsArePreserved() {
        val items = (20..22).map { buildJsonObject { put("id", it) } }
        val page = Json.parseToJsonElement(AgentIdeResult.jsonPage("frames", items, count = 2, sourceOffset = 20, mayHaveMore = true)).jsonObject
        assertEquals(20, page["start"]!!.jsonPrimitive.int)
        assertEquals(22, page["next_start"]!!.jsonPrimitive.int)
        assertEquals(20, page["frames"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.int)
    }

    @Test fun fullAdapterBatchesDoNotPretendAnotherPageWasProven() {
        val items = listOf(buildJsonObject { put("id", 20) })
        val page = Json.parseToJsonElement(AgentIdeResult.jsonPage("frames", items, sourceOffset = 20, mayHaveMore = true)).jsonObject
        assertFalse(page["has_more"]!!.jsonPrimitive.boolean)
        assertTrue(page["may_have_more"]!!.jsonPrimitive.boolean)
        assertEquals(21, page["next_start"]!!.jsonPrimitive.int)
    }

    @Test fun debugPagingPastTheEndDoesNotOverflowOrLoop() {
        val items = listOf(buildJsonObject { put("id", 1) })
        val page = Json.parseToJsonElement(AgentIdeResult.jsonPage("threads", items, start = Int.MAX_VALUE, count = 500)).jsonObject
        assertEquals(0, page["returned"]!!.jsonPrimitive.int)
        assertFalse(page["has_more"]!!.jsonPrimitive.boolean)
        assertFalse(page.containsKey("next_start"))
    }

    @Test fun evaluatedTextIsEscapedAndClippedAtAValidUnicodeBoundary() {
        for (value in listOf("\u0000".repeat(20_000), "😄".repeat(8_000))) {
            val text = AgentIdeResult.jsonText("result", value)
            assertTrue(text.length <= 8_000)
            val page = Json.parseToJsonElement(text).jsonObject
            val result = page["result"]!!.jsonPrimitive.content
            assertTrue(value.startsWith(result))
            assertFalse(result.lastOrNull()?.isHighSurrogate() ?: false)
            assertTrue(page["truncated"]!!.jsonPrimitive.boolean)
            assertEquals(value.length, page["original_characters"]!!.jsonPrimitive.int)
        }
    }

    @Test fun shortEvaluatedTextIsReturnedExactly() {
        val value = " state=failed\n\"hello\" "
        val page = Json.parseToJsonElement(AgentIdeResult.jsonText("result", value)).jsonObject
        assertEquals(value, page["result"]!!.jsonPrimitive.content)
        assertFalse(page["truncated"]!!.jsonPrimitive.boolean)
    }
}
