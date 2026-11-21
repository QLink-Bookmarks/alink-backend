package com.qlink.ai.client

import com.qlink.common.error.ErrorCode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

internal class AiVendorException(
    val errorCode: ErrorCode,
    val retryAfter: Duration? = null,
) : RuntimeException(errorCode.message) {
    companion object {
        fun fromStatus(
            status: Int,
            quotaExhausted: Boolean = false,
            retryAfter: String? = null,
            retryAfterMillis: String? = null,
        ): AiVendorException {
            val error =
                when {
                    status == 401 -> ErrorCode.AI_API_KEY_INVALID
                    status == 403 -> ErrorCode.AI_VENDOR_PERMISSION_DENIED
                    status == 408 || status in 500..599 -> ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE
                    quotaExhausted && status in 400..499 -> ErrorCode.AI_VENDOR_QUOTA_EXHAUSTED
                    status == 429 -> ErrorCode.AI_VENDOR_RATE_LIMITED
                    else -> ErrorCode.AI_VENDOR_REQUEST_REJECTED
                }
            val delay =
                if (error == ErrorCode.AI_VENDOR_RATE_LIMITED || error == ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE) {
                    retryAfterMillis?.let { parseNumericDelay(it, DurationUnit.MILLISECONDS) }
                        ?: retryAfter?.let(::parseRetryAfter)
                } else {
                    null
                }
            return AiVendorException(errorCode = error, retryAfter = delay)
        }

        private val numericDelay = Regex("[0-9]+(?:\\.[0-9]+)?")

        private fun parseNumericDelay(
            value: String,
            unit: DurationUnit,
        ): Duration? =
            value
                .trim()
                .takeIf(numericDelay::matches)
                ?.toDoubleOrNull()
                ?.toDuration(unit)

        private fun parseRetryAfter(value: String): Duration? {
            parseNumericDelay(value, DurationUnit.SECONDS)?.let { return it }
            return try {
                val retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                val remaining = java.time.Duration.between(java.time.Instant.now(), retryAt)
                if (remaining.isNegative) Duration.ZERO else (remaining.seconds + if (remaining.nano == 0) 0 else 1).seconds
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
