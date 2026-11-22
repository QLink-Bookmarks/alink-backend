package com.qlink.ai.client

import com.google.genai.types.BlockedReason
import com.google.genai.types.FinishReason
import com.qlink.ai.dto.AiSummaryClientResponse
import com.qlink.ai.dto.GeminiSummaryResponse
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

private val geminiResponseJson = Json { ignoreUnknownKeys = true }

internal fun parseGeminiSummary(body: String): AiSummaryClientResponse =
    try {
        if (body.isBlank()) {
            throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
        }
        val response = geminiResponseJson.decodeFromString<GeminiSummaryResponse>(body)
        if (response.error != null) {
            throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
        }
        val blockReason = response.promptFeedback?.blockReason?.takeIf(String::isNotBlank)
        if (blockReason != null && blockReason != BlockedReason.Known.BLOCKED_REASON_UNSPECIFIED.name) {
            throw BusinessException(ErrorCode.AI_SUMMARY_PROMPT_BLOCKED)
        }

        val candidate = response.candidates.firstOrNull()
        val finishReason = candidate?.finishReason?.takeIf(String::isNotBlank)
        finishReason?.geminiFinishError()?.let { throw BusinessException(it) }
        val text =
            candidate
                ?.content
                ?.parts
                ?.firstNotNullOfOrNull { part -> part.text?.trim()?.takeIf { !part.thought && it.isNotBlank() } }
                ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
        parseVendorSummary(rawResponse = text, totalTokens = response.usageMetadata.geminiTotalTokens())
    } catch (_: IllegalArgumentException) {
        throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
    }

private fun String.geminiFinishError(): ErrorCode? =
    when (FinishReason(this).knownEnum()) {
        FinishReason.Known.STOP -> {
            null
        }

        FinishReason.Known.FINISH_REASON_UNSPECIFIED -> {
            ErrorCode.AI_SUMMARY_INCOMPLETE.takeUnless { this == FinishReason.Known.FINISH_REASON_UNSPECIFIED.name }
        }

        FinishReason.Known.MAX_TOKENS, FinishReason.Known.CONTINUATION -> {
            ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_REACHED
        }

        FinishReason.Known.SAFETY,
        FinishReason.Known.RECITATION,
        FinishReason.Known.BLOCKLIST,
        FinishReason.Known.PROHIBITED_CONTENT,
        FinishReason.Known.SPII,
        FinishReason.Known.IMAGE_SAFETY,
        FinishReason.Known.IMAGE_PROHIBITED_CONTENT,
        FinishReason.Known.IMAGE_RECITATION,
        FinishReason.Known.ESCALATION,
        FinishReason.Known.MODEL_ARMOR,
        -> {
            ErrorCode.AI_SUMMARY_OUTPUT_BLOCKED
        }

        FinishReason.Known.PUP_LIMITED_DISABLED -> {
            ErrorCode.AI_VENDOR_PERMISSION_DENIED
        }

        FinishReason.Known.MALFORMED_RESPONSE -> {
            ErrorCode.AI_SUMMARY_RESPONSE_INVALID
        }

        else -> {
            ErrorCode.AI_SUMMARY_INCOMPLETE
        }
    }

private fun JsonElement?.geminiTotalTokens(): Long? {
    if (this == null) {
        return null
    }
    val usage = this as? JsonObject ?: throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID)
    val total = usage["totalTokenCount"] ?: return 0L
    return (total as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?: throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID)
}

internal fun geminiHttpError(
    status: Int,
    body: String,
): ErrorCode {
    val reasons =
        (vendorErrorObject(body)?.get("details") as? JsonArray)
            .orEmpty()
            .mapNotNull { detail ->
                (detail as? JsonObject)
                    ?.takeIf { it.errorString("@type") == "type.googleapis.com/google.rpc.ErrorInfo" }
                    ?.errorString("reason")
            }
    if (status in 400..499 && "API_KEY_INVALID" in reasons) {
        return ErrorCode.AI_API_KEY_INVALID
    }
    return aiVendorErrorCode(status = status, quotaExhausted = reasons.any { it in geminiQuotaReasons })
}

private val geminiQuotaReasons = setOf("QUOTA_EXCEEDED", "BILLING_DISABLED", "BILLING_NOT_ACTIVE")
