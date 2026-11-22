package com.qlink.ai.client

import com.openai.client.OpenAIClient
import com.openai.core.JsonMissing
import com.openai.errors.OpenAIInvalidDataException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.models.models.ModelListParams
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputText
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.responses.WebSearchTool
import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.dto.AiApiKeyValidationRequest
import com.qlink.ai.dto.AiSummaryClientRequest
import com.qlink.ai.dto.AiSummaryClientResponse
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import com.qlink.config.AiSdkHttpTransport
import okio.blackholeSink
import okio.buffer
import okio.source
import java.io.IOException

internal class OpenAiSdkClient(
    private val client: OpenAIClient,
    private val transport: AiSdkHttpTransport,
    private val textConfig: ResponseTextConfig,
    private val webSearch: WebSearchTool,
) : AiClient {
    override val providerType: AiProviderType = AiProviderType.OPENAI

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        request.validatePrompt()
        val input =
            EasyInputMessage
                .builder()
                .role(EasyInputMessage.Role.USER)
                .type(JsonMissing.of())
                .contentOfResponseInputMessageContentList(
                    listOf(ResponseInputContent.ofInputText(ResponseInputText.builder().text(request.prompt).build())),
                ).build()
        val params =
            ResponseCreateParams
                .builder()
                .model(request.model)
                .instructions(AiSummarySpec.systemInstruction)
                .inputOfResponse(listOf(ResponseInputItem.ofEasyInputMessage(input)))
                .addTool(webSearch)
                .putAdditionalHeader("Authorization", "Bearer ${request.apiKey}")
                .text(textConfig)
                .build()
        val body =
            execute(summarizing = true) {
                client
                    .responses()
                    .withRawResponse()
                    .create(params)
                    .use { it.body().bufferedReader(Charsets.UTF_8).readText() }
            }
        return parseOpenAiSummary(body)
    }

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        val params = ModelListParams.builder().putAdditionalHeader("Authorization", "Bearer ${request.apiKey}").build()
        execute {
            client
                .models()
                .withRawResponse()
                .list(params)
                .use {
                    it
                        .body()
                        .source()
                        .buffer()
                        .readAll(blackholeSink())
                }
        }
    }

    private suspend fun <T> execute(
        summarizing: Boolean = false,
        block: () -> T,
    ): T =
        try {
            transport.execute(block)
        } catch (failure: OpenAIServiceException) {
            val diagnostics =
                listOfNotNull(
                    readDiagnostic { failure.code().orElse(null) },
                    readDiagnostic { failure.type().orElse(null) },
                )
            throw BusinessException(openAiHttpError(status = failure.statusCode(), diagnostics = diagnostics, summarizing = summarizing))
        } catch (_: OpenAIIoException) {
            throw BusinessException(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)
        } catch (_: IOException) {
            throw BusinessException(ErrorCode.AI_VENDOR_TEMPORARY_UNAVAILABLE)
        }

    private fun readDiagnostic(read: () -> String?): String? =
        try {
            read()
        } catch (_: OpenAIInvalidDataException) {
            null
        }
}
