package com.baystudio.droide.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

 
fun CoroutineScope.launchUiCatching(
    onError: suspend (Throwable) -> Unit = {},
    block: suspend () -> Unit,
): Job = launch {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        onError(failure)
    }
}
