package com.baystudio.droide.core


internal data class RunnerExecutionResult(
    val output: String,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val processStarted: Boolean = false,
) {
    val success: Boolean get() = processStarted && exitCode == 0 && !timedOut
}
