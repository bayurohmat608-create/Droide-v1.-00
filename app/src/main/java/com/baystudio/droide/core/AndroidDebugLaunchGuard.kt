package com.baystudio.droide.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// A canceled/failed debug startup must not leave the target waiting for JDWP forever.
internal object AndroidDebugLaunchGuard {
    suspend fun <T> run(stopOnFailure: suspend () -> Unit, action: suspend () -> T): T {
        try {
            return action()
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { stopOnFailure() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }
}
