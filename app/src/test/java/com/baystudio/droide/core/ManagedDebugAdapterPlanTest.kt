package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDebugAdapterPlanTest {
    @Test fun kotlinJdwpPlanIsPinnedAndRequiresJvm() {
        ManagedDebugAdapterPlan.validate()
        val plan = ManagedDebugAdapterPlan.forAdapter("kotlin-jdwp")!!
        assertEquals("debug.kotlin-debug-adapter", plan.packageFamilyId)
        assertEquals("0.4.4", plan.pinnedVersion)
        assertEquals("kotlin-debug-adapter", plan.command)
        assertTrue("java" in plan.requiredCommands)
    }
}
