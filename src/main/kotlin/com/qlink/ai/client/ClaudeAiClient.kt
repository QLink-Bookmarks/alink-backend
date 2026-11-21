package com.qlink.ai.client

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.AnthropicClientAsyncImpl
import com.anthropic.core.ClientOptions
import com.anthropic.core.LogLevel
import com.anthropic.core.RequestOptions
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.CitationsConfigParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ServerToolUseBlock
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.WebFetchTool20250910
import com.anthropic.models.models.ModelListParams
import com.fasterxml.jackson.databind.json.JsonMapper
import com.qlink.ai.domain.AiProviderType
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.common.error.requireTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.asDeferred
import org.slf4j.Logger
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.jvm.optionals.getOrNull
import kotlin.time.TimeSource

class ClaudeAiClient(
    private val transport: HttpClient,
    private val log: Logger,
) : AiClient {
    override val providerType: AiProviderType = AiProviderType.CLAUDE

    private val client =
        AnthropicClientAsyncImpl(
            ClientOptions
                .builder()
                .httpClient(transport)
                .maxRetries(0)
                .logLevel(LogLevel.OFF)
                .build(),
        )

    internal fun close() = client.close()

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        exchange(baseUrl = request.baseUrl, apiKey = request.apiKey, operation = "models") {
            models()
                .withRawResponse()
                .list(ModelListParams.builder().limit(1).build(), requestOptions)
                .thenApply { it.use { response -> response.statusCode() } }
        }
    }

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        val sourceUrl = request.requestedUrl?.trim()?.takeIf(String::isNotBlank) ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
        val sourceHost =
            try {
                URI(sourceUrl).takeIf { it.scheme == "https" && it.host != null }?.host
                    ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
            } catch (_: URISyntaxException) {
                throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
            }
        val webFetch =
            WebFetchTool20250910
                .builder()
                .maxUses(1)
                .addAllowedDomain(sourceHost)
                .citations(CitationsConfigParam.builder().enabled(false).build())
                .build()
        val params =
            MessageCreateParams
                .builder()
                .model(request.model)
                .system(AiSummarySpec.systemInstruction)
                .maxTokens(4096)
                .addUserMessage("Use web_fetch to fetch this exact source URL before summarizing: $sourceUrl\n\n${request.prompt}")
                .addTool(webFetch)
                .outputConfig(outputConfig)
                .build()
        val message =
            exchange(baseUrl = request.baseUrl, apiKey = request.apiKey, operation = "messages") {
                messages().create(params, requestOptions)
            }

        val summary =
            try {
                (message.stopReason().getOrNull() == StopReason.END_TURN).requireTrue(ErrorCode.AI_EMPTY_RESPONSE)
                val text = message.finalTextAfterFetch(sourceUrl) ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
                val usage = message.usage()
                val usedTokens =
                    usage.inputTokens() + usage.outputTokens() +
                        usage.cacheCreationInputTokens().orElse(0) + usage.cacheReadInputTokens().orElse(0)
                parseSummaryResponse(rawResponse = text, usedTokens = Math.toIntExact(usedTokens))
            } catch (_: AnthropicInvalidDataException) {
                throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
            } catch (_: IllegalArgumentException) {
                throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
            } catch (_: ArithmeticException) {
                throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
            }
        (summary.linkId != null).requireTrue(ErrorCode.AI_SUMMARY_LINK_MISMATCH)
        return summary
    }

    private suspend fun <T> exchange(
        baseUrl: String,
        apiKey: String,
        operation: String,
        call: AnthropicClientAsync.() -> CompletableFuture<T>,
    ): T {
        providerType.isAllowedBaseUrl(baseUrl).requireTrue(ErrorCode.AI_PROVIDER_BASE_URL_INVALID)
        val started = TimeSource.Monotonic.markNow()
        val lifetime = Job()
        val requestTransport =
            object : HttpClient by transport {
                override fun executeAsync(
                    request: HttpRequest,
                    requestOptions: RequestOptions,
                ) = transport.executeAsync(request, requestOptions).also { pending ->
                    lifetime.invokeOnCompletion {
                        if (!pending.cancel(false)) pending.thenAccept { it.close() }
                    }
                }

                override fun close() = Unit
            }
        return try {
            val result =
                client
                    .withOptions {
                        it.baseUrl(baseUrl.trimEnd('/').removeSuffix("/v1"))
                        it.httpClient(requestTransport)
                        it.putHeader("x-api-key", apiKey)
                        it.putHeader("anthropic-version", "2023-06-01")
                    }.call()
                    .asDeferred()
                    .await()
            log.info("Claude operation={} status=success elapsedMs={}", operation, started.elapsedNow().inWholeMilliseconds)
            result
        } catch (error: AnthropicServiceException) {
            log.info("Claude operation={} status={} elapsedMs={}", operation, error.statusCode(), started.elapsedNow().inWholeMilliseconds)
            if (operation == "models") throw AiApiKeyValidationException(error.statusCode())
            throw BusinessException(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)
        } catch (_: AnthropicInvalidDataException) {
            throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            log.warn("Claude operation={} status=transport_failure elapsedMs={}", operation, started.elapsedNow().inWholeMilliseconds)
            throw BusinessException(ErrorCode.COMMON_INTERNAL_SERVER_ERROR)
        } finally {
            lifetime.cancel()
        }
    }

    private fun Message.finalTextAfterFetch(sourceUrl: String): String? {
        val blocks = content()
        val fetchIndex =
            blocks.indexOfFirst { block ->
                val fetch = block.serverToolUse().getOrNull() ?: return@indexOfFirst false
                fetch._type().asString().getOrNull() == "server_tool_use" &&
                    fetch.name() == ServerToolUseBlock.Name.WEB_FETCH &&
                    fetch
                        ._input()
                        .asObject()
                        .getOrNull()
                        ?.get("url")
                        ?.asString()
                        ?.getOrNull() == sourceUrl
            }
        val fetchId =
            blocks
                .getOrNull(fetchIndex)
                ?.asServerToolUse()
                ?.id()
                ?.takeIf(String::isNotBlank) ?: return null
        val blocksAfterFetch = blocks.drop(fetchIndex + 1)
        val resultIndex =
            blocksAfterFetch.indexOfFirst { block ->
                val result = block.webFetchToolResult().getOrNull() ?: return@indexOfFirst false
                result._type().asString().getOrNull() == "web_fetch_tool_result" && result.toolUseId() == fetchId
            }
        val fetchedUrl =
            blocksAfterFetch
                .getOrNull(resultIndex)
                ?.asWebFetchToolResult()
                ?.content()
                ?.webFetchBlock()
                ?.getOrNull()
                ?.takeIf { it._type().asString().getOrNull() == "web_fetch_result" }
                ?.url()
        if (fetchedUrl != sourceUrl) return null

        return blocksAfterFetch
            .drop(resultIndex + 1)
            .asReversed()
            .firstNotNullOfOrNull {
                it
                    .text()
                    .getOrNull()
                    ?.takeIf { it._type().asString().getOrNull() == "text" }
                    ?.text()
                    ?.takeIf(String::isNotBlank)
            }
    }

    private companion object {
        val requestOptions =
            RequestOptions
                .builder()
                .followRedirects(false)
                .timeout(Duration.ofSeconds(15))
                .build()
        val outputConfig =
            OutputConfig
                .builder()
                .format(
                    JsonOutputFormat.of(
                        JsonMapper.builder().build().readValue(
                            AiSummarySpec.claudeJsonSchema.toString(),
                            JsonOutputFormat.Schema::class.java,
                        ),
                    ),
                ).build()
    }
}
