package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeVersionTest {
    @Test fun parsesCommonRuntimeOutputs() {
        assertEquals(RuntimeVersion(22, 22, 2), RuntimeVersion.parseFirst("v22.22.2"))
        assertEquals(RuntimeVersion(3, 9, 20), RuntimeVersion.parseFirst("Python 3.9.20"))
        assertEquals(RuntimeVersion(21, 0, 8), RuntimeVersion.parseFirst("openjdk version \"21.0.8\" 2026-07-15"))
    }

    @Test fun comparesSemanticTriples() {
        assertTrue(RuntimeVersion(22, 22, 2) >= RuntimeVersion(22, 22, 2))
        assertTrue(RuntimeVersion(22, 23, 0) > RuntimeVersion(22, 22, 99))
        assertTrue(RuntimeVersion(21) < RuntimeVersion(22))
        assertNull(RuntimeVersion.parseFirst("no version here"))
    }
}
