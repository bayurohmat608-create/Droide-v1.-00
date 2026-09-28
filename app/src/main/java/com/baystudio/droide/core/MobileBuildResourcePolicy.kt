package com.baystudio.droide.core








internal object MobileBuildResourcePolicy {
    private const val GIB = 1024L * 1024L * 1024L

    enum class Pressure { NORMAL, WARM, CONSTRAINED, CRITICAL }

    data class Snapshot(
        val cpuCount: Int,
        val totalMemoryBytes: Long,
        val availableMemoryBytes: Long,
        val lowMemory: Boolean,
        val powerSaveMode: Boolean,
         
        val thermalStatus: Int,
         
        val thermalHeadroom: Float?,
    )

    data class Plan(
        val pressure: Pressure,
        val maxWorkers: Int,
        val daemonIdleMillis: Long,
        val useBuildCache: Boolean,
         
        val forceConfigurationCache: Boolean = false,
    ) {
        init {
            require(maxWorkers in 1..4)
            require(daemonIdleMillis in 30_000L..15 * 60_000L)
            require(!forceConfigurationCache) { "Droide must not force configuration cache on unknown projects" }
        }
    }

    fun decide(snapshot: Snapshot): Plan {
        val cpu = snapshot.cpuCount.coerceAtLeast(1)
        val available = snapshot.availableMemoryBytes.coerceAtLeast(0L)
        val thermal = snapshot.thermalStatus.coerceIn(0, 6)
        val headroom = snapshot.thermalHeadroom?.takeIf { it.isFinite() && it >= 0f }

        val pressure = when {
            thermal >= 3 || (headroom != null && headroom >= 1.0f) -> Pressure.CRITICAL
            snapshot.lowMemory || available < 1 * GIB || thermal >= 2 ||
                (headroom != null && headroom >= 0.95f) -> Pressure.CONSTRAINED
            snapshot.powerSaveMode || available < 2 * GIB || thermal >= 1 ||
                (headroom != null && headroom >= 0.85f) -> Pressure.WARM
            else -> Pressure.NORMAL
        }

        val workers = when (pressure) {
            Pressure.CRITICAL, Pressure.CONSTRAINED -> 1
            Pressure.WARM -> minOf(2, cpu)
            Pressure.NORMAL -> when {
                available >= 5 * GIB && cpu >= 10 -> 4
                available >= 3 * GIB && cpu >= 6 -> 3
                cpu >= 2 -> 2
                else -> 1
            }
        }.coerceAtLeast(1)

        val idleMillis = when (pressure) {
            Pressure.NORMAL -> 10 * 60_000L
            Pressure.WARM -> 5 * 60_000L
            Pressure.CONSTRAINED -> 2 * 60_000L
            Pressure.CRITICAL -> 60_000L
        }

        return Plan(
            pressure = pressure,
            maxWorkers = workers,
            daemonIdleMillis = idleMillis,
            useBuildCache = true,
        )
    }
}
