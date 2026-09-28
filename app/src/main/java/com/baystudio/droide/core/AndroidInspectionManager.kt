package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

 
data class AndroidProcessProfile(
    val packageName: String,
    val pid: Int,
    val totalPssKb: Long?,
    val nativeHeapKb: Long?,
    val javaHeapKb: Long?,
    val cpuPercent: Double?,
    val threadCount: Int?,
    val rawMeminfo: String,
)

data class AndroidLayoutSnapshot(
    val packageName: String?,
    val capturedAtEpochMs: Long,
    val xml: String,
)

object AndroidInspectionParsers {
    fun parseMemoryKb(text: String): Triple<Long?, Long?, Long?> {
        fun capture(vararg patterns: Regex): Long? = patterns.firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.getOrNull(1)?.replace(",", "")?.toLongOrNull()
        }
        val total = capture(
            Regex("(?im)^\\s*TOTAL\\s+PSS:\\s*([0-9,]+)"),
            Regex("(?im)^\\s*TOTAL\\s+([0-9,]+)\\s+"),
        )
        val native = capture(
            Regex("(?im)^\\s*Native Heap\\s+([0-9,]+)\\s+"),
            Regex("(?im)^\\s*Native Heap:\\s*([0-9,]+)"),
        )
        val java = capture(
            Regex("(?im)^\\s*Dalvik Heap\\s+([0-9,]+)\\s+"),
            Regex("(?im)^\\s*Java Heap:\\s*([0-9,]+)"),
        )
        return Triple(total, native, java)
    }

    fun parseCpuPercent(text: String, pid: Int): Double? {
        if (pid <= 0) return null
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val header = lines.firstOrNull { line ->
            val columns = line.split(Regex("\\s+"))
            columns.any { it.equals("PID", ignoreCase = true) } &&
                columns.any { it.equals("%CPU", ignoreCase = true) || it.equals("CPU%", ignoreCase = true) }
        }
        if (header != null) {
            val headerColumns = header.split(Regex("\\s+"))
            val pidIndex = headerColumns.indexOfFirst { it.equals("PID", ignoreCase = true) }
            val cpuIndex = headerColumns.indexOfFirst { it.equals("%CPU", ignoreCase = true) || it.equals("CPU%", ignoreCase = true) }
            if (pidIndex >= 0 && cpuIndex >= 0) {
                lines.forEach { line ->
                    val columns = line.split(Regex("\\s+"))
                    if (columns.getOrNull(pidIndex)?.toIntOrNull() == pid) {
                        return columns.getOrNull(cpuIndex)?.removeSuffix("%")?.toDoubleOrNull()
                            ?.takeIf { it in 0.0..10_000.0 }
                    }
                }
            }
        }
        return lines.firstNotNullOfOrNull { line ->
            val columns = line.split(Regex("\\s+"))
            if (columns.firstOrNull()?.toIntOrNull() != pid) return@firstNotNullOfOrNull null
            columns.drop(1).firstNotNullOfOrNull { token ->
                if (!token.endsWith('%')) null
                else token.dropLast(1).toDoubleOrNull()?.takeIf { it in 0.0..10_000.0 }
            }
        }
    }

    fun parseThreadCount(status: String): Int? =
        Regex("(?im)^Threads:\\s*(\\d+)\\s*$").find(status)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun validateUiAutomatorXml(xml: String) {
        require(xml.length in 20..2_000_000) { "Layout hierarchy is empty or unexpectedly large" }
        val trimmed = xml.trimStart()
        require(trimmed.startsWith("<?xml") || trimmed.startsWith("<hierarchy")) { "Invalid UI hierarchy payload" }
        require("<hierarchy" in xml) { "UI hierarchy root is missing" }
    }
}





class AndroidInspectionManager(private val bridge: DeviceBridgeManager) {
    suspend fun profilePackage(packageName: String): AndroidProcessProfile = withContext(Dispatchers.IO) {
        validatePackageName(packageName)
        check(bridge.state.value.connected != null) { "Device Workstation is not connected" }
        val pid = bridge.pidOf(packageName) ?: error("$packageName is not running")
        val mem = bridge.shellBounded(
            "dumpsys meminfo ${DeviceBridgeManager.shellQuote(packageName)}",
            maxOutputBytes = 512_000,
        )
        check(mem.exitCode == 0) { "dumpsys meminfo failed: ${mem.combined.take(2_000)}" }
        val top = bridge.shellBounded(
            "top -b -n 1 -p ${DeviceBridgeManager.shellQuote(pid.toString())}",
            maxOutputBytes = 128_000,
        )
        val status = bridge.shellBounded(
            "cat /proc/${DeviceBridgeManager.shellQuote(pid.toString())}/status",
            maxOutputBytes = 64_000,
        )
        val (total, native, java) = AndroidInspectionParsers.parseMemoryKb(mem.stdout)
        AndroidProcessProfile(
            packageName = packageName,
            pid = pid,
            totalPssKb = total,
            nativeHeapKb = native,
            javaHeapKb = java,
            cpuPercent = if (top.exitCode == 0) AndroidInspectionParsers.parseCpuPercent(top.stdout, pid) else null,
            threadCount = if (status.exitCode == 0) AndroidInspectionParsers.parseThreadCount(status.stdout) else null,
            rawMeminfo = mem.stdout.take(512_000),
        )
    }

    suspend fun captureLayoutHierarchy(packageName: String? = null): AndroidLayoutSnapshot = withContext(Dispatchers.IO) {
        packageName?.let(::validatePackageName)
        check(bridge.state.value.connected != null) { "Device Workstation is not connected" }
        packageName?.let { target ->
            check(bridge.pidOf(target) != null) { "$target is not running" }
            val focus = bridge.shellBounded(
                "dumpsys window windows | toybox grep -E 'mCurrentFocus|mFocusedApp' | toybox head -n 4",
                maxOutputBytes = 32_000,
            )
            check(focus.exitCode == 0 && target in focus.stdout) { "$target is not the focused app; refusing an ambiguous layout capture" }
        }
        val path = DeviceBridgeManager.remoteRoot() + "/tmp/layout-hierarchy.xml"
        DeviceBridgeManager.requireSafeRemotePath(path)
        val dump = bridge.shellBounded(
            "rm -f ${DeviceBridgeManager.shellQuote(path)}; " +
                "uiautomator dump --compressed ${DeviceBridgeManager.shellQuote(path)} >/dev/null; " +
                "cat ${DeviceBridgeManager.shellQuote(path)}; rm -f ${DeviceBridgeManager.shellQuote(path)}",
            maxOutputBytes = 2_000_000,
        )
        check(dump.exitCode == 0) { "UI hierarchy capture failed: ${dump.combined.take(2_000)}" }
        AndroidInspectionParsers.validateUiAutomatorXml(dump.stdout)
        AndroidLayoutSnapshot(packageName, System.currentTimeMillis(), dump.stdout)
    }

    private fun validatePackageName(packageName: String) {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))) { "Invalid Android package name" }
        require(packageName.length <= 240) { "Android package name is too long" }
    }
}
