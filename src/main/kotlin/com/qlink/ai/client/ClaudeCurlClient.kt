package com.qlink.ai.client

import com.qlink.ai.domain.AiProviderType
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import com.qlink.config.CLAUDE_API_KEY_HEADER
import com.qlink.config.CLAUDE_API_VERSION
import com.qlink.config.CLAUDE_VERSION_HEADER
import com.qlink.config.ClaudeConfig
import com.qlink.config.claudeRedirectTarget
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.Logger
import java.io.IOException
import java.net.URI

class ClaudeCurlClient(
    private val httpClient: HttpClient,
    private val config: ClaudeConfig,
    private val log: Logger,
) : AiClient {
    override val providerType: AiProviderType = AiProviderType.CLAUDE

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        exchange(
            url = endpoint(baseUrl = request.baseUrl, operation = "models?limit=1"),
            apiKey = validClaudeApiKey(request.apiKey),
            method = HttpMethod.Get,
        ) { it.cancel() }
    }

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        val source = request.claudeSource()
        val url = endpoint(baseUrl = request.baseUrl, operation = "messages")
        val maxTokens = config.maxOutputTokens ?: throw BusinessException(ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_UNAVAILABLE)
        val body =
            ClaudeMessagesRequest(
                model = request.model,
                system = AiSummarySpec.systemInstruction,
                maxTokens = maxTokens,
                messages = listOf(ClaudeMessagesRequest.Message(role = "user", content = request.prompt)),
                tools = listOf(config.curlWebFetch(source.host)),
                outputConfig = config.curlOutputConfig,
            )
        val rawResponse =
            exchange(
                url = url,
                apiKey = validClaudeApiKey(request.apiKey),
                method = HttpMethod.Post,
                body = json.encodeToString(body),
            ) { it.bodyAsText() }
        val message =
            try {
                json.decodeFromString<MessagesResponse>(rawResponse)
            } catch (_: IllegalArgumentException) {
                throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
            }
        val text = message.summaryText(source.toString())
        return parseClaudeSummary(text = text, usedTokens = message.totalTokens())
    }

    private fun endpoint(
        baseUrl: String,
        operation: String,
    ): URI {
        providerType.isAllowedBaseUrl(baseUrl).requireTrue(ErrorCode.AI_PROVIDER_BASE_URL_INVALID)
        val base = baseUrl.removeSuffix("/")
        val path = if (URI(base).rawPath.isEmpty()) "$base/v1" else base
        return URI("$path/$operation")
    }

    private suspend fun <T : Any> exchange(
        url: URI,
        apiKey: String,
        method: HttpMethod,
        body: String? = null,
        read: suspend (HttpResponse) -> T,
    ): T {
        val statuses = if (method == HttpMethod.Get) setOf(301, 302, 303, 307, 308) else setOf(307, 308)
        var target: URI? = null
        val first =
            send(url = url, apiKey = apiKey, method = method, body = body) { response ->
                target =
                    response.headers
                        .getAll(HttpHeaders.Location)
                        ?.singleOrNull()
                        ?.takeIf { response.status.value in statuses }
                        ?.let { claudeRedirectTarget(origin = url, location = it) }
                if (target == null) {
                    read(response.requireSuccess())
                } else {
                    response.cancel()
                    null
                }
            }
        if (first != null) return first
        val redirect = target ?: throw BusinessException(ErrorCode.AI_VENDOR_REQUEST_REJECTED)
        return send(url = redirect, apiKey = apiKey, method = method, body = body) { read(it.requireSuccess()) }
    }

    private suspend fun HttpResponse.requireSuccess(): HttpResponse {
        if (!status.isSuccess()) {
            val status = status.value
            log.info("Claude request failed with HTTP status={}", status)
            val quotaExhausted = status in 400..499 && status !in setOf(401, 403, 408) && hasBillingError()
            cancel()
            throw BusinessException(aiVendorErrorCode(status = status, quotaExhausted = quotaExhausted))
        }
        return this
    }

    private suspend fun <T> send(
        url: URI,
        apiKey: String,
        method: HttpMethod,
        body: String?,
        read: suspend (HttpResponse) -> T,
    ): T =
        try {
            httpClient
                .prepareRequest(url.toASCIIString()) {
                    this.method = method
                    header(CLAUDE_API_KEY_HEADER, apiKey)
                    header(CLAUDE_VERSION_HEADER, CLAUDE_API_VERSION)
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }.execute(read)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw BusinessException(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)
        }

    private suspend fun HttpResponse.hasBillingError(): Boolean {
        val error =
            try {
                json.decodeFromString<ErrorResponse>(bodyAsText()).error
            } catch (_: IllegalArgumentException) {
                return false
            }
        return error?.type == "billing_error" || error?.details?.errorCode in claudeBillingErrorCodes
    }

    @Serializable
    private data class ErrorResponse(
        val error: Error? = null,
    ) {
        @Serializable
        data class Error(
            val type: String? = null,
            val details: Details? = null,
        )

        @Serializable
        data class Details(
            @SerialName("error_code") val errorCode: String? = null,
        )
    }

    @Serializable
    private data class MessagesResponse(
        @SerialName("stop_reason") val stopReason: String? = null,
        val content: List<ContentBlock>,
        val usage: JsonElement? = null,
    ) {
        fun summaryText(sourceUrl: String): String {
            validateClaudeStopReason(stopReason)
            val types = content.map { it.type.requiredString() }
            val fetchIndex =
                content.indices.singleOrNull { types[it] == "server_tool_use" && content[it].name.requiredString() == "web_fetch" }
                    ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
            val fetch = content[fetchIndex]
            val fetchId =
                fetch.id.requiredString().takeIf(String::isNotBlank) ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
            val requested = (fetch.input as? JsonObject)?.get("url").stringOrNull()
            if (requested != sourceUrl) throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_URL_MISMATCH)

            val resultIndex =
                types.indices.singleOrNull { types[it] == "web_fetch_tool_result" }
                    ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
            val resultBlock = content[resultIndex]
            val resultId = resultBlock.toolUseId.requiredString()
            if (resultIndex <= fetchIndex || resultId != fetchId) {
                throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
            }
            val resultContent = resultBlock.content as? JsonObject ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
            val result =
                try {
                    json.decodeFromJsonElement(FetchResult.serializer(), resultContent)
                } catch (_: IllegalArgumentException) {
                    throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
                }
            if (result.type != "web_fetch_result") throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
            val resultUrl = result.url.stringOrNull() ?: throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
            if (resultUrl != sourceUrl) throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_URL_MISMATCH)
            val document = result.content as? JsonObject ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
            val source = document["source"] as? JsonObject ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
            validateClaudeDocument(
                documentType = document["type"].stringOrNull(),
                sourceType = source["type"].stringOrNull(),
                mediaType = source["media_type"].stringOrNull(),
                data = source["data"].stringOrNull(),
            )

            val last =
                content.last().takeIf { resultIndex < content.lastIndex && types.last() == "text" }
                    ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
            val text = last.text.requiredString()
            return text.takeIf(String::isNotBlank) ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
        }

        fun totalTokens(): Int {
            val counts =
                try {
                    json.decodeFromJsonElement(Usage.serializer(), usage ?: throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID))
                } catch (_: IllegalArgumentException) {
                    throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID)
                }
            return claudeTotalTokens(
                listOf(
                    counts.inputTokens.tokenCount(),
                    counts.outputTokens.tokenCount(),
                    counts.cacheCreation.tokenCount(default = 0),
                    counts.cacheRead.tokenCount(default = 0),
                ),
            )
        }
    }

    @Serializable
    private data class ContentBlock(
        val type: JsonElement? = null,
        val name: JsonElement? = null,
        val id: JsonElement? = null,
        val input: JsonElement? = null,
        @SerialName("tool_use_id") val toolUseId: JsonElement? = null,
        val content: JsonElement? = null,
        val text: JsonElement? = null,
    )

    @Serializable
    private data class FetchResult(
        val type: String,
        val url: JsonElement? = null,
        val content: JsonElement? = null,
    )

    @Serializable
    private data class Usage(
        @SerialName("input_tokens") val inputTokens: JsonPrimitive,
        @SerialName("output_tokens") val outputTokens: JsonPrimitive,
        @SerialName("cache_creation_input_tokens") val cacheCreation: JsonPrimitive? = null,
        @SerialName("cache_read_input_tokens") val cacheRead: JsonPrimitive? = null,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun JsonElement?.requiredString(): String = stringOrNull() ?: throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)

        fun JsonPrimitive?.tokenCount(default: Long? = null): Long =
            (if (this == null || this == JsonNull) default else takeUnless { it.isString }?.longOrNull)
                ?: throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID)
    }
}
