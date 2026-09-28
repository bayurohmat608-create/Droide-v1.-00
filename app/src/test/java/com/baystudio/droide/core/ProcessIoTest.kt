package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessIoTest {
    @Test fun outputIsBoundedAndKeepsTail() {
        val input = ("A".repeat(20_000) + "TAIL").byteInputStream()
        val result = ProcessIo.readTextBounded(input, 1_024)
        assertEquals(1_024, result.length)
        assertTrue(result.endsWith("TAIL"))
    }
}
