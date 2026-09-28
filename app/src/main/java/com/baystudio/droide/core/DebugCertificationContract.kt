package com.baystudio.droide.core

 
object DebugCertificationContract {
    fun normalizeSourcePath(path: String): String = path
        .replace('\\', '/')
        .removePrefix("./")
        .trimStart('/')

    fun provesBreakpoint(stop: DebugStopSnapshot, breakpointId: Int?): Boolean {
        if (breakpointId != null && stop.hitBreakpointIds.isNotEmpty()) {
            return breakpointId in stop.hitBreakpointIds
        }
        return stop.reason.contains("breakpoint", ignoreCase = true)
    }

    fun provesStep(stop: DebugStopSnapshot): Boolean = stop.reason.equals("step", ignoreCase = true)

    fun frameMatches(frame: DebugFrame, activeFile: String, resolvedLine: Int): Boolean =
        frame.line == resolvedLine &&
            frame.path?.let(::normalizeSourcePath) == normalizeSourcePath(activeFile)
}
