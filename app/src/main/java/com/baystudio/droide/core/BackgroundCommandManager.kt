package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

// Durable complete command logs are a separate concern/checkpoint.







class BackgroundCommandManager(
    private val terminal: ITerminalSession,
) {
    enum class State { RUNNING, EXITED, FAILED, TIMED_OUT, KILLED }

    data class Snapshot(
        val id: String,
        val state: State,
        val command: String,
        val output: String,
        val exitCode: Int?,
        val timeoutMs: Long?,
        val createdAtMs: Long,
        val finishedAtMs: Long?,
        val launchDiskState: AgentDiskExecutionState = AgentDiskExecutionState.CLEAN,
    )

    private data class Entry(
        val id: String,
        val command: String,
        val timeoutMs: Long?,
        val createdAtMs: Long,
        var state: State = State.RUNNING,
        var output: String = "",
        var exitCode: Int? = null,
        var finishedAtMs: Long? = null,
        val launchDiskState: AgentDiskExecutionState = AgentDiskExecutionState.CLEAN,
        var job: Job? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val counter = AtomicLong(0)
    private val entries = LinkedHashMap<String, Entry>()

    fun start(
        command: String,
        timeoutMs: Long? = null,
        launchDiskState: AgentDiskExecutionState = AgentDiskExecutionState.CLEAN,
    ): Snapshot {
        require(command.isNotBlank()) { "Background command cannot be blank" }
        require(command.length <= MAX_COMMAND_CHARS) { "Background command is too long" }
        require(timeoutMs == null || timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) { "Invalid background timeout" }

        val id = "bg-${counter.incrementAndGet()}"
        val entry = Entry(
            id = id,
            command = command,
            timeoutMs = timeoutMs,
            createdAtMs = System.currentTimeMillis(),
            launchDiskState = launchDiskState,
        )
        synchronized(lock) {
            pruneLocked()
            check(entries.values.count { it.state == State.RUNNING } < MAX_RUNNING_JOBS) {
                "Too many concurrent background jobs (max $MAX_RUNNING_JOBS)"
            }
            check(entries.size < MAX_JOBS) { "Background job history is full" }
            entries[id] = entry
        }
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = terminal.execStreaming(command, timeoutMs ?: NO_TIMEOUT) { chunk ->
                    appendOutput(id, chunk)
                }
                synchronized(lock) {
                    val current = entries[id] ?: return@synchronized
                    if (current.state == State.KILLED) return@synchronized
                    current.exitCode = result.exitCode
                    if (result.output.isNotBlank() && current.output.isBlank()) {
                        current.output = result.output.takeLast(MAX_LIVE_OUTPUT_CHARS)
                    }
                    current.state = when {
                        result.timedOut -> State.TIMED_OUT
                        result.exitCode == 0 -> State.EXITED
                        else -> State.FAILED
                    }
                    current.finishedAtMs = System.currentTimeMillis()
                }
            } catch (cancelled: CancellationException) {
                synchronized(lock) {
                    entries[id]?.let { current ->
                        current.state = State.KILLED
                        current.finishedAtMs = current.finishedAtMs ?: System.currentTimeMillis()
                    }
                }
                throw cancelled
            } catch (error: Throwable) {
                appendOutput(id, "\n[background error] ${error.message ?: error.javaClass.simpleName}\n")
                synchronized(lock) {
                    entries[id]?.let { current ->
                        current.state = State.FAILED
                        current.exitCode = -1
                        current.finishedAtMs = System.currentTimeMillis()
                    }
                }
            }
        }
        entry.job = launched
        launched.start()
        return snapshot(id) ?: error("Background job disappeared during launch")
    }

    fun snapshot(id: String): Snapshot? = synchronized(lock) { entries[id]?.toSnapshot() }

    fun list(): List<Snapshot> = synchronized(lock) {
        entries.values.map { it.toSnapshot() }.sortedByDescending { it.createdAtMs }
    }

    suspend fun kill(id: String): Snapshot? {
        val runningJob = synchronized(lock) {
            val entry = entries[id] ?: return null
            if (entry.state != State.RUNNING) return entry.toSnapshot()
            entry.state = State.KILLED
            entry.finishedAtMs = System.currentTimeMillis()
            entry.job
        }
        runningJob?.cancelAndJoin()
        return snapshot(id)
    }

    fun close() {
        synchronized(lock) {
            entries.values.filter { it.state == State.RUNNING }.forEach {
                it.state = State.KILLED
                it.finishedAtMs = System.currentTimeMillis()
            }
        }
        scope.cancel("Workspace closed")
    }

    private fun appendOutput(id: String, chunk: String) {
        if (chunk.isEmpty()) return
        val clean = Ansi.strip(chunk)
        synchronized(lock) {
            entries[id]?.let { entry ->
                entry.output = (entry.output + clean).takeLast(MAX_LIVE_OUTPUT_CHARS)
            }
        }
    }

    private fun Entry.toSnapshot() = Snapshot(
        id = id,
        state = state,
        command = command,
        output = output,
        exitCode = exitCode,
        timeoutMs = timeoutMs,
        createdAtMs = createdAtMs,
        finishedAtMs = finishedAtMs,
        launchDiskState = launchDiskState,
    )

    private fun pruneLocked() {
        while (entries.size >= MAX_JOBS) {
            val removable = entries.values.firstOrNull { it.state != State.RUNNING } ?: break
            entries.remove(removable.id)
        }
    }

    companion object {
        const val NO_TIMEOUT = 0L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 86_400_000L
        const val MAX_COMMAND_CHARS = 128_000
        const val MAX_LIVE_OUTPUT_CHARS = 20_000
        const val MAX_JOBS = 32
        const val MAX_RUNNING_JOBS = 8
    }
}
