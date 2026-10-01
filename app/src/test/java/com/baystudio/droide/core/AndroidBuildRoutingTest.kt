package com.baystudio.droide.core

import com.baystudio.droide.core.AndroidDevelopmentManager.BuildBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidBuildRoutingTest {
    @Test fun deviceCertificationDoesNotProbeOrExecuteAnAvailableLocalToolchain() = runBlocking {
        val result = AndroidBuildRouting.execute(
            BuildBackend.DEVICE_WORKSTATION,
            resolveLocal = { error("Device certification must not discover local tools") },
            runLocal = { _: String -> error("Device evidence must not come from a local build") },
            runDevice = { "device-evidence" },
        )
        assertEquals("device-evidence", result)
    }

    @Test fun automaticBuildUsesHealthyLocalToolsWithoutRequiringADevice() = runBlocking {
        val result = AndroidBuildRouting.execute(null, { "selected-jdk-sdk" }, { it }, { error("No device is connected") })
        assertEquals("selected-jdk-sdk", result)
    }

    @Test fun automaticBuildFallsBackToDeviceWhenLocalToolsAreAbsent() = runBlocking {
        val result = AndroidBuildRouting.execute(null, { null as String? }, { error("No local toolchain") }, { "device-fallback" })
        assertEquals("device-fallback", result)
    }

    @Test fun requiredLocalBackendCannotSilentlyProduceDeviceEvidence() = runBlocking {
        var deviceRan = false
        val failure = runCatching {
            AndroidBuildRouting.execute(BuildBackend.LOCAL_UBUNTU, { null as String? }, { it }, { deviceRan = true; "device" })
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(deviceRan)
    }

    @Test fun canceledDiscoveryDoesNotStartTheFallbackBuild() = runBlocking {
        var deviceRan = false
        val failure = runCatching {
            AndroidBuildRouting.execute(null, { throw CancellationException("stop") }, { _: String -> "local" }, { deviceRan = true; "device" })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(deviceRan)
    }
}
