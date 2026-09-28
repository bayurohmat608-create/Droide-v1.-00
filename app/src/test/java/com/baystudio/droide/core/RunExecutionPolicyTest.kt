package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunExecutionPolicyTest {
    @Test
    fun admittedLocalRuntimeIsSelected() {
        val plan = RunPlan(listOf(listOf("python3", "main.py")), "python3 main.py")
        val decision = RunExecutionPolicy.decide(plan, localRuntimeAvailable = true)
        assertEquals(RunExecutionPolicy.Target.LOCAL_LINUX_ARM64, decision.target)
        assertEquals(plan, decision.plan)
    }

    @Test
    fun disconnectedRuntimeFailsClosedBeforeProcessBuilder() {
        val plan = RunPlan(listOf(listOf("python3", "main.py")), "python3 main.py")
        val decision = RunExecutionPolicy.decide(plan, localRuntimeAvailable = false)
        assertEquals(RunExecutionPolicy.Target.UNAVAILABLE, decision.target)
        assertEquals("python3", decision.unavailableCommand)
        assertTrue(RunExecutionPolicy.unavailableMessage("python3").contains("Local Linux"))
    }

    @Test
    fun systemShellDoesNotBypassAppUidBoundary() {
        val plan = RunPlan(listOf(listOf("/system/bin/sh", "script.sh")), "sh script.sh")
        val decision = RunExecutionPolicy.decide(plan, localRuntimeAvailable = false)
        assertEquals(RunExecutionPolicy.Target.UNAVAILABLE, decision.target)
        assertEquals("/system/bin/sh", decision.unavailableCommand)
    }

    @Test
    fun generatedWorkspaceExecutableNeverFallsThroughLocalAppUid() {
        val plan = RunPlan(
            listOf(
                listOf("gcc", "main.c", "-o", ".droide/run/c"),
                listOf("./.droide/run/c"),
            ),
            "gcc main.c -o .droide/run/c && ./.droide/run/c",
        )
        val decision = RunExecutionPolicy.decide(plan, localRuntimeAvailable = false)
        assertEquals(RunExecutionPolicy.Target.UNAVAILABLE, decision.target)
        assertEquals("gcc", decision.unavailableCommand)
    }
}
