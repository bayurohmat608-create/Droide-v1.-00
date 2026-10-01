package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunnerExecutionResultTest {
    @get:Rule val temp = TemporaryFolder()

    private class Terminal(vararg responses: ExecResult) : ITerminalSession {
        override val output = MutableStateFlow("")
        val calls = mutableListOf<List<String>>()
        private val queued = ArrayDeque(responses.toList())
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
            calls += argv
            return queued.removeFirst()
        }
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("Expected argv")
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    @Test fun successfulCompileFollowedByFailedRunIsFailure() { runBlocking {
        val terminal = Terminal(ExecResult(0, "compiled", false), ExecResult(7, "run failed", false))
        val result = Runner(terminal, temp.root).runPlanResult(RunPlan(listOf(listOf("compiler"), listOf("program")), "Compile and run"))
        assertEquals(7, result.exitCode)
        assertFalse(result.success)
        assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("ide", AgentIdeResult.runFile(result)).state)
    } }

    @Test fun firstFailureStopsTheSequence() { runBlocking {
        val terminal = Terminal(ExecResult(2, "compile failed", false))
        val result = Runner(terminal, temp.root).runPlanResult(RunPlan(listOf(listOf("compiler"), listOf("program")), "Compile and run"))
        assertFalse(result.success)
        assertEquals(listOf(listOf("compiler")), terminal.calls)
    } }

    @Test fun timeoutIsFailureEvenWithZeroExitCode() { runBlocking {
        val terminal = Terminal(ExecResult(0, "IDE_EXECUTION_PENDING\nexit=0", true))
        val result = Runner(terminal, temp.root).runPlanResult(RunPlan(listOf(listOf("program")), "Run"))
        assertTrue(result.timedOut)
        assertFalse(result.success)
        assertEquals(AgentToolEvidenceState.FAILED, AgentToolResultSemantics.classify("ide", AgentIdeResult.runFile(result)).state)
    } }

    @Test fun outputTruncationDoesNotEraseTheFinalFailure() { runBlocking {
        val terminal = Terminal(ExecResult(0, "a".repeat(9_000), false), ExecResult(0, "b".repeat(9_000), false), ExecResult(126, "not executable", false))
        val result = Runner(terminal, temp.root).runPlanResult(RunPlan(listOf(listOf("one"), listOf("two"), listOf("three")), "Run"))
        assertEquals(16_000, result.output.length)
        assertEquals(126, result.exitCode)
        assertFalse(result.success)
    } }

    @Test fun emptyCommandsNeverReportVerifiedExecution() { runBlocking {
        val terminal = Terminal()
        for (steps in listOf(emptyList(), listOf(emptyList<String>()))) {
            val result = Runner(terminal, temp.root).runPlanResult(RunPlan(steps, "No command"))
            assertFalse(result.processStarted)
            assertFalse(result.success)
        }
        assertTrue(terminal.calls.isEmpty())
    } }

    @Test fun completeSuccessfulSequenceKeepsArgvExact() { runBlocking {
        val terminal = Terminal(ExecResult(0, "exit=1\nBACKGROUND_JOB_STARTED", false), ExecResult(0, "done", false))
        val steps = listOf(listOf("compiler", "a ' file.kt"), listOf("program", "\$(touch sentinel)"))
        val result = Runner(terminal, temp.root).runPlanResult(RunPlan(steps, "Run"))
        assertTrue(result.success)
        assertEquals(steps, terminal.calls)
        assertEquals(AgentToolEvidenceState.SUCCEEDED, AgentToolResultSemantics.classify("ide", AgentIdeResult.runFile(result)).state)
    } }
}
