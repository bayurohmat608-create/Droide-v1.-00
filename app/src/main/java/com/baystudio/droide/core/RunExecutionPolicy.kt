package com.baystudio.droide.core







object RunExecutionPolicy {
    enum class Target { LOCAL_LINUX_ARM64, UNAVAILABLE }

    data class Decision(
        val target: Target,
        val plan: RunPlan,
        val unavailableCommand: String? = null,
    )

    fun decide(plan: RunPlan, localRuntimeAvailable: Boolean): Decision {
        return if (localRuntimeAvailable) Decision(Target.LOCAL_LINUX_ARM64, plan)
        else Decision(Target.UNAVAILABLE, plan, plan.steps.firstOrNull()?.firstOrNull() ?: "runtime")
    }

    fun unavailableMessage(command: String): String = buildString {
        append("Runner: automated execution of '").append(command.take(200)).append("' is blocked.\n")
        append("Local Linux ARM64 substrate is unavailable or corrupt.")
    }
}
