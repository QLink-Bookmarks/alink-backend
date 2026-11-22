package com.qlink.ai.client

import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import java.net.URI
import java.net.URISyntaxException

internal val claudeBillingErrorCodes =
    setOf("contract_spend_limit_reached", "linked_organization_billing_unavailable", "linked_organization_spend_limit_reached")

internal fun validClaudeApiKey(apiKey: String): String {
    if (apiKey.isBlank() || apiKey.any { it !in ' '..'~' }) throw BusinessException(ErrorCode.AI_API_KEY_INVALID)
    return apiKey
}

internal fun AiSummaryClientRequest.claudeSource(): URI {
    val source =
        try {
            URI(requestedUrl?.trim() ?: throw BusinessException(ErrorCode.AI_SUMMARY_SOURCE_URL_INVALID))
        } catch (_: URISyntaxException) {
            throw BusinessException(ErrorCode.AI_SUMMARY_SOURCE_URL_INVALID)
        }
    return source.takeIf { it.scheme == "https" && it.rawUserInfo == null && it.host != null }
        ?: throw BusinessException(ErrorCode.AI_SUMMARY_SOURCE_URL_INVALID)
}

internal fun validateClaudeStopReason(stopReason: String?) {
    when (stopReason) {
        "end_turn" -> Unit
        "refusal" -> throw BusinessException(ErrorCode.AI_SUMMARY_REFUSED)
        "max_tokens" -> throw BusinessException(ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_REACHED)
        else -> throw BusinessException(ErrorCode.AI_SUMMARY_INCOMPLETE)
    }
}

internal fun validateClaudeDocument(
    documentType: String?,
    sourceType: String?,
    mediaType: String?,
    data: String?,
) {
    val expectedMediaType =
        when (sourceType) {
            "text" -> "text/plain"
            "base64" -> "application/pdf"
            else -> throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
        }
    if (documentType != "document" || mediaType != expectedMediaType || data.isNullOrBlank()) {
        throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
    }
}

internal fun claudeTotalTokens(counts: List<Long>): Int {
    if (counts.any { it !in 0..Int.MAX_VALUE.toLong() }) throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID)
    return counts.sum().takeIf { it <= Int.MAX_VALUE }?.toInt() ?: throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID)
}

internal fun parseClaudeSummary(
    text: String,
    usedTokens: Int,
): AiSummaryClientResponse {
    val summary =
        try {
            parseSummaryResponse(rawResponse = text, usedTokens = usedTokens)
        } catch (_: IllegalArgumentException) {
            throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
        }
    if (summary.linkId == null) throw BusinessException(ErrorCode.AI_SUMMARY_LINK_MISMATCH)
    return summary
}
