package com.qlink.ai.client

import com.qlink.ai.domain.AiProviderType
import com.qlink.common.coroutines.catchingFailure
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
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.Logger
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
        )
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
        val response =
            exchange(
                url = url,
                apiKey = validClaudeApiKey(request.apiKey),
                method = HttpMethod.Post,
                body = json.encodeToString(body),
            )
        val message =
            try {
                json.decodeFromString<MessagesResponse>(response.bodyAsText())
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

    private suspend fun exchange(
        url: URI,
        apiKey: String,
        method: HttpMethod,
        body: String? = null,
    ): HttpResponse {
        val first = send(url = url, apiKey = apiKey, method = method, body = body)
        val statuses = if (method == HttpMethod.Get) setOf(301, 302, 303, 307, 308) else setOf(307, 308)
        val redirect =
            first.headers
                .getAll(HttpHeaders.Location)
                ?.singleOrNull()
                ?.takeIf { first.status.value in statuses }
                ?.let { claudeRedirectTarget(origin = url, location = it) }
        val response = redirect?.let { send(url = it, apiKey = apiKey, method = method, body = body) } ?: first
        if (!response.status.isSuccess()) {
            val status = response.status.value
            log.info("Claude request failed with HTTP status={}", status)
            val quotaExhausted = status in 400..499 && response.hasBillingError()
            throw BusinessException(aiVendorErrorCode(status = status, quotaExhausted = quotaExhausted))
        }
        return response
    }

    private suspend fun send(
        url: URI,
        apiKey: String,
        method: HttpMethod,
        body: String?,
    ): HttpResponse =
        catchingFailure {
            httpClient.request(url.toASCIIString()) {
                this.method = method
                header(CLAUDE_API_KEY_HEADER, apiKey)
                header(CLAUDE_VERSION_HEADER, CLAUDE_API_VERSION)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        }.getOrElse { throw BusinessException(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE) }

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
            val fetchIndex = content.indexOfFirst { it.type == "server_tool_use" && it.name == "web_fetch" }
            val fetch = content.getOrNull(fetchIndex) ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
            val fetchId = fetch.id?.takeIf(String::isNotBlank) ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
            if (fetch.input?.url != sourceUrl) throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_URL_MISMATCH)

            val resultIndex = content.indexOfLast { it.type == "web_fetch_tool_result" && it.toolUseId == fetchId }
            val result =
                content
                    .getOrNull(resultIndex)
                    ?.takeIf { resultIndex > fetchIndex }
                    ?.content
                    ?.takeIf { it.type == "web_fetch_result" }
                    ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
            if (result.url != sourceUrl) throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_URL_MISMATCH)

            return content
                .lastOrNull()
                ?.takeIf { resultIndex < content.lastIndex && it.type == "text" }
                ?.text
                ?.takeIf(String::isNotBlank) ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
        }

        fun totalTokens(): Int {
            val counts =
                try {
                    json.decodeFromJsonElement(Usage.serializer(), usage ?: throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID))
                } catch (_: IllegalArgumentException) {
                    throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID)
                }
            return claudeTotalTokens(listOf(counts.inputTokens, counts.outputTokens, counts.cacheCreation ?: 0, counts.cacheRead ?: 0))
        }
    }

    @Serializable
    private data class ContentBlock(
        val type: String,
        val name: String? = null,
        val id: String? = null,
        val input: FetchInput? = null,
        @SerialName("tool_use_id") val toolUseId: String? = null,
        val content: FetchResult? = null,
        val text: String? = null,
    )

    @Serializable
    private data class FetchInput(
        val url: String? = null,
    )

    @Serializable
    private data class FetchResult(
        val type: String,
        val url: String? = null,
    )

    @Serializable
    private data class Usage(
        @SerialName("input_tokens") val inputTokens: Long,
        @SerialName("output_tokens") val outputTokens: Long,
        @SerialName("cache_creation_input_tokens") val cacheCreation: Long? = null,
        @SerialName("cache_read_input_tokens") val cacheRead: Long? = null,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
