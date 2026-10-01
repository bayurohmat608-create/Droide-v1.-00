package com.baystudio.droide.core

import java.io.File

// Only CLI discovery and the Android PTY subtype are omitted. Runner executes against
// the actual ITerminalSession contract extracted by the verifier and scripted results.
internal interface DevicePtySessionHandle : ITerminalSession {
    suspend fun refreshWorkspace()
}
internal object DroideCli {
    sealed interface Result {
        data class Err(val msg: String) : Result
        data class Run(val plan: RunPlan) : Result
        data object Open : Result
        data object Pkg : Result
    }
    fun handle(args: List<String>, workDir: File): Result = error("CLI resolution is outside host Runner test scope")
}
