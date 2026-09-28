package com.baystudio.droide.core

 
object DeviceWorkstationStorageGuard {
    const val EMERGENCY_RESERVE_BYTES: Long = 512L * 1024L * 1024L

    data class Snapshot(
        val totalBytes: Long,
        val availableBytes: Long,
    )

    suspend fun snapshot(
        path: String = LocalExecutionSubstrate.localRoot(),
    ): Snapshot {
        LocalExecutionSubstrate.requireSafeLocalPath(path)
        val probe = LocalExecutionSubstrate.shellBounded(
            "df -k ${LocalExecutionSubstrate.shellQuote(path)}",
            maxOutputBytes = 16_384,
        )
        check(probe.exitCode == 0) { "Could not inspect free space: ${probe.output.takeLast(4_000)}" }
        return parseDf(probe.output)
    }

     
    suspend fun snapshot(bridge: DeviceBridgeManager): Snapshot {
        check(bridge.state.value.connected != null) { "Connect Device Workstation before checking device storage" }
        
        val probe = bridge.shellBounded(
            "df -k ${DeviceBridgeManager.shellQuote("/data/local/tmp")}",
            maxOutputBytes = 16_384,
        )
        check(probe.exitCode == 0 && !probe.truncated) {
            "Could not inspect device free space: ${probe.combined.takeLast(4_000)}"
        }
        return parseDf(probe.stdout)
    }

    internal fun parseDf(output: String): Snapshot {
        val line = output.trim().lineSequence().lastOrNull()?.trim().orEmpty()
        val fields = line.split(Regex("\\s+"))
        require(fields.size >= 4) { "Unexpected free-space report" }
        val totalKiB = fields[1].toLongOrNull() ?: error("Invalid total-space value")
        val usedKiB = fields[2].toLongOrNull() ?: error("Invalid used-space value")
        val availableKiB = fields[3].toLongOrNull() ?: error("Invalid free-space value")
        require(totalKiB >= 0 && usedKiB >= 0 && availableKiB >= 0 &&
            usedKiB <= totalKiB && availableKiB <= totalKiB - usedKiB) {
            "Invalid storage values"
        }
        return Snapshot(
            totalBytes = Math.multiplyExact(totalKiB, 1024L),
            availableBytes = Math.multiplyExact(availableKiB, 1024L),
        )
    }

    suspend fun availableBytes(
        path: String = LocalExecutionSubstrate.localRoot(),
    ): Long = snapshot(path).availableBytes

    suspend fun availableBytes(bridge: DeviceBridgeManager): Long = snapshot(bridge).availableBytes

    suspend fun requireHeadroom(
        additionalBytes: Long,
        purpose: String,
        reserveBytes: Long = EMERGENCY_RESERVE_BYTES,
    ): Long {
        validateBudget(additionalBytes, purpose, reserveBytes)
        return checkHeadroom(additionalBytes, purpose, reserveBytes, availableBytes())
    }

    suspend fun requireHeadroom(
        bridge: DeviceBridgeManager,
        additionalBytes: Long,
        purpose: String,
        reserveBytes: Long = EMERGENCY_RESERVE_BYTES,
    ): Long {
        validateBudget(additionalBytes, purpose, reserveBytes)
        return checkHeadroom(additionalBytes, purpose, reserveBytes, availableBytes(bridge))
    }

    private fun validateBudget(additionalBytes: Long, purpose: String, reserveBytes: Long) {
        require(additionalBytes >= 0L && reserveBytes >= 0L) { "Storage requirements must be non-negative" }
        require(purpose.isNotBlank() && purpose.length <= 160) { "Invalid storage-guard purpose" }
        Math.addExact(additionalBytes, reserveBytes)
    }

    internal fun checkHeadroom(additionalBytes: Long, purpose: String, reserveBytes: Long, available: Long): Long {
        validateBudget(additionalBytes, purpose, reserveBytes)
        val required = Math.addExact(additionalBytes, reserveBytes)
        check(available >= required) {
            "Not enough storage to $purpose. " +
                "Need ${formatBytes(required)} free (${formatBytes(additionalBytes)} operation + " +
                "${formatBytes(reserveBytes)} safety reserve), but only ${formatBytes(available)} is available."
        }
        return available
    }

    internal fun formatBytes(bytes: Long): String {
        val mib = bytes / (1024L * 1024L)
        return if (mib < 1024L) "$mib MiB" else String.format(java.util.Locale.US, "%.1f GiB", mib / 1024.0)
    }
}
