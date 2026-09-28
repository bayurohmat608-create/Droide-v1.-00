package com.baystudio.droide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessSecurityPolicyTest {
    @Test fun remoteExecutableMustStayInSystemApexOrDroideRoot() {
        val root = "/data/local/tmp/droide"
        assertTrue(ProcessSecurityPolicy.isAllowedRemoteExecutable("/system/bin/sh", root))
        assertTrue(ProcessSecurityPolicy.isAllowedRemoteExecutable("/apex/com.android.runtime/bin/linker64", root))
        assertTrue(ProcessSecurityPolicy.isAllowedRemoteExecutable("$root/managed/bin/gopls", root))
        assertFalse(ProcessSecurityPolicy.isAllowedRemoteExecutable("/data/local/tmp/other/tool", root))
        assertFalse(ProcessSecurityPolicy.isAllowedRemoteExecutable("$root/../other/tool", root))
        assertFalse(ProcessSecurityPolicy.isAllowedRemoteExecutable("$root//tool", root))
        assertFalse(ProcessSecurityPolicy.isAllowedRemoteExecutable("relative/tool", root))
    }

    @Test fun processArgumentsAndEnvironmentRejectControlCharacters() {
        ProcessSecurityPolicy.validateArgv(listOf("tool", "--stdio"))
        ProcessSecurityPolicy.validateEnvironment(mapOf("HOME" to "/data/local/tmp/droide/home"))
        assertThrows(IllegalArgumentException::class.java) {
            ProcessSecurityPolicy.validateArgv(listOf("tool", "bad\narg"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessSecurityPolicy.validateEnvironment(mapOf("BAD-KEY" to "value"))
        }
    }
}
