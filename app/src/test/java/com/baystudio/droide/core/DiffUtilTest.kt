package com.baystudio.droide.core

import org.junit.Assert.assertTrue
import org.junit.Test

class DiffUtilTest {
    @Test fun handlesLargeFilesWithoutQuadraticMatrix() {
        val old = (1..10_000).joinToString("\n") { "line-$it" }
        val new = old.replace("line-5000", "line-five-thousand")
        val diff = DiffUtil.unified(old, new)
        assertTrue(diff.contains("-line-5000"))
        assertTrue(diff.contains("+line-five-thousand"))
    }
}
