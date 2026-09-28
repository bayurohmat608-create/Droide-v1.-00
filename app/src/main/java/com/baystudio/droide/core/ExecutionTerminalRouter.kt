package com.baystudio.droide.core

import kotlinx.coroutines.flow.StateFlow







class ExecutionTerminalRouter(
    private val terminals: TerminalManager,
    private val fallback: ITerminalSession,
) : ITerminalSession {
    override val output: StateFlow<String> get() = fallback.output
    override fun start() = Unit
    override fun send(cmd: String) = Unit

    private fun unavailable(): ExecResult = ExecResult(
        exitCode = 126,
        output = "Local Linux execution unavailable: ${terminals.automatedExecutionUnavailableReason()}",
        timedOut = false,
    )

    override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
        val session = terminals.automatedExecutionSession(createIfMissing = true) ?: return unavailable()
        return session.execOnce(cmd, timeoutMs)
    }

    override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
        val session = terminals.automatedExecutionSession(createIfMissing = true) ?: return unavailable()
        return session.execStreaming(cmd, timeoutMs, onOutput)
    }

    override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
        val session = terminals.automatedExecutionSession(createIfMissing = true) ?: return unavailable()
        return session.execArgv(argv, timeoutMs)
    }

    override fun interrupt() = terminals.existingAutomatedExecutionSession()?.interrupt() ?: Unit
    override fun clear() = Unit

     
    override fun destroy() = Unit

}
