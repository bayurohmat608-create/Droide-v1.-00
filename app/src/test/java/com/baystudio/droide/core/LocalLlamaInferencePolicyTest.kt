package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalLlamaInferencePolicyTest {
    @Test fun conservativePhonePlanIsBounded() {
        val model = 2L * 1024 * 1024 * 1024
        val memory = LocalLlamaInferencePolicy.MemorySnapshot(
            totalBytes = 8L * 1024 * 1024 * 1024,
            availableBytes = 5L * 1024 * 1024 * 1024,
            cpuCount = 8,
        )
        val plan = LocalLlamaInferencePolicy.plan(model, memory)
        assertEquals(512, plan.contextSize)
        assertEquals(1, plan.predictTokens)
        assertEquals(64, plan.batchSize)
        assertEquals(64, plan.ubatchSize)
        assertEquals(2, plan.threads)
        assertEquals(1L * 1024 * 1024 * 1024, plan.systemReserveBytes)
        assertEquals(512L * 1024 * 1024, plan.workingOverheadBytes)
        assertEquals(model + plan.systemReserveBytes + plan.workingOverheadBytes, plan.requiredAvailableBytes)
    }

    @Test fun lowAvailableMemoryFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlamaInferencePolicy.plan(
                modelBytes = 4L * 1024 * 1024 * 1024,
                memory = LocalLlamaInferencePolicy.MemorySnapshot(
                    totalBytes = 8L * 1024 * 1024 * 1024,
                    availableBytes = 4L * 1024 * 1024 * 1024,
                    cpuCount = 8,
                ),
            )
        }
    }

    @Test fun impossibleModelSizeFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlamaInferencePolicy.plan(
                modelBytes = 10L * 1024 * 1024 * 1024,
                memory = LocalLlamaInferencePolicy.MemorySnapshot(
                    totalBytes = 8L * 1024 * 1024 * 1024,
                    availableBytes = 7L * 1024 * 1024 * 1024,
                    cpuCount = 4,
                ),
            )
        }
    }
}
