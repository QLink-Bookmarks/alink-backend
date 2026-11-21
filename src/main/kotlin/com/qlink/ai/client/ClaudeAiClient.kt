package com.qlink.ai.client

import com.qlink.ai.domain.AiProviderType
import com.qlink.common.coroutines.catchingFailure
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.Logger
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.TimeSource

class ClaudeAiClient(
    private val httpClient: HttpClient,
    private val log: Logger,
) : AiClient {
    override val providerType: AiProviderType = AiProviderType.CLAUDE

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        val response =
            exchange(
                baseUrl = request.baseUrl,
                apiKey = request.apiKey,
                operation = "models",
                httpMethod = HttpMethod.Get,
            ) {
                parameter("limit", 1)
            }
        if (!response.status.isSuccess()) {
            throw AiApiKeyValidationException(response.status.value)
        }
    }

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        val sourceUrl = request.requestedUrl.trim()
        val sourceHost =
            try {
                URI(sourceUrl).host ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
            } catch (_: URISyntaxException) {
                throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
            }
        val response =
            exchange(
                baseUrl = request.baseUrl,
                apiKey = request.apiKey,
                operation = "messages",
                httpMethod = HttpMethod.Post,
            ) {
                contentType(ContentType.Application.Json)
                setBody(messagesBody(request = request, sourceUrl = sourceUrl, sourceHost = sourceHost).toString())
            }
        response.status.isSuccess().requireTrue(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)

        val message =
            catchingFailure { json.decodeFromString<MessagesResponse>(response.bodyAsText()) }
                .getOrElse { throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE) }
        (message.stopReason == "end_turn").requireTrue(ErrorCode.AI_EMPTY_RESPONSE)
        val text = message.finalTextAfterFetch(sourceUrl) ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
        val summary =
            try {
                parseSummaryResponse(rawResponse = text, usedTokens = message.usage.totalTokens())
            } catch (_: IllegalArgumentException) {
                throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
            }
        (summary.linkId != null).requireTrue(ErrorCode.AI_SUMMARY_LINK_MISMATCH)
        return summary
    }

    private suspend fun exchange(
        baseUrl: String,
        apiKey: String,
        operation: String,
        httpMethod: HttpMethod,
        configure: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        providerType.isAllowedBaseUrl(baseUrl).requireTrue(ErrorCode.AI_PROVIDER_BASE_URL_INVALID)
        val started = TimeSource.Monotonic.markNow()
        val response =
            catchingFailure {
                httpClient.request("https://api.anthropic.com/v1/$operation") {
                    method = httpMethod
                    header("x-api-key", apiKey)
                    header("anthropic-version", "2023-06-01")
                    configure()
                }
            }.getOrElse {
                log.warn("Claude operation={} status=transport_failure elapsedMs={}", operation, started.elapsedNow().inWholeMilliseconds)
                throw BusinessException(ErrorCode.COMMON_INTERNAL_SERVER_ERROR)
            }
        log.info("Claude operation={} status={} elapsedMs={}", operation, response.status.value, started.elapsedNow().inWholeMilliseconds)
        return response
    }

    private fun messagesBody(
        request: AiSummaryClientRequest,
        sourceUrl: String,
        sourceHost: String,
    ) = buildJsonObject {
        put("model", request.model)
        put("system", AiSummarySpec.systemInstruction)
        put("max_tokens", 4096)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", "Use web_fetch to fetch this exact source URL before summarizing: $sourceUrl\n\n${request.prompt}")
            }
        }
        putJsonArray("tools") {
            addJsonObject {
                put("type", "web_fetch_20250910")
                put("name", "web_fetch")
                put("max_uses", 1)
                putJsonArray("allowed_domains") { add(sourceHost) }
                putJsonObject("citations") { put("enabled", false) }
            }
        }
        putJsonObject("output_config") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", AiSummarySpec.claudeJsonSchema)
            }
        }
    }

    @Serializable
    private data class MessagesResponse(
        @SerialName("stop_reason") val stopReason: String,
        val content: List<ContentBlock>,
        val usage: Usage,
    ) {
        fun finalTextAfterFetch(sourceUrl: String): String? {
            val fetchIndex =
                content.indexOfFirst { it.type == "server_tool_use" && it.name == "web_fetch" && it.input?.url == sourceUrl }
            val fetchId = content.getOrNull(fetchIndex)?.id?.takeIf(String::isNotBlank) ?: return null
            val blocksAfterFetch = content.drop(fetchIndex + 1)
            val resultIndex =
                blocksAfterFetch.indexOfFirst { it.type == "web_fetch_tool_result" && it.toolUseId == fetchId }
            val result = blocksAfterFetch.getOrNull(resultIndex)?.content ?: return null
            if (result.type != "web_fetch_result") return null

            return blocksAfterFetch
                .drop(resultIndex + 1)
                .lastOrNull { it.type == "text" && !it.text.isNullOrBlank() }
                ?.text
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
    )

    @Serializable
    private data class Usage(
        @SerialName("input_tokens") val inputTokens: Int,
        @SerialName("output_tokens") val outputTokens: Int,
        @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int? = null,
        @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null,
    ) {
        fun totalTokens(): Int = inputTokens + outputTokens + (cacheCreationInputTokens ?: 0) + (cacheReadInputTokens ?: 0)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
