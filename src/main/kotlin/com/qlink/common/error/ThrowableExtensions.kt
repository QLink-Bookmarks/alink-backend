package com.qlink.common.error

internal fun Throwable?.messageOrFallback(fallback: String): String =
    this
        ?.message
        ?.takeIf { it.isNotBlank() }
        ?: fallback
