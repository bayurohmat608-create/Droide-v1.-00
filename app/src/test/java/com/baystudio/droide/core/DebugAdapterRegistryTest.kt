package com.baystudio.droide.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugAdapterRegistryTest {
    @Test fun pythonAdapterUsesArgvNotShellString() {
        val spec = DebugAdapterRegistry.forPath("sample.py").single()
        assertEquals("python-debugpy", spec.id)
        assertTrue(spec.commandCandidates.all { it.size >= 3 && it[1] == "-m" && it[2] == "debugpy.adapter" })
        val args = spec.defaultArguments("sample.py", File("/tmp/project"))
        assertTrue(args.toString().contains("sample.py"))
    }

    @Test fun unknownExtensionHasNoFakeDebugger() {
        assertTrue(DebugAdapterRegistry.forPath("README.md").isEmpty())
    }
    @Test fun jdwpProxyAdvertisesTheExactIpv4LoopbackItBinds() {
        assertEquals("127.0.0.1", AndroidJdwpProxy.LOOPBACK_HOST)
    }

}
