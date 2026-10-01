package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalProcessSupervisorTest {
    private val cwd = File(requireNotNull(System.getProperty("java.io.tmpdir")))
    @Test fun tailCaptureRetainsFinalBuildFailure() = runBlocking {
        val result = LocalProcessSupervisor.capture(
            listOf("/bin/sh", "-c", "head -c 100000 /dev/zero; printf 'FINAL: compilation failed'; exit 7"), cwd,
            maxOutputBytes = 1024, keepTail = true,
        )
        assertEquals(7, result.exitCode)
        assertTrue(result.output.contains("FINAL: compilation failed"))
        assertTrue(result.output.endsWith("[output truncated]\n"))
        assertTrue(result.output.length < 1100)
    }

    @Test fun streamedUnicodeMatchesCapturedOutput() = runBlocking {
        val chunks = StringBuilder()
        val result = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "printf '日本語 😀 selesai'"), cwd, onOutput = chunks::append)
        assertEquals("日本語 😀 selesai", result.output)
        assertEquals(result.output, chunks.toString())
    }
    @Test fun capturesExitStatusAndStderr() = runBlocking {
        val r = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "printf out; printf err >&2; exit 7"), cwd)
        assertEquals(7, r.exitCode); assertEquals("outerr", r.output); assertFalse(r.timedOut)
    }
    @Test fun drainsBeyondOutputLimit() = runBlocking {
        val r = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "head -c 2000000 /dev/zero"), cwd, maxOutputBytes = 1024, timeoutMs = 5_000)
        assertEquals(0, r.exitCode); assertTrue(r.output.endsWith("[output truncated]\n")); assertTrue(r.output.length < 1100)
    }
    @Test fun timesOutWithoutWaitingForOutput() = runBlocking {
        val start = System.nanoTime()
        val r = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exec sleep 20"), cwd, timeoutMs = 150)
        assertEquals(124, r.exitCode); assertTrue(r.timedOut)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2500)
    }
    @Test fun cancellationRunsCleanup() = runBlocking {
        val cleaned = AtomicBoolean(false)
        val task = launch { LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exec sleep 20"), cwd, cleanup = { cleaned.set(true) }) }
        delay(150)
        withTimeout(2500) { task.cancelAndJoin() }
        assertTrue(cleaned.get())
    }
    @Test fun streamsStdinAndClosesIt() = runBlocking {
        val r = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "wc -c"), cwd,
            input = { out -> repeat(300) { out.write(ByteArray(1000) { 42 }) } })
        assertEquals(0, r.exitCode); assertEquals("300000", r.output.trim())
    }
}
