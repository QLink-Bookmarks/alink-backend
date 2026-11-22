package com.qlink.ai.client

import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.dto.AiApiKeyValidationRequest
import com.qlink.ai.dto.AiSummaryClientRequest
import com.qlink.ai.dto.AiSummaryClientResponse
import com.qlink.ai.dto.AiSummaryResult
import com.qlink.ai.dto.AiSummaryTodo
import com.qlink.common.error.ErrorCode
import kotlinx.serialization.json.Json
import kotlin.time.Instant

interface AiClient {
    val providerType: AiProviderType

    suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse

    suspend fun validateApiKey(request: AiApiKeyValidationRequest)
}

internal fun aiVendorErrorCode(
    status: Int,
    quotaExhausted: Boolean = false,
): ErrorCode =
    when {
        status == 401 -> ErrorCode.AI_API_KEY_INVALID
        status == 403 -> ErrorCode.AI_VENDOR_PERMISSION_DENIED
        status == 408 || status in 500..599 -> ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE
        quotaExhausted && status in 400..499 -> ErrorCode.AI_VENDOR_QUOTA_EXHAUSTED
        status == 429 -> ErrorCode.AI_VENDOR_RATE_LIMITED
        else -> ErrorCode.AI_VENDOR_REQUEST_REJECTED
    }

private val summaryJson = Json { ignoreUnknownKeys = true }

internal fun parseSummaryResponse(
    rawResponse: String,
    usedTokens: Int,
): AiSummaryClientResponse {
    val normalizedResponse = rawResponse.extractJsonObject()
    val result = summaryJson.decodeFromString<AiSummaryResult>(normalizedResponse)

    return AiSummaryClientResponse(
        linkId = result.linkId ?: result.id,
        folderId = result.folderId,
        rawResponse = normalizedResponse,
        title = result.title,
        summary = result.summary,
        tags = result.tags,
        todos = result.todos.map { AiSummaryTodo(title = it.title, reminderAt = it.reminderAt?.let(Instant::parse)) },
        usedTokens = usedTokens,
    )
}

private fun String.extractJsonObject(): String {
    val text = trim().removeMarkdownJsonFence().trim()
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')

    return if (start in 0..end) {
        text.substring(start, end + 1)
    } else {
        text
    }
}

private fun String.removeMarkdownJsonFence(): String =
    removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
