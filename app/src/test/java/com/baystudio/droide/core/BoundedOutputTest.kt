package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedOutputTest {
    @Test fun keepsOnlyBoundedPrefixWhileCountingEverything() {
        val out = BoundedOutput(8)
        out.write("abcdefghijk".toByteArray())
        assertEquals("abcdefgh", out.utf8())
        assertEquals(11L, out.totalBytes)
        assertTrue(out.truncated)
    }
}
