package com.qlink.ai.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.okhttp.OpenAIOkHttpClientAsync
import com.openai.core.JsonMissing
import com.openai.core.JsonValue
import com.openai.core.Timeout
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration

internal class OpenAiSdkExperiment(
    timeoutMillis: Long,
) : AiClient,
    AutoCloseable {
    override val providerType = AiProviderType.OPENAI

    private val client =
        OpenAIOkHttpClientAsync
            .builder()
            .apiKey("test-client-placeholder")
            .maxRetries(0)
            .followRedirects(false)
            .timeout(
                Timeout
                    .builder()
                    .request(Duration.ofMillis(timeoutMillis))
                    .connect(Duration.ofSeconds(5))
                    .read(Duration.ZERO)
                    .write(Duration.ZERO)
                    .build(),
            ).build()
    private val schema =
        ResponseFormatTextJsonSchemaConfig.Schema
            .builder()
            .also { builder ->
                val mapper = ObjectMapper()
                AiSummarySpec.openAiJsonSchema.forEach { (key, value) ->
                    builder.putAdditionalProperty(key, JsonValue.fromJsonNode(mapper.readTree(value.toString())))
                }
            }.build()

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        val input =
            EasyInputMessage
                .builder()
                .role(EasyInputMessage.Role.USER)
                .type(JsonMissing.of())
                .contentOfResponseInputMessageContentList(
                    listOf(ResponseInputContent.ofInputText(ResponseInputText.builder().text(request.prompt).build())),
                ).build()
        val format =
            ResponseFormatTextJsonSchemaConfig
                .builder()
                .name(AiSummarySpec.RESPONSE_SCHEMA_NAME)
                .strict(true)
                .schema(schema)
                .build()
        val params =
            ResponseCreateParams
                .builder()
                .model(request.model)
                .instructions(AiSummarySpec.systemInstruction)
                .inputOfResponse(listOf(ResponseInputItem.ofEasyInputMessage(input)))
                .addTool(WebSearchTool.builder().type(WebSearchTool.Type.WEB_SEARCH).build())
                .text(ResponseTextConfig.builder().format(format).build())
                .build()
        val response =
            withContext(Dispatchers.IO) {
                client
                    .withOptions { it.baseUrl(request.baseUrl).apiKey(request.apiKey) }
                    .responses()
                    .withRawResponse()
                    .create(params)
                    .await()
                    .use { raw ->
                        Json.parseToJsonElement(raw.body().bufferedReader().readText()).jsonObject
                    }
            }
        val text =
            response["output"]
                ?.jsonArray
                .orEmpty()
                .asSequence()
                .flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
                .firstNotNullOfOrNull {
                    it.jsonObject["text"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }
                ?: throw BusinessException(ErrorCode.AI_EMPTY_RESPONSE)
        val usage = response["usage"]?.takeUnless { it == JsonNull }?.jsonObject
        return parseSummaryResponse(
            rawResponse = text,
            usedTokens = if (usage == null) text.length else usage["total_tokens"]?.jsonPrimitive?.content?.toInt() ?: 0,
        )
    }

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        try {
            client
                .withOptions { it.baseUrl(request.baseUrl).apiKey(request.apiKey) }
                .models()
                .withRawResponse()
                .list()
                .await()
                .close()
        } catch (exception: OpenAIServiceException) {
            throw AiApiKeyValidationException(exception.statusCode())
        }
    }

    override fun close() = client.close()
}
