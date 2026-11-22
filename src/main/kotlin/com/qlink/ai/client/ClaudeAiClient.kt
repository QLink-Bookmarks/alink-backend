package com.qlink.ai.client

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.ErrorType
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.ServerToolUseBlock
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Usage
import com.anthropic.models.models.ModelListParams
import com.qlink.ai.domain.AiProviderType
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.config.ClaudeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.slf4j.Logger
import java.net.URI
import java.net.URISyntaxException
import kotlin.jvm.optionals.getOrNull

class ClaudeAiClient(
    private val client: AnthropicClient,
    private val config: ClaudeConfig,
    private val log: Logger,
) : AiClient {
    override val providerType: AiProviderType = AiProviderType.CLAUDE

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        val provider = providerClient(baseUrl = request.baseUrl, apiKey = request.apiKey)
        exchange {
            provider
                .models()
                .withRawResponse()
                .list(ModelListParams.builder().limit(1).build())
                .close()
        }
    }

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        val sourceUrl = request.requestedUrl?.trim() ?: throw BusinessException(ErrorCode.AI_SUMMARY_SOURCE_URL_INVALID)
        val sourceHost = sourceHost(sourceUrl)
        val provider = providerClient(baseUrl = request.baseUrl, apiKey = request.apiKey)
        val maxTokens = config.maxOutputTokens ?: throw BusinessException(ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_UNAVAILABLE)

        val params =
            MessageCreateParams
                .builder()
                .model(request.model)
                .system(AiSummarySpec.systemInstruction)
                .maxTokens(maxTokens)
                .addUserMessage(request.prompt)
                .addTool(config.webFetch(sourceHost))
                .outputConfig(config.outputConfig)
                .build()
        val message = exchange { provider.messages().create(params) }

        val text = readResponse { message.summaryText(sourceUrl) }
        val usedTokens = readResponse(ErrorCode.AI_SUMMARY_USAGE_INVALID) { message.usage().totalTokens() }
        val summary =
            try {
                parseSummaryResponse(rawResponse = text, usedTokens = usedTokens)
            } catch (_: IllegalArgumentException) {
                throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
            }
        if (summary.linkId == null) throw BusinessException(ErrorCode.AI_SUMMARY_LINK_MISMATCH)
        return summary
    }

    private fun sourceHost(sourceUrl: String): String {
        val uri =
            try {
                URI(sourceUrl)
            } catch (_: URISyntaxException) {
                throw BusinessException(ErrorCode.AI_SUMMARY_SOURCE_URL_INVALID)
            }
        return uri.takeIf { it.scheme == "https" && it.rawUserInfo == null }?.host
            ?: throw BusinessException(ErrorCode.AI_SUMMARY_SOURCE_URL_INVALID)
    }

    private fun providerClient(
        baseUrl: String,
        apiKey: String,
    ): AnthropicClient {
        if (!providerType.isAllowedBaseUrl(baseUrl)) throw BusinessException(ErrorCode.AI_PROVIDER_BASE_URL_INVALID)
        if (apiKey.isBlank() || apiKey.any { it !in ' '..'~' }) throw BusinessException(ErrorCode.AI_API_KEY_INVALID)
        return client.withOptions {
            it.baseUrl(baseUrl.trimEnd('/').removeSuffix("/v1") + "/")
            it.putHeader("x-api-key", apiKey)
            it.putHeader("anthropic-version", "2023-06-01")
        }
    }

    private suspend fun <T> exchange(call: () -> T): T =
        runInterruptible(Dispatchers.IO) {
            try {
                call()
            } catch (error: AnthropicServiceException) {
                log.info("Claude request failed with HTTP status={}", error.statusCode())
                val quotaExhausted =
                    error.type().getOrNull() == ErrorType.BILLING_ERROR || error.errorCode().getOrNull() in billingErrorCodes
                throw BusinessException(aiVendorErrorCode(status = error.statusCode(), quotaExhausted = quotaExhausted))
            } catch (_: AnthropicIoException) {
                throw BusinessException(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)
            } catch (_: AnthropicInvalidDataException) {
                throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
            }
        }

    private fun <T> readResponse(
        errorCode: ErrorCode = ErrorCode.AI_SUMMARY_RESPONSE_INVALID,
        read: () -> T,
    ): T =
        try {
            read()
        } catch (_: AnthropicInvalidDataException) {
            throw BusinessException(errorCode)
        }

    private fun Message.summaryText(sourceUrl: String): String {
        when (stopReason().getOrNull()) {
            StopReason.END_TURN -> Unit
            StopReason.REFUSAL -> throw BusinessException(ErrorCode.AI_SUMMARY_REFUSED)
            StopReason.MAX_TOKENS -> throw BusinessException(ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_REACHED)
            else -> throw BusinessException(ErrorCode.AI_SUMMARY_INCOMPLETE)
        }

        val blocks = content()
        val fetchIndex = blocks.indexOfFirst { it.serverToolUse().getOrNull()?.name() == ServerToolUseBlock.Name.WEB_FETCH }
        val fetch =
            blocks.getOrNull(fetchIndex)?.asServerToolUse()
                ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
        val fetchId = fetch.id().takeIf(String::isNotBlank) ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
        val requested =
            fetch
                ._input()
                .asObject()
                .getOrNull()
                ?.get("url")
                ?.asString()
                ?.getOrNull()
        if (requested != sourceUrl) throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_URL_MISMATCH)

        val resultIndex = blocks.indexOfLast { it.webFetchToolResult().getOrNull()?.toolUseId() == fetchId }
        if (resultIndex <= fetchIndex) throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
        val result =
            blocks
                .getOrNull(resultIndex)
                ?.asWebFetchToolResult()
                ?.content()
                ?.webFetchBlock()
                ?.getOrNull()
                ?.takeIf { it._type().asString().getOrNull() == "web_fetch_result" }
                ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
        if (result.url() != sourceUrl) throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_URL_MISMATCH)

        return blocks
            .lastOrNull()
            ?.takeIf { resultIndex < blocks.lastIndex }
            ?.text()
            ?.getOrNull()
            ?.text()
            ?.takeIf(String::isNotBlank) ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
    }

    private fun Usage.totalTokens(): Int {
        val counts = listOf(inputTokens(), outputTokens(), cacheCreationInputTokens().orElse(0), cacheReadInputTokens().orElse(0))
        if (counts.any { it !in 0..Int.MAX_VALUE.toLong() }) throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID)
        return counts.sum().takeIf { it <= Int.MAX_VALUE }?.toInt() ?: throw BusinessException(ErrorCode.AI_SUMMARY_USAGE_INVALID)
    }

    private companion object {
        val billingErrorCodes =
            setOf("contract_spend_limit_reached", "linked_organization_billing_unavailable", "linked_organization_spend_limit_reached")
    }
}
