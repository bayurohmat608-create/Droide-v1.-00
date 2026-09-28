package com.baystudio.droide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTabRetentionTest {
    private val fullTabs = (1..32).map { "file$it.kt" }

    @Test fun `opening tab thirty three evicts oldest clean and keeps unsaved active`() {
        val tabs = EditorTabRetention.open(fullTabs, "new.kt", "file1.kt") { it == "file1.kt" }
        assertEquals(32, tabs?.size)
        assertTrue("file1.kt" in tabs.orEmpty())
        assertFalse("file2.kt" in tabs.orEmpty())
        assertEquals("new.kt", tabs?.last())
    }

    @Test fun `opening tab fails when every tab has unsaved changes`() {
        assertNull(EditorTabRetention.open(fullTabs, "new.kt", "file1.kt") { true })
        assertEquals(fullTabs, EditorTabRetention.open(fullTabs, "file1.kt", "file1.kt") { true })
    }

    @Test fun `existing hidden dirty document remains accessible when all visible tabs are dirty`() {
        val restored = EditorTabRetention.open(fullTabs, "hidden.kt", "file1.kt", allowExistingDirtyOverflow = true) { true }
        assertEquals(33, restored?.size)
        assertTrue("hidden.kt" in restored.orEmpty())
    }

    @Test fun `legacy recovery brings dirty buffers outside saved tabs back into view`() {
        val outside = listOf("hidden33.kt", "hidden34.kt")
        val restored = EditorTabRetention.restore(fullTabs, fullTabs + outside, outside.toSet() + "file1.kt")
        assertTrue(restored.containsAll(outside + "file1.kt"))
        assertEquals(32, restored.size)
        assertFalse("file32.kt" in restored)
    }

    @Test fun `recovery keeps active clean tab when dirty buffers displace clean tabs`() {
        val restored = EditorTabRetention.restore(fullTabs, fullTabs + "hidden.kt", setOf("hidden.kt"), "file32.kt")
        assertTrue("hidden.kt" in restored)
        assertTrue("file32.kt" in restored)
        assertFalse("file31.kt" in restored)
    }

    @Test fun `recovery never hides a dirty buffer even when legacy snapshot exceeds limit`() {
        val dirty = (1..40).map { "dirty$it.kt" }
        val restored = EditorTabRetention.restore(fullTabs, fullTabs + dirty, dirty.toSet())
        assertEquals(dirty, restored)
        assertNull(EditorTabRetention.open(restored, "new.kt", dirty.first()) { true })
    }

    @Test fun `workspace edit plan rejects a second file when only one clean slot exists`() {
        val edited = setOf("new1.kt", "new2.kt")
        try {
            EditorTabRetention.planWorkspaceEdit(fullTabs, edited, "file1.kt") { it != "file2.kt" }
            throw AssertionError("Workspace edit must be rejected before any buffer changes")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("Unsaved editor tabs") == true)
        }
    }

    @Test fun `recovery with no saved open tabs still exposes a dirty file`() {
        assertEquals(listOf("orphan.kt"), EditorTabRetention.restore(emptyList(), listOf("orphan.kt"), setOf("orphan.kt")))
    }

    @Test fun `unreadable original does not discard recovered content including empty edits`() {
        val recovered = EditorDocument("damaged.kt")
        recovered.restoreUnsaved("unsaved changes")
        assertEquals("unsaved changes", recovered.content)
        assertTrue(recovered.dirty)
        assertTrue(recovered.editable)

        val empty = EditorDocument("deleted.kt")
        empty.restoreUnsaved("")
        assertTrue(empty.dirty)
    }
}
