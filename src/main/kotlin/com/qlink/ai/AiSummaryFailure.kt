package com.qlink.ai

internal const val DEFAULT_FAILURE_MESSAGE = "AI summary generation failed"

internal fun Throwable?.failureSummary(): String =
    this
        ?.message
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_FAILURE_MESSAGE
