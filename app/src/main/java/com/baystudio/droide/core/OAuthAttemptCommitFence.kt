package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicBoolean

// If cancellation wins, a late network response can never authorize persistence.






internal class OAuthAttemptCommitFence {
    private val active = AtomicBoolean(true)

    fun isActive(): Boolean = active.get()

    fun cancel(): Boolean = active.compareAndSet(true, false)

    fun tryClaimCommit(): Boolean = active.compareAndSet(true, false)
}
