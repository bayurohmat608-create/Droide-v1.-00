package com.baystudio.droide.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

 
internal class MobileBuildResourceGovernor(context: Context) {
    private val appContext = context.applicationContext

    fun snapshot(): MobileBuildResourcePolicy.Snapshot {
        val memory = ActivityManager.MemoryInfo()
        val activity = appContext.getSystemService(ActivityManager::class.java)
        runCatching { activity?.getMemoryInfo(memory) }

        val power = appContext.getSystemService(PowerManager::class.java)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else 0
        val thermalHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { power?.getThermalHeadroom(30)?.takeIf { it.isFinite() && it > 0f } }.getOrNull()
        } else null

        return MobileBuildResourcePolicy.Snapshot(
            cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            totalMemoryBytes = memory.totalMem.coerceAtLeast(0L),
            availableMemoryBytes = memory.availMem.coerceAtLeast(0L),
            lowMemory = memory.lowMemory,
            powerSaveMode = runCatching { power?.isPowerSaveMode == true }.getOrDefault(false),
            thermalStatus = thermalStatus,
            thermalHeadroom = thermalHeadroom,
        )
    }

    fun plan(): MobileBuildResourcePolicy.Plan = MobileBuildResourcePolicy.decide(snapshot())
}
