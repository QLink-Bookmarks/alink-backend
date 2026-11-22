package com.qlink.ai.client

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.ErrorType
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Usage
import com.anthropic.models.models.ModelListParams
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.dto.AiApiKeyValidationRequest
import com.qlink.ai.dto.AiSummaryClientRequest
import com.qlink.ai.dto.AiSummaryClientResponse
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.config.CLAUDE_API_KEY_HEADER
import com.qlink.config.ClaudeConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.slf4j.Logger
import kotlin.jvm.optionals.getOrNull

class ClaudeSdkClient(
    private val client: AnthropicClient,
    private val config: ClaudeConfig,
    private val dispatcher: CoroutineDispatcher,
    private val log: Logger,
) : AiClient {
    override val providerType: AiProviderType = AiProviderType.CLAUDE

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        validateEndpoint()
        val params =
            ModelListParams
                .builder()
                .limit(1)
                .putAdditionalHeader(CLAUDE_API_KEY_HEADER, validClaudeApiKey(request.apiKey))
                .build()
        exchange {
            client
                .models()
                .withRawResponse()
                .list(params)
                .close()
        }
    }

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        val source = request.claudeSource()
        validateEndpoint()
        val maxTokens = config.maxOutputTokens
        if (maxTokens == null) {
            log.warn("Claude summary requires a positive integer in ai.claude.maxOutputTokens or AI_CLAUDE_MAX_OUTPUT_TOKENS")
            throw BusinessException(ErrorCode.AI_SUMMARY_OUTPUT_LIMIT_UNAVAILABLE)
        }

        val params =
            MessageCreateParams
                .builder()
                .model(request.model)
                .system(AiSummarySpec.systemInstruction)
                .maxTokens(maxTokens)
                .putAdditionalHeader(CLAUDE_API_KEY_HEADER, validClaudeApiKey(request.apiKey))
                .addUserMessage(request.prompt)
                .addTool(config.webFetch(source.host))
                .outputConfig(config.outputConfig)
                .build()
        val message = exchange { client.messages().create(params) }

        val text = readResponse { message.summaryText(source.toString()) }
        val usedTokens = readResponse(ErrorCode.AI_SUMMARY_USAGE_INVALID) { message.usage().totalTokens() }
        return parseClaudeSummary(text = text, usedTokens = usedTokens)
    }

    private fun validateEndpoint() {
        if (config.sdkEndpoint == null) {
            throw BusinessException(ErrorCode.AI_PROVIDER_BASE_URL_INVALID)
        }
    }

    private suspend fun <T> exchange(call: () -> T): T =
        try {
            runInterruptible(dispatcher, call)
        } catch (error: AnthropicServiceException) {
            currentCoroutineContext().ensureActive()
            log.info("Claude request failed with HTTP status={}", error.statusCode())
            val quotaExhausted =
                error.type().getOrNull() == ErrorType.BILLING_ERROR || error.errorCode().getOrNull() in claudeBillingErrorCodes
            throw BusinessException(aiVendorErrorCode(status = error.statusCode(), quotaExhausted = quotaExhausted))
        } catch (_: AnthropicIoException) {
            currentCoroutineContext().ensureActive()
            throw BusinessException(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)
        } catch (_: AnthropicInvalidDataException) {
            currentCoroutineContext().ensureActive()
            throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
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
        validateClaudeStopReason(stopReason().getOrNull()?.asString())

        val blocks = content()
        blocks.forEach { block ->
            val valid =
                when (
                    block
                        .type()
                        ._value()
                        .asString()
                        .getOrNull()
                ) {
                    "server_tool_use" -> block.isServerToolUse()
                    "web_fetch_tool_result" -> block.isWebFetchToolResult()
                    "text" -> block.isText()
                    null -> false
                    else -> true
                }
            if (!valid) {
                throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
            }
        }
        val fetchIndex =
            blocks.indices.singleOrNull { index ->
                val tool = blocks[index].serverToolUse().getOrNull() ?: return@singleOrNull false
                val name =
                    tool
                        .name()
                        ._value()
                        .asString()
                        .getOrNull() ?: throw BusinessException(ErrorCode.AI_SUMMARY_RESPONSE_INVALID)
                name == "web_fetch"
            }
                ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
        val fetch = blocks[fetchIndex].asServerToolUse()
        val fetchId = fetch.id().takeIf(String::isNotBlank) ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
        val requested =
            fetch
                ._input()
                .asObject()
                .getOrNull()
                ?.get("url")
                ?.asString()
                ?.getOrNull()
        if (requested != sourceUrl) {
            throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_URL_MISMATCH)
        }

        val resultIndex =
            blocks.indices.singleOrNull { blocks[it].webFetchToolResult().isPresent }
                ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
        val resultBlock = blocks[resultIndex].asWebFetchToolResult()
        if (resultIndex <= fetchIndex || resultBlock.toolUseId() != fetchId) {
            throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
        }
        val result =
            resultBlock
                .content()
                .webFetchBlock()
                .getOrNull()
                ?.takeIf { it._type().asString().getOrNull() == "web_fetch_result" }
                ?: throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_FAILED)
        if (result.url() != sourceUrl) {
            throw BusinessException(ErrorCode.AI_SUMMARY_FETCH_URL_MISMATCH)
        }
        readResponse(ErrorCode.AI_SUMMARY_FETCH_FAILED) {
            val document = result.content()
            val source = document.source()
            val pdf = source.base64().getOrNull()
            val text = source.text().getOrNull()
            validateClaudeDocument(
                documentType = document._type().asString().getOrNull(),
                sourceType = (pdf?._type() ?: text?._type())?.asString()?.getOrNull(),
                mediaType = (pdf?._mediaType() ?: text?._mediaType())?.asString()?.getOrNull(),
                data = pdf?.data() ?: text?.data(),
            )
        }

        return blocks
            .lastOrNull()
            ?.takeIf { resultIndex < blocks.lastIndex }
            ?.text()
            ?.getOrNull()
            ?.text()
            ?.takeIf(String::isNotBlank) ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
    }

    private fun Usage.totalTokens(): Int =
        claudeTotalTokens(listOf(inputTokens(), outputTokens(), cacheCreationInputTokens().orElse(0), cacheReadInputTokens().orElse(0)))
}
