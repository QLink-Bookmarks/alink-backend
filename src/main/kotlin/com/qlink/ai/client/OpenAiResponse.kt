package com.qlink.ai.client

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.openai.core.JsonValue
import com.openai.core.jsonMapper
import com.openai.errors.OpenAIInvalidDataException
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseOutputMessage
import com.openai.models.responses.ResponseStatus
import com.qlink.ai.dto.AiSummaryClientResponse
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import kotlin.jvm.optionals.getOrNull

private val responseReader =
    jsonMapper()
        .readerFor(Response::class.java)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

internal fun parseOpenAiSummary(body: String): AiSummaryClientResponse {
    if (body.isBlank()) {
        throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
    }
    return try {
        val response = responseReader.readValue<Response>(body) ?: throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
        response.verifyCompletion()
        val messages =
            if (response._output().isMissing()) {
                emptyList()
            } else {
                response.output().mapNotNull { item ->
                    item.message().getOrNull() ?: item._json().getOrNull()?.legacyMessage()
                }
            }
        if (messages.any { message -> message.content().any { it.refusal().isPresent } }) {
            throw BusinessException(ErrorCode.AI_SUMMARY_REFUSED)
        }

        val text =
            messages.firstNotNullOfOrNull { message ->
                message
                    .content()
                    .firstNotNullOfOrNull { part ->
                        val raw = part.outputText().getOrNull()?.text() ?: part._json().getOrNull()?.legacyText()
                        raw?.trim()?.takeIf(String::isNotBlank)
                    }?.also {
                        if (message.isUnfinished()) {
                            throw BusinessException(ErrorCode.AI_SUMMARY_INCOMPLETE)
                        }
                    }
            }
        if (text == null) {
            val error =
                if (messages.any { it.isUnfinished() }) {
                    ErrorCode.AI_SUMMARY_INCOMPLETE
                } else {
                    ErrorCode.AI_EMPTY_RESPONSE
                }
            throw BusinessException(error)
        }

        val total =
            try {
                response.usage().getOrNull()?.let { usage ->
                    if (usage._totalTokens().isMissing()) {
                        0L
                    } else {
                        usage.totalTokens()
                    }
                }
            } catch (_: OpenAIInvalidDataException) {
                throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID)
            }
        parseVendorSummary(rawResponse = text, totalTokens = total)
    } catch (_: JsonProcessingException) {
        throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
    } catch (_: OpenAIInvalidDataException) {
        throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
    } catch (_: IllegalArgumentException) {
        throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
    }
}

private fun ResponseOutputMessage.isUnfinished(): Boolean = !_status().isMissing() && status() != ResponseOutputMessage.Status.COMPLETED

private fun Response.verifyCompletion() {
    val reason = incompleteDetails().getOrNull()?.reason()?.getOrNull()
    val error = error().getOrNull()?.code()?.asString()
    val code =
        when {
            error != null -> openAiDiagnostic(diagnostics = listOf(error), summarizing = true) ?: ErrorCode.AI_SUMMARY_INCOMPLETE
            reason == Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS -> ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_REACHED
            reason == Response.IncompleteDetails.Reason.CONTENT_FILTER -> ErrorCode.AI_SUMMARY_OUTPUT_BLOCKED
            reason != null -> ErrorCode.AI_SUMMARY_INCOMPLETE
            status().getOrNull()?.let { it != ResponseStatus.COMPLETED } == true -> ErrorCode.AI_SUMMARY_INCOMPLETE
            else -> null
        }
    if (code != null) {
        throw BusinessException(code)
    }
}

private fun JsonValue.legacyMessage(): ResponseOutputMessage? {
    val fields = asObject().getOrNull() ?: throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
    if (fields["type"] != null || "content" !in fields) {
        return null
    }
    return convert(ResponseOutputMessage::class.java) ?: throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
}

private fun JsonValue.legacyText(): String? {
    val fields = asObject().getOrNull() ?: throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
    if (fields["type"] != null && fields["type"]?.asString()?.getOrNull() != "output_text") {
        return null
    }
    val text = fields["text"]?.takeUnless(JsonValue::isNull) ?: return null
    return text.asString().getOrNull() ?: throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
}

internal fun openAiHttpError(
    status: Int,
    body: String,
    summarizing: Boolean = false,
): ErrorCode {
    val failure = vendorErrorObject(body)
    return openAiHttpError(
        status = status,
        diagnostics = listOfNotNull(failure?.errorString("code"), failure?.errorString("type")),
        summarizing = summarizing,
    )
}

internal fun openAiHttpError(
    status: Int,
    diagnostics: List<String>,
    summarizing: Boolean,
): ErrorCode =
    if (status in 400..499 && status !in setOf(401, 403, 408)) {
        openAiDiagnostic(diagnostics = diagnostics, summarizing = summarizing) ?: aiVendorErrorCode(status)
    } else {
        aiVendorErrorCode(status)
    }

private fun openAiDiagnostic(
    diagnostics: List<String>,
    summarizing: Boolean,
): ErrorCode? =
    when {
        "invalid_api_key" in diagnostics -> {
            ErrorCode.AI_API_KEY_INVALID
        }

        diagnostics.any { it in openAiQuotaCodes } -> {
            ErrorCode.AI_VENDOR_QUOTA_EXHAUSTED
        }

        summarizing && "context_length_exceeded" in diagnostics -> {
            ErrorCode.AI_SUMMARY_INPUT_LIMIT_REACHED
        }

        "rate_limit_exceeded" in diagnostics -> {
            ErrorCode.AI_VENDOR_RATE_LIMITED
        }

        diagnostics.any { it in setOf("server_error", "vector_store_timeout") } -> {
            ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE
        }

        summarizing && diagnostics.any { it in openAiPolicyCodes } -> {
            ErrorCode.AI_SUMMARY_PROMPT_BLOCKED
        }

        diagnostics.any { it in openAiRequestRejectedCodes } -> {
            ErrorCode.AI_VENDOR_REQUEST_REJECTED
        }

        else -> {
            null
        }
    }

private val openAiQuotaCodes = setOf("insufficient_quota", "billing_hard_limit_reached", "billing_not_active")

private val openAiRequestRejectedCodes = setOf("invalid_prompt", "data_residency_mismatch")

private val openAiPolicyCodes = setOf("bio_policy", "misalignment_policy_violation", "content_policy_violation")

internal fun parseVendorSummary(
    rawResponse: String,
    totalTokens: Long?,
): AiSummaryClientResponse {
    if (totalTokens != null && totalTokens !in 0..Int.MAX_VALUE.toLong()) {
        throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID)
    }
    return try {
        parseSummaryResponse(rawResponse = rawResponse, usedTokens = totalTokens?.toInt() ?: rawResponse.length)
    } catch (_: IllegalArgumentException) {
        throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
    }
}
