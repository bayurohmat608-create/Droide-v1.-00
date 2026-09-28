package com.baystudio.droide.core

enum class SafMirrorPhase {
    PREPARING,
    COPYING_EXTERNAL,
    SCANNING_LOCAL,
    RESOLVING,
    CANCELLING,
    APPLYING,
    FINALIZING,
}

data class SafMirrorProgress(
    val phase: SafMirrorPhase,
    val filesProcessed: Int = 0,
    val bytesProcessed: Long = 0L,
    val currentPath: String? = null,
) {
    // The final external/local merge is deliberately atomic/non-cancellable.
    val cancellable: Boolean
        get() = phase == SafMirrorPhase.PREPARING ||
            phase == SafMirrorPhase.COPYING_EXTERNAL ||
            phase == SafMirrorPhase.SCANNING_LOCAL ||
            phase == SafMirrorPhase.RESOLVING
}
