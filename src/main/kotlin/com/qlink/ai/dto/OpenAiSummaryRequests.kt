package com.qlink.ai.dto

import com.qlink.ai.client.AiSummarySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class OpenAiResponsesRequest(
    val model: String,
    val instructions: String,
    val input: List<OpenAiInput>,
    val tools: List<OpenAiTool>,
    val text: OpenAiText,
)

@Serializable
internal data class OpenAiInput(
    val role: String,
    val content: List<OpenAiContent>,
)

@Serializable
internal data class OpenAiContent(
    val type: String,
    val text: String,
)

@Serializable
internal data class OpenAiTool(
    val type: String,
)

@Serializable
internal data class OpenAiText(
    val format: OpenAiFormat,
)

@Serializable
internal data class OpenAiFormat(
    val type: String = "json_schema",
    val name: String = AiSummarySpec.RESPONSE_SCHEMA_NAME,
    val strict: Boolean = true,
    val schema: JsonObject = AiSummarySpec.openAiJsonSchema,
)
