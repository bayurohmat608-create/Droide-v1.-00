package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalResizeAuthorityTest {
    @Test
    fun burstIsConflatedToNewestGeometry() = runBlocking {
        val authority = TerminalResizeAuthority()
        assertTrue(authority.offer(80, 24))
        assertTrue(authority.offer(90, 25))
        assertTrue(authority.offer(100, 26))

        val next = withTimeout(1_000) { authority.receive() }
        assertEquals(TerminalResizeAuthority.Geometry(100, 26), next)
        assertEquals(next, authority.latest())
        authority.close()
    }

    @Test
    fun duplicateGeometryIsNoOpAndBoundsAreCanonical() {
        val authority = TerminalResizeAuthority(1, 999)
        assertEquals(TerminalResizeAuthority.Geometry(2, 300), authority.latest())
        assertFalse(authority.offer(2, 300))
        assertTrue(authority.offer(5000, -4))
        assertEquals(TerminalResizeAuthority.Geometry(500, 2), authority.latest())
        authority.close()
    }
}
