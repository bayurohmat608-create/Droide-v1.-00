package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDocumentAuthorityTest {
    private fun snap(path: String, content: String, dirty: Boolean = false) = WorkspaceDocumentSnapshot(
        path = path,
        content = content,
        loaded = true,
        kind = WorkspaceDocumentKind.TEXT,
        dirty = dirty,
        editable = true,
        revision = 1,
        changeVersion = if (dirty) 2 else 0,
        selectionStart = 0,
        selectionEnd = 0,
        savedContentLength = content.length,
    )

    private class FakeAuthority(private val items: List<WorkspaceDocumentSnapshot>) : WorkspaceDocumentAuthority {
        override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = items.firstOrNull { it.path == path }
        override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = items
    }

    @Test fun unboundBridgeIsEmptyAndBindingForwardsSnapshots() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        assertFalse(bridge.isBound)
        assertNull(bridge.snapshot("Main.kt"))
        assertTrue(bridge.snapshots().isEmpty())

        val expected = snap("Main.kt", "fun main() = Unit\n", dirty = true)
        val binding = bridge.bind(FakeAuthority(listOf(expected)))
        assertTrue(bridge.isBound)
        assertEquals(expected, bridge.snapshot("Main.kt"))
        assertEquals(listOf(expected), bridge.dirtySnapshots())

        binding.close()
        assertFalse(bridge.isBound)
        assertNull(bridge.snapshot("Main.kt"))
    }

    @Test fun staleBindingCannotDetachNewerAuthority() = runBlocking {
        val bridge = WorkspaceDocumentAuthorityBridge()
        val first = bridge.bind(FakeAuthority(listOf(snap("First.kt", "first"))))
        val secondExpected = snap("Second.kt", "second")
        val second = bridge.bind(FakeAuthority(listOf(secondExpected)))

        first.close()
        assertTrue(bridge.isBound)
        assertEquals(secondExpected, bridge.snapshot("Second.kt"))

        second.close()
        assertFalse(bridge.isBound)
    }
}
