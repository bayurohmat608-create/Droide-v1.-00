package com.baystudio.droide.core

internal object SafWriteRecoveryPolicy {
    enum class Decision { ORIGINAL_UNCHANGED, INTENDED_COMMITTED, RESTORE_ORIGINAL, CONFLICT }

    fun decide(actual: String?, previous: String?, intended: String, committed: Boolean): Decision = when {
        actual == previous -> Decision.ORIGINAL_UNCHANGED
        actual == intended && committed -> Decision.INTENDED_COMMITTED
        actual == intended -> Decision.RESTORE_ORIGINAL
        else -> Decision.CONFLICT
    }
}
