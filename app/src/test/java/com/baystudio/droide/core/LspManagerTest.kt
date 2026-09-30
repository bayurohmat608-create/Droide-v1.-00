package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LspManagerTest {
    private lateinit var root: java.io.File

    @Before fun setUp() {
        root = Files.createTempDirectory("droide-lsp-").toFile()
    }

    @After fun tearDown() {
        PathSecurity.deleteTreeNoFollow(root)
    }

    @Test fun pythonFallbackUsesLiteralArgvAndFallsBackToPyCompile() = runBlocking {
        val terminal = RecordingTerminal { argv ->
            when (argv.firstOrNull()) {
                "/bin/sh" -> ExecResult(0, "python3", false)
                "python3" -> ExecResult(0, "", false)
                else -> ExecResult(1, "unexpected", false)
            }
        }
        val lsp: LspManager = CliLspManager(terminal, FileRepository(root))
        val result = lsp.diagnose("main.py")
        assertTrue(result.startsWith("OK: main.py"))
        assertEquals("/bin/sh", terminal.argvCalls[0].first())
        assertTrue(terminal.argvCalls[0].last().contains("command -v ruff"))
        assertEquals(listOf("python3", "-m", "py_compile", "main.py"), terminal.argvCalls[1])
        assertTrue(terminal.shellCalls.isEmpty())
    }

    @Test fun documentSymbolsAreAvailableWithoutExternalServer() = runBlocking {
        root.resolve("Main.kt").writeText("class Engine\nfun start() = Unit\n")
        val lsp: LspManager = CliLspManager(RecordingTerminal { ExecResult(0, "", false) }, FileRepository(root))
        val result = lsp.lsp("documentSymbol", "Main.kt")
        assertTrue(result.contains("class Engine"))
        assertTrue(result.contains("fun start"))
    }

    private class RecordingTerminal(
        private val responder: suspend (List<String>) -> ExecResult,
    ) : ITerminalSession {
        private val state = MutableStateFlow("")
        override val output: StateFlow<String> = state
        val argvCalls = mutableListOf<List<String>>()
        val shellCalls = mutableListOf<String>()

        override fun start() = Unit
        override fun send(cmd: String) { shellCalls += cmd }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            shellCalls += cmd
            return ExecResult(0, "", false)
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
            argvCalls += argv
            return responder(argv)
        }
        override fun interrupt() = Unit
        override fun clear() { state.value = "" }
        override fun destroy() = Unit
    }
}
