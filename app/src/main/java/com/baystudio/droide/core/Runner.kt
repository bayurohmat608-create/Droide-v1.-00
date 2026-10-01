package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Filenames are passed as argv and never interpolated into a shell.
class Runner(
    private val terminal: ITerminalSession,
    private val workDir: File,
    private val contributedRunPlan: (String) -> RunPlan? = { null },
) {
    sealed interface FilePlanResolution {
        data class Ready(val plan: RunPlan) : FilePlanResolution
        data class Error(val message: String) : FilePlanResolution
    }

    fun resolveFilePlan(relPath: String): FilePlanResolution =
        resolveFilePlan(relPath, workDir, contributedRunPlan)

    companion object {
        // Resolving a run plan must never require constructing a Local shell.
        fun resolveFilePlan(
            relPath: String,
            workDir: File,
            contributedRunPlan: (String) -> RunPlan? = { null },
        ): FilePlanResolution {
            val cliResult = DroideCli.handle(listOf("run", relPath), workDir)
            val resolved = if (cliResult is DroideCli.Result.Err) {
                contributedRunPlan(relPath)?.let { DroideCli.Result.Run(it) } ?: cliResult
            } else cliResult
            return when (resolved) {
                is DroideCli.Result.Err -> FilePlanResolution.Error(resolved.msg)
                is DroideCli.Result.Open -> FilePlanResolution.Error("unexpected open result")
                is DroideCli.Result.Run -> FilePlanResolution.Ready(resolved.plan)
                is DroideCli.Result.Pkg -> FilePlanResolution.Error("package commands cannot be run as a file")
            }
        }
    }

    suspend fun runFile(relPath: String): String = withContext(Dispatchers.IO) {
        when (val resolved = resolveFilePlan(relPath)) {
            is FilePlanResolution.Error -> "Runner: ${resolved.message}"
            is FilePlanResolution.Ready -> executePlan(resolved.plan, 30_000).output
        }
    }

     
    suspend fun runPlan(plan: RunPlan, timeoutMs: Long = 120_000): String = runPlanResult(plan, timeoutMs).output

    internal suspend fun runPlanResult(plan: RunPlan, timeoutMs: Long = 120_000): RunnerExecutionResult = withContext(Dispatchers.IO) {
        require(timeoutMs in 1_000L..15L * 60_000L) { "Invalid tool timeout" }
        executePlan(plan, timeoutMs)
    }

    private suspend fun executePlan(plan: RunPlan, timeoutMs: Long): RunnerExecutionResult {
        if (plan.steps.isEmpty() || plan.steps.any { it.isEmpty() }) {
            return RunnerExecutionResult("Runner: run plan contains no executable command")
        }
        val runDirectory = PathSecurity.resolveWithin(workDir, ".droide/run")
        check(runDirectory.mkdirs() || runDirectory.isDirectory) { "Could not prepare run directory" }
        if (terminal is DevicePtySessionHandle) {
            terminal.refreshWorkspace()
            val prepare = terminal.execArgv(listOf("mkdir", "-p", ".droide/run"))
            if (prepare.exitCode != 0) {
                return RunnerExecutionResult("Runner: could not prepare remote run directory\n${prepare.output.take(8_000)}")
            }
        }
        val out = StringBuilder("▶ ${plan.display}\n")
        var lastExit: Int? = null
        var timedOut = false
        for (originalStep in plan.steps) {
            val step = resolveCompilerAlias(originalStep)
            val r = terminal.execArgv(step, timeoutMs = timeoutMs)
            lastExit = r.exitCode
            timedOut = r.timedOut
            out.append("exit=${r.exitCode}\n${r.output.take(8_000)}\n")
            if (r.exitCode != 0 || r.timedOut) {
                if (r.exitCode in setOf(-1, 126, 127)) {
                    out.append("Runtime/tool is unavailable in the selected execution environment. Install the tool in the Linux terminal or use a supported Extensions installer.\n")
                }
                if (r.timedOut) out.append("Tool execution timed out after ${timeoutMs}ms.\n")
                break
            }
        }
        return RunnerExecutionResult(out.toString().take(16_000), lastExit, timedOut, processStarted = true)
    }

    private suspend fun resolveCompilerAlias(step: List<String>): List<String> {
        if (step.isEmpty()) return step
        val alternative = when (step.first()) {
            "gcc" -> "clang"
            "g++" -> "clang++"
            else -> return step
        }
        val probe = terminal.execArgv(listOf(alternative, "--version"), timeoutMs = 5_000)
        return if (probe.exitCode == 0) listOf(alternative) + step.drop(1) else step
    }

}
