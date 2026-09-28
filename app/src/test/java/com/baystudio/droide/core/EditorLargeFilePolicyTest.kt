package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLargeFilePolicyTest {
    @Test fun professionalTierBoundariesAreStable() {
        assertEquals(
            EditorFilePerformanceMode.FULL_INTELLIGENCE,
            EditorLargeFilePolicy.classify(2_500L * 1024L, 10_000).mode,
        )
        assertEquals(
            EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED,
            EditorLargeFilePolicy.classify(2_500L * 1024L + 1L, 10_000).mode,
        )
        assertEquals(
            EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED,
            EditorLargeFilePolicy.classify(128_000L, 300_001).mode,
        )
        assertEquals(
            EditorFilePerformanceMode.PREVIEW_ONLY,
            EditorLargeFilePolicy.classify(20L * 1024L * 1024L + 1L, 10).mode,
        )
    }

    @Test fun utf8AccountingDoesNotAssumeAscii() {
        assertEquals(3L, EditorLargeFilePolicy.utf8Bytes("abc"))
        assertEquals(2L, EditorLargeFilePolicy.utf8Bytes("é"))
        assertEquals(4L, EditorLargeFilePolicy.utf8Bytes("😀"))
        assertTrue(EditorLargeFilePolicy.lineCount("a\nb\n") == 3)
    }
}
