package com.baystudio.droide.core









object LocalLlamaInferencePolicy {
    const val POLICY_VERSION = 1
    const val CONTEXT_SIZE = 512
    const val PREDICT_TOKENS = 1
    const val BATCH_SIZE = 64
    const val UBATCH_SIZE = 64
    const val MAX_THREADS = 2
    const val CERTIFICATION_TIMEOUT_MS = 4L * 60L * 1000L
    const val MAX_RETAINED_OUTPUT_BYTES = 512 * 1024
    const val MIN_MODEL_BYTES = 24L
    const val MAX_MODEL_BYTES = 96L * 1024L * 1024L * 1024L

    const val MIN_SYSTEM_RESERVE_BYTES = 768L * 1024L * 1024L
    const val MIN_WORKING_OVERHEAD_BYTES = 512L * 1024L * 1024L
    const val MAX_WORKING_OVERHEAD_BYTES = 1536L * 1024L * 1024L

    data class MemorySnapshot(
        val totalBytes: Long,
        val availableBytes: Long,
        val cpuCount: Int,
    ) {
        init {
            require(totalBytes > 0L) { "Total memory must be positive" }
            require(availableBytes in 1L..totalBytes) { "Available memory is invalid" }
            require(cpuCount in 1..512) { "CPU count is invalid" }
        }
    }

    data class Plan(
        val contextSize: Int,
        val predictTokens: Int,
        val batchSize: Int,
        val ubatchSize: Int,
        val threads: Int,
        val systemReserveBytes: Long,
        val workingOverheadBytes: Long,
        val requiredAvailableBytes: Long,
    )

    fun plan(modelBytes: Long, memory: MemorySnapshot): Plan {
        require(modelBytes in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            "GGUF model size is outside the supported certification range"
        }
        require(modelBytes < memory.totalBytes) {
            "GGUF model is too large for the target's physical memory"
        }

        val systemReserve = maxOf(MIN_SYSTEM_RESERVE_BYTES, memory.totalBytes / 8L)
        val proportionalWorking = modelBytes / 6L
        val working = proportionalWorking.coerceIn(MIN_WORKING_OVERHEAD_BYTES, MAX_WORKING_OVERHEAD_BYTES)
        val required = Math.addExact(Math.addExact(modelBytes, working), systemReserve)
        require(memory.availableBytes >= required) {
            "Insufficient available memory for bounded local-model certification: " +
                "need at least ${formatBytes(required)}, have ${formatBytes(memory.availableBytes)}"
        }

        return Plan(
            contextSize = CONTEXT_SIZE,
            predictTokens = PREDICT_TOKENS,
            batchSize = BATCH_SIZE,
            ubatchSize = UBATCH_SIZE,
            threads = minOf(MAX_THREADS, memory.cpuCount).coerceAtLeast(1),
            systemReserveBytes = systemReserve,
            workingOverheadBytes = working,
            requiredAvailableBytes = required,
        )
    }

    internal fun formatBytes(bytes: Long): String {
        val mib = bytes / (1024L * 1024L)
        return if (mib < 1024L) "$mib MiB" else String.format(java.util.Locale.US, "%.1f GiB", mib / 1024.0)
    }
}
