package com.qlink.ai.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val DEFAULT_FAILURE_MESSAGE = "AI summary generation failed"

internal suspend fun <T> catchingFailure(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        currentCoroutineContext().ensureActive()
        Result.failure(error)
    } catch (error: Throwable) {
        Result.failure(error)
    }

internal fun Throwable?.failureSummary(): String =
    this
        ?.message
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_FAILURE_MESSAGE
