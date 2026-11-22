package com.qlink.ai.client

import com.qlink.ai.dto.AiSummaryClientRequest
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

internal suspend fun <T> executeVendorHttp(request: suspend () -> T): T =
    try {
        request()
    } catch (_: IOException) {
        throw BusinessException(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)
    }

internal suspend fun HttpResponse.verifyVendorStatus(classify: (Int, String) -> ErrorCode) {
    val statusCode = status.value
    if (statusCode in 200..299) {
        return
    }

    val body =
        if (statusCode in 400..499) {
            bodyAsText()
        } else {
            ""
        }
    throw BusinessException(classify(statusCode, body))
}

internal fun vendorErrorObject(body: String): JsonObject? {
    if (body.isBlank()) {
        return null
    }
    return try {
        (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun JsonObject.errorString(field: String): String? = (get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun AiSummaryClientRequest.validatePrompt() {
    if (prompt.isBlank()) {
        throw BusinessException(ErrorCode.AI_SUMMARY_INPUT_EMPTY)
    }
}
