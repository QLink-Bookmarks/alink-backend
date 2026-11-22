package com.qlink.ai.client

import com.qlink.ai.domain.AiProviderType
import com.qlink.ai.dto.AiApiKeyValidationRequest
import com.qlink.ai.dto.AiSummaryClientRequest
import com.qlink.ai.dto.AiSummaryClientResponse
import com.qlink.ai.dto.OpenAiContent
import com.qlink.ai.dto.OpenAiFormat
import com.qlink.ai.dto.OpenAiInput
import com.qlink.ai.dto.OpenAiResponsesRequest
import com.qlink.ai.dto.OpenAiText
import com.qlink.ai.dto.OpenAiTool
import com.qlink.plugin.omitExternalHttpResponseDetails
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

class OpenAiCurlClient(
    private val httpClient: HttpClient,
) : AiClient {
    override val providerType: AiProviderType = AiProviderType.OPENAI

    override suspend fun summarize(request: AiSummaryClientRequest): AiSummaryClientResponse {
        request.validatePrompt()
        val body =
            executeVendorHttp {
                httpClient
                    .post(request.baseUrl.openAiResponsesUrl()) {
                        attributes.put(omitExternalHttpResponseDetails, Unit)
                        bearerAuth(request.apiKey)
                        contentType(ContentType.Application.Json)
                        setBody(openAiSummaryRequest(model = request.model, prompt = request.prompt))
                    }.let {
                        it.verifyVendorStatus { status, body -> openAiHttpError(status = status, body = body, summarizing = true) }
                        it.bodyAsText()
                    }
            }
        return parseOpenAiSummary(body)
    }

    override suspend fun validateApiKey(request: AiApiKeyValidationRequest) {
        executeVendorHttp {
            httpClient
                .get(request.baseUrl.openAiModelsUrl()) {
                    attributes.put(omitExternalHttpResponseDetails, Unit)
                    bearerAuth(request.apiKey)
                }.verifyVendorStatus { status, body -> openAiHttpError(status = status, body = body) }
        }
    }
}

private fun String.openAiModelsUrl(): String =
    removeSuffix("/")
        .let { "$it/models" }

private fun String.openAiResponsesUrl(): String =
    removeSuffix("/")
        .let { "$it/responses" }

private fun openAiSummaryRequest(
    model: String,
    prompt: String,
): OpenAiResponsesRequest =
    OpenAiResponsesRequest(
        model = model,
        instructions = AiSummarySpec.systemInstruction,
        input =
            listOf(
                OpenAiInput(
                    role = "user",
                    content = listOf(OpenAiContent(type = "input_text", text = prompt)),
                ),
            ),
        tools = listOf(OpenAiTool(type = "web_search")),
        text = OpenAiText(format = OpenAiFormat()),
    )
