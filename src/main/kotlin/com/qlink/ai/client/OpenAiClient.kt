package com.qlink.ai.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonMissing
import com.openai.core.JsonValue
import com.openai.errors.OpenAIServiceException
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputText
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.responses.WebSearchTool
import com.qlink.ai.domain.AiProviderType
import com.qlink.common.error.BusinessException
import com.qlink.common.error.ErrorCode
import kotlinx.serialization.json.Json

class OpenAiClient :
    AiClient,
    AutoCloseable {
    override val providerType: AiProviderType = AiProviderType.OPENAI

    private val transport = AiSdkHttpTransport()
    private val client =
        OpenAIOkHttpClient
            .builder()
            .apiKey("set-per-request")
            .okHttpClient(transport.client)
            .maxRetries(0)
            .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val schema =
        ResponseFormatTextJsonSchemaConfig.Schema
            .builder()
            .apply {
                val mapper = ObjectMapper()
                AiSummarySpec.openAiJsonSchema.forEach { (key, value) ->
                    putAdditionalProperty(key, JsonValue.fromJsonNode(mapper.readTree(value.toString())))
                }
            }.build()
    private val textConfig =
        ResponseTextConfig
            .builder()
            .format(
                ResponseFormatTextJsonSchemaConfig
                    .builder()
                    .name(AiSummarySpec.RESPONSE_SCHEMA_NAME)
                    .strict(true)
                    .schema(schema)
                    .build(),
            ).build()

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
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
                .addTool(WebSearchTool.builder().type(WebSearchTool.Type.WEB_SEARCH).build())
                .text(textConfig)
                .build()
        val response =
            transport.execute {
                try {
                    client
                        .withOptions { it.baseUrl(request.baseUrl).apiKey(request.apiKey) }
                        .responses()
                        .withRawResponse()
                        .create(params)
                        .use { json.decodeFromString<OpenAiResponsesResponse>(it.body().bufferedReader().readText()) }
                } catch (_: OpenAIServiceException) {
                    throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
                }
            }
        val text =
            response.output
                .asSequence()
                .flatMap { it.content }
                .firstNotNullOfOrNull { it.text?.trim()?.takeIf(String::isNotBlank) }
                ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)

        return parseSummaryResponse(rawResponse = text, usedTokens = response.usage?.totalTokens ?: text.length)
    }

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        transport.execute {
            try {
                client
                    .withOptions { it.baseUrl(request.baseUrl).apiKey(request.apiKey) }
                    .models()
                    .withRawResponse()
                    .list()
                    .close()
            } catch (exception: OpenAIServiceException) {
                throw AiApiKeyValidationException(exception.statusCode())
            }
        }
    }

    override fun close() {
        client.close()
        transport.close()
    }
}
