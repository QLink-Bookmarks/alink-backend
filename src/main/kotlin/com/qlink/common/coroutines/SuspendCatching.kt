package com.qlink.common.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun <T> catchingFailure(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        currentCoroutineContext().ensureActive()
        Result.failure(error)
    } catch (error: Throwable) {
        Result.failure(error)
    }
