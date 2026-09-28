package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExecutableExtensionHostPolicyTest {
    @Test fun defaultHostLimitsAreBoundedAndProduceProcessLimits() {
        val limits = ExtensionHostLimits()
        limits.validate()
        assertEquals(2 * 1024 * 1024, limits.maxProtocolMessageBytes)
        assertEquals(120, limits.processResourceLimits().cpuSeconds)
        assertEquals(768 * 1024, limits.processResourceLimits().virtualMemoryKiB)
    }

    @Test fun rejectsUnboundedHostAndProcessLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionHostLimits(commandTimeoutMs = 999_999).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessResourceLimits(virtualMemoryKiB = 64 * 1024).validate()
        }
    }

    @Test fun isolationNameDoesNotPretendToBeHardSandbox() {
        assertEquals("PROCESS_SEPARATE_REVIEWED", ExtensionHostIsolationKind.PROCESS_SEPARATE_REVIEWED.name)
    }
}
