package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class DebugManagerTest {
    private lateinit var root: java.io.File
    private lateinit var scope: CoroutineScope
    private lateinit var debugger: DebugManager

    @Before fun setUp() {
        root = Files.createTempDirectory("droide-debug-").toFile()
        root.resolve("Main.kt").writeText("fun main() = Unit\n")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val files = FileRepository(root)
        debugger = DebugManager(scope, files, TerminalManager(scope, root))
    }

    @After fun tearDown() {
        debugger.close()
        scope.cancel()
        PathSecurity.deleteTreeNoFollow(root)
    }

    @Test fun breakpointStateIsDeduplicatedAndToggleable() {
        debugger.addBreakpoint("Main.kt", 1)
        debugger.addBreakpoint("Main.kt", 1)
        assertEquals(1, debugger.breakpoints.value.getValue("Main.kt").size)
        debugger.toggleBreakpoint("Main.kt", 1)
        assertEquals(0, debugger.breakpoints.value.getValue("Main.kt").size)
    }

    @Test fun breakpointsRejectInvalidOrEscapingLocations() {
        assertThrows(IllegalArgumentException::class.java) { debugger.addBreakpoint("Main.kt", 0) }
        assertThrows(IllegalArgumentException::class.java) { debugger.addBreakpoint("../outside.kt", 1) }
    }

    @Test fun watchesAreTrimmedDeduplicatedAndRemovable() {
        debugger.addWatch("  value.count  ")
        debugger.addWatch("value.count")
        assertEquals(listOf("value.count"), debugger.watches.value.map { it.expression })
        debugger.removeWatch("value.count")
        assertEquals(emptyList<DebugWatch>(), debugger.watches.value)
        assertThrows(IllegalArgumentException::class.java) { debugger.addWatch("   ") }
    }
    @Test fun breakpointPreservesRequestedLineWhenAdapterRelocatesExecutableLine() {
        val requested = DebugBreakpoint("Main.kt", line = 11)
        val relocated = requested.copy(line = 12, verified = true, id = 7)
        assertEquals(11, relocated.requestedLine)
        assertEquals(12, relocated.line)
    }

}
