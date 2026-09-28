package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.*

 
data class ProcessRunResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
    val durationMs: Long,
)

object ProcessRunner {
    suspend fun run(
        argv: List<String>,
        cwd: File,
        timeoutMs: Long,
        outputCap: Int,
        stdinText: String? = null,
        environment: Map<String, String> = emptyMap(),
    ): ProcessRunResult = withContext(Dispatchers.IO) {
        ProcessSecurityPolicy.validateArgv(argv)
        ProcessSecurityPolicy.validateEnvironment(environment)
        require(timeoutMs in 1..30L * 60_000L) { "Invalid process timeout" }
        require(outputCap in 1..1_000_000) { "Invalid process output cap" }
        require(cwd.isDirectory) { "Process working directory does not exist" }

        val started = System.nanoTime()
        var process: Process? = null
        var reader: Deferred<String>? = null
        try {
            val p = ProcessBuilder(argv)
                .directory(cwd)
                .redirectErrorStream(true)
                .apply { environment().putAll(environment) }
                .start()
            process = p
            reader = async(Dispatchers.IO) { ProcessIo.readTextBounded(p.inputStream, outputCap) }

            if (stdinText != null) {
                p.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(stdinText)
                    writer.flush()
                }
            } else {
                
                runCatching { p.outputStream.close() }
            }

            val finished = waitForResponsive(p, timeoutMs)
            if (!finished) {
                ProcessIo.terminate(p, graceMs = 100)
                val partial = withTimeoutOrNull(1_500) { reader.await() }.orEmpty()
                return@withContext ProcessRunResult(
                    exitCode = -1,
                    output = partial.takeLast(outputCap),
                    timedOut = true,
                    durationMs = elapsedMs(started),
                )
            }
            val output = withTimeoutOrNull(2_000) { reader.await() }.orEmpty().takeLast(outputCap)
            ProcessRunResult(p.exitValue(), output, false, elapsedMs(started))
        } catch (cancel: CancellationException) {
            reader?.cancel()
            ProcessIo.terminate(process, graceMs = 100)
            throw cancel
        } finally {
            reader?.cancel()
            runCatching { process?.inputStream?.close() }
            runCatching { process?.outputStream?.close() }
            runCatching { process?.errorStream?.close() }
            if (process?.isAlive == true) ProcessIo.terminate(process, graceMs = 100)
        }
    }

    private suspend fun waitForResponsive(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (process.isAlive) {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() >= deadline) return false
            delay(40)
        }
        return true
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000L
}
