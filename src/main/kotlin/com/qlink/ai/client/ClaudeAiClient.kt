package com.qlink.ai.client

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.AnthropicClientAsyncImpl
import com.anthropic.core.ClientOptions
import com.anthropic.core.LogLevel
import com.anthropic.core.RequestOptions
import com.anthropic.core.http.Headers
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.ErrorType
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
import com.qlink.common.error.ErrorCode
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
        val sourceUrl = request.requestedUrl?.trim()?.takeIf(String::isNotBlank) ?: throw AiVendorException(ErrorCode.AI_EMPTY_RESPONSE)
        val sourceHost =
            try {
                URI(sourceUrl).takeIf { it.scheme == "https" && it.host != null }?.host
                    ?: throw AiVendorException(ErrorCode.AI_EMPTY_RESPONSE)
            } catch (_: URISyntaxException) {
                throw AiVendorException(ErrorCode.AI_EMPTY_RESPONSE)
            }
        val webFetch =
            WebFetchTool20250910
                .builder()
                .maxUses(1)
                .addAllowedDomain(sourceHost)
                .citations(CitationsConfigParam.builder().enabled(false).build())
                .build()
        val hasSourceSection =
            request.prompt
                .lineSequence()
                .map(String::trim)
                .zipWithNext()
                .any { (heading, entry) -> heading == "## URL" && entry == "- $sourceUrl" }
        val fetchInstruction =
            if (hasSourceSection) {
                "Use web_fetch to fetch the URL in the URL section before summarizing."
            } else {
                "Use web_fetch to fetch this exact source URL before summarizing: $sourceUrl"
            }
        val params =
            MessageCreateParams
                .builder()
                .model(request.model)
                .system(AiSummarySpec.systemInstruction)
                .maxTokens(4096)
                .addUserMessage("$fetchInstruction\n\n${request.prompt}")
                .addTool(webFetch)
                .outputConfig(outputConfig)
                .build()
        val message =
            exchange(baseUrl = request.baseUrl, apiKey = request.apiKey, operation = "messages") {
                messages().create(params, requestOptions)
            }

        val summary =
            try {
                if (message.stopReason().getOrNull() != StopReason.END_TURN) throw AiVendorException(ErrorCode.AI_EMPTY_RESPONSE)
                val text = message.finalTextAfterFetch(sourceUrl) ?: throw AiVendorException(ErrorCode.AI_EMPTY_RESPONSE)
                val usage = message.usage()
                val usedTokens =
                    usage.inputTokens() + usage.outputTokens() +
                        usage.cacheCreationInputTokens().orElse(0) + usage.cacheReadInputTokens().orElse(0)
                parseSummaryResponse(rawResponse = text, usedTokens = Math.toIntExact(usedTokens))
            } catch (_: AnthropicInvalidDataException) {
                throw AiVendorException(ErrorCode.AI_EMPTY_RESPONSE)
            } catch (_: IllegalArgumentException) {
                throw AiVendorException(ErrorCode.AI_EMPTY_RESPONSE)
            } catch (_: ArithmeticException) {
                throw AiVendorException(ErrorCode.AI_EMPTY_RESPONSE)
            }
        if (summary.linkId == null) throw AiVendorException(ErrorCode.AI_SUMMARY_LINK_MISMATCH)
        return summary
    }

    private suspend fun <T> exchange(
        baseUrl: String,
        apiKey: String,
        operation: String,
        call: AnthropicClientAsync.() -> CompletableFuture<T>,
    ): T {
        if (!providerType.isAllowedBaseUrl(baseUrl)) throw AiVendorException(ErrorCode.AI_PROVIDER_BASE_URL_INVALID)
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
                        it.baseUrl(baseUrl.trimEnd('/').removeSuffix("/v1") + "/")
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
            val quotaExhausted =
                error.type().getOrNull() == ErrorType.BILLING_ERROR || error.errorCode().getOrNull() in billingErrorCodes
            val headers = error.headers()
            throw AiVendorException.fromStatus(
                status = error.statusCode(),
                quotaExhausted = quotaExhausted,
                retryAfter = headers.singleValue("Retry-After"),
                retryAfterMillis = headers.singleValue("Retry-After-Ms"),
            )
        } catch (_: AnthropicInvalidDataException) {
            throw AiVendorException(ErrorCode.AI_EMPTY_RESPONSE)
        } catch (error: CancellationException) {
            throw error
        } catch (_: AnthropicIoException) {
            log.warn("Claude operation={} status=transport_failure elapsedMs={}", operation, started.elapsedNow().inWholeMilliseconds)
            throw AiVendorException(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)
        } catch (_: RuntimeException) {
            log.warn("Claude operation={} status=unexpected_failure elapsedMs={}", operation, started.elapsedNow().inWholeMilliseconds)
            throw AiVendorException(ErrorCode.COMMON_INTERNAL_SERVER_ERROR)
        } finally {
            lifetime.cancel()
        }
    }

    private fun Headers.singleValue(name: String): String? =
        names()
            .filter { it.equals(name, ignoreCase = true) }
            .flatMap(::values)
            .singleOrNull()

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
        val billingErrorCodes =
            setOf("contract_spend_limit_reached", "linked_organization_billing_unavailable", "linked_organization_spend_limit_reached")
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
