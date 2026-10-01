package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TerminalLaunchSpecTest {
    @Test fun executesMultilineScriptWithGuestEnvironment() = runBlocking {
        val spec = TerminalLaunchSpec(prefix = listOf("/usr/bin/env"), shell = "/bin/sh")
        val argv = spec.shellCommand("cat <<'END'\nhello\nEND\nprintf '%s' \"\$DROIDE_JOB\"", mapOf("DROIDE_JOB" to "value with spaces"))
        val result = LocalProcessSupervisor.capture(argv, File(System.getProperty("java.io.tmpdir")))
        assertEquals(0, result.exitCode)
        assertEquals("hello\nvalue with spaces", result.output)
    }

    @Test fun rejectsNulAndOversizedScripts() {
        val spec = TerminalLaunchSpec(shell = "/bin/sh")
        for (script in listOf("echo\u0000bad", "x".repeat(32_001))) {
            try { spec.shellCommand(script); fail("Expected invalid script") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun argvAndEnvironmentRemainValidated() {
        val spec = TerminalLaunchSpec(shell = "/bin/sh")
        try { spec.command(listOf("echo", "two\nlines")); fail("Expected argv rejection") } catch (_: IllegalArgumentException) { }
        try { spec.shellCommand("true", mapOf("BAD-KEY" to "value")); fail("Expected env rejection") } catch (_: IllegalArgumentException) { }
    }
}
