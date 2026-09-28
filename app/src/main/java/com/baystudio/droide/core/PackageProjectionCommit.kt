package com.baystudio.droide.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// Projection and durable registry share one commit boundary.
internal object PackageProjectionCommit {
    suspend fun <T> commit(
        before: List<T>,
        next: List<T>,
        project: suspend (List<T>) -> Unit,
        persist: (List<T>) -> Unit,
    ) {
        try {
            project(next)
            // No suspension between durable registry commit and return; cancellation cannot undo a committed registry.
            persist(next)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                
                try {
                    persist(before)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
                try {
                    project(before)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
    }
}
