package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.*

// Blocking pipe workers cannot prevent deadline/cancellation supervision.
object LocalProcessSupervisor {
    suspend fun capture(
        argv: List<String>, cwd: File, environment: Map<String, String> = emptyMap(),
        maxOutputBytes: Int = 1_048_576, timeoutMs: Long = 30_000,
        input: ((OutputStream) -> Unit)? = null, cleanup: (() -> Unit)? = null,
        onOutput: ((String) -> Unit)? = null,
    ): ExecResult = withContext(Dispatchers.IO) {
        require(maxOutputBytes in 1..16_777_216 && timeoutMs in 1..86_400_000)
        val process = ProcessBuilder(argv).directory(cwd).redirectErrorStream(true)
            .apply { environment().putAll(environment) }.start()
        val failure = AtomicReference<Throwable?>()
        val bytes = ByteArrayOutputStream()
        var truncated = false
        val reader = thread(name = "droide-process-output", isDaemon = true) {
            try {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        synchronized(bytes) {
                            val keep = minOf(n, maxOutputBytes - bytes.size())
                            if (keep > 0) bytes.write(buffer, 0, keep)
                            if (keep < n) truncated = true
                        }
                        if (onOutput != null) {
                            runCatching { onOutput(String(buffer, 0, n, Charsets.UTF_8)) }
                        }
                    }
                }
            } catch (e: Throwable) { failure.compareAndSet(null, e) }
        }
        val writer = thread(name = "droide-process-input", isDaemon = true) {
            try { process.outputStream.use { input?.invoke(it) } }
            catch (e: Throwable) { failure.compareAndSet(null, e) }
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var timedOut = false
        try {
            while (process.isAlive || reader.isAlive || writer.isAlive) {
                currentCoroutineContext().ensureActive()
                failure.get()?.let { throw it }
                if (System.nanoTime() >= deadline) { timedOut = true; break }
                delay(25)
            }
            if (!timedOut) failure.get()?.let { throw it }
            val text = synchronized(bytes) { bytes.toString("UTF-8") + if (truncated) "\n[output truncated]\n" else "" }
            ExecResult(if (timedOut) 124 else process.exitValue(), text, timedOut)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { cleanup?.invoke() } finally { terminate(process) }
                reader.join(500); writer.join(500)
            }
        }
    }
    fun terminate(process: Process) {
        if (process.isAlive) process.destroy()
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }
}
