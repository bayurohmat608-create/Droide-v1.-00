package com.baystudio.droide.core


internal enum class AgentPromptDrainPhase { IDLE, STARTING, READY }

internal data class AgentPromptDrainSnapshot(
    val phase: AgentPromptDrainPhase = AgentPromptDrainPhase.IDLE,
    val lease: Long = 0L,
)

internal sealed interface AgentPromptDrainRoute {
    data class Start(val lease: Long) : AgentPromptDrainRoute
    data class WaitForStartup(val lease: Long) : AgentPromptDrainRoute
    data class Admit(val lease: Long) : AgentPromptDrainRoute
}

internal class AgentPromptDrainState {
    private var nextLease = 0L
    var snapshot: AgentPromptDrainSnapshot = AgentPromptDrainSnapshot()
        private set

    fun routeSubmission(): AgentPromptDrainRoute = when (snapshot.phase) {
        AgentPromptDrainPhase.IDLE -> {
            val lease = ++nextLease
            snapshot = AgentPromptDrainSnapshot(AgentPromptDrainPhase.STARTING, lease)
            AgentPromptDrainRoute.Start(lease)
        }
        AgentPromptDrainPhase.STARTING -> AgentPromptDrainRoute.WaitForStartup(snapshot.lease)
        AgentPromptDrainPhase.READY -> AgentPromptDrainRoute.Admit(snapshot.lease)
    }

    fun reserveDirectRun(): Long {
        check(snapshot.phase == AgentPromptDrainPhase.IDLE) { "Agent session is already active" }
        val lease = ++nextLease
        snapshot = AgentPromptDrainSnapshot(AgentPromptDrainPhase.STARTING, lease)
        return lease
    }

    fun markReady(lease: Long): Boolean {
        if (snapshot.phase != AgentPromptDrainPhase.STARTING || snapshot.lease != lease) return false
        snapshot = AgentPromptDrainSnapshot(AgentPromptDrainPhase.READY, lease)
        return true
    }

    fun markIdle(lease: Long): Boolean {
        if (snapshot.lease != lease || snapshot.phase == AgentPromptDrainPhase.IDLE) return false
        snapshot = AgentPromptDrainSnapshot(AgentPromptDrainPhase.IDLE, lease)
        return true
    }

    fun isReady(lease: Long): Boolean =
        snapshot.phase == AgentPromptDrainPhase.READY && snapshot.lease == lease

    // Reset visible lifecycle while preserving monotonic leases so stale finalizers cannot collide.
    fun reset() {
        snapshot = AgentPromptDrainSnapshot(AgentPromptDrainPhase.IDLE, nextLease)
    }
}
