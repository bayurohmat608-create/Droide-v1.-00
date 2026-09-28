package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GradleTaskPathTest {
    @Test fun parsesRootAndNestedModuleTasks() {
        val root = GradleTaskPath.parse("assembleDebug")
        assertNull(root.modulePath)
        assertEquals("assembleDebug", root.taskName)

        val nested = GradleTaskPath.parse(":features:feature-login:testDebugUnitTest")
        assertEquals("features/feature-login", nested.modulePath)
        assertEquals("testDebugUnitTest", nested.taskName)
    }

    @Test fun rejectsCliOptionsTraversalAndMalformedPaths() {
        listOf(
            "--offline",
            "-q",
            ":..:assembleDebug",
            ":app::assembleDebug",
            "assembleDebug --offline",
            ":-evil:assembleDebug",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { GradleTaskPath.parse(value) }
        }
    }
}
