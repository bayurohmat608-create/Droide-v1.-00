package com.baystudio.droide.core

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleRuntimeCompatibilityTest {
    @Test fun acceptsGradle813OnJdk21() {
        assertNull(GradleRuntimeCompatibility.problem(21, "8.13"))
    }

    @Test fun rejectsGradle84OnJdk21() {
        assertTrue(GradleRuntimeCompatibility.problem(21, "8.4").orEmpty().contains("8.5"))
    }

    @Test fun rejectsGradle9OnLegacyJdkEvenWhenMinimumWasOlder() {
        assertTrue(GradleRuntimeCompatibility.problem(11, "9.0.0").orEmpty().contains("JDK 17"))
    }

    @Test fun acceptsReleaseCandidateUsingBaseVersion() {
        assertNull(GradleRuntimeCompatibility.problem(24, "8.14-rc-1"))
    }

    @Test fun rejectsAgp8OnJdk11() {
        assertTrue(AndroidGradlePluginJdkCompatibility.problem(11, setOf("8.13.2")).orEmpty().contains("JDK 17"))
    }

    @Test fun acceptsAgp9OnJdk17() {
        assertNull(AndroidGradlePluginJdkCompatibility.problem(17, setOf("9.4.0")))
    }

    @Test fun customWrapperHasNoInventedStaticVerdict() {
        assertNull(GradleRuntimeCompatibility.problem(21, null))
    }
}
