package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException

// Result wrapper for suspend work that never converts coroutine cancellation into an ordinary failure.
suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (fatal: Error) {
    throw fatal
} catch (t: Throwable) {
    Result.failure(t)
}
