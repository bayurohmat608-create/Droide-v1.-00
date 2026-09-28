package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLanguageServerPlanTest {
    @Test fun priorityPlanCoversCoreAuditTargets() {
        ManagedLanguageServerPlan.validate()
        val ids = ManagedLanguageServerPlan.priority.map { it.languageServerId }.toSet()
        assertTrue(setOf("python-pyright", "python-pylsp", "typescript", "clangd", "gopls", "rust-analyzer", "kotlin", "jdtls").all { it in ids })
    }

    @Test fun everyPlanCommandIsLaunchableByItsRegisteredServerSpec() {
        ManagedLanguageServerPlan.priority.forEach { plan ->
            val spec = LanguageServerRegistry.builtIns.firstOrNull { it.id == plan.languageServerId }
            assertNotNull(plan.languageServerId, spec)
            assertTrue(spec!!.commands.any { it.firstOrNull() == plan.command })
        }
    }

    @Test fun runtimeRequirementsRemainExplicit() {
        assertEquals(LanguageServerRuntimeKind.NODE, ManagedLanguageServerPlan.forServer("python-pyright")?.runtime)
        assertEquals(LanguageServerRuntimeKind.ANDROID_NATIVE, ManagedLanguageServerPlan.forServer("gopls")?.runtime)
        val rust = ManagedLanguageServerPlan.forServer("rust-analyzer")!!
        assertEquals(LanguageServerRuntimeKind.ANDROID_NATIVE, rust.runtime)
        assertEquals("20260914", rust.pinnedVersion)
        val typescript = ManagedLanguageServerPlan.forServer("typescript")!!
        assertEquals(RuntimeVersion(22, 22, 2), typescript.runtimeRequirements.single().let { RuntimeVersion(it.minimumMajor, it.minimumMinor, it.minimumPatch) })
        val jdtls = ManagedLanguageServerPlan.forServer("jdtls")!!
        assertEquals(LanguageServerRuntimeKind.JVM, jdtls.runtime)
        assertEquals("1.61.0", jdtls.pinnedVersion)
        assertEquals(21, jdtls.runtimeRequirements.single().minimumMajor)
    }
}
